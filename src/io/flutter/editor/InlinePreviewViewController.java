/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import io.flutter.inspector.DiagnosticsNode;
import io.flutter.inspector.InspectorService;
import io.flutter.inspector.Screenshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;

import static java.lang.Math.*;
import static java.lang.Math.min;

/**
 * PreviewViewController that renders inline in a code editor.
 */
public class InlinePreviewViewController extends PreviewViewControllerBase implements CustomHighlighterRenderer {
  public InlinePreviewViewController(InlineWidgetViewModelData data, boolean drawBackground, Disposable disposable) {
    super(data, drawBackground, disposable);
    data.context.editorPositionService.addListener(getEditor(), new EditorPositionService.Listener() {
      @Override
      public void updateVisibleArea(Rectangle newRectangle) {
        InlinePreviewViewController.this.updateVisibleArea(newRectangle);
      }

      @Override
      public void onVisibleChanged() {
        InlinePreviewViewController.this.onVisibleChanged();
      }
    },
   this);
  }

  InlineWidgetViewModelData getData() {
    return (InlineWidgetViewModelData) data;
  }

  protected EditorEx getEditor() {
    return getData().editor;
  }

  public Point offsetToPoint(int offset) {
    return getEditor().visualPositionToXY(getEditor().offsetToVisualPosition(offset));
  }

  @Override
  public void forceRender() {
    if (!visible) return;

    getEditor().getComponent().repaint(); // XXX repaint rect?
    /*
    if (data.descriptor == null) {
      // TODO(just repaint the sreenshot area.
      data.editor.repaint(0, data.document.getTextLength());
      return;
    }
    data.editor.repaint(0, data.document.getTextLength());

     */
/*
    final TextRange marker = data.getMarker();
    if (marker == null) return;

    data.editor.repaint(marker.getStartOffset(), marker.getEndOffset());
 */
  }

  InspectorService.Location getLocation() {
    final WidgetIndentGuideDescriptor descriptor = getDescriptor();
    if (descriptor == null || descriptor.widget == null) return null;

    return InspectorService.Location.outlineToLocation(getEditor(), descriptor.outlineNode);
  }

  public WidgetIndentGuideDescriptor getDescriptor() { return getData().descriptor; }

  @Override
  public void computeScreenshotBounds() {
    final Rectangle previousScreenshotBounds = screenshotBounds;
    screenshotBounds = null;
    maxHeight = Math.round(PREVIEW_MAX_HEIGHT * 0.16f);
    final WidgetIndentGuideDescriptor descriptor = getDescriptor();

    final int lineHeight = getEditor() != null ? getEditor().getLineHeight() : defaultLineHeight;
    extraHeight = descriptor != null && screenshot != null ? lineHeight: 0;

    final Rectangle visibleRect = this.visibleRect;
    if (visibleRect == null) {
      return;
    }

    if (descriptor == null) {
      // Special case to float in the bottom right corner.
      final Screenshot latestScreenshot = getScreenshotNow();
      int previewWidth = round(PREVIEW_MAX_WIDTH * previewWidthScale);
      int previewHeight = round((PREVIEW_MAX_HEIGHT * 0.16f) * previewWidthScale);
      if (latestScreenshot != null) {
        previewWidth = (int)(latestScreenshot.image.getWidth() / getDPI());
        previewHeight = (int)(latestScreenshot.image.getHeight() / getDPI());
      }
      int previewStartX = max(0, visibleRect.x + visibleRect.width - previewWidth - PREVIEW_PADDING_X);
      previewHeight = min(previewHeight, visibleRect.height);

      maxHeight = visibleRect.height;
      int previewStartY = max(visibleRect.y, visibleRect.y + visibleRect.height - previewHeight);
      screenshotBounds = new Rectangle(previewStartX, previewStartY, previewWidth, previewHeight);
      return;
    }

    final TextRange marker = getData().getMarker();
    if (marker == null) return;
    final int startOffset = marker.getStartOffset();
    final Document doc = getData().document;
    final int textLength = doc.getTextLength();
    if (startOffset >= textLength) return;

    final int endOffset = min(marker.getEndOffset(), textLength);

    int off;
    int startLine = doc.getLineNumber(startOffset);

    int widgetOffset = getDescriptor().widget.getGuideOffset();
    int widgetLine = doc.getLineNumber(widgetOffset);
    int lineEndOffset = doc.getLineEndOffset(widgetLine);

    // Request a thumbnail and render it in the space available.
    VisualPosition visualPosition = getEditor().offsetToVisualPosition(lineEndOffset); // e
    visualPosition = new VisualPosition(max(visualPosition.line, 0), 81);
    final Point start = getEditor().visualPositionToXY(visualPosition);
    final Point endz = offsetToPoint(endOffset);
    int endY = endz.y;
    int visibleEndX = visibleRect.x + visibleRect.width;
    int width = max(0, visibleEndX - 20 - start.x);
    int height = max(0, endY - start.y);
    int previewStartY = start.y;
    int previewStartX = start.x;
    int visibleStart = visibleRect.y;
    int visibleEnd = (int)visibleRect.getMaxY();

    // Add extra room for the descriptor.
    final Screenshot latestScreenshot = getScreenshotNow();
    int previewWidth = PREVIEW_MAX_WIDTH;
    int previewHeight = PREVIEW_MAX_HEIGHT / 6;
    if (latestScreenshot != null) {
      previewWidth = (int)(latestScreenshot.image.getWidth() / getDPI());
      previewHeight = (int)(latestScreenshot.image.getHeight() / getDPI());
    }
    previewStartX = max(previewStartX, visibleEndX - previewWidth - PREVIEW_PADDING_X);
    previewHeight += extraHeight;
    previewHeight = min(previewHeight, height);

    maxHeight = endz.y - start.y;
    if (popupActive()) {
      // Keep the bounds sticky maintining the same lastScreenshotBoundsWindow.
      screenshotBounds = new Rectangle(lastScreenshotBoundsWindow);
      screenshotBounds.translate(visibleRect.x, visibleRect.y);
    } else {
      boolean lockUpdate =false;
      if (isVisiblityLocked()) {
        // TODO(jacobr): also need to keep sticky if there is some minor scrolling
        if (previousScreenshotBounds != null && visibleRect.contains(previousScreenshotBounds)) {
          screenshotBounds = new Rectangle(previousScreenshotBounds);

          // Fixup if the screenshot changed
          if (previewWidth != screenshotBounds.width) {
            screenshotBounds.x += screenshotBounds.width - previewWidth;
            screenshotBounds.width = previewWidth;
          }
          screenshotBounds.height = previewHeight;

          // XXX dupe code.
          lastScreenshotBoundsWindow = new Rectangle(screenshotBounds);
          lastScreenshotBoundsWindow.translate(-visibleRect.x, -visibleRect.y);
          lockUpdate = true;
        }
      }

      if (!lockUpdate){
        lastLockedRectangle = null;
        if (start.y <= visibleEnd && endY >= visibleStart) {
          if (visibleStart > previewStartY) {
            previewStartY = max(previewStartY, visibleStart);
            previewStartY = min(previewStartY, min(endY - previewHeight, visibleEnd - previewHeight));
          }
          screenshotBounds = new Rectangle(previewStartX, previewStartY, previewWidth, previewHeight);
          lastScreenshotBoundsWindow = new Rectangle(screenshotBounds);
          lastScreenshotBoundsWindow.translate(-visibleRect.x, -visibleRect.y);
        }
      }
    }

    if (popupActive()) {
      lastLockedRectangle = new Rectangle(visibleRect);
    }
  }

  @Override
  protected Dimension getPreviewSize() {
    int previewWidth;
    int previewHeight;
    previewWidth = PREVIEW_MAX_WIDTH;
    previewHeight = PREVIEW_MAX_HEIGHT;

    if (getDescriptor() == null) {
      previewWidth = round(previewWidth * previewWidthScale);
      previewHeight = round(previewHeight * previewWidthScale);
    }
    return new Dimension(previewWidth, previewHeight);
  }

  private void updateVisibleArea(Rectangle newRectangle) {
    visibleRect = newRectangle;
    if (getDescriptor() == null || getData().getMarker() == null) {
      if (!visible) {
        visible = true;
        onVisibleChanged();
      }
      return;
    }
    final TextRange marker = getData().getMarker();
    if (marker == null) return;

    final Point start = offsetToPoint(marker.getStartOffset());
    final Point end = offsetToPoint(marker.getEndOffset());
    final boolean nowVisible = newRectangle == null || newRectangle.y <= end.y && newRectangle.y + newRectangle.height >= start.y ||
                               updateVisiblityLocked(newRectangle);
    if (visible != nowVisible) {
      visible = nowVisible;
      onVisibleChanged();
    }
  }

  @Override
  public void dispose() {
  }

  @Override
  protected Component getComponent() {
    return getEditor().getComponent();
  }

  @Override
  protected VirtualFile getVirtualFile() {
    return getEditor().getVirtualFile();
  }

  @Override
  protected Document getDocument() {
    return getEditor().getDocument();
  }

  @Override
  protected void showPopup(Point location, DiagnosticsNode node) {
    location =
        SwingUtilities.convertPoint(
          getEditor().getContentComponent(),
          location,
          getEditor().getComponent()
        );
    popup = PropertyEditorPanel
      .showPopup(data.context.inspectorGroupManagerService, getEditor(), node, node.getCreationLocation().getLocation(), data.context.flutterDartAnalysisService, location);
  }

  @Override
  TextRange getActiveRange() {
    return getData().getMarker();
  }

  @Override
  void setCustomCursor(@Nullable Cursor cursor) {
    if (getEditor() == null) {
      // TODO(jacobr): customize the cursor when there is not an associated editor.
      return;
    }
    getEditor().setCustomCursor(this, cursor);
  }

  // Determine zOrder of overlapping previews.
  // Ideally we should work harder to prevent overlapping.
  public int getPriority() {
    int priority = 0;
    if (popupActive()) {
      priority += 20;
    }
    if (isVisiblityLocked()) {
      priority += 10;
    }

    if (isSelected) {
      priority += 5;
    }

    if (getDescriptor() != null) {
      priority += 1;
    }

    if (screenshot == null && (elements == null || elements.isEmpty())) {
      priority -= 5;
      if (getDescriptor() != null) {
        priority -= 100;
      }
    } else {
      if (hasCurrentHits() || _mouseInScreenshot) {
        priority += 10;
      }
    }
    if (_mouseInScreenshot) {
      priority += 20;
    }
    return priority;
  }

  @Override
  public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
    if (editor != getEditor()) {
      // Don't want to render on the wrong editor. This shouldn't happen.
      return;
    }
    if (getEditor().isPurePaintingMode()) {
      // Don't show previews in pure mode.
      return;
    }
    if (!highlighter.isValid()) {
      return;
    }
    if (getDescriptor() != null && !getDescriptor().widget.isValid()) {
      return;
    }
    final int lineHeight = editor.getLineHeight();
    paint(g, lineHeight);
  }

  public void paint(@NotNull Graphics g, int lineHeight) {
    final Graphics2D g2d = (Graphics2D)g.create();

    final Screenshot latestScreenshot = getScreenshotNow();
    if (latestScreenshot != null) {
      final int imageWidth = (int)(latestScreenshot.image.getWidth() * getDPI());
      final int imageHeight = (int)(latestScreenshot.image.getHeight() * getDPI());
      if (extraHeight > 0) {
        if (drawBackground) {
          g2d.setColor(JBColor.LIGHT_GRAY);
          g2d.fillRect(screenshotBounds.x, screenshotBounds.y, min(screenshotBounds.width, imageWidth),
                       min(screenshotBounds.height, extraHeight));
        }
        final WidgetIndentGuideDescriptor descriptor = getDescriptor();
        if (descriptor != null) {
          final int line = descriptor.widget.getLine() + 1;
          final int column = descriptor.widget.getColumn() + 1;
          int numActive = elements != null ? elements.size() : 0;
          String message = descriptor.outlineNode.getClassName() + " ";//+ " Widget ";
          if (numActive == 0) {
            message += "(inactive)";
          }
          else if (numActive == 1) {
            //            message += "(active)";
          }
          else {
            //            message += "(" + (activeIndex + 1) + " of " + numActive + " active)";
            message += "(" + (activeIndex + 1) + " of " + numActive + ")";
          }
          if (numActive > 0 && screenshot != null && screenshot.transformedRect != null) {
            Rectangle2D bounds = screenshot.transformedRect.getRectangle();
            long w = Math.round(bounds.getWidth());
            long h = Math.round(bounds.getHeight());
            message += " " + w + "x" + h;
          }

          g2d.setColor(JBColor.BLACK);
          drawMultilineString(g2d,
                              message,
                              screenshotBounds.x + 4,
                              screenshotBounds.y + lineHeight - 6, lineHeight);
        }
      }
    }
    super.paint(g, lineHeight);
  }

  @Override
  String getNoScreenshotMessage() {
    final WidgetIndentGuideDescriptor descriptor = getDescriptor();
    if (descriptor == null) {
      return super.getNoScreenshotMessage();
    }
    final int line = descriptor.widget.getLine() + 1;
    final int column = descriptor.widget.getColumn() + 1;
    return descriptor.outlineNode.getClassName() + " Widget " + line + ":" + column + "\n"+
                             "not currently active";
  }
}
