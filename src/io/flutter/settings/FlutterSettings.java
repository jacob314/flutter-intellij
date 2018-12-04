/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.settings;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.EventDispatcher;
import io.flutter.analytics.Analytics;
import io.flutter.bazel.Workspace;
import io.flutter.sdk.FlutterSdk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

public class FlutterSettings {
  private static final String reloadOnSaveKey = "io.flutter.reloadOnSave";
  private static final String openInspectorOnAppLaunchKey = "io.flutter.openInspectorOnAppLaunch";
  private static final String verboseLoggingKey = "io.flutter.verboseLogging";
  private static final String formatCodeOnSaveKey = "io.flutter.formatCodeOnSave";
  private static final String organizeImportsOnSaveKey = "io.flutter.organizeImportsOnSave";
  private static final String showOnlyWidgetsKey = "io.flutter.showOnlyWidgets";
  private static final String showPreviewAreaKey = "io.flutter.showPreviewArea";
  private static final String syncAndroidLibrariesKey = "io.flutter.syncAndroidLibraries";
  private static final String legacyTrackWidgetCreationKey = "io.flutter.trackWidgetCreation";
  private static final String disableTrackWidgetCreationKey = "io.flutter.disableTrackWidgetCreation";
  private static final String useFlutterLogView = "io.flutter.useLogView";
  private static final String memoryProfilerKey = "io.flutter.memoryProfiler";
  private static final String newBazelTestRunnerKey = "io.flutter.bazel.legacyTestBehavior";

  // Settings for UI as Code experiments.
  private static final String showBuildMethodGuidesKey = "io.flutter.editor.showBuildMethodGuides";
  private static final String showMultipleChildrenGuidesKey = "io.flutter.editor.showMultipleChildrenGuides";
  private static final String greyUnimportantPropertiesKey = "io.flutter.editor.greyUnimportantProperties";
  private static final String showDashedLineGuidesKey = "io.flutter.editor.showDashedLineGuides";
  private static final String simpleIndentIntersectionKey = "io.flutter.editor.simpleIndentIntersectionKey";
  private static final String showBuildMethodsOnScrollbarKey = "io.flutter.editor.showBuildMethodsOnScrollbarKey";
  private static final String ligaturesFontKey = "io.flutter.editor.ligaturesFontKey";

  public static FlutterSettings getInstance() {
    return ServiceManager.getService(FlutterSettings.class);
  }

  protected static PropertiesComponent getPropertiesComponent() {
    return PropertiesComponent.getInstance();
  }

  public interface Listener extends EventListener {
    void settingsChanged();
  }

  private final EventDispatcher<Listener> dispatcher = EventDispatcher.create(Listener.class);

  public FlutterSettings() {
    updateAnalysisServerArgs();
  }

  public void sendSettingsToAnalytics(Analytics analytics) {
    final PropertiesComponent properties = getPropertiesComponent();

    // Send data on the number of experimental features enabled by users.
    analytics.sendEvent("settings", "ping");

    if (isReloadOnSave()) {
      analytics.sendEvent("settings", afterLastPeriod(reloadOnSaveKey));
    }
    if (isOpenInspectorOnAppLaunch()) {
      analytics.sendEvent("settings", afterLastPeriod(openInspectorOnAppLaunchKey));
    }
    if (isFormatCodeOnSave()) {
      analytics.sendEvent("settings", afterLastPeriod(formatCodeOnSaveKey));

      if (isOrganizeImportsOnSaveKey()) {
        analytics.sendEvent("settings", afterLastPeriod(organizeImportsOnSaveKey));
      }
    }
    if (isShowOnlyWidgets()) {
      analytics.sendEvent("settings", afterLastPeriod(showOnlyWidgetsKey));
    }
    if (isShowPreviewArea()) {
      analytics.sendEvent("settings", afterLastPeriod(showPreviewAreaKey));
    }

    if (isSyncingAndroidLibraries()) {
      analytics.sendEvent("settings", afterLastPeriod(syncAndroidLibrariesKey));
    }
    if (isLegacyTrackWidgetCreation()) {
      analytics.sendEvent("settings", afterLastPeriod(legacyTrackWidgetCreationKey));
    }
    if (isDisableTrackWidgetCreation()) {
      analytics.sendEvent("settings", afterLastPeriod(disableTrackWidgetCreationKey));
    }
    if (useFlutterLogView()) {
      analytics.sendEvent("settings", afterLastPeriod(useFlutterLogView));
    }
    if (shouldUseNewBazelTestRunner()) {
      analytics.sendEvent("settings", afterLastPeriod(newBazelTestRunnerKey));
    }
  }

  public void addListener(Listener listener) {
    dispatcher.addListener(listener);
  }

  public void removeListener(Listener listener) {
    dispatcher.removeListener(listener);
  }

  public boolean isReloadOnSave() {
    return getPropertiesComponent().getBoolean(reloadOnSaveKey, true);
  }

  // TODO(jacobr): remove after 0.10.2 is the default.
  public boolean isLegacyTrackWidgetCreation() {
    return getPropertiesComponent().getBoolean(legacyTrackWidgetCreationKey, false);
  }

  public void setLegacyTrackWidgetCreation(boolean value) {
    getPropertiesComponent().setValue(legacyTrackWidgetCreationKey, value, false);

    fireEvent();
  }

  public boolean isTrackWidgetCreationEnabled(Project project) {
    final FlutterSdk flutterSdk = FlutterSdk.getFlutterSdk(project);
    if (flutterSdk != null && flutterSdk.getVersion().isTrackWidgetCreationRecommended()) {
      return !getPropertiesComponent().getBoolean(disableTrackWidgetCreationKey, false);
    }
    else {
      return isLegacyTrackWidgetCreation();
    }
  }

  public boolean isDisableTrackWidgetCreation() {
    return getPropertiesComponent().getBoolean(disableTrackWidgetCreationKey, false);
  }

  public void setDisableTrackWidgetCreation(boolean value) {
    getPropertiesComponent().setValue(disableTrackWidgetCreationKey, value, false);
    fireEvent();
  }

  public void setReloadOnSave(boolean value) {
    getPropertiesComponent().setValue(reloadOnSaveKey, value, true);

    fireEvent();
  }

  public boolean isFormatCodeOnSave() {
    return getPropertiesComponent().getBoolean(formatCodeOnSaveKey, false);
  }

  public void setFormatCodeOnSave(boolean value) {
    getPropertiesComponent().setValue(formatCodeOnSaveKey, value, false);

    fireEvent();
  }

  public boolean isOrganizeImportsOnSaveKey() {
    return getPropertiesComponent().getBoolean(organizeImportsOnSaveKey, false);
  }

  public void setOrganizeImportsOnSaveKey(boolean value) {
    getPropertiesComponent().setValue(organizeImportsOnSaveKey, value, false);

    fireEvent();
  }

  public boolean isShowOnlyWidgets() {
    return getPropertiesComponent().getBoolean(showOnlyWidgetsKey, false);
  }

  public void setShowOnlyWidgets(boolean value) {
    getPropertiesComponent().setValue(showOnlyWidgetsKey, value, false);

    fireEvent();
  }

  public boolean isShowPreviewArea() {
    return getPropertiesComponent().getBoolean(showPreviewAreaKey, false);
  }

  public void setShowPreviewArea(boolean value) {
    getPropertiesComponent().setValue(showPreviewAreaKey, value, false);

    fireEvent();
  }

  public boolean isSyncingAndroidLibraries() {
    return getPropertiesComponent().getBoolean(syncAndroidLibrariesKey, false);
  }

  public void setSyncingAndroidLibraries(boolean value) {
    getPropertiesComponent().setValue(syncAndroidLibrariesKey, value, false);

    fireEvent();
  }

  public boolean useFlutterLogView() {
    return getPropertiesComponent().getBoolean(useFlutterLogView, false);
  }

  public void setUseFlutterLogView(boolean value) {
    getPropertiesComponent().setValue(useFlutterLogView, value, false);

    fireEvent();
  }

  public boolean isOpenInspectorOnAppLaunch() {
    return getPropertiesComponent().getBoolean(openInspectorOnAppLaunchKey, false);
  }

  public void setOpenInspectorOnAppLaunch(boolean value) {
    getPropertiesComponent().setValue(openInspectorOnAppLaunchKey, value, false);

    fireEvent();
  }

  // TODO(devoncarew): Remove this after M31 ships.
  private void updateAnalysisServerArgs() {
    final String serverRegistryKey = "dart.server.additional.arguments";
    final String previewDart2FlagSuffix = "preview-dart-2";

    final List<String> params = new ArrayList<>(StringUtil.split(Registry.stringValue(serverRegistryKey), " "));
    if (params.removeIf((s) -> s.endsWith(previewDart2FlagSuffix))) {
      Registry.get(serverRegistryKey).setValue(StringUtil.join(params, " "));
    }
  }

  public boolean isVerboseLogging() {
    return getPropertiesComponent().getBoolean(verboseLoggingKey, false);
  }

  public void setVerboseLogging(boolean value) {
    getPropertiesComponent().setValue(verboseLoggingKey, value, false);

    fireEvent();
  }

  public boolean isShowBuildMethodGuides() {
    return getPropertiesComponent().getBoolean(showBuildMethodGuidesKey, true);
  }

  public void setShowBuildMethodGuides(boolean value) {
    getPropertiesComponent().setValue(showBuildMethodGuidesKey, value, true);

    fireEvent();
  }

  public void setShowDashedLineGuides(boolean value) {
    getPropertiesComponent().setValue(showDashedLineGuidesKey, value, false);

    fireEvent();
  }

  public boolean isShowDashedLineGuides() {
    return getPropertiesComponent().getBoolean(showDashedLineGuidesKey, false);
  }

  public boolean isSimpleIndentIntersectionMode() {
    return getPropertiesComponent().getBoolean(simpleIndentIntersectionKey, true);
  }

  public void setSimpleIndentIntersectionMode(boolean value) {
    getPropertiesComponent().setValue(simpleIndentIntersectionKey, value, true);

    fireEvent();
  }

  public boolean isShowBuildMethodsOnScrollbar() {
    return getPropertiesComponent().getBoolean(showBuildMethodsOnScrollbarKey, false);
  }

  public void setShowBuildMethodsOnScrollbar(boolean value) {
    getPropertiesComponent().setValue(showBuildMethodsOnScrollbarKey, value, false);

    fireEvent();
  }

  public boolean isShowMultipleChildrenGuides() {
    return getPropertiesComponent().getBoolean(showMultipleChildrenGuidesKey, false);
  }

  public void setShowMultipleChildrenGuides(boolean value) {
    getPropertiesComponent().setValue(showMultipleChildrenGuidesKey, value, false);

    fireEvent();
  }

  public boolean isGreyUnimportantProperties() {
    return getPropertiesComponent().getBoolean(greyUnimportantPropertiesKey, false);
  }

  public void setGreyUnimportantProperties(boolean value) {
    getPropertiesComponent().setValue(greyUnimportantPropertiesKey, value, false);

    fireEvent();
  }

  public void setUseLigaturesFont(boolean value) {
    getPropertiesComponent().setValue(ligaturesFontKey, value, false);

    fireEvent();
  }

  public boolean isUseLigaturesFont() {
    return getPropertiesComponent().getBoolean(ligaturesFontKey, false);
  }

  public boolean isMemoryProfilerDisabled() {
    return getPropertiesComponent().getBoolean(memoryProfilerKey, false);
  }

  public void setMemoryProfilerDisabled(boolean value) {
    getPropertiesComponent().setValue(memoryProfilerKey, value, false);

    fireEvent();
  }

  /**
   * Whether to use the new bazel-test script instead of the old bazel-run script to run tests.
   *
   * Defaults to false.
   */
  public boolean useNewBazelTestRunner(Project project) {
    // Check that the new test runner is available.
    final Workspace workspace = Workspace.load(project);
    // If the workspace can't be found, we'll return false. This normally happens during tests. Test code that covers this setting
    // has an override for this setting built-in.
    if (workspace == null) {
      return false;
    }
    @Nullable String testScript = workspace.getTestScript();
    if (testScript == null) {
      // The test script was not found, so it can't be used.
      return false;
    }
    return shouldUseNewBazelTestRunner();
  }

  private boolean shouldUseNewBazelTestRunner() {
    return getPropertiesComponent().getBoolean(newBazelTestRunnerKey, true);
  }

  public void setUseNewBazelTestRunner(boolean value) {
    getPropertiesComponent().setValue(newBazelTestRunnerKey, value, true);
    fireEvent();
  }

  protected void fireEvent() {
    dispatcher.getMulticaster().settingsChanged();
  }

  private static String afterLastPeriod(String str) {
    final int index = str.lastIndexOf('.');
    return index == -1 ? str : str.substring(index + 1);
  }
}
