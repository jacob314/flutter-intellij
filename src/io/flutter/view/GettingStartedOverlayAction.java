/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.execution.ui.layout.impl.JBRunnerTabs;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.TabLabel;
import com.intellij.util.ui.UIUtil;
import io.flutter.inspector.InspectorTree;
import io.flutter.run.daemon.FlutterApp;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Objects;

public class GettingStartedOverlayAction extends DumbAwareAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    // TODO(jacobr): determine which windows are open and show tips as appropriate.
    showInspectorViewMessage(event.getProject());
  }

  private void showInspectorViewMessage(Project project) {
    /* XXX
    final FlutterApp flutterApp = FlutterApp.fromProjectProcess(project);
    final FlutterView flutterView = ServiceManager.getService(project, FlutterView.class);
    final FlutterView.PerAppState state = flutterView.getStateForApp(flutterApp);
    if (state == null) {
      return;
    }
    JComponent component = state.content.getComponent();
    final Window mainWindow = UIUtil.getWindow(component);
    Component lastFocused = mainWindow.getFocusOwner();
    final JBPopupFactory popupFactory = JBPopupFactory.getInstance();

    System.out.println("XXX lastFocused=" + lastFocused);
    //    state.flutterViewActions.get(0).getTemplatePresentation().get

    final TipListBuilder tipListBuilder = new TipListBuilder(component);

    if (lastFocused instanceof InspectorTree) {
      // Show detailed tips in this case
      final JBRunnerTabs tabs = (JBRunnerTabs)component.getComponents()[0];
      InspectorPanel panel = null;
      for (TabInfo tab : tabs.getTabs()) {
        String label = null;
        switch (tab.getText()) {
          case "Widgets":
            label = "Most useful tab";
            if (tab.getComponent() instanceof InspectorPanel) {
              panel = (InspectorPanel) tab.getComponent();
            }
            break;
          case "Render Tree":
            label = "Painting and Layout Debugging";
            break;
          case "Performance":
            label = "Moved to Flutter Performance Window";
            break;
        }
        if (label != null) {
          tipListBuilder.addTip(tabs.getTabLabel(tab), label, true, 0, 0);
        }
      }
      if (panel != null) {
        tipListBuilder.addTip(panel.treeScrollPane, "Summary tree of widgets created\nby local project", 0.0, 0.7);
        tipListBuilder.addTip(panel.subtreePanel.treeScrollPane, "Details for selected\nwidget from summary tree", 0.0, 0.05);
      }
    } else {
      final ActionToolbarImpl toolbar = (ActionToolbarImpl)component.getComponents()[1];
      for (Component item : toolbar.getComponents()) {
        if (item instanceof ActionButton) {
          final ActionButton button = (ActionButton)item;
          final String tooltip = button.getToolTipText();
          tipListBuilder.addTip(button, tooltip, true, 0, 2);
        }
        else if (item instanceof JPanel) {
          final Component[] childComponents = ((JPanel)item).getComponents();
          if (childComponents.length == 1 && childComponents[0] instanceof ToolbarComboBoxAction.ToolbarComboBoxButton) {
            final ToolbarComboBoxAction.ToolbarComboBoxButton button = (ToolbarComboBoxAction.ToolbarComboBoxButton)childComponents[0];
            String tooltip = button.getToolTipText();
            if (tooltip == null) {
              tooltip = "Advanced Options";
            }
            tipListBuilder.addTip(button, tooltip, true, 0, 2);
          }
        }
      }
    }

    final Rectangle visibleRect = component.getVisibleRect();

    // Shrink visibleRect to match startY.
    visibleRect.height = visibleRect.height - tipListBuilder.startY + visibleRect.y;
    visibleRect.y = tipListBuilder.startY;


    final TipDiagram tip = new TipDiagram(tipListBuilder.tips, visibleRect);
    final RelativePoint p = new RelativePoint(component, tip.getTipOffset());

    final JBPopup popup =
      popupFactory.createComponentPopupBuilder(new NonOpaquePanel(tip), null)
        .setRequestFocus(false).setResizable(false).setMovable(false)
        .setShowBorder(false)
        .setShowShadow(false)
        .createPopup();

    popup.show(p);
    JComponent c = popup.getContent();
    while (c != null) {
      c.setOpaque(false);
      c.setBackground(new Color(0,0,0,0));
      Container parent = c.getParent();
      if (parent instanceof JComponent) {
        c = (JComponent) parent;
      } else {
        if (parent instanceof JWindow) {
          final JWindow window = (JWindow)parent;
          window.setBackground(new Color(0,0,0,0));
          tip.showTip();
          window.pack();
          window.invalidate();
          window.repaint();
        }
        break;
      }
    }
    final long startTime = System.currentTimeMillis();
    tip.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        super.mouseClicked(e);
        // Don't dispose if the popup was just opened.
        if (System.currentTimeMillis() > startTime + 500) {
          if (!popup.isDisposed()) {
            popup.dispose();
          }
        }
      }
    });
    */
  }

  private class TipListBuilder {
    final Point origin;

    int startY = 0;

    ArrayList<TutorialDiagramTip> tips = new ArrayList<>();

    TipListBuilder(JComponent root) {
      this.origin = root.getLocationOnScreen();
    }

    void addTip(JComponent component, String label, boolean modifyStartY, int dx, int dy) {
      final Point location = new Point(component.getLocationOnScreen());
      location.translate(-origin.x, -origin.y);
      final Dimension size = component.getSize();
      location.translate(size.width / 2  + dx, size.height + dy);
      startY = Math.max(startY, location.y);
      tips.add(new TutorialDiagramTip(label, location));
    }

    void addTip(JComponent component, String label, double fractionX, double fractionY) {
      final Point location = new Point(component.getLocationOnScreen());
      location.translate(-origin.x, -origin.y);
      final Dimension size = component.getSize();
      location.translate((int)(size.width * fractionX), (int)(size.height * fractionY));
      tips.add(new TutorialDiagramTip(label, location));
    }
  }
}
