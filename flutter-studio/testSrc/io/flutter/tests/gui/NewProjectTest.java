/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.flutter.tests.gui;

import com.android.tools.idea.tests.gui.framework.FlutterGuiTestRule;
import com.android.tools.idea.tests.gui.framework.GuiTestRunner;
import com.android.tools.idea.tests.gui.framework.fixture.EditorFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.NlEditorFixture;
import com.android.tools.idea.tests.gui.framework.fixture.newProjectWizard.FlutterProjectStepFixture;
import com.android.tools.idea.tests.gui.framework.fixture.newProjectWizard.FlutterSettingsStepFixture;
import com.android.tools.idea.tests.gui.framework.fixture.newProjectWizard.NewFlutterProjectWizardFixture;
import io.flutter.module.FlutterProjectType;
import io.flutter.tests.util.WizardUtils;
import org.fest.swing.core.Settings;
import org.fest.swing.exception.WaitTimedOutError;
import org.fest.swing.fixture.DialogFixture;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * If flakey tests are found try adjusting these settings:
 * Settings festSettings = myGuiTest.robot().settings();
 * festSettings.delayBetweenEvents(50); // 30
 * festSettings.eventPostingDelay(150); // 100
 */
@RunWith(GuiTestRunner.class)
public class NewProjectTest {
  @Rule public final FlutterGuiTestRule myGuiTest = new FlutterGuiTestRule();

  @Test
  public void createNewProjectWithDefaults() {
    NewFlutterProjectWizardFixture wizard = myGuiTest.welcomeFrame().createNewProject();
    try {
      wizard.clickNext().clickNext().clickFinish();
      myGuiTest.waitForBackgroundTasks();
      myGuiTest.ideFrame().waitForProjectSyncToFinish();
    }
    catch (Exception ex) {
      // If this happens to be the first test run in a suite then there will be no SDK and it times out.
      assertThat(ex.getClass()).isAssignableTo(WaitTimedOutError.class);
      assertThat(ex.getMessage()).isEqualTo("Timed out waiting for matching JButton");
      wizard.clickCancel();
    }
  }

  @Test
  public void createNewApplicationWithDefaults() {
    WizardUtils.createNewApplication(myGuiTest);
    EditorFixture editor = myGuiTest.ideFrame().getEditor();
    editor.waitUntilErrorAnalysisFinishes();
    String expected =
      "import 'package:flutter/material.dart';\n" +
      "\n" +
      "void main() {\n" +
      "  runApp(new MyApp());\n" +
      "}\n";

    assertEquals(expected, editor.getCurrentFileContents().substring(0, expected.length()));
  }

  @Test
  public void createNewPackageWithDefaults() {
    WizardUtils.createNewPackage(myGuiTest);
    EditorFixture editor = myGuiTest.ideFrame().getEditor();
    editor.waitUntilErrorAnalysisFinishes();
    String expected =
      "library flutter_package;\n" +
      "\n" +
      "/// A Calculator.\n" +
      "class Calculator {\n" +
      "  /// Returns [value] plus 1.\n" +
      "  int addOne(int value) => value + 1;\n" +
      "}\n";

    assertEquals(expected, editor.getCurrentFileContents().substring(0, expected.length()));
  }

  @Test
  public void createNewPluginWithDefaults() {
    WizardUtils.createNewPlugin(myGuiTest);
    EditorFixture editor = myGuiTest.ideFrame().getEditor();
    editor.waitUntilErrorAnalysisFinishes();
    String expected =
      "import 'dart:async';\n" +
      "\n" +
      "import 'package:flutter/services.dart';\n" +
      "\n" +
      "class FlutterPlugin {\n" +
      "  static const MethodChannel _channel =\n" +
      "      const MethodChannel('flutter_plugin');\n" +
      "\n" +
      "  static Future<String> get platformVersion =>\n" +
      "      _channel.invokeMethod('getPlatformVersion');\n" +
      "}\n";

    assertEquals(expected, editor.getCurrentFileContents().substring(0, expected.length()));
  }

  @Test
  public void checkPersistentState() {
    FlutterProjectType type = FlutterProjectType.APP;
    WizardUtils.createNewProject(myGuiTest, type, "super_tron", "A super fancy tron", "google.com", true, true);
    EditorFixture editor = myGuiTest.ideFrame().getEditor();
    editor.waitUntilErrorAnalysisFinishes();

    myGuiTest.ideFrame().invokeMenuPath("File", "New", "New Flutter Project...");
    NewFlutterProjectWizardFixture wizard = myGuiTest.ideFrame().findNewProjectWizard();
    wizard.chooseProjectType("Flutter Application").clickNext();

    FlutterProjectStepFixture projectStep = wizard.getFlutterProjectStep(type);
    assertThat(projectStep.getProjectName()).isEqualTo("flutter_app"); // Not persisting
    assertThat(projectStep.getSdkPath()).isNotEmpty(); // Persisting
    assertThat(projectStep.getProjectLocation()).endsWith("flutter_app"); // Not persisting
    assertThat(projectStep.getDescription()).isEqualTo("A new Flutter application."); // Not persisting
    wizard.clickNext();

    FlutterSettingsStepFixture settingsStep = wizard.getFlutterSettingsStep();
    assertThat(settingsStep.getCompanyDomain()).isEqualTo("google.com"); // Persisting
    assertThat(settingsStep.getPackageName()).isEqualTo("com.google.flutter_app"); // Partially persisting
    settingsStep.getKotlinFixture().requireSelected(); // Persisting
    settingsStep.getSwiftFixture().requireSelected(); // Persisting
    wizard.clickCancel();
  }
}
