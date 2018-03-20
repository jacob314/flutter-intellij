/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

/**
 * Inspector groups are used to manage lifecycles of inspector objects.
 *
 * Groups of objects can be freed at once making it simple to avoid memory
 */
public class InspectorObjectGroup {

  private static int nextGroupId = 0;


  public InspectorObjectGroup() {
    this.groupName = "intellij_inspector_" + nextGroupId;
    nextGroupId++;
    disposed = false;
  }

  @Override
  public String toString() {
    return groupName;
  }

  public String getGroupName() {
    assert(disposed == false);
    return groupName;
  }

  protected void markDisposed() {
    disposed = true;
  }

  private final String groupName;

  private boolean disposed;
}
