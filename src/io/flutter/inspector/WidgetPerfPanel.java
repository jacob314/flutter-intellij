/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.labels.LinkListener;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import io.flutter.perf.FlutterWidgetPerf;
import io.flutter.perf.FlutterWidgetPerfManager;
import io.flutter.perf.PerfTip;
import io.flutter.run.daemon.FlutterApp;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;

// TODO(jacobr): display a table of all widget perf stats in this panel along
// with the summary information about the current performance stats.

/**
 * Panel displaying basic information on Widget perf for the currently selected
 * file.
 */
public class WidgetPerfPanel extends JPanel {
  static final int PANEL_HEIGHT = 120;
  static final int UI_FPS = 1;
  private final JBLabel perfMessage;
  private final FlutterApp app;
  private final FlutterWidgetPerfManager perfManager;

  private final Timer timer;
  long lastUpdateTime;
  final private Box perfTips;

  /**
   * Currently active file editor if it is a TextEditor.
   */
  private TextEditor currentEditor;

  /**
   * Range within the current active file editor to show stats for.
   */
  private TextRange currentRange;
  LinkListener<PerfTip> linkListener;
  private boolean visible = true;

  public WidgetPerfPanel(Disposable parentDisposable, @NotNull FlutterApp app) {
    this.app = app;
    perfManager = FlutterWidgetPerfManager.getInstance(app.getProject());
    setLayout(new GridBagLayout());
    final GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weightx = 1.0;
    constraints.fill = GridBagConstraints.BOTH;

    setPreferredSize(new Dimension(-1, PANEL_HEIGHT));
    setMaximumSize(new Dimension(Short.MAX_VALUE, PANEL_HEIGHT));
    perfMessage = new JBLabel();
    final Box labelBox = Box.createHorizontalBox();
    constraints.gridy = 0;
    labelBox.add(perfMessage, constraints);
    labelBox.add(Box.createHorizontalGlue());
    labelBox.setBorder(JBUI.Borders.empty(3, 10));

    constraints.gridy = 2;
    add(labelBox, constraints);

    perfTips = Box.createVerticalBox();
    linkListener = (source, tip) -> {
      BrowserLauncher.getInstance().browse(tip.getUrl(), null);

    };
    perfTips.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Perf Tips"));
    final Project project = app.getProject();
    final MessageBusConnection bus = project.getMessageBus().connect(project);
    final FileEditorManagerListener listener = new FileEditorManagerListener() {
      @Override
      public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        setSelectedEditor(event.getNewEditor());
      }
    };
    final FileEditor[] selectedEditors = FileEditorManager.getInstance(project).getSelectedEditors();
    if (selectedEditors.length > 0) {
      setSelectedEditor(selectedEditors[0]);
    }
    bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, listener);

    Disposer.register(parentDisposable, () -> {
      // TODO(jacobr): unsubscribe?
    });
    timer = new Timer(1000 / UI_FPS, this::onFrame);
    ;
  }

  private void onFrame(ActionEvent event) {
    final FlutterWidgetPerf stats = perfManager.getCurrentStats();
    if (stats != null) {
      long latestPerfUpdate = stats.getLastLocalPerfEventTime();
      // Only do work if new performance stats have been recorded.
      if (latestPerfUpdate != lastUpdateTime) {
        lastUpdateTime = latestPerfUpdate;
        // TODO(jacobr): update list of top results.
        updateTip();
      }
    }
  }

  void hidePerfTip() {
    remove(perfTips);
  }

  private void setSelectedEditor(FileEditor editor) {
    if (!(editor instanceof TextEditor)) {
      editor = null;
    }
    if (editor == currentEditor) {
      return;
    }
    currentRange = null;
    currentEditor = (TextEditor) editor;
    perfMessage.setText("");

    updateTip();
  }

  private void updateTip() {
    if (perfManager.getCurrentStats() == null) {
      return;
    }
    if (currentEditor == null) {
      hidePerfTip();
      return;
    }
    perfManager.getCurrentStats().getPerfLinter().getTipsFor(currentEditor).whenCompleteAsync((tips, throwable) -> {
      if (tips == null || throwable != null || tips.isEmpty()) {
        hidePerfTip();
        return;
      }
      showPerfTips(tips);
    });
  }

  private void showPerfTips(ArrayList<PerfTip> tips) {
    final GridBagConstraints constraints = new GridBagConstraints();
    perfTips.removeAll();;
    int tipIndex = 0;
    for (PerfTip tip : tips) {
      constraints.gridx = 0;
      constraints.gridy = tipIndex++;
      constraints.weightx = 1.0;
      constraints.fill = GridBagConstraints.BOTH;
      perfTips.add(new LinkLabel<>(tip.getMessage(), tip.getRule().getIcon(), linkListener, tip), constraints);
    }

    constraints.gridx = 0;
    constraints.gridy = 1;
    constraints.weighty = 1.0;
    constraints.fill = GridBagConstraints.BOTH;
    add(perfTips, constraints);
  }

  public void setPerfStatusMessage(FileEditor editor, TextRange range, String message) {
    setSelectedEditor(editor);
    currentRange = range;
    perfMessage.setText(message);
  }

  public void setVisibleToUser(boolean visible) {
    if (visible != this.visible) {
      this.visible = visible;
      if (visible) {
        timer.start();
      } else {
        timer.stop();
      }
    }
  }
}
