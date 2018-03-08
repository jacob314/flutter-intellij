/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.utils;

import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBScrollBar;
import io.flutter.inspector.DiagnosticsNode;
import javafx.animation.Interpolator;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.lang.System;
import java.util.concurrent.locks.Lock;

import static java.lang.Math.abs;
import static java.lang.Math.max;

/**
 * This class provides animated scrolling of large scrollable tree views.
 *
 * The tree is scrolled horizontally so that as many rows are possible are in
 * view and scrolled vertically so that a specified row and all of its properties
 * are in view. All scrolling operations are animated to improve usability.
 *
 * This class assumes the tree is a tree of DefaultMutableTreeNode objects
 * where the userData values are either DiagnosticsNode objects or
 * placeholder leaf nodes. See InspectorPanel which manages a tree of nodes
 * meeting these requirments.
 */
public class DiagnosticsTreeScrollAnimator implements Disposable {

  /**
   * Scrollbar that can be locked to prevent user triggered scroll.
   *
   * The purpose of this class is to improve behavior handling mouse wheel
   * scroll triggered by a trackpad where it can be easy to accidentally
   * trigger both horizontal and vertical scroll. This class can be used to
   * help avoid spurious scroll in that case.
   */
  class LockableScrollbar extends JBScrollBar {
    boolean allowScroll;

    LockableScrollbar(int orientation) {
      super(orientation);
      allowScroll = true;
    }

    void setAllowScroll(boolean value) {
      allowScroll = value;
    }

    @Override
    public boolean isVisible() {
      return allowScroll && super.isVisible();
    }
  }

  private final JTree tree;
  private final JScrollPane scrollPane;
  private final Timer timer;
  private final Timer scrollIdleTimer;
  private TreePath target;
  private long animationStartTime;
  private final LockableScrollbar[] scrollbars = {new LockableScrollbar(JScrollBar.HORIZONTAL), new LockableScrollbar(JScrollBar.VERTICAL)};

  /**
   * Last time the user initiated a scroll directly using a scrollbar.
   */
  private long lastScrollTime;
  private int activeScrollbar = JScrollBar.NO_ORIENTATION;

  double animationDuration;

  /**
   * Maximum amount to attempt to keep the left side of the tree indented by.
   */
  double maxTargetLeftIndent = 75.0;

  static final double minHorizontalDistanceToTrigger = 50.0;
  static final int MS_DELAY_BEFORE_CHANGING_SCROLL_AXIS = 100;
  static final int DEFAULT_ANIMATION_DURATION = 150;

  private Point start;
  private Point end;

  Interpolator interpolator;

  private boolean scrollTriggeredByCode = false;
  private Point scrollPosition;

  public DiagnosticsTreeScrollAnimator(JTree tree, JScrollPane scrollPane) {
    this.tree = tree;
    this.scrollPane = scrollPane;
    // Target 60fps which is perhaps a bit ambitious.
    timer = new Timer(1000 / 60, this::onFrame);
    scrollIdleTimer = new Timer(MS_DELAY_BEFORE_CHANGING_SCROLL_AXIS, this::onScrollIdle);
    scrollPane.setHorizontalScrollBar(scrollbars[JScrollBar.HORIZONTAL]);
    scrollPane.setVerticalScrollBar(scrollbars[JScrollBar.VERTICAL]);

    scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
    scrollPane.getVerticalScrollBar().getModel().addChangeListener(this::verticalScrollChanged);
    scrollPane.getHorizontalScrollBar().getModel().addChangeListener(this::horizontalScrollChanged);
    scrollPosition = scrollPane.getViewport().getViewPosition();
  }

  /**
   * @return whether the scroll in the specified axis was an allowed regular user scroll.
   */
  private boolean handleScrollChanged()  {
    Point last = scrollPosition;
    scrollPosition = scrollPane.getViewport().getViewPosition();
    int dx = scrollPosition.x - last.x;
    int dy = scrollPosition.y - last.y;
    if (dx == 0 && dy == 0) {
      return true;
    }
    int orientation = abs(dy) >= abs(dx) ? JScrollBar.VERTICAL : JScrollBar.HORIZONTAL;
    if (scrollTriggeredByCode || timer.isRunning()) {
      return false;
    }
    System.out.println("Delta = " + dx +", " + dy + "   position = " + scrollPosition);

    if (activeScrollbar != JScrollBar.NO_ORIENTATION && activeScrollbar != orientation) {
      // This should only occur if  vertical and horizontal scrolling was initiated at the same time.
      return false;
    }

    lastScrollTime = System.currentTimeMillis();
    setActiveScrollbar(orientation);
    scrollIdleTimer.restart();
    return true;
  }

  private void setActiveScrollbar(int orientation) {
    if (activeScrollbar != orientation) {
      System.out.println("XX orientation set to=" + orientation);
      activeScrollbar = orientation;
      /*
      scrollPane.setHorizontalScrollBarPolicy(activeScrollbar == JScrollBar.VERTICAL
                                              ? ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                                              : ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      scrollPane.setVerticalScrollBarPolicy(activeScrollbar == JScrollBar.HORIZONTAL
                                            ? ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                                            : ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
      // Unfortunately changing the policy confuses the scroll bars unless we do this.
      */
      for (int axis = 0; axis <= 1; ++axis) {
        int otherAxis = 1 - axis;
        scrollbars[axis].setAllowScroll(orientation != otherAxis);
      }
    }
  }

  private void horizontalScrollChanged(ChangeEvent e) {
    handleScrollChanged();
  }

  private void verticalScrollChanged(ChangeEvent e) {
    handleScrollChanged();
    if (target != null) {
      return;
    }
    final Point current = scrollPosition;
    final int rowStart = tree.getClosestRowForLocation(current.x, current.y);
    final int rowEnd = tree.getClosestRowForLocation(current.x, current.y + scrollPane.getHeight() - 1);
    Rectangle union = null;
    for (int i = rowStart; i <= rowEnd; ++i) {
      final Rectangle bounds = tree.getRowBounds(i);
      union = union == null ? bounds : union.union(bounds);
    }
    if (union == null) {
      // No rows in view.
      return;
    }

    final int targetX = Math.max(union.x - (int)maxTargetLeftIndent, 0);
    if (!timer.isRunning()) {
      if (Math.abs(current.x - targetX) <  minHorizontalDistanceToTrigger) {
        // No need to animmate... we are good enough.
        return;
      }
    }
    animateToX(targetX);
  }

  public void animateTo(DefaultMutableTreeNode node) {
    if (node != null) {
      animateTo(new TreePath(node.getPath()));
    }
  }

  private void animateToX(int x) {
    target = null;
    start = scrollPane.getViewport().getViewPosition();
    end = new Point(x, start.y);
    final long currentTime = System.currentTimeMillis();

    if (timer.isRunning()) {
      interpolator = Interpolator.LINEAR;
      animationDuration = DEFAULT_ANIMATION_DURATION;
    } else {
      // We have the same target but that target's position has changed.
      // Adjust the animation duration to account for the time we have left
      // ensuring the animation proceeds for at least half the default animation
      // duration.
      animationDuration = Math.max(DEFAULT_ANIMATION_DURATION / 2, animationDuration - (currentTime - animationStartTime));
      // Ideally we would manage the velocity keeping it consistent
      // with the existing velocity at the start of the animation
      // but this is good enough. We use EASE_OUT assuming the
      // animation was already at a moderate speed when the
      // destination position was updated.

      interpolator = Interpolator.LINEAR;
    }
    animationStartTime = currentTime;

    if (!timer.isRunning()) {
      timer.start();
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
    start = scrollPane.getViewport().getViewPosition();
    // Grow bound up to half the width of the window to the left so that
    // connections to ancestors are still visible. Otherwise, the window could
    // get scrolled so that ancestors are all hidden with the new target placed
    // on the left side of the window.
    final double minX = max(0.0, bounds.getMinX() - Math.min(scrollPane.getWidth() * 0.5, maxTargetLeftIndent));
    final double maxX = bounds.getMaxX();
    final double y = bounds.getMinY();
    final double height = bounds.getHeight();
    bounds.setRect(minX, y, maxX - minX, height);
    if (target.getLastPathComponent() instanceof DefaultMutableTreeNode) {
      // Make sure we scroll so that immediate un-expanded children
      // are also in view. There is no risk in including these children as
      // the amount of space they take up is bounded. This ensures that if
      // a node is selected, its properties will also be selected.
      DefaultMutableTreeNode node = (DefaultMutableTreeNode)target.getLastPathComponent();
      // Find the first non-property parent.
      for (int i = target.getPathCount() - 1; i >= 0; i--) {
        node = (DefaultMutableTreeNode)target.getPathComponent(i);
        final Object userObject = node.getUserObject();
        if (userObject instanceof DiagnosticsNode && !((DiagnosticsNode)userObject).isProperty()) {
          break;
        }
      }
      for (int i = 0; i < node.getChildCount(); ++i) {
        final DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
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
    scrollTriggeredByCode = true;
    tree.scrollRectToVisible(bounds);
    end = scrollPane.getViewport().getViewPosition();
    scrollPane.getViewport().setViewPosition(start);
    scrollTriggeredByCode = false;
    if (start.y == end.y && Math.abs(start.x - end.x) < minHorizontalDistanceToTrigger && !timer.isRunning()) {
      // Abort as the animation would only be horizontal and we don't exceed the minimum to trigger.
      return;
    }

    this.target = target;

    final long currentTime = System.currentTimeMillis();

    if (newTarget) {
      interpolator = Interpolator.EASE_BOTH;
      animationDuration = DEFAULT_ANIMATION_DURATION;
    } else {
      // We have the same target but that target's position has changed.
      // Adjust the animation duration to account for the time we have left
      // ensuring the animation proceeds for at least half the default animation
      // duration.
      animationDuration = Math.max(DEFAULT_ANIMATION_DURATION / 2, animationDuration - (currentTime - animationStartTime));
      // Ideally we would manage the velocity keeping it consistent
      // with the existing velocity at the start of the animation
      // but this is good enough. We use EASE_OUT assuming the
      // animation was already at a moderate speed when the
      // destination position was updated.

      interpolator = Interpolator.EASE_OUT;
    }
    animationStartTime = currentTime;

    if (!timer.isRunning()) {
      timer.start();
    }
  }

  private void setScrollPosition(int x, int y) {
    scrollPosition = new Point(x, y);
    scrollTriggeredByCode = true;
    scrollPane.getViewport().setViewPosition(scrollPosition);
    scrollTriggeredByCode = false;
  }

  private void onFrame(ActionEvent e) {
    final long now = System.currentTimeMillis();
    final long delta = now - animationStartTime;
    final double fraction = Math.min((double) delta / animationDuration, 1.0);
    final boolean animateX = start.x != end.x;
    final boolean animateY = start.y != end.y;
    final Point current = scrollPane.getViewport().getViewPosition();
    final int x = animateX ? interpolator.interpolate(start.x, end.x, fraction) : current.x;
    final int y = animateY ? interpolator.interpolate(start.y, end.y, fraction) : current.y;
    setScrollPosition(x, y);
    if (fraction >= 1.0) {
      target = null;
      timer.stop();
    }
  }

  private void onScrollIdle(ActionEvent e) {
    setActiveScrollbar(JScrollBar.NO_ORIENTATION);
  }

  @Override
  public void dispose() {
    if (timer.isRunning()) {
      timer.stop();
    }
    if (scrollIdleTimer.isRunning()) {
      scrollIdleTimer.stop();
    }
  }
}
