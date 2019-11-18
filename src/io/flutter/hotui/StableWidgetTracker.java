/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.hotui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import io.flutter.dart.FlutterDartAnalysisServer;
import io.flutter.dart.FlutterOutlineListener;
import io.flutter.inspector.InspectorService;
import io.flutter.utils.EventStream;
import org.dartlang.analysis.server.protocol.FlutterOutline;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Class that uses the FlutterOutline to maintain the source location for a
 * Widget even when code edits that would otherwise confuse location tracking
 * are occurring.
 */
public class StableWidgetTracker implements Disposable {
  private final String currentFilePath;
  private final InspectorService.Location initialLocation;
  private final FlutterDartAnalysisServer flutterAnalysisServer;
  private final DartAnalysisServerService analysisServerService;

  // Path to the current outline
  private ArrayList<FlutterOutline> lastPath;

  FlutterOutline root;

  private final FlutterOutlineListener outlineListener = new FlutterOutlineListener() {
    @Override
    public void outlineUpdated(@NotNull String filePath, @NotNull FlutterOutline outline, @Nullable String instrumentedCode) {
      if (Objects.equals(currentFilePath, filePath)) {
        ApplicationManager.getApplication().invokeLater(() -> outlineChanged(outline));
      }
    }
  };

  private void outlineChanged(FlutterOutline outline) {
    this.root = outline;
    FlutterOutline match;
    if (lastPath == null) {
       // First outline.
       lastPath = new ArrayList<>();
       findOutlineAtOffset(root, initialLocation.getOffset(), lastPath);
    } else {
      lastPath = findSimilarPath(root, lastPath);
    }
    currentOutline.setValue(lastPath.isEmpty() ? null : lastPath.get(lastPath.size() - 1));
  }

  private static int findChildIndex(FlutterOutline node, FlutterOutline child) {
    final List<FlutterOutline> children = node.getChildren();
    for (int i = 0; i < children.size(); i++) {
      if (children.get(i) == child) return i;
    }
    return -1;
  }

  private ArrayList<FlutterOutline> findSimilarPath(FlutterOutline root, ArrayList<FlutterOutline> lastPath) {
    final ArrayList<FlutterOutline> path = new ArrayList<>();
    FlutterOutline node = root;
    path.add(node);
    for (int i = 1; i < lastPath.size(); i++) {
      final int expectedIndex = findChildIndex(lastPath.get(i-1), lastPath.get(i));
      assert(expectedIndex != -1);
      final List<FlutterOutline> children = node.getChildren();
      final int index = Math.min(Math.max(0, expectedIndex), children.size());
      node = children.get(index);
      // TODO(jacobr): consider doing some additional validation that the children have the same class names, etc.
      // We could use that to be reslient to small changes such as adding a new parent widget, etc.
      path.add(node);
    }
    return path;
  }

  // XXX duped.
  private boolean findOutlineAtOffset(FlutterOutline outline, int offset, ArrayList<FlutterOutline> path) {
    if (outline == null) {
      return false;
    }
    path.add(outline);
    if (getConvertedOutlineOffset(outline) <= offset && offset <= getConvertedOutlineEnd(outline)) {
      if (outline.getChildren() != null) {
        for (FlutterOutline child : outline.getChildren()) {
          final boolean foundChild = findOutlineAtOffset(child, offset, path);
          if (foundChild) {
            return true;
          }
        }
      }
      return true;
    }
    path.remove(path.size() - 1);
    return false;
  }

  // XXX duped
  private int getConvertedFileOffset(int offset) {
    return analysisServerService.getConvertedOffset(initialLocation.getFile(), offset);
  }
// XXX duped
  public int getConvertedOutlineOffset(FlutterOutline outline) {
    final int offset = outline.getOffset();
    return getConvertedFileOffset(offset);
  }
// XXX DUPED
  private int getConvertedOutlineEnd(FlutterOutline outline) {
    final int end = outline.getOffset() + outline.getLength();
    return getConvertedFileOffset(end);
  }

  private final EventStream<FlutterOutline> currentOutline;

  public EventStream<FlutterOutline> getCurrentOutline() { return currentOutline; }

  public StableWidgetTracker(
    InspectorService.Location initialLocation,
    FlutterDartAnalysisServer flutterAnalysisServer,
    InspectorService inspectorService,
    Project project,
    Disposable parentDisposable
  ) {
    Disposer.register(parentDisposable, this);
    currentOutline = new EventStream<>();
    this.flutterAnalysisServer = flutterAnalysisServer;
    this.initialLocation = initialLocation;
    analysisServerService = DartAnalysisServerService.getInstance(project);
    currentFilePath = FileUtil.toSystemDependentName(initialLocation.getFile().getPath());
    flutterAnalysisServer.addOutlineListener(currentFilePath, outlineListener);
  }

  @Override
  public void dispose() {
    flutterAnalysisServer.removeOutlineListener(currentFilePath, outlineListener);
  }

}
