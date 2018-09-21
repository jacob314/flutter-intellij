/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBusConnection;
import io.flutter.FlutterUtils;
import io.flutter.run.FlutterAppManager;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.view.FlutterViewMessages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// TODO(jacobr): Have an opt-out for this feature.

/**
 * A singleton for the current Project. This class watches for changes to the current
 * Flutter app, and orchestrates displaying rebuild counts and other performance
 * results broken down at the widget level for the current file.
 *
 * Rebuilt counts provide an easy way to understand the coarse grained
 * performance of an application and avoid common pitfalls.
 */
public class FlutterWidgetPerfManager implements Disposable {
  // XXX cleanup this name.
  public static final String TRACK_REBUILD_WIDGETS = "ext.flutter.inspector.trackRebuildDirtyWidgets";
  public static final String TRACK_REPAINT_WIDGETS = "ext.flutter.inspector.trackRepaintWidgets";

  private static final boolean ENABLE_WIDGET_PERF_MANAGER = true;
  private FlutterApp app;

  public static boolean trackRebuildWidgetsDefault = false;
  public static boolean trackRepaintWidgetsDefault = false;

  private boolean trackRebuildWidgets = trackRebuildWidgetsDefault;
  private boolean trackRepaintWidgets = trackRepaintWidgetsDefault;
  private boolean debugIsActive = false;

  public boolean isTrackRebuildWidgets() {
    return trackRebuildWidgets;
  }

  public boolean isTrackRepaintWidgets() {
    return trackRepaintWidgets;
  }

  public void setTrackRebuildWidgets(boolean value) {
    if (value == trackRebuildWidgets) {
      return;
    }
    trackRebuildWidgets = value;
    if (debugIsActive && app != null && app.isSessionActive()) {
      updateTrackWidgetRebuilds();
    }
  }

  public void setTrackRepaintWidgets(boolean value) {
    if (value == trackRepaintWidgets) {
      return;
    }
    trackRepaintWidgets = value;
    if (debugIsActive && app != null && app.isSessionActive()) {
      updateTrackWidgetRepaints();
    }
  }

  /**
   * Initialize the rebuild count manager for the given project.
   */
  public static void init(@NotNull Project project) {
    // Call getInstance() will init FlutterWidgetPerfManager for the given project.
    getInstance(project);
  }

  public static FlutterWidgetPerfManager getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, FlutterWidgetPerfManager.class);
  }

  FlutterWidgetPerf currentStats;
  private VirtualFile lastFile;
  private FileEditor lastEditor;

  private FlutterWidgetPerfManager(@NotNull Project project) {
    if (!ENABLE_WIDGET_PERF_MANAGER) {
      return;
    }

    Disposer.register(project, this);

    FlutterAppManager.getInstance(project).getActiveAppAsStream().listen(
      this::updateCurrentAppChanged, true);

    project.getMessageBus().connect().subscribe(
      FlutterViewMessages.FLUTTER_DEBUG_TOPIC, (event) -> debugActive(project, event)
    );

    final MessageBusConnection connection = project.getMessageBus().connect(project);

    final Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
    if (editor instanceof EditorEx) {
      lastFile = ((EditorEx)editor).getVirtualFile();

      if (couldContainWidgets(lastFile)) {
        lastEditor = FileEditorManager.getInstance(project).getSelectedEditor(lastFile);

        if (lastEditor == null) {
          lastFile = null;
        }
      }
    }

    connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
      public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        if (couldContainWidgets(event.getNewFile())) {
          lastFile = event.getNewFile();
          lastEditor = editorFor(event);
        }
        else {
          lastFile = null;
          lastEditor = null;
        }

        notifyPerf();
      }
    });
  }

  private void debugActive(Project project, FlutterViewMessages.FlutterDebugEvent event) {
    if (currentStats == null) {
      currentStats = new FlutterWidgetPerf(app);
      updateTrackWidgetRebuilds();
      updateTrackWidgetRepaints();
    }
    debugIsActive = true;
    app.addStateListener(new FlutterApp.FlutterAppListener() {
      public void stateChanged(FlutterApp.State newState) {
        if (newState == FlutterApp.State.RELOADING ||
            newState == FlutterApp.State.RESTARTING) {
          currentStats.clear();
        }
      }

      public void notifyAppRestarted() {
        currentStats.clear();
      }

      public void notifyAppReloaded() {
        currentStats.clear();
      }
    });
  }

  private void updateTrackWidgetRebuilds() {
    app.hasServiceExtension(TRACK_REBUILD_WIDGETS, (enabled) -> {
      if (enabled) {
        app.callBooleanExtension(TRACK_REBUILD_WIDGETS, trackRebuildWidgets);
        notifyPerf();
      }
    });
  }

  private void updateTrackWidgetRepaints() {
    app.hasServiceExtension(TRACK_REPAINT_WIDGETS, (enabled) -> {
      if (enabled) {
        app.callBooleanExtension(TRACK_REPAINT_WIDGETS, trackRepaintWidgets);
        notifyPerf();
      }
    });
  }

  private boolean couldContainWidgets(@Nullable VirtualFile file) {
    return file != null && FlutterUtils.isDartFile(file);
  }

  private FileEditor editorFor(FileEditorManagerEvent event) {
    if (!(event.getNewEditor() instanceof TextEditor)) {
      return null;
    }
    return event.getNewEditor();
  }

  private void updateCurrentAppChanged(@Nullable FlutterApp app) {
    if (!ENABLE_WIDGET_PERF_MANAGER) {
      return;
    }
    this.app = app;

    if (app == null) {
      if (currentStats != null) {
        currentStats.dispose();
        currentStats = null;
      }
      return;
    }
    if (currentStats != null) {
      if (currentStats.getApp() == app) {
        return;
      }
      currentStats.dispose();
      currentStats = null;
    }
  }

  private void notifyPerf() {
    if (currentStats == null) {
      return;
    }

    if (lastFile == null) {
      currentStats.showFor(null, null);
    }
    else {
      final Module module = currentStats.getApp().getModule();

      if (module != null && ModuleUtilCore.moduleContainsFile(module, lastFile, false)) {
        currentStats.showFor(lastFile, lastEditor);
      }
      else {
        currentStats.showFor(null, null);
      }
    }
  }

  @Override
  public void dispose() {
    if (currentStats != null) {
      currentStats.dispose();
      currentStats = null;
    }
  }
}
