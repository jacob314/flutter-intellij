/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.AppUIUtil;
import com.intellij.ui.Colors;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.JBUI;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XNavigatable;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree;
import com.intellij.xdebugger.impl.ui.tree.XInspectDialog;
import io.flutter.server.vmService.frame.DartVmServiceValue;
import io.flutter.FlutterInitializer;
import io.flutter.utils.AsyncUtils;
import org.intellij.images.ui.ImageComponent;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

public class ScreenshotAction extends InspectorTreeActionBase {
  final String id;

  public ScreenshotAction() {
    this("screenshot");
  }

  ScreenshotAction(String id) {
    this.id = id;
  }

  @Override
  protected void perform(DefaultMutableTreeNode node, DiagnosticsNode diagnosticsNode, AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) {
      return;
    }

    FlutterInitializer.getAnalytics().sendEvent("inspector", id);

    final InspectorService.ObjectGroup service = diagnosticsNode.getInspectorService();
    final double devicePixelRatio = JBUI.pixScale();
    service.safeWhenComplete(
      service.getScreenshot(diagnosticsNode.getValueRef(), 1000, 1000, true, devicePixelRatio),
      (BufferedImage image, Throwable t) -> {
        if (t != null) {
          // TODO(jacobr): explain what went wrong.
          // For example, image = null means the widget is not actively being
          // rendered so a screenshot cannot be shown.
          return;
        }
        final JComponent imageComponent = createScreenshotComponent(image, devicePixelRatio);

        final JBPopup popup = DebuggerUIUtil.createValuePopup(project, imageComponent, null);
        final JFrame frame = WindowManager.getInstance().getFrame(project);
        Dimension frameSize = frame.getSize();

        Dimension size = imageComponent.getPreferredSize();

        popup.setSize(size);
        popup.show(new RelativePoint(frame, new Point(size.width / 2, size.height / 2)));
      });
  }

  /**
   * Class to help determine whether portions of an image next to transparent
   * pixels are generally
   * decide whether to use a dark or light checkboard background.
   *
   * We evaluate the average brightness
   */
  static class ImageEdgeAnalysis {
    long totalBrightness;
    long totalSamples;

    ImageEdgeAnalysis(BufferedImage image) {
      final int w = image.getWidth();
      final int h = image.getHeight();

      final int stepX = Math.max(1, w / 30);
      final int stepY = Math.max(1, h / 30);
      for (int y = stepY*5; y < h - stepY* 5; y += stepY) {
        for (int x = stepX * 5; x < w - stepX * 5; x += stepX) {
          int pixel = image.getRGB(x, y);
          int alpha = (pixel >> 24) & 0xff;
          // Analyze neighbors of pixels that are generally transparent.
          if (alpha < 128) {
            analyzePixel(image.getRGB(x - stepX, y));
            analyzePixel(image.getRGB(x + stepX, y));
            analyzePixel(image.getRGB(x, y - stepY));
            analyzePixel(image.getRGB(x, y + stepY));
          }
        }
      }
    }

    private void analyzePixel(int pixel) {
      final int alpha = (pixel >> 24) & 0xff;
      // Skip pixels that are transparent.
      if (alpha < 10) {
        return;
      }
      final int red = (pixel >> 16) & 0xff;
      final int green = (pixel >> 8) & 0xff;
      final int blue = (pixel) & 0xff;
      final int avgChan = (red + green + blue) / 3;
      totalBrightness += avgChan;
      totalSamples++;
    }

    /**
     * Whether a dark or light background will probably provide better contrast
     * for the image
     */
    public boolean useDarkBackground() {
      if (totalSamples == 0) {
        // Doesn't really matter. The image appears to be opaque.
        return true;
      }
      double averageValue = (double)totalBrightness / (double)totalSamples;
      return averageValue >= 160;
    }
  }

  @NotNull
  public static JComponent createScreenshotComponent(BufferedImage image, double devicePixelRatio) {
    if (image == null) {
      return new JTextField("Image not available");
    }

    final ImageComponent imageComponent = new ImageComponent();
    imageComponent.setAutoscrolls(true);
    imageComponent.setTransparencyChessboardVisible(true);
    if (new ImageEdgeAnalysis(image).useDarkBackground()) {
      imageComponent.setTransparencyChessboardBlankColor(Color.darkGray);
      imageComponent.setTransparencyChessboardWhiteColor(Color.black);
    } else {
      imageComponent.setTransparencyChessboardBlankColor(Color.lightGray);
      imageComponent.setTransparencyChessboardWhiteColor(Color.white);
    }
    imageComponent.getDocument().setValue(image);
    imageComponent.setPreferredSize(new Dimension((int)(image.getWidth() / devicePixelRatio), (int)(image.getHeight() / devicePixelRatio)));
    return imageComponent;
  }
}
