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

  @Attribute(value = "details-splitter-proportion")
  public float detailsSplitterPortion;

  public FlutterViewState() {
  }

  public float getSplitterProportion() {
    return splitterProportion <= 0.0f ? 0.7f : splitterProportion;
  }

  public float getDetailsSplitterProportion() {
    return detailsSplitterPortion <= 0.0f ? 0.6f : detailsSplitterPortion;
  }

  public void setSplitterProportion(float value) {
    splitterProportion = value;
    dispatcher.getMulticaster().stateChanged(new ChangeEvent(this));
  }

  public void setDetailsSplitterProportion(float value) {
    detailsSplitterPortion = value;
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
    detailsSplitterPortion = other.detailsSplitterPortion;
  }
}
