/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

import com.jetbrains.lang.dart.ide.runner.server.vmService.frame.DartVmServiceValue;

import java.util.concurrent.CompletableFuture;

public class DebuggerInspectElementAction extends DebuggerInspectAction {
  DebuggerInspectElementAction() {
    super("debuggerInspectElement");
  }

  @Override
  CompletableFuture<DartVmServiceValue> getVmServiceValue(DiagnosticsNode node) {
    return node.getInspectorService().toDartVmServiceValue(node.getValueRef());
  }
}
