/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class PerfSourceReport {
  private final PerfReportKind kind;

  class Entry {
    Entry(JsonArray entry) {
      assert entry.size() == 4;
      line = entry.get(0).getAsInt();
      column = entry.get(1).getAsInt();
      total = entry.get(2).getAsInt();
      pastSecond = entry.get(3).getAsInt();
    }

    public final int line;
    public final int column;
    public final int total;
    public final int pastSecond;
  }

  public PerfSourceReport(JsonObject json, PerfReportKind kind) {
    this.json = json;
    this.kind = kind;
  }
  private final JsonObject json;

  PerfReportKind getKind() { return kind; }
  String getFileName() {
    return json.getAsJsonPrimitive("file").getAsString();
  }

  List<Entry> getEntries() {
    final JsonArray entries = json.getAsJsonArray("entries");
    final ArrayList<Entry> ret = new ArrayList<>(entries.size());
    for(JsonElement entryJson : entries) {
      ret.add(new Entry(entryJson.getAsJsonArray()));
    }
    return ret;
  }
}
