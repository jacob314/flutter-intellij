/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import gnu.trove.*;

import java.util.Collection;

class SlidingWindowStats {

  SlidingWindowStats(PerfReportKind kind, int total, int pastSecond, String description) {
    this.kind = kind;
    this.total = total;
    this.pastSecond = pastSecond;
    this.description = description;
  }

  private final PerfReportKind kind;
  private final int total;
  private int pastSecond = 0;
  private final String description;

  PerfReportKind getKind() { return kind; }
  int getTotal() {
    return total;
  }

  int getPastSecond() {
    return pastSecond;
  }

  public void markAppIdle() {
    pastSecond = 0;
  }

  public String getDescription() { return description; }
}

class FilePerfInfo {
  long maxTimestamp = -1;

  private final Multimap<TextRange, SlidingWindowStats> stats = LinkedListMultimap.create();

  public void clear() {
    stats.clear();
    maxTimestamp = -1;
  }

  public Iterable<TextRange> getLines() {
    return stats.keys();
  }

  public int getCountPastSecond(TextRange range) {
    final Collection<SlidingWindowStats> entries = stats.get(range);
    if (entries == null) {
      return 0;
    }
    int count = 0;
    for (SlidingWindowStats entry : entries) {
      count += entry.getPastSecond();
    }
    return count;
  }

  public int getTotalCount(TextRange range) {
    final Collection<SlidingWindowStats> entries = stats.get(range);
    if (entries == null) {
      return 0;
    }
    int count = 0;
    for (SlidingWindowStats entry : entries) {
      count += entry.getTotal();
    }
    return count;
  }

  Iterable<SlidingWindowStats> getLineStats(TextRange range) {
    return stats.get(range);
  }

  public long getMaxTimestamp() {
    return maxTimestamp;
  }

  public void add(TextRange range, SlidingWindowStats entry) {
    stats.put(range, entry);
  }

  public void markAppIdle() {
    for (SlidingWindowStats stats : stats.values()) {
      stats.markAppIdle();
    }
  }
}
