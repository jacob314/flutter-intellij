/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.gui;

import com.intellij.testGuiFramework.fixtures.IdeFrameFixture;
import com.intellij.testGuiFramework.impl.GuiTestCase;
import org.junit.Test;

public class ProjectWizardTest extends GuiTestCase {

  @Test
  public void createNewProjectWithDefaults() throws Exception {
    //import project
    IdeFrameFixture ideFrameFixture = importSimpleProject();
    ideFrameFixture.waitForBackgroundTasksToFinish();
    ideFrameFixture.invokeMenuPath("File", "New", "Project...");
  }
}