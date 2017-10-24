/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

// TODO: oval

class ViewLabel extends JLabel {
  public ViewLabel(String text) {
    super(text);

    setOpaque(true);
    setForeground(UIUtil.getTreeSelectionForeground());
    setBackground(UIUtil.getTreeSelectionBackground());
    setBorder(new EmptyBorder(4, 4, 4, 4));
  }
}
