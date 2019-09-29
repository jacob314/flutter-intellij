/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.util.TextRange;
import io.flutter.inspector.DiagnosticsNode;
import io.flutter.inspector.InspectorObjectGroupManager;
import io.flutter.inspector.InspectorService;
import io.flutter.inspector.InspectorStateService;
import io.flutter.run.daemon.FlutterApp;

import java.awt.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static io.flutter.inspector.InspectorService.toSourceLocationUri;
import static java.lang.Math.min;

/**
 * Base class for a controller managing UI describing a widget.
 */
public abstract class WidgetViewController implements EditorMouseEventService.Listener,
                                                      InspectorStateService.Listener, Disposable {
  protected boolean isSelected = false;
  // TODO(jacobr): make this private.
  final WidgetViewModelData data;

  boolean visible = false;
  private InspectorObjectGroupManager groups;
  boolean isDisposed = false;

  Rectangle visibleRect;
  DiagnosticsNode inspectorSelection;
  InspectorService inspectorService;

  public FlutterApp getApp() {
    if (inspectorService == null) return null;
    return inspectorService.getApp();
  }

  InspectorObjectGroupManager getGroups() {
    final InspectorService service = getInspectorService();
    if (service == null) return null;
    if (groups == null || groups.getInspectorService() != service) {
      groups = new InspectorObjectGroupManager(service, "active");
    }
    return groups;
  }

  public ArrayList<DiagnosticsNode> elements;
  public int activeIndex = 0;

  WidgetViewController(WidgetViewModelData data) {
    this.data = data;
    data.context.inspectorStateService.addListener(this);
  }

  /**
   * Subclasses can override this method to be notified when whether the widget is visible in IntelliJ.
   *
   * This is whether the UI for this component is visible not whether the widget is visible on the device.
   */
  public void onVisibleChanged() {
  }

  public boolean updateVisiblityLocked(Rectangle newRectangle) { return false; }

  @Override
  public void onInspectorAvailable(InspectorService inspectorService) {
    if (this.inspectorService == inspectorService) return;
    setElements(null);
    inspectorSelection = null;
    this.inspectorService = inspectorService;
    groups = null;
    onVisibleChanged();
    forceRender();
  }

  // @Override
  public abstract void forceRender();

  public InspectorService getInspectorService() {
    return inspectorService;
  }

  @Override
  public void dispose() {
    if (isDisposed) return;
    isDisposed = true;
    // Descriptors must be disposed so they stop getting notified about
    // changes to the Editor.
    data.context.inspectorStateService.removeListener(this);

    // TODO(Jacobr): fix missing code disposing descriptors?
    if (groups != null) {
      groups.clear(false);// XXX??
      groups = null;
    }
  }

  @Override
  public boolean isValid() {
    return true;
  }

  @Override
  public void onSelectionChanged(DiagnosticsNode selection) {
/* XXX review and delete.
    if (data.context.editor != null && data.context.editor.isDisposed()) {
      return;
    }

 */

    final InspectorObjectGroupManager manager = getGroups();
    if (manager != null ){
      manager.cancelNext();;
    }
  }

  abstract InspectorService.Location getLocation();

  public boolean isElementsEmpty() {
    return elements == null || elements.isEmpty() || isDisposed;
  }

  public void setElements(ArrayList<DiagnosticsNode> elements) {
    this.elements = elements;
  }

  public void onActiveElementsChanged() {
    if (isElementsEmpty()) return;
    final InspectorObjectGroupManager manager = getGroups();
    if (manager == null) return;

    if (isSelected) {
      manager.getCurrent().setSelection(
        getSelectedElement().getValueRef(),
        false,
        true
      );
    }
  }

  public DiagnosticsNode getSelectedElement() {
    if (isElementsEmpty()) return null;
    return elements.get(0);
  }

  private boolean isEquivalent(ArrayList<DiagnosticsNode> a, ArrayList<DiagnosticsNode> b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) {
      if (!isEquivalent(a.get(i), b.get(i))) {
        return false;
      }
    }
    return true;
  }

  private boolean isEquivalent(DiagnosticsNode a, DiagnosticsNode b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    if (!Objects.equals(a.getValueRef(), b.getValueRef())) return false;
    if (!a.identicalDisplay(b)) return false;
    return true;
  }
}
