/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run;

import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunnerLayoutUi;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Computable;
import com.intellij.ui.GuiUtils;
import com.intellij.ui.content.Content;
import com.intellij.util.Alarm;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValue;
import com.jetbrains.lang.dart.ide.runner.server.vmService.DartVmServiceDebugProcessZ;
import com.jetbrains.lang.dart.ide.runner.server.vmService.frame.DartVmServiceStackFrame;
import com.jetbrains.lang.dart.ide.runner.server.vmService.frame.DartVmServiceValue;
import com.jetbrains.lang.dart.util.DartUrlResolver;
import io.flutter.actions.ReloadFlutterApp;
import io.flutter.actions.RestartFlutterApp;
import io.flutter.inspector.EvalOnDartLibrary;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.run.daemon.RunMode;
import io.flutter.view.FlutterViewMessages;
import io.flutter.view.OpenFlutterViewAction;
import org.dartlang.vm.service.VmService;
import org.dartlang.vm.service.element.Frame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * A debug process that handles hot reloads for Flutter.
 * <p>
 * It's used for both the 'Run' and 'Debug' modes. (We apparently need a debug process even
 * when not debugging in order to support hot reload.)
 */
public class FlutterDebugProcess extends DartVmServiceDebugProcessZ implements Disposable {
  private static final Logger LOG = Logger.getInstance(FlutterDebugProcess.class);

  private final @NotNull FlutterApp app;

  public FlutterDebugProcess(@NotNull FlutterApp app,
                             @NotNull ExecutionEnvironment executionEnvironment,
                             @NotNull XDebugSession session,
                             @NotNull ExecutionResult executionResult,
                             @NotNull DartUrlResolver dartUrlResolver,
                             @NotNull PositionMapper mapper) {
    super(executionEnvironment, session, executionResult, dartUrlResolver, app.getConnector(), mapper);
    this.app = app;
  }

  @NotNull
  public FlutterApp getApp() {
    return app;
  }

  String currentIsolateId;

  EvalOnDartLibrary dartLibraryForEval;

  public String getCurrentIsolateId() {
    return currentIsolateId;
  }

  public EvalOnDartLibrary getDartLibraryForEval() {
    return dartLibraryForEval;
  }

  public void setDartLibraryForEval(EvalOnDartLibrary library) {
    dartLibraryForEval = library;
  }

  @Override
  public void dispose() {
    // XXX really dispose.
    setCurrentIsolateId(null);
  }

  public class ConsoleDebuggerScope {
    private int MAX_VALUES = 7;
    final ArrayList<DartVmServiceValue> values = new ArrayList<>();

    public void add(DartVmServiceValue value) {
      if (values.size() >= MAX_VALUES) {
        values.remove(values.size() - 1);
      }
      values.add(0, value);
    }

    void clear() {
      values.clear();
    }

    Map<String, String> toMap() {
     final Map<String, String> map = new HashMap<>();
     for (int i = 0; i < values.size(); ++i) {
       // TODO(jacobr): due to Observatory lifetime issues, this value may
       // have expired so we may need to recompute form a backing store.
       final DartVmServiceValue value = values.get(i);

       map.put("$" + i, value.getInstanceRef().getId());
     }
     return map;
    }
  }

  final Map<String, ConsoleDebuggerScope> debuggerScopes = new HashMap<>();

  public void setCurrentIsolateId(String isolateId) {
    currentIsolateId = isolateId;
    if (isolateId == null ) {
      debuggerScopes.clear();
    } else {
      debuggerScopes.putIfAbsent(isolateId, new ConsoleDebuggerScope());
    }
  }


  public ConsoleDebuggerScope getDebuggerScope(String isolateId) {
    return debuggerScopes.get(isolateId);
  }

  public ConsoleDebuggerScope getDebuggerScope() {
    final XStackFrame frame = getSession().getCurrentStackFrame();
    if (frame instanceof DartVmServiceStackFrame) {
      final DartVmServiceStackFrame dartStackFrame = (DartVmServiceStackFrame)frame;
      setCurrentIsolateId(dartStackFrame.getIsolateId());
    }
    return currentIsolateId != null ? debuggerScopes.get(currentIsolateId) : null;
  }

  @Nullable
  public XDebuggerEvaluator getEvaluator() {
    XStackFrame frame = getSession().getCurrentStackFrame();
    if (frame== null) {
      return new FlutterVmServiceEvaluator(this);
    }
    return frame == null ? null : frame.getEvaluator();
  }

  @Override
  protected void onVmConnected(@NotNull VmService vmService) {
    app.setFlutterDebugProcess(this);
    debuggerScopes.clear();
    FlutterViewMessages.sendDebugActive(getSession().getProject(), app, vmService);
  }

  @Override
  public void registerAdditionalActions(@NotNull final DefaultActionGroup leftToolbar,
                                        @NotNull final DefaultActionGroup topToolbar,
                                        @NotNull final DefaultActionGroup settings) {

    if (app.getMode() != RunMode.DEBUG) {
      // Remove the debug-specific actions that aren't needed when we're not debugging.

      // Remove all but specified actions.
      final AnAction[] leftActions = leftToolbar.getChildActionsOrStubs();
      // Not all on the classpath so we resort to Strings.
      final List<String> actionClassNames = Arrays
        .asList("com.intellij.execution.actions.StopAction", "com.intellij.ui.content.tabs.PinToolwindowTabAction",
                "com.intellij.execution.ui.actions.CloseAction", "com.intellij.ide.actions.ContextHelpAction");
      for (AnAction a : leftActions) {
        if (!actionClassNames.contains(a.getClass().getName())) {
          leftToolbar.remove(a);
        }
      }

      // Remove all top actions.
      final AnAction[] topActions = topToolbar.getChildActionsOrStubs();
      for (AnAction action : topActions) {
        topToolbar.remove(action);
      }

      // Remove all settings actions.
      final AnAction[] settingsActions = settings.getChildActionsOrStubs();
      for (AnAction a : settingsActions) {
        settings.remove(a);
      }
    }

    // Add actions common to the run and debug windows.
    final Computable<Boolean> isSessionActive = () -> app.isStarted() && getVmConnected() && !getSession().isStopped();
    final Computable<Boolean> canReload = () -> app.getLaunchMode().supportsReload() && isSessionActive.compute() && !app.isReloading();
    final Computable<Boolean> observatoryAvailable = () -> isSessionActive.compute() && app.getConnector().getBrowserUrl() != null;

    if (app.getMode() == RunMode.DEBUG) {
      topToolbar.addSeparator();
      topToolbar.addAction(new FlutterPopFrameAction());
    }

    topToolbar.addSeparator();
    topToolbar.addAction(new ReloadFlutterApp(app, canReload));
    topToolbar.addAction(new RestartFlutterApp(app, canReload));
    topToolbar.addSeparator();
    topToolbar.addAction(new OpenObservatoryAction(app.getConnector(), observatoryAvailable));
    topToolbar.addAction(new OpenTimelineViewAction(app.getConnector(), observatoryAvailable));
    topToolbar.addSeparator();
    topToolbar.addAction(new OpenFlutterViewAction(isSessionActive));

    // Don't call super since we have our own observatory action.
  }

  @Override
  public void sessionInitialized() {
    if (app.getMode() != RunMode.DEBUG) {
      suppressDebugViews(getSession().getUI());
    }
  }

  /**
   * Turn off debug-only views (variables and frames).
   */
  private static void suppressDebugViews(@Nullable RunnerLayoutUi ui) {
    if (ui == null) {
      return;
    }

    for (Content c : ui.getContents()) {
      if (!Objects.equals(c.getTabName(), "Console")) {
        try {
          GuiUtils.runOrInvokeAndWait(() -> ui.removeContent(c, false /* dispose? */));
        }
        catch (InvocationTargetException | InterruptedException e) {
          LOG.warn(e);
        }
      }
    }
  }
}
