/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.flutter.run.daemon.FlutterApp;
import org.dartlang.vm.service.VmService;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import java.awt.*;

// TODO: custom layout

public class FpsView extends JComponent {
  private static final int LEFT_RIGHT = 5;
  private static final int TOP_BOTTOM = 3;

  FpsView() {
    setOpaque(true);

    setLayout(new BorderLayout());
    add(BorderLayout.WEST, new ViewLabel("60 FPS"));

    setBorder(new CompoundBorder(
      JBUI.Borders.customLine(UIUtil.getSeparatorColor(), 1, 0, 0, 0),
      new EmptyBorder(4, 4, 4, 4)
    ));

    setPreferredSize(new Dimension(0, 60));
  }

  public void updateUI() {
    super.updateUI();

    setBorder(new CompoundBorder(
      JBUI.Borders.customLine(UIUtil.getSeparatorColor(), 1, 0, 0, 0),
      new EmptyBorder(4, 4, 4, 4)
    ));
  }

  public void connectionClosed() {
    // TODO:

  }

  public void debugActive(FlutterApp app, VmService service) {
    // TODO:

  }

  @Override
  protected void paintComponent(Graphics g) {
    // this custom component does not have a corresponding UI,
    // so we should care of painting its background
    if (isOpaque()) {
      g.setColor(getBackground());
      g.fillRect(0, 0, getWidth(), getHeight());
    }
    //if (g instanceof Graphics2D) {
    //  Graphics2D g2d = (Graphics2D)g;
    //  g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    //  g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, getKeyForCurrentScope(!Registry.is("editor.breadcrumbs.system.font")));
    //  for (Breadcrumbs.CrumbView view : views) {
    //    if (view.crumb != null) view.paint(g2d);
    //  }
    //}
  }

  @Override
  public Color getBackground() {
    if (!isBackgroundSet()) {
      final Color background = EditorColorsManager.getInstance().getGlobalScheme().getColor(EditorColors.GUTTER_BACKGROUND);
      if (background != null) {
        return background;
      }
    }
    return super.getBackground();
  }
}
