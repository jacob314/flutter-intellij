/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE path.
 */
package io.flutter.perf;

public class Location {
  public Location(String path, int line, int column) {
    this.path = path;
    this.line = line;
    this.column = column;
  }

  public final int line;
  public final int column;
  public final String path;
}
