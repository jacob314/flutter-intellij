/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.impl.ui.tree.XInspectDialog;
import com.jetbrains.lang.dart.ide.runner.server.vmService.frame.FlutterVmServiceValue;
import io.flutter.FlutterInitializer;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.concurrent.CompletableFuture;

public class DebuggerInspectAction extends InspectorTreeActionBase {
  final String id;

  public DebuggerInspectAction() {
    this("debuggerInspect");
  }

  DebuggerInspectAction(String id) {
    this.id = id;
  }

  @Override
  protected void perform(DefaultMutableTreeNode node, DiagnosticsNode diagnosticsNode, AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) {
      return;
    }

    FlutterInitializer.getAnalytics().sendEvent("inspector", id);

    final InspectorService.ObjectGroup inspectorService = diagnosticsNode.getInspectorService();
    inspectorService.safeWhenComplete(getVmServiceValue(diagnosticsNode), (value, throwable) -> {
      if (throwable != null) {
        return;
      }
      final String name = diagnosticsNode.toString();
      XInspectDialog dialog = new XInspectDialog(project, inspectorService.getEditorsProvider(), null, name, value,
                                                 null,
                                                 XDebuggerManager.getInstance(project).getCurrentSession(), true);
      dialog.show();
      return;
    });
  }

  CompletableFuture<FlutterVmServiceValue> getVmServiceValue(DiagnosticsNode node) {
    // We use this instead of just toDartVmServiceValue as showing an Element
    // instead of a Widget is also confusing for users here.
    return node.getInspectorService().toDartVmServiceValueForSourceLocation(node.getValueRef());
  }
}
