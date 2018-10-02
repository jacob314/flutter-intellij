/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBusConnection;
import io.flutter.FlutterUtils;
import io.flutter.run.FlutterAppManager;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.settings.FlutterSettings;
import io.flutter.utils.StreamSubscription;
import io.flutter.view.FlutterViewMessages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A singleton for the current Project. This class watches for changes to the
 * current Flutter app, and orchestrates displaying rebuild counts and other
 * widget performance stats for widgets created in the active source files.
 * Performance stats are displayed directly in the TextEditor windows so that
 * users can see them as they look at the source code.
 *
 * Rebuild counts provide an easy way to understand the coarse grained
 * performance of an application and avoid common pitfalls.
 *
 * FlutterWidgetPerfManager tracks which source files are visible and
 * passes that information to FlutterWidgetPerf which performs the work to
 * actually fetch performance information and display them.
 */
public class FlutterWidgetPerfManager implements Disposable, FlutterApp.FlutterAppListener {

  // Service extensions used by the perf manager.
  public static final String TRACK_REBUILD_WIDGETS = "ext.flutter.inspector.trackRebuildDirtyWidgets";
  public static final String TRACK_REPAINT_WIDGETS = "ext.flutter.inspector.trackRepaintWidgets";

  // Whether each of the performance metrics tracked should be tracked by
  // default when starting a new application.
  public static boolean trackRebuildWidgetsDefault = false;
  public static boolean trackRepaintWidgetsDefault = false;

  private FlutterWidgetPerf currentStats;
  private FlutterApp app;
  private final Project project;
  private boolean trackRebuildWidgets = trackRebuildWidgetsDefault;
  private boolean trackRepaintWidgets = trackRepaintWidgetsDefault;
  private boolean debugIsActive = false;

  /**
   * File editors visible to the user that might contain widgets.
   */
  private Set<TextEditor> lastSelectedEditors = new HashSet<>();

  private final List<StreamSubscription<Boolean>> streamSubscriptions = new ArrayList<>();

  private FlutterWidgetPerfManager(@NotNull Project project) {
    this.project = project;

    Disposer.register(project, this);

    FlutterAppManager.getInstance(project).getActiveAppAsStream().listen(
      this::updateCurrentAppChanged, true);

    project.getMessageBus().connect().subscribe(
      FlutterViewMessages.FLUTTER_DEBUG_TOPIC, (event) -> debugActive(project, event)
    );

    final MessageBusConnection connection = project.getMessageBus().connect(project);

    updateSelectedEditors();
    connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
      public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        if (updateSelectedEditors()) {
          notifyPerf();
        }
      }
    });
  }

  /**
   * @return whether the set of selected editors actually changed.
   */
  private boolean updateSelectedEditors() {
    final FileEditor[] editors = FileEditorManager.getInstance(project).getSelectedEditors();
    final Set<TextEditor> newEditors = new HashSet<>();
    for  (FileEditor editor : editors) {
      if (editor instanceof TextEditor) {
        final VirtualFile file = editor.getFile();
        if (couldContainWidgets(file)) {
          newEditors.add((TextEditor) editor);
        }
      }
    }
    if (newEditors.equals(lastSelectedEditors)) {
      return false;
    }
    lastSelectedEditors = newEditors;
    return true;
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

  public boolean isTrackRebuildWidgets() {
    return trackRebuildWidgets;
  }

  public void setTrackRebuildWidgets(boolean value) {
    if (value == trackRebuildWidgets) {
      return;
    }
    trackRebuildWidgets = value;
    onProfilingFlagsChanged();
    if (debugIsActive && app != null && app.isSessionActive()) {
      updateTrackWidgetRebuilds();
    }
  }

  public boolean isTrackRepaintWidgets() {
    return trackRepaintWidgets;
  }

  public void setTrackRepaintWidgets(boolean value) {
    if (value == trackRepaintWidgets) {
      return;
    }
    trackRepaintWidgets = value;
    onProfilingFlagsChanged();
    if (debugIsActive && app != null && app.isSessionActive()) {
      updateTrackWidgetRepaints();
    }
  }

  private void onProfilingFlagsChanged() {
    if (currentStats != null) {
      currentStats.setProfilingEnabled(isProfilingEnabled());
    }
  }

  private boolean isProfilingEnabled() {
    return trackRebuildWidgets || trackRepaintWidgets;
  }

  private void debugActive(Project project, FlutterViewMessages.FlutterDebugEvent event) {
    debugIsActive = true;

    assert(app != null);
    app.addStateListener(this);
    syncBooleanServiceExtension(TRACK_REBUILD_WIDGETS, () -> trackRebuildWidgets);
    syncBooleanServiceExtension(TRACK_REPAINT_WIDGETS, () -> trackRepaintWidgets);

    currentStats = new FlutterWidgetPerf(
      isProfilingEnabled(),
      new VmServiceWidgetPerfProvider(app),
      (TextEditor textEditor) -> new EditorPerfDecorations(textEditor, app),
      (TextEditor textEditor) -> new TextEditorFileLocationMapper(textEditor, app.getProject())
    );
  }

  public void stateChanged(FlutterApp.State newState) {
    switch (newState) {
      case RELOADING:
      case RESTARTING:
        currentStats.clear();
      break;
      case STARTED:
        notifyPerf();
        break;
    }
  }

  public void notifyAppRestarted() {
    currentStats.clear();
  }

  public void notifyAppReloaded() {
    currentStats.clear();
  }

  private void updateTrackWidgetRebuilds() {
    app.maybeCallBooleanExtension(TRACK_REBUILD_WIDGETS, trackRebuildWidgets).whenCompleteAsync((v, e) -> {
      notifyPerf();
    });
  }

  private void updateTrackWidgetRepaints() {
    app.maybeCallBooleanExtension(TRACK_REPAINT_WIDGETS, trackRepaintWidgets).whenCompleteAsync((v, e) -> {
      notifyPerf();
    });
  }

  private void syncBooleanServiceExtension(String serviceExtension, Computable<Boolean> valueProvider) {
    streamSubscriptions.add(app.hasServiceExtension(serviceExtension, (supported) -> {
      if (supported) {
        app.callBooleanExtension(serviceExtension, valueProvider.compute());
      }
    }));
  }

  private boolean couldContainWidgets(@Nullable VirtualFile file) {
    // TODO(jacobr): we might also want to filter for files not under the
    // current project root.
    return file != null && FlutterUtils.isDartFile(file);
  }

  private void updateCurrentAppChanged(@Nullable FlutterApp app) {
    // TODO(jacobr): we currently only support showing stats for the last app
    // that was run. After the initial CL lands we should fix this to track
    // multiple running apps if needed. The most important use case is if the
    // user has one profile app and one debug app running at the same time.
    // We should track stats for all running apps and display the aggregated
    // stats. A well behaved flutter app should not be painting frames very
    // frequently when a user is not interacting with it so showing aggregated
    // stats for all apps should match user expectations without forcing users
    // to manage which app they have selected.
    if (app == this.app) {
      return;
    }
    debugIsActive = false;

    if (this.app != null) {
      this.app.removeStateListener(this);
    }

    this.app = app;

    for (StreamSubscription<Boolean> subscription : streamSubscriptions) {
      subscription.dispose();
    }
    streamSubscriptions.clear();

    if (currentStats != null) {
      currentStats.dispose();
      currentStats = null;
    }
  }

  private void notifyPerf() {
    if (currentStats == null) {
      return;
    }

    if (lastSelectedEditors.isEmpty()) {
      currentStats.showFor(lastSelectedEditors);
      return;
    }
    final Module module = app.getModule();
    final Set<TextEditor> editors = new HashSet<>();
    if (module != null) {
      for (TextEditor editor : lastSelectedEditors) {
        final VirtualFile file = editor.getFile();
        if (file != null &&
            ModuleUtilCore.moduleContainsFile(module, file, false) &&
            !app.isReloading() || !app.isLatestVersionRunning(file)) {
          // We skip querying files that have been modified locally as we
          // cannot safely display the profile information so there is no
          // point in tracking it.
          editors.add(editor);
        }
      }
    }
    currentStats.showFor(editors);
  }

  @Override
  public void dispose() {
    if (currentStats != null) {
      currentStats.dispose();
      currentStats = null;
    }
  }
}
