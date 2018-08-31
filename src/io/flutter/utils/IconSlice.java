/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.utils;

import javax.swing.*;
import java.awt.*;

public class IconSlice implements Icon {
  private final int part;
  private final Icon mainIcon;
  private final Icon splitIcon;
  private final int height;

  public IconSlice(Icon mainIcon, Icon splitIcon, int part, int height) {
    this.mainIcon = mainIcon;
    this.splitIcon = splitIcon;
    this.part = part;
    this.height = height;
  }

  @Override
  public void paintIcon(Component c, Graphics g, int x, int y) {
//    assert(height == c.getHeight());
    if (part == 0) {
      mainIcon.paintIcon(c, g, x, y);
    }
    int offset = part == 0 ? mainIcon.getIconWidth() : -2;
    splitIcon.paintIcon(c, g, x + offset, y - height * part);
  }

  @Override
  public int getIconWidth() {
    return mainIcon.getIconWidth() + splitIcon.getIconWidth();
  }

  @Override
  public int getIconHeight() {
    return height;
  }
}
