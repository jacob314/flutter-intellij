/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.preview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.flutter.dart.FlutterDartAnalysisServer;
import io.flutter.editor.*;
import io.flutter.inspector.DiagnosticsNode;
import io.flutter.inspector.InspectorService;
import io.flutter.inspector.InspectorStateService;
import org.dartlang.analysis.server.protocol.FlutterOutline;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.Set;

public class PreviewArea implements Disposable {
  public static int BORDER_WIDTH = 0;

  private final DefaultActionGroup toolbarGroup = new DefaultActionGroup();
  private final ActionToolbar windowToolbar;

  private final SimpleToolWindowPanel window;

  private final JLayeredPane layeredPanel = new JLayeredPane();
  private final JPanel primaryLayer;

  private final PreviewViewController preview;
  private final WidgetEditingContext context;
  private final Set<FlutterOutline> outlinesWithWidgets;

  private InspectorService inspectorService;
  private InspectorService.ObjectGroup objectGroup;

  private boolean isDisposed;

  InspectorStateService.Listener inspectorListener = new InspectorStateService.Listener() {
    @Override
    public boolean isValid() {
      return !isDisposed;
    }

    @Override
    public void onSelectionChanged(DiagnosticsNode selection) {

    }
    @Override
    public void onInspectorAvailable(InspectorService service) {
      if (service == inspectorService) {
        return;
      }
      inspectorService = service;
      if (objectGroup != null) {
        objectGroup.dispose();
        ;
      }
      objectGroup = null;
    }

    @Override
    public void requestRepaint(boolean force) {

    }

    @Override
    public void onFlutterFrame() {

    }
  };

  public PreviewArea(Project project, Set<FlutterOutline> outlinesWithWidgets) {
    this.outlinesWithWidgets = outlinesWithWidgets;

    context = new WidgetEditingContext(
      project,
      FlutterDartAnalysisServer.getInstance(project),
      InspectorStateService.getInstance(project),
      EditorPositionService.getInstance(project)
    );

    context.inspectorStateService.addListener(inspectorListener);
    preview = new PreviewViewController(new WidgetViewModelData(context), false, layeredPanel);

    primaryLayer = new PreviewViewModelPanel(preview);

    windowToolbar = ActionManager.getInstance().createActionToolbar("PreviewArea", toolbarGroup, true);
    toolbarGroup.add(new TitleAction("Device Mirror"));

    window = new SimpleToolWindowPanel(true, true);
    window.setToolbar(windowToolbar.getComponent());

    primaryLayer.setLayout(new BorderLayout());

    window.setContent(layeredPanel);
    layeredPanel.add(primaryLayer, Integer.valueOf(0));

    // Layers should cover the whole root panel.
    layeredPanel.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        final Dimension renderSize = getRenderSize();
        preview.setScreenshotBounds(new Rectangle(0, 0, renderSize.width, renderSize.height));
      }
    });
    }

  /**
   * Return the Swing component of the area.
   */
  public JComponent getComponent() {
    return window;
  }

  public void select(@NotNull List<FlutterOutline> outlines, Editor editor) {
    if (editor.isDisposed()) return;

    final InspectorService.ObjectGroup group = getObjectGroup();
    if (group != null && outlines.size() > 0) {
      FlutterOutline outline = findWidgetToHighlight(outlines.get(0));
      if (outline == null) return;
      final InspectorService.Location location = InspectorService.Location.outlineToLocation(editor, outline);
      if (location == null) return;
      group.setSelection(location, false , true);
    }
  }

  /**
   * Find the first descendant of the outline that is a widget as long as there
   * is only one path down the tree that leeds to a widget.
   */
  private FlutterOutline findWidgetToHighlight(FlutterOutline outline) {
    if (outline == null || outline.getClassName() != null) return outline;
    if (!outlinesWithWidgets.contains(outline)) return null;
    FlutterOutline candidate = null;
    for (FlutterOutline child : outline.getChildren()) {
      if (outlinesWithWidgets.contains(child)) {
        if (candidate != null) {
          // It is not unambiguous candidate to show so don't show anything.
          // TODO(jacobr): consider showing multiple locations instead if the
          // inspector on device protocol is enhanced to support that.
          return null;
        }
        candidate = findWidgetToHighlight(child);
      }
    }
    return candidate;
  }

  public Dimension getRenderSize() {
    final int width = layeredPanel.getWidth();
    final int height = layeredPanel.getHeight();
    for (Component child : layeredPanel.getComponents()) {
      child.setBounds(0, 0, width, height);
    }

    final int renderWidth = width - 2 * BORDER_WIDTH;
    final int renderHeight = height - 2 * BORDER_WIDTH;
    return new Dimension(renderWidth, renderHeight);
  }

  @Override
  public void dispose() {
    isDisposed = true;
    if (objectGroup != null) {
      objectGroup.dispose();
    }
    context.inspectorStateService.removeListener(inspectorListener);
    Disposer.dispose(this);
  }

  InspectorService.ObjectGroup getObjectGroup() {
    if (objectGroup != null || inspectorService == null) {
      return objectGroup;
    }
    objectGroup = inspectorService.createObjectGroup("preview-area");
    return objectGroup;
  }
}

class TitleAction extends AnAction implements CustomComponentAction {
  TitleAction(String text) {
    super(text);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
  }

  @Override
  public JComponent createCustomComponent(Presentation presentation) {
    final JPanel panel = new JPanel(new BorderLayout());

    // Add left border to make the title look similar to the tool window title.
    panel.setBorder(BorderFactory.createEmptyBorder(0, JBUI.scale(3), 0, 0));

    final String text = getTemplatePresentation().getText();
    panel.add(new JBLabel(text != null ? text : "", UIUtil.ComponentStyle.SMALL));

    return panel;
  }
}