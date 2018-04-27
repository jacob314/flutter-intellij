/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

import io.flutter.server.vmService.VmServiceWrapper;
import org.dartlang.vm.service.element.Instance;
import org.dartlang.vm.service.element.InstanceKind;
import org.dartlang.vm.service.element.InstanceRef;
import org.dartlang.vm.service.element.MapAssociation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DebuggerMetadata {
  public enum Kind {
    Element,
    Widget,
    RenderObject,
    Object,
    Color,
    IconData
  }

  Map<String, InstanceRef> metadata;
  private DebuggerMetadata(Map<String, InstanceRef> metadata) {
    this.metadata = metadata;
  }

  public static CompletableFuture<DebuggerMetadata> create(InstanceRef ref, InspectorService service) {
    if (ref == null || ref.getKind().equals(InstanceKind.Null)) {
      return null;
    }
    return service.getInstance(ref).thenApplyAsync((Instance instance) -> {
      final Map<String, InstanceRef> metadata = new HashMap<>();
      for (MapAssociation association : instance.getAssociations()) {
        metadata.put(association.getKey().getValueAsString(), association.getValue());
      }
      return new DebuggerMetadata(metadata);
    });
  }

  public boolean isInspectable() {
    return metadata.containsKey("isInspectable");
  }

  public boolean hasCreationLocation() {
    return metadata.containsKey("hasCreationLocation");
  }

  public InstanceRef getWidget() {
    return metadata.get("widget");
  }

  public boolean containsKey(String name) {
    return metadata.containsKey(name);
  }

  public InstanceRef get(String name) {
    return metadata.get(name);
  }

  public Kind getKind() {
    InstanceRef name = metadata.get("className");
    if (name == null) {
      return Kind.Object;
    }
    switch (name.getValueAsString()) {
      case "Widget":
        return Kind.Widget;
      case "Element":
        return Kind.Element;
      case "RenderObject":
        return Kind.RenderObject;
      case "Color":
        return Kind.Color;
      case "IconData":
        return Kind.IconData;
      default:
        return Kind.Object;
    }
  }
}
