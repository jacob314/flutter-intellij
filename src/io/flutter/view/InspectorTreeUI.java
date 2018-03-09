/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.WideSelectionTreeUI;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;

/**
 * This class has some redundant code with BasicTreeUI and it could be simpler to subclass
 * BasicTreeUI directly instead.
 *
 * This UI class varies from WideSelectionTreeUI by drawing lines showing nodes with multiple children.
 */
class InspectorTreeUI extends WideSelectionTreeUI {
  boolean leftToRight = true; /// XXX LOOKUP.
  private boolean isUnderDarcula;

  InspectorTreeUI(boolean isUnderDarcula) {
    super(false, (Integer value) -> false);
    this.isUnderDarcula = isUnderDarcula;
  }

  public boolean isUnderDarcula() {
    return isUnderDarcula;
  }

  @Override
  protected void paintExpandControl(Graphics g,
                                    Rectangle clipBounds,
                                    Insets insets,
                                    Rectangle bounds,
                                    TreePath path,
                                    int row,
                                    boolean isExpanded,
                                    boolean hasBeenExpanded,
                                    boolean isLeaf) {
    boolean isPathSelected = tree.getSelectionModel().isPathSelected(path);
    if (!isLeaf(row)) {
      setExpandedIcon(UIUtil.getTreeNodeIcon(true, false, false));
      setCollapsedIcon(UIUtil.getTreeNodeIcon(false, false, false));
    }

    paintExpandControlHelper(g, clipBounds, insets, bounds, path, row, isExpanded, hasBeenExpanded, isLeaf);
  }

  /**
   * Paints the expand (toggle) part of a row. The receiver should
   * NOT modify <code>clipBounds</code>, or <code>insets</code>.
   */
  /// METHOD FROM BasicTreeUI we can't modify. TODO(jacobr): consider extending that class directly for less FOOBAR.
  protected void paintExpandControlHelper(Graphics g,
                                    Rectangle clipBounds, Insets insets,
                                    Rectangle bounds, TreePath path,
                                    int row, boolean isExpanded,
                                    boolean hasBeenExpanded,
                                    boolean isLeaf) {
    Object value = path.getLastPathComponent();

    // Draw icons if not a leaf and either hasn't been loaded,
    // or the model child count is > 0.
    if (!isLeaf && (!hasBeenExpanded ||
                    treeModel.getChildCount(value) > 0)) {
      int middleXOfKnob;
      if (leftToRight) {
        middleXOfKnob = bounds.x - getRightChildIndent() + 1;
      } else {
        middleXOfKnob = bounds.x + bounds.width + getRightChildIndent() - 1;
      }
      int middleYOfKnob = bounds.y + (bounds.height / 2);

      if (isExpanded) {
        Icon expandedIcon = getExpandedIcon();
        if(expandedIcon != null)
          drawCentered(tree, g, expandedIcon, middleXOfKnob,
                       middleYOfKnob );
      }
      else {
        Icon collapsedIcon = getCollapsedIcon();
        if(collapsedIcon != null)
          drawCentered(tree, g, collapsedIcon, middleXOfKnob,
                       middleYOfKnob);
      }
    }
  }


  @Override
  /**
   * Paints the vertical part of the leg. The receiver should
   * NOT modify <code>clipBounds</code>, <code>insets</code>.
   */
  protected void paintVerticalPartOfLeg(Graphics g, Rectangle clipBounds,
                                        Insets insets, TreePath path) {
    boolean paintLines = true;

    if (path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
      if (node.getChildCount() < 2) {
        return;
      }
    }

    if (!paintLines) {
      return;
    }

    int depth = path.getPathCount() - 1;
    if (depth == 0 && !getShowsRootHandles() && !isRootVisible()) {
      return;
    }
    int lineX = getRowX(-1, depth);
    if (leftToRight) {
      lineX = lineX - getRightChildIndent() + insets.left;
    }
    else {
      lineX = tree.getWidth() - lineX - insets.right +
              getRightChildIndent() - 1;
    }
    int clipLeft = clipBounds.x;
    int clipRight = clipBounds.x + (clipBounds.width - 1);

    if (lineX >= clipLeft && lineX <= clipRight) {
      int clipTop = clipBounds.y;
      int clipBottom = clipBounds.y + clipBounds.height;
      Rectangle parentBounds = getPathBounds(tree, path);
      TreePath lastChildPath = getLastChildPath(path);
      while(tree.isExpanded(lastChildPath)) {
        TreePath candidate = getLastChildPath(lastChildPath);
        if (candidate == null)
          break;
        lastChildPath = candidate;
      }
      Rectangle lastChildBounds = getPathBounds(tree, lastChildPath);


      if(lastChildBounds == null)
        // This shouldn't happen, but if the model is modified
        // in another thread it is possible for this to happen.
        // Swing isn't multithreaded, but I'll add this check in
        // anyway.
        return;

      int top;

      if(parentBounds == null) {
        top = Math.max(insets.top + getVerticalLegBuffer(),
                       clipTop);
      }
      else
        top = Math.max(parentBounds.y + parentBounds.height +
                       getVerticalLegBuffer(), clipTop);
      if(depth == 0 && !isRootVisible()) {
        TreeModel model = getModel();

        if(model != null) {
          Object        root = model.getRoot();

          if(model.getChildCount(root) > 0) {
            parentBounds = getPathBounds(tree, path.
              pathByAddingChild(model.getChild(root, 0)));
            if(parentBounds != null)
              top = Math.max(insets.top + getVerticalLegBuffer(),
                             parentBounds.y +
                             parentBounds.height / 2); /// XXX update this as well?
          }
        }
      }

      int bottom = Math.min(lastChildBounds.y +
                            (lastChildBounds.height), clipBottom);

      if (top <= bottom) {
        g.setColor(getHashColor());
        paintVerticalLine(g, tree, lineX, top, bottom);
      }
    }
  }

  /**
   * Paints the horizontal part of the leg. The receiver should
   * NOT modify <code>clipBounds</code>, or <code>insets</code>.<p>
   * NOTE: <code>parentRow</code> can be -1 if the root is not visible.
   */
  @Override
  protected void paintHorizontalPartOfLeg(Graphics g, Rectangle clipBounds,
                                          Insets insets, Rectangle bounds,
                                          TreePath path, int row,
                                          boolean isExpanded,
                                          boolean hasBeenExpanded, boolean
                                            isLeaf) {
  }

  /**
   * Paints a vertical line.
   */
  protected void paintVerticalLine(Graphics g, JComponent c, int x, int top,
                                   int bottom) {
    boolean lineTypeDashed = false;
    if (lineTypeDashed) {
      drawDashedVerticalLine(g, x, top, bottom);
    } else {
      g.drawLine(x, top, x, bottom);
    }
  }
}
