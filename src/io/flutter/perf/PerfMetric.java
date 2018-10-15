/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

public enum PerfMetric {
  total("total"),
  totalSinceRouteChange("totalSinceRouteChange"),
  lastSecond("lastSecond");
  public final String name;

  PerfMetric(String name) {
    this.name = name;
  }
}
