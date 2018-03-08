/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.utils;

import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBScrollPane;
import javafx.animation.Interpolator;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.lang.System;

import static java.lang.Math.max;

public class TreeScrollAnimator implements Disposable {

  private final JTree tree;
  private final JScrollPane scrollPane;
  private final Timer timer;
  private TreePath target;
  private long startTime;

  static final double defaultAnimationDuration = 150.0;
  double animationDuration;

  /**
   * Maximum amount to attempt to keep the left side of the tree indented by.
   */
  double maxTargetLeftIndent = 75.0;

  private Point start;
  private Point end;

  Interpolator interpolator;

  public TreeScrollAnimator(JTree tree, JScrollPane scrollPane) {
    this.tree = tree;
    this.scrollPane = scrollPane;
    // Target 60fps which is perhaps a bit ambitious.
    timer = new Timer(1000 / 60, null);
    timer.addActionListener(this::onFrame);
  }

  public void animateTo(DefaultMutableTreeNode node) {
    if (node != null) {
      animateTo(new TreePath(node.getPath()));
    }
  }

  public void animateTo(TreePath target) {
    if (target == null) {
      return;
    }
    Rectangle bounds = tree.getPathBounds(target);
    if (bounds == null) {
      // The target is the child of a collapsed node.
      return;
    }

    final boolean newTarget = !target.equals(this.target);

    if (bounds == null) {
      // The  target isn't actually visible.
      return;
    }

    this.target = target;
    start = scrollPane.getViewport().getViewPosition();

    // Grow bound up to half the width of the window to the left so that
    // connections to ancestors are still visible. Otherwise, the window could
    // get scrolled so that ancestors are all hidden with the new target placed
    // on the left side of the window.
    double minX = max(0.0, bounds.getMinX() - Math.min(scrollPane.getWidth() * 0.5, maxTargetLeftIndent));
    double maxX = bounds.getMaxX();
    double y = bounds.getMinY();
    double height = bounds.getHeight();
    bounds.setRect(minX, y, maxX - minX, height);
    if (target.getLastPathComponent() instanceof DefaultMutableTreeNode) {
      // Make sure we scroll so that immediate un-expanded children
      // are also in view. There is no risk in including these children as
      // the amount of space they take up is bounded. This ensures that if
      // a node is selected, its properties will also be selected.
      DefaultMutableTreeNode node = (DefaultMutableTreeNode)target.getLastPathComponent();
      for (int i = 0; i < node.getChildCount(); ++i) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
        final TreePath childPath = new TreePath(child.getPath());
        final Rectangle childBounds = tree.getPathBounds(childPath);
        if (childBounds != null) {
          bounds = bounds.union(childBounds);
        }
        if (!child.isLeaf() && tree.isExpanded(childPath)) {
          // Stop if we get to expanded children as they might be too large
          // to try to scroll into view.
          break;
        }
      }
    }
    // We need to clarify that we care most about keeping the top left corner
    // of the bounds in view by clamping if our bounds are larger than the viewport.
    bounds.setSize(Math.min(bounds.width, scrollPane.getViewport().getWidth()),
                  Math.min(bounds.height, scrollPane.getViewport().getHeight()));
    tree.scrollRectToVisible(bounds);
    end = scrollPane.getViewport().getViewPosition();
    scrollPane.getViewport().setViewPosition(start);
    long currentTime = System.currentTimeMillis();

    if (newTarget) {
      interpolator = Interpolator.EASE_BOTH;
      animationDuration = defaultAnimationDuration;
    } else {
      // We have the same target but that target's position has changed.
      // Adjust the animation duration to account for the time we have left
      // ensuring the animation proceeds for at least half the default animation
      // duration.
      animationDuration = Math.max(defaultAnimationDuration / 2, animationDuration - (currentTime - startTime));
      // Ideally we would manage the velocity keeping it consistent
      // with the existing velocity at the start of the animation
      // but this is good enough. We use EASE_OUT assuming the
      // animation was already at a moderate speed when the
      // destination position was updated.

      interpolator = Interpolator.EASE_OUT;
    }
    startTime = currentTime;

    if (!timer.isRunning()) {
      timer.start();
    }
  }

  private void onFrame(ActionEvent e) {
    final long now = System.currentTimeMillis();
    final long delta = now - startTime;
    final double fraction = Math.min((double) delta / animationDuration, 1.0);
    final int x = interpolator.interpolate(start.x, end.x, fraction);
    final int y = interpolator.interpolate(start.y, end.y, fraction);
    scrollPane.getViewport().setViewPosition(new Point(x, y));
    if (fraction >= 1.0) {
      target = null;
      timer.stop();
    }
  }

  @Override
  public void dispose() {
    if (timer.isRunning()) {
      timer.stop();
    }
  }
}
