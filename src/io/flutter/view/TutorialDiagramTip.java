/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import java.awt.*;

public class TutorialDiagramTip {
  public final String message;
  public final Point location;

  public TutorialDiagramTip(String message, Point location) {
    this.message = message;
    this.location = location;
  }
}
