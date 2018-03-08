/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

/**
 * Inspector groups are used to manage lifecycles of inspector objects.
 *
 * Essentially, groups support arena style allocation where a client allocates
 * and allocates and then frees all objects in the arena/group at once.
 */
public class InspectorObjectGroup {

  private static int nextGroupId = 0;

  public InspectorObjectGroup() {
    this.groupName = "ij_" + nextGroupId;
    nextGroupId++;
    disposed = false;
  }

  @Override
  public String toString() {
    return groupName;
  }

  public String getGroupName() {
    // This makes it easier to catch errors due to accidentally disposing a
    // group and then continuing to use it.
    assert(!disposed);
    return groupName;
  }

  /**
   * Called by the InspectorService when a group has been disposed.
   */
  protected void markDisposed() {
    disposed = true;
  }

  private final String groupName;

  private boolean disposed;
}
