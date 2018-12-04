/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.ui.JBColor;
import com.intellij.ui.LineEndDecorator;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;

public class TipDiagram extends JPanel {
  public static final int BACKGROUND_PADDING = 5;
  public static final int BACKGROUND_ALPHA = 0;
  public static final double TIP_ARROW_SLOPE = 0.7;
  public static final JBColor BACKGROUND_COLOR = new JBColor(
    new Color(255, 255, 255, BACKGROUND_ALPHA),
    new Color(43, 43, 43, BACKGROUND_ALPHA)
  );

  public static final JBColor BACKGROUND_COLOR_SHADOW = new JBColor(
    new Color(255, 255, 255, 230),
    new Color(43, 43, 43, 230)
  );

  private final ArrayList<TutorialDiagramTip> tips;
  private final Rectangle targetComponentRect;
  private final Rectangle tipArea;

  TipDiagram(ArrayList<TutorialDiagramTip> tips, Rectangle targetComponentRect) {
    this.targetComponentRect = targetComponentRect;
    this.tips = tips;
    setOpaque(false);
    setSize(new Dimension(1,1));
    setPreferredSize(new Dimension(1,1));
    setMaximumSize(new Dimension(1,1));

    tipArea = paintHelper(null);
  }

  public void showTip() {
    setVisible(true);
    final Rectangle visibleRect = paintHelper(null);
    final Dimension tipSize = visibleRect.getSize();
    setSize(tipSize);
    setPreferredSize(tipSize);
    setMaximumSize(tipSize);
  }

  @Override
  public void paintComponent(Graphics g) {
    super.paintComponent(g);
    final Graphics2D gr = (Graphics2D) g; //this is if you want to use Graphics2D
    final Object old = gr.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
    gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    gr.setColor(BACKGROUND_COLOR);
    gr.fillRect(0, BACKGROUND_PADDING, tipArea.width, tipArea.height - BACKGROUND_PADDING);

    paintHelper(gr);

    gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, old);
  }

  private Rectangle paintHelper(Graphics2D gr) {
    Rectangle recommendedTipArea = targetComponentRect;
    if (gr != null) {
      gr.setFont(UIUtil.getLabelFont(UIUtil.FontSize.NORMAL));
      assert(tipArea != null);
      gr.translate(-tipArea.x, -tipArea.y);
    }

    final FontMetrics metrics = getFontMetrics(UIUtil.getLabelFont(UIUtil.FontSize.NORMAL));

    final int lineHeight = metrics.getHeight() + 10;
    final int hPadding = 5;
    int targetY = 50 + targetComponentRect.y;
    for (TutorialDiagramTip tip : tips) {
      final int x = tip.location.x;
      final int y = tip.location.y;
      // Ensure arrows aren't too short.
      targetY = Math.max(y + 30, targetY);
      final int targetX = (int)(x - (targetY - y) / TIP_ARROW_SLOPE);
      final String message = tip.message;
      final Rectangle2D bounds = metrics.getStringBounds(message, gr);

      if (gr != null) {
        final Line2D line = new Line2D.Double(targetX, targetY, x, y);
        final Shape arrow = LineEndDecorator.getArrowShape(line, line.getP2());
        int BORDER_SHADOW_STEPS = 11;
        for (int i = 0; i < BORDER_SHADOW_STEPS; i++) {
          gr.setStroke(new BasicStroke(3 + i * 2));
          gr.setColor(new Color(BACKGROUND_COLOR_SHADOW.getRed(),
                                BACKGROUND_COLOR_SHADOW.getGreen(),
                                BACKGROUND_COLOR_SHADOW.getBlue(),
                                BACKGROUND_COLOR_SHADOW.getAlpha() / 6
          ));
          gr.drawLine(x, y, targetX, targetY);
          gr.draw(arrow);

          if (i < 3) {
            i++;
          }
          if (i < 6) {
            i++;
          }
        }

        gr.setStroke(new BasicStroke(1));
        gr.setColor(JBColor.black);
        gr.drawLine(x, y, targetX, targetY);
        gr.fill(arrow);
      }

      final Rectangle lineRect = new Rectangle(targetX - BACKGROUND_PADDING, y - BACKGROUND_PADDING, x - targetX + BACKGROUND_PADDING * 2, targetY - y + BACKGROUND_PADDING * 2);
      recommendedTipArea = recommendedTipArea.union(lineRect);
      int textX = (int)(targetX - bounds.getWidth() - hPadding);
      int textY = (int)(targetY + (bounds.getHeight() / 2) );
      final Rectangle textBounds = new Rectangle((int)(textX + bounds.getX()), (int)(textY + bounds.getY()), (int)bounds.getWidth(), (int)bounds.getHeight());
      textBounds.grow(BACKGROUND_PADDING, BACKGROUND_PADDING);
      if (gr != null) {
        gr.setStroke(new BasicStroke(1));
        for (int i = 0; i < 5;  i++) {
          gr.setColor(new Color(BACKGROUND_COLOR_SHADOW.getRed(),
                                BACKGROUND_COLOR_SHADOW.getGreen(),
                                BACKGROUND_COLOR_SHADOW.getBlue(),
                                BACKGROUND_COLOR_SHADOW.getAlpha() * (5 -  i) / 5
                                ));
          gr.drawRect(textBounds.x - i, textBounds.y - i, textBounds.width + i * 2, textBounds.height + i * 2);
        }

        gr.setColor(BACKGROUND_COLOR_SHADOW);
        gr.fillRect(textBounds.x, textBounds.y, textBounds.width, textBounds.height);

        gr.setColor(JBColor.black);
        gr.drawString(message, textX, textY);
      }
      recommendedTipArea = recommendedTipArea.union(textBounds);
      targetY += lineHeight;
    }
    if (gr != null) {
      gr.translate(tipArea.x, tipArea.y);
    }
    return recommendedTipArea;
  }

  public Point getTipOffset() {
    return tipArea.getLocation();
  }
}
