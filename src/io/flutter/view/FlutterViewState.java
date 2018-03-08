/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.util.EventDispatcher;
import com.intellij.util.xmlb.annotations.Attribute;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * State for the Flutter view.
 */
public class FlutterViewState {
  private final EventDispatcher<ChangeListener> dispatcher = EventDispatcher.create(ChangeListener.class);

  @Attribute(value = "splitter-proportion-horizontal")
  public float splitterProportion;

  @Attribute(value = "splitter-proportion-horizontal")
  public float subtreeSplitterProportion;

  public FlutterViewState() {
  }

  public float getSplitterProportion(boolean detailsSubtree) {
    float value = detailsSubtree ? subtreeSplitterProportion : splitterProportion;
    return value<= 0.0f ? 0.7f : value;
  }

  public void setSplitterProportion(float value, boolean detailsSubtree) {
    if (detailsSubtree) {
      subtreeSplitterProportion = value;
    } else {
      splitterProportion = value;
    }
    dispatcher.getMulticaster().stateChanged(new ChangeEvent(this));
  }

  public void addListener(ChangeListener listener) {
    dispatcher.addListener(listener);
  }

  public void removeListener(ChangeListener listener) {
    dispatcher.removeListener(listener);
  }

  void copyFrom(FlutterViewState other) {
    splitterProportion = other.splitterProportion;
    subtreeSplitterProportion = other.subtreeSplitterProportion;
  }
}
