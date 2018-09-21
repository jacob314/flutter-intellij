/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import io.flutter.inspector.FPSDisplay;
import io.flutter.inspector.HeapDisplay;
import io.flutter.inspector.WidgetPerfPanel;
import io.flutter.perf.FlutterWidgetPerfManager;
import io.flutter.run.FlutterLaunchMode;
import io.flutter.run.daemon.FlutterApp;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class InspectorPerfTab extends JPanel implements InspectorTabPanel {
  private final WidgetPerfPanel myWidgetPerfPanel;
  private @NotNull FlutterApp app;

  InspectorPerfTab(Disposable parentDisposable, @NotNull FlutterApp app) {
    this.app = app;

    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

    add(Box.createVerticalStrut(6));

    Box labelBox = Box.createHorizontalBox();
    labelBox.add(new JBLabel("Running in " + app.getLaunchMode() + " mode"));
    labelBox.add(Box.createHorizontalGlue());
    labelBox.setBorder(JBUI.Borders.empty(3, 10));
    add(labelBox);

    if (app.getLaunchMode() == FlutterLaunchMode.DEBUG) {
      labelBox = Box.createHorizontalBox();
      labelBox.add(new JBLabel("<html><body><p style='color:red'>WARNING: for best results, re-run in profile mode</p></body></html>"));
      labelBox.add(Box.createHorizontalGlue());
      labelBox.setBorder(JBUI.Borders.empty(3, 10));
      add(labelBox);
    }

    add(Box.createVerticalStrut(6));

    add(FPSDisplay.createJPanelView(parentDisposable, app), BorderLayout.NORTH);
    add(Box.createVerticalStrut(16));
    add(HeapDisplay.createJPanelView(parentDisposable, app), BorderLayout.SOUTH);
    add(Box.createVerticalStrut(16));

    add(Box.createVerticalGlue());
    FlutterWidgetPerfManager widgetPerfManager = FlutterWidgetPerfManager.getInstance(app.getProject());

    JBCheckBox trackRebuildsCheckbox = new JBCheckBox("Track Widget Rebuilds", widgetPerfManager.isTrackRebuildWidgets());
    JBCheckBox trackRepaintsCheckbox = new JBCheckBox("Track Widget Repaints", widgetPerfManager.isTrackRepaintWidgets());

    // XXX HOW DO I get the checkboxes to left align?
    trackRebuildsCheckbox.setHorizontalAlignment(SwingConstants.LEFT);
    trackRepaintsCheckbox.setHorizontalAlignment(SwingConstants.LEFT);

    trackRebuildsCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
    Box settingBox = Box.createHorizontalBox();
    settingBox.add(trackRebuildsCheckbox);
    settingBox.setBorder(JBUI.Borders.empty(3, 10));
    add(settingBox);

    settingBox = Box.createHorizontalBox();
    settingBox.add(trackRepaintsCheckbox);
    settingBox.setBorder(JBUI.Borders.empty(3, 10));
    add(settingBox);

    // TODO(jacobr): need to dispose these two listeners.
    app.hasServiceExtension(FlutterWidgetPerfManager.TRACK_REBUILD_WIDGETS, (enabled) -> {
      trackRebuildsCheckbox.setEnabled(enabled);
    });
    app.hasServiceExtension(FlutterWidgetPerfManager.TRACK_REPAINT_WIDGETS, (enabled) -> {
      trackRepaintsCheckbox.setEnabled(enabled);
    });

    trackRebuildsCheckbox.addChangeListener((l) -> {
      setTrackRebuildWidgets(trackRebuildsCheckbox.isSelected());
    });

    trackRepaintsCheckbox.addChangeListener((l) -> {
      setTrackRepaintWidgets(trackRepaintsCheckbox.isSelected());
    });

    myWidgetPerfPanel = new WidgetPerfPanel(parentDisposable, app);
    add(myWidgetPerfPanel);
  }

  private void setTrackRebuildWidgets(boolean selected) {
    final FlutterWidgetPerfManager widgetPerfManager = FlutterWidgetPerfManager.getInstance(app.getProject());
    widgetPerfManager.setTrackRebuildWidgets(selected);
    // Update default so next app launched will match the existing setting.
    FlutterWidgetPerfManager.trackRebuildWidgetsDefault = selected;
  }

  private void setTrackRepaintWidgets(boolean selected) {
    final FlutterWidgetPerfManager widgetPerfManager = FlutterWidgetPerfManager.getInstance(app.getProject());
    widgetPerfManager.setTrackRepaintWidgets(selected);
    // Update default so next app launched will match the existing setting.
    FlutterWidgetPerfManager.trackRepaintWidgetsDefault = selected;
  }

  public WidgetPerfPanel getWidgetPerfPanel() {
    return myWidgetPerfPanel;
  }
  @Override
  public void setVisibleToUser(boolean visible) {
    assert app.getPerfService() != null;

    if (visible) {
      app.getPerfService().resumePolling();
    }
    else {
      app.getPerfService().pausePolling();
    }
  }
}
