/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

public class PerfSourceReport {
  private final PerfReportKind kind;

  private static final int ENTRY_LENGTH = 4;

  private final List<Entry> entries;
  public PerfSourceReport(JsonArray json, PerfReportKind kind) {
    this.kind = kind;
    assert (json.size() % ENTRY_LENGTH == 0);
    entries = new ArrayList<>(json.size() / ENTRY_LENGTH);
    for (int i = 0; i < json.size(); i += ENTRY_LENGTH) {
      entries.add(new Entry(
        json.get(i).getAsInt(),
        json.get(i + 1).getAsInt(),
        json.get(i + 2).getAsInt(),
        json.get(i + 3).getAsInt()
      ));
    }
  }

  PerfReportKind getKind() {
    return kind;
  }

  List<Entry> getEntries() {
    return entries;
  }

  class Entry {
    public final int locationId;
    public final int total;
    public final int totalSinceNavigation;
    public final int pastSecond;

    Entry(int locationId, int total, int totalSinceNavigation, int pastSecond) {
      this.locationId = locationId;
      this.total = total;
      this.totalSinceNavigation = totalSinceNavigation;
      this.pastSecond = pastSecond;
    }
  }
}
