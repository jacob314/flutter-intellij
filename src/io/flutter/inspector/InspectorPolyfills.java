/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class InspectorPolyfills {
  private static final InspectorPolyfills instance = new InspectorPolyfills();

  public static InspectorPolyfills getInstance() {
    return instance;
  }

  final Map<String, String> cachedExpressions = new HashMap<>();

  public String getPolyfillMethod(String methodName) {
    // TODO(jacobr): enable cache.
    final URL resource = getClass().getResource("polyfills/lib/polyfills.dart");
    final byte[] contentBytes;
    try {
      contentBytes = ByteStreams.toByteArray((InputStream)resource.getContent());
    }
    catch (IOException e) {
      throw new RuntimeException("Unable to load polyfill file");
    }
    final String content = new String(contentBytes, Charsets.UTF_8);

    final String[] lines = content.split("\n");
    String methodBody = null;
    for (int i = 0; i < lines.length; ++i) {
      if (lines[i].contains(" " + methodName + "(")) {
        // Make sure our simplistic parser detecting Dart method bodies did not get confused.
        assert (methodBody == null);
        // skip comments and imports.
        final ArrayList<String> methodLines = new ArrayList<>();

        for (int j = i; j < lines.length; j++) {
          final String line = lines[j];
          if (line.trim().startsWith("//")) continue;
          methodLines.add(line);

          if (line.equals("}")) break;
        }
        methodBody = Joiner.on("\n").join(methodLines);
      }
    }
    assert(methodBody != null);
    return methodBody;
  }
}
