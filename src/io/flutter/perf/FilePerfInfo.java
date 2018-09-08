/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import gnu.trove.*;

import java.util.List;
import java.util.PriorityQueue;

class SlidingWindowstats {
  static final int MAX_TIME_WINDOW = 10000;

  PriorityQueue<Long> timeStamps = new PriorityQueue<Long>();
  
  void addCount(long timestamp) {
    timeStamps.add(timestamp);
    removeOlderThan(timestamp - MAX_TIME_WINDOW);
  }

  int getCount(long minTimestamp) {
    removeOlderThan(minTimestamp);
    return timeStamps.size();
  }
  
  void removeOlderThan(long minTimestamp) {
    while(true) {
      Long candidate = timeStamps.peek();
      if (candidate == null || candidate >= minTimestamp) {
        return; 
      }
      timeStamps.remove();
    }
  }
}

class FilePerfInfo {
  private final VirtualFile file;

  private final TIntObjectHashMap<SlidingWindowstats> lineCounts = new TIntObjectHashMap<>();

  public FilePerfInfo(VirtualFile file) {
    this.file = file;
  }

  public VirtualFile getFile() {
    return file;
  }

  public void addCounts(int line, TLongArrayList timestamps) {
    SlidingWindowstats stats = lineCounts.get(line);
    if (stats == null) {
      stats = new SlidingWindowstats();
      lineCounts.put(line, stats);
    }
    for (int i = 0; i < timestamps.size(); ++i) {
      stats.addCount(timestamps.get(i));
    }
  }

  interface LineCountCallback {
    void execute(int line, int count);
  }
  /**
   * Executes callback for each line that
   * @param timestamp
   * @param callback
   */
  public void getCounts(long timestamp, LineCountCallback callback) {
    lineCounts.retainEntries((int line, SlidingWindowstats stats) -> {
      final int count = stats.getCount(0); // XXX timestamp - SlidingWindowstats.MAX_TIME_WINDOW);
      if (count > 0) {
        callback.execute(line, count);
        return true;
      }
      return false;
    });
  }

  /*
  public int getCount(int line, long timestamp) {
    final SlidingWindowstats stats = lineCounts.get(line);
    if (stats == null) {
      return 0;
    }
    return stats.getCount(timestamp - SlidingWindowstats.MAX_TIME_WINDOW);
  }*/
}
