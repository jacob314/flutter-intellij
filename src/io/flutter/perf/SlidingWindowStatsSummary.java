/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

public class SlidingWindowStatsSummary {
  final int total;
  final int pastSecond;
  final int totalSinceNavigation;
  private final Location location;

  SlidingWindowStatsSummary(SlidingWindowStats stats, int currentTime, Location location) {
    total = stats.getTotal();
    this.location = location;
    pastSecond = stats.getTotalWithinWindow(currentTime - 1000);
    totalSinceNavigation = stats.getTotalSinceNavigation();
  }

  public int getTotal() {
    return total;
  }

  public int getPastSecond() {
    return pastSecond;
  }

  public int getTotalSinceNavigation() {
    return totalSinceNavigation;
  }

  public Location getLocation() {
    return location;
  }
}
