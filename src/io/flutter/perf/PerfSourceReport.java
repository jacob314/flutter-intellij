/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gnu.trove.TLongArrayList;

import java.util.ArrayList;
import java.util.List;

public class PerfSourceReport {
  class Entry {
    Entry(JsonArray entry) {
      line = entry.get(0).getAsInt();
      timeStamps = new TLongArrayList(entry.size() - 1);
      for (int i = 1; i < entry.size(); i++) {
        timeStamps.add(entry.get(i).getAsLong());
      }
    }

    public final int line;
    public final TLongArrayList timeStamps;
  }

  public PerfSourceReport(JsonObject json) {
    this.json = json;
  }
  private final JsonObject json;

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
