/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;

public class PreviewsForEditor implements WidgetViewModeInterface, Disposable {

  private final EditorMouseEventService editorEventService;
  private Balloon popup;

  static final boolean showOverallPreview = false;
  boolean isDisposed = false;
  final EditorEx editor;

  public PreviewsForEditor(WidgetEditingContext data, EditorMouseEventService editorEventService, EditorEx editor) {
    this.data = data;
    this.editor = editor;
    this.editorEventService = editorEventService;
    previews = new ArrayList<>();
    if (showOverallPreview) {
      overallPreview = new InlinePreviewViewController(new InlineWidgetViewModelData(null, editor, data), true);
    } else {
      overallPreview = null;
    }
    editorEventService.addListener(editor,this);
  }

  @Override
  public void dispose() {
    if (isDisposed) return;
    
    if (editorEventService != null && editor != null) {
      editorEventService.removeListener(editor, this);
      if (popup != null && !popup.isDisposed()) {
        popup.dispose();
      }
    }
    for (PreviewViewControllerBase preview : getAllPreviews(false)) {
      preview.dispose();
    }
    previews = null;
    overallPreview = null;
    isDisposed = true;
  }

  private final WidgetEditingContext data;
  private ArrayList<InlinePreviewViewController> previews;

  private InlinePreviewViewController overallPreview;

  public void outlinesChanged(Iterable<WidgetIndentGuideDescriptor> newDescriptors) {
    final ArrayList<InlinePreviewViewController> newPreviews = new ArrayList<>();
    boolean changed = false;

    int i = 0;
    // TODO(jacobr): be smarter about reusing.
    for (WidgetIndentGuideDescriptor descriptor : newDescriptors) {
      if (descriptor.parent == null) {
        if (i >= previews.size() || !descriptor.equals(previews.get(i).getDescriptor())) {
          newPreviews.add(new InlinePreviewViewController(new InlineWidgetViewModelData(descriptor, editor, data), true));
          changed = true;
        } else {
          newPreviews.add(previews.get(i));
          i++;
        }
      }
    }
    while ( i < previews.size()) {
      changed = true;
      previews.get(i).dispose();
      i++;
    }
    previews = newPreviews;
  }

  private Iterable<InlinePreviewViewController> getAllPreviews(boolean paintOrder) {
    final ArrayList<InlinePreviewViewController> all = new ArrayList<>();
    if (overallPreview != null) {
      all.add(overallPreview);
    }
    all.addAll(previews);
    if (paintOrder ) {
      all.sort((a, b) -> { return Integer.compare(a.getPriority(), b.getPriority());});
    } else {
      all.sort((a, b) -> {
        return Integer.compare(b.getPriority(), a.getPriority());
      });
    }
    return all;
  }

  @Override
  public void onMouseMoved(MouseEvent event) {
    for (PreviewViewControllerBase preview : getAllPreviews(false)) {
      if (event.isConsumed()) {
        preview.onMouseExited(event);
      } else {
        preview.onMouseMoved(event);
      }
    }
  }

  @Override
  public void onMousePressed(MouseEvent event) {
    for (PreviewViewControllerBase preview : getAllPreviews(false)) {
      preview.onMousePressed(event);
      if (event.isConsumed()) break;
    }
    // XXX this appears to be duplicated with the viewModel code.
    /* XXX
    if (!event.isConsumed() && event.isShiftDown()) {
      event.consume();
      final LogicalPosition logicalPosition = data.editor.xyToLogicalPosition(event.getPoint());
      System.out.println("XXX logicalPosition = " + logicalPosition);

      XSourcePositionImpl position = XSourcePositionImpl.create(data.editor.getVirtualFile(), logicalPosition.line, logicalPosition.column);
      Point point = event.getLocationOnScreen();

      if (popup != null) {
        popup.dispose();;
        popup = null;
      }
      popup = PropertyEditorPanel.showPopup(getApp(), data.editor, null, position, data.flutterDartAnalysisService, point);
    } else {
      if (popup != null) {
        popup.dispose();
      }
    }*/
  }

  @Override
  public void onMouseReleased(MouseEvent event) {
    for (PreviewViewControllerBase preview : getAllPreviews(false)) {
      preview.onMouseReleased(event);
      if (event.isConsumed()) break;
    }
  }

  @Override
  public void onMouseEntered(MouseEvent event) {
    for (PreviewViewControllerBase preview : getAllPreviews(false)) {
      if (event.isConsumed()) {
        preview.onMouseExited(event);
      } else {
        preview.onMouseEntered(event);
      }
    }
  }

  @Override
  public void onMouseExited(MouseEvent event) {
    for (PreviewViewControllerBase preview : getAllPreviews(false)) {
      preview.onMouseExited(event);
    }
  }

  @Override
  public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics graphics) {
    for (InlinePreviewViewController preview : getAllPreviews(true)) {
      if (preview.visible) {
        preview.paint(editor, highlighter, graphics);
      }
    }
  }
}
