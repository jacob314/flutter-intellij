/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.UIUtil;
import io.flutter.inspector.*;
import io.flutter.utils.AsyncRateLimiter;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static io.flutter.inspector.InspectorService.toSourceLocationUri;
import static java.lang.Math.*;

public class PreviewViewModel extends WidgetViewModel {
  static final int PREVIEW_WIDTH = 240;
  static final int PREVIEW_HEIGHT = 320;
  public static final int PREVIEW_PADDING_X = 20;
  private JBPopup popup;
  private Point lastPoint;
  private ArrayList<DiagnosticsNode> currentHits;

  private AsyncRateLimiter mouseRateLimiter;
  public static final double MOUSE_FRAMES_PER_SECOND = 10.0;
  private boolean controlDown;
  private boolean shiftDown;

  private InspectorObjectGroupManager hover;
  static final Color SHADOW_COLOR = new Color(0, 0, 0, 64);
  private boolean screenshotDirty = false;

  AsyncRateLimiter getMouseRateLimiter() {
    if (mouseRateLimiter != null) return mouseRateLimiter;
    mouseRateLimiter = new AsyncRateLimiter(MOUSE_FRAMES_PER_SECOND, () -> { return updateMouse(false); });
    return mouseRateLimiter;
  }

  public InspectorObjectGroupManager getHovers() {
    if (hover != null && hover.getInspectorService() == getInspectorService()) {
      return hover;
    }
    if (getInspectorService() == null) return null;
    hover = new InspectorObjectGroupManager(getInspectorService(), "hover");
    return hover;
  }

  // XXX hard coded for Macbooks.
  double getDPI() { return 2.0; }
  int toPixels(int value) { return (int)(value * getDPI()); }

  private Screenshot screenshot;

  /// XXX rename to currentScreenshotBounds.
  Rectangle screenshotBounds;
  // Screenshot bounds in absolute window coordinates.
  Rectangle lastScreenshotBoundsWindow;
  private ArrayList<DiagnosticsNode> boxes;
  private DiagnosticsNode root;
  int maxHeight;

  // XXX add in provider for getting the widget.
  public PreviewViewModel(WidgetViewModelData data) {
    super(data);
  }


  public void onInspectorDataChange(boolean invalidateScreenshot) {
    if (invalidateScreenshot) {
      screenshotDirty = true;
    }
    super.onInspectorDataChange(invalidateScreenshot);
  }

  public void onInspectorAvailable() {
    onVisibleChanged();
  }

  public CompletableFuture<?> updateMouse(boolean navigateTo) {
    final Screenshot latestScreenshot = getScreenshotNow();
    if (screenshotBounds == null || latestScreenshot == null || lastPoint == null || !screenshotBounds.contains(lastPoint) || root == null) return CompletableFuture.completedFuture(null);
    InspectorObjectGroupManager hoverGroups = getHovers();
    hoverGroups.cancelNext();
    final InspectorService.ObjectGroup nextGroup = hoverGroups.getNext();
    final Matrix4 matrix = buildTransformToScreenshot(latestScreenshot);
    matrix.invert();
    final Vector3 point = matrix.perspectiveTransform(new Vector3(lastPoint.getX(), lastPoint.getY(), 0));
    final String file;
    final int startLine, endLine;
    if (controlDown) {
      file = null;
    } else {
      file = toSourceLocationUri(data.editor.getVirtualFile().getPath());
    }
    if (controlDown || shiftDown || data.descriptor == null) {
      startLine = -1;
      endLine = -1;
    } else {
      final TextRange marker = data.getMarker();
      if (marker == null) {
        // XXX is this right?
        return CompletableFuture.completedFuture(null);
      }
      startLine = data.document.getLineNumber(marker.getStartOffset());
      endLine = data.document.getLineNumber(marker.getEndOffset());
    }

    final CompletableFuture<ArrayList<DiagnosticsNode>> hitResults = nextGroup.hitTest(root, point.getX(), point.getY(), file, startLine, endLine);
    nextGroup.safeWhenComplete(hitResults, (hits, error) -> {
      if (error != null) {
        System.out.println("Got error:" + error);
        return;
      }
      if (hits == currentHits) {
        // Existing hits are still valid.
        // TODO(jacobr): check cases where similar but not identical.. E.g. check the bounding box matricies and ids!
        return;
      }
      currentHits = hits;
      hoverGroups.promoteNext();

      if (navigateTo && hits != null && hits.size() > 0) {
        /// XXX kindof the wrong group.
        getGroups().getCurrent().setSelection(hits.get(0).getValueRef(), false, false);
      }
      forceRender();
    });
    return hitResults;
  }

  boolean _mouseInScreenshot = false;
  void setMouseInScreenshot(boolean v) {
    if (_mouseInScreenshot == v) return;
    _mouseInScreenshot = v;
    forceRender();
  }
  public void updateMouseCursor() {
    if (screenshotBounds == null) {
      setMouseInScreenshot(false);
      return;
    }
    if (lastPoint != null && screenshotBounds.contains(lastPoint)) {
      // TODO(jacobr): consider CROSSHAIR_CURSOR instead which gives more of
      //  a pixel selection feel.
      data.editor.setCustomCursor(this, Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
      setMouseInScreenshot(true);
    }
    else {
      data.editor.setCustomCursor(this, null);
      _mouseOutOfScreenshot();
    }
  }

  void registerLastEvent(MouseEvent event) {
    lastPoint = event.getPoint();
    controlDown = event.isControlDown();
    shiftDown = event.isShiftDown();
    updateMouseCursor();
  }

  public void onMouseMoved(MouseEvent event) {
    assert (!event.isConsumed());
    registerLastEvent(event);
    if (_mouseInScreenshot) {
      event.consume();
    }
    getMouseRateLimiter().scheduleRequest();
  }

  boolean popupActive() {
    return popup != null && !popup.isDisposed();
  }

  private void _hidePopup() {
    if (popup != null && !popup.isDisposed()) {
      popup.dispose();
      popup = null;
    }
  }
  public void onMousePressed(MouseEvent event) {
    registerLastEvent(event);

    if (screenshotBounds == null) return;
    if (screenshotBounds.contains(event.getPoint())) {
      _hidePopup();
      updateMouse(true);
      // XXX only show popup after load of properties?
      final Point topRight = event.getLocationOnScreen();
      WidgetIndentsHighlightingPass.PropertyEditorPanel panel = new WidgetIndentsHighlightingPass.PropertyEditorPanel();
      popup = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(panel, panel)
        .setMovable(true)
        .setAlpha(0.0f)
        .setMinSize(new Dimension(200, 5))
        // .setTitle("Properties")
        .setCancelOnWindowDeactivation(true)
        .setRequestFocus(true)
        .createPopup();
      popup.show(RelativePoint.fromScreen(topRight));
      event.consume();
    }
  }

  public void onMouseExited(MouseEvent event) {
    lastPoint = null;
    controlDown = false;
    shiftDown = false;
    setMouseInScreenshot(false);
    updateMouseCursor();
  }

  @Override
  public void updateSelected(Caret carat) {

  }

  public void _mouseOutOfScreenshot() {
    setMouseInScreenshot(false);
    lastPoint = null; // XXX?
    hover = getHovers();
    if (hover != null) {
      hover.cancelNext();
      controlDown = false;
      shiftDown = false;
    }
    if (currentHits != null) {
      currentHits = null;
      forceRender();
    }
  }

  public void onMouseEntered(MouseEvent event) {
    onMouseMoved(event);
  }

  // XXX break this pipeline down and avoid fetching screenshots when not needed.
  public void onVisibleChanged() {
    if (!visible) {
      _hidePopup();
    }
    if (visible && data != null && getInspectorService() != null) {
      computeScreenshotBounds();
      computeActiveElements();
      if (screenshot == null || !isNodesEmpty()) {
        // XXX call a helper instead.
        onActiveNodesChanged();
      }
    }
  }

  public void computeScreenshotBounds() {
    Rectangle previousScreenshotBounds = screenshotBounds;
    screenshotBounds = null;
    maxHeight = PREVIEW_HEIGHT;

    if (data.descriptor == null) {
      // Special case to float in the bottom right corner.
      Screenshot latestScreenshot = getScreenshotNow();
      float previewWidthScale = 0.7f;
      int previewWidth = round(PREVIEW_WIDTH * previewWidthScale);
      int previewHeight = round(PREVIEW_HEIGHT * previewWidthScale);
      if (latestScreenshot != null) {
        previewWidth = (int)(latestScreenshot.image.getWidth() / getDPI());
        previewHeight = (int)(latestScreenshot.image.getHeight() / getDPI());
      }
      int previewStartX = max(0, data.data.visibleRect.x + data.data.visibleRect.width - previewWidth - PREVIEW_PADDING_X);
      previewHeight = min(previewHeight, data.data.visibleRect.height);

      maxHeight = data.data.visibleRect.height;
      int previewStartY = max(data.data.visibleRect.y, data.data.visibleRect.y + data.data.visibleRect.height - previewHeight);
      screenshotBounds = new Rectangle(previewStartX, previewStartY, previewWidth, previewHeight);
      return;
    }

    final TextRange marker = data.getMarker();
    if (marker == null) return;
    final int startOffset = marker.getStartOffset();
    final Document doc = data.document;
    final int textLength = doc.getTextLength();
    if (startOffset >= textLength) return;

    final int endOffset = min(marker.getEndOffset(), textLength);

    int off;
    int startLine = doc.getLineNumber(startOffset);
    final int lineHeight = data.editor.getLineHeight();

    int widgetOffset = data.descriptor.widget.getOffset();
    int widgetLine = doc.getLineNumber(widgetOffset);
    int lineEndOffset = doc.getLineEndOffset(widgetLine);

    // Request a thumbnail and render it in the space available.
    VisualPosition visualPosition = data.editor.offsetToVisualPosition(lineEndOffset); // e
    visualPosition = new VisualPosition(max(visualPosition.line, 0), 81);
    final Point start = data.editor.visualPositionToXY(visualPosition);
    final Point endz = offsetToPoint(endOffset);
    int endY = endz.y;
    int visibleEndX = data.data.visibleRect.x + data.data.visibleRect.width;
    int width = max(0, visibleEndX - 20 - start.x);
    int height = max(0, endY - start.y);
    int previewStartY = start.y;
    int previewStartX = start.x;
    final Rectangle visibleRect = data.data.visibleRect;
    assert (visibleRect != null);
    int visibleStart = visibleRect.y;
    int visibleEnd = (int)visibleRect.getMaxY();

    final WidgetIndentGuideDescriptor descriptor = getDescriptor();
    // Add extra room for the descriptor.
    int extraHeight = descriptor != null && screenshot != null ? lineHeight : 0;
    final Screenshot latestScreenshot = getScreenshotNow();
    int previewWidth = PREVIEW_WIDTH;
    int previewHeight = PREVIEW_HEIGHT;
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

    if (visible) {
      // Move the popup?
      /*
      if (popup != null && popup.isVisible() && !popup.isDisposed()) {
        Point existing = popup.getLocationOnScreen();
        // editor.get
        // editor.getScrollPane().getVerticalScrollBar().getValue()
        Point newPoint = new Point((int)lastScreenshotBounds.getCenterX(), (int)lastScreenshotBounds.getCenterY());
        SwingUtilities
          .convertPointFromScreen(newPoint, editor.getComponent());
        System.out.println("XXX Existing = " + existing);
        System.out.println("XXX NewPoint = " + newPoint);

        if (!existing.equals(newPoint)) {
          // popup.setLocation(newPoint);
        }

      }
       */
    } else {
      boolean hidePopupOnMoveOut = false;
      if (hidePopupOnMoveOut) {
        _hidePopup();
      }
    }
    if (popupActive()) {
      lastLockedRectangle = new Rectangle(data.data.visibleRect);
    }
  }
  Rectangle relativeRect;
  Rectangle lastLockedRectangle;
  @Override
  public boolean updateVisiblityLocked(Rectangle newRectangle) {
    if (popupActive()) {
      lastLockedRectangle = new Rectangle(newRectangle);
      return true;
    }
    if (lastLockedRectangle != null && visible) {
      if (newRectangle.intersects(lastLockedRectangle)) {
        return true;
      }
      // Stop locking.
      lastLockedRectangle = null;
    }
    return false;
  }

  /// XXX merge with update method.
  public boolean isVisiblityLocked() {
    if (popupActive()) {
      return true;
    }
    if (lastLockedRectangle != null && visible) {
      return data.data.visibleRect.intersects(lastLockedRectangle);
    }
    return false;
  }

  public Screenshot getScreenshotNow() {
    return screenshot;
  }

  @NotNull
  /**
   * Builds a transform that
   */
  private Matrix4 buildTransformToScreenshot(Screenshot latestScreenshot) {
    final Matrix4 matrix = Matrix4.identity();
    matrix.translate(screenshotBounds.x, screenshotBounds.y, 0);
    final Rectangle2D imageRect = latestScreenshot.transformedRect.getRectangle();
    final double centerX = imageRect.getCenterX();
    final double centerY = imageRect.getCenterY();
    matrix.translate(-centerX, -centerY, 0);
    matrix.scale(1/getDPI(), 1/getDPI(), 1/getDPI());
    matrix.translate(centerX * getDPI(), centerY * getDPI(), 0);
    //                matrix.translate(-latestScreenshot.transformedRect.getRectangle().getX(), -latestScreenshot.transformedRect.getRectangle().getY(), 0);
    matrix.multiply(latestScreenshot.transformedRect.getTransform());
    return matrix;
  }

  private ArrayList<DiagnosticsNode> getNodesToHighlight() {
    return currentHits != null && currentHits.size() > 0 ? currentHits : boxes;
  }

  private void clearScreenshot() {
    if (getScreenshotNow() != null) {
      screenshot = null;
      computeScreenshotBounds();
      forceRender();
    }
  }

  @Override
  public void onActiveNodesChanged() {
    super.onActiveNodesChanged();
    InspectorObjectGroupManager groups = getGroups();
    if (isNodesEmpty() || groups == null) {
      clearScreenshot();
      return;
      // XXX?
    }

    root = getSelectedNode();
    final InspectorService.ObjectGroup group = getGroups().getCurrent();
    if (data.data.inspectorSelection != null) {
      group.safeWhenComplete(group.getBoundingBoxes(root, data.data.inspectorSelection), (boxes, selectionError) -> {
        if (selectionError != null) {
          this.boxes = null;
          forceRender(); // XXX needed?
          return;
        }
        this.boxes = boxes;
        if (!hasCurrentHits()) {
          forceRender();
        }
      });
    }
    fetchScreenshot();
  }

  boolean hasCurrentHits() {
    return currentHits != null && !currentHits.isEmpty();
  }

  @Override
  public void onMaybeFetchScreenshot() {
    if (screenshot == null || screenshotDirty) {
      fetchScreenshot();;
    }
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

    if (screenshot == null && _nodes == null || _nodes.isEmpty()) {
      priority -= 5;
    } else {
      if (hasCurrentHits() || _mouseInScreenshot) {
        priority += 10;
      }
    }
    if (_mouseInScreenshot) {
      priority += 2000;
    }
    return priority;
  }

  void fetchScreenshot() {
    screenshotDirty = false;
    InspectorObjectGroupManager groups = getGroups();
    if (isNodesEmpty() || groups == null) {
      clearScreenshot();
      return;
    }
    final InspectorService.ObjectGroup group = groups.getCurrent();
    int height = PREVIEW_HEIGHT;
    if (screenshotBounds != null) {
      // Unless something went horribly wrong
      height = max(screenshotBounds.height, 80); // XXX arbitrary.
    }
    group.safeWhenComplete(
      group.getScreenshot(root.getValueRef(), toPixels(PREVIEW_WIDTH), toPixels(height), getDPI()),
      (s, e2) -> {
        if (e2 != null) return;
        screenshot = s;
        // This calculation might be out of date due to the new screenshot.
        computeScreenshotBounds();
        forceRender();
      }
    );
  }

  @Override
  public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
    if (data.editor.isPurePaintingMode()) {
      // Don't show previews in pure mode.
      return;
    }
    if (!highlighter.isValid()) {
      return;
    }
    if (data.descriptor != null && !data.descriptor.widget.isValid()) {
      return;
    }

    final Graphics2D g2d = (Graphics2D)g.create();
    // Required to render colors with an alpha channel. Rendering with an
    // alpha chanel makes it easier to keep relationships between shadows
    // and lines looking consistent when the background color changes such
    // as in the case of selection or a different highlighter turning the
    // background yellow.
    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1));

    final int startOffset = highlighter.getStartOffset();
    final Document doc = highlighter.getDocument();
    final int textLength = doc.getTextLength();
    if (startOffset >= textLength) return;

    final int endOffset = min(highlighter.getEndOffset(), textLength);

    /// XXX this start line is pointless.
    int off;
    int startLine = doc.getLineNumber(startOffset);
    final int lineHeight = editor.getLineHeight();
    final Rectangle clip = g2d.getClipBounds();

    computeScreenshotBounds();
    if (screenshotBounds == null || !clip.intersects(screenshotBounds)) {
      return;
    }

    final Screenshot latestScreenshot = getScreenshotNow();
    int imageWidth = screenshotBounds.width;
    int imageHeight = screenshotBounds.height;

    final int SHADOW_HEIGHT = 5;
    for (int h = 1; h < SHADOW_HEIGHT; h++) {
      g2d.setColor(new Color(43, 43, 43, 100 - h*20));
      g2d.fillRect(screenshotBounds.x-h+1, screenshotBounds.y + min(screenshotBounds.height, imageHeight),
                   min(screenshotBounds.width, imageWidth) + h - 1,
                   1);
      g2d.fillRect(screenshotBounds.x-h+1,
                   screenshotBounds.y - h,
                   min(screenshotBounds.width, imageWidth) + h - 1,
                   1);
      g2d.fillRect(screenshotBounds.x-h, screenshotBounds.y-h, 1, imageHeight + 2*h);
    }
    g2d.clip(screenshotBounds);

    final Font font = UIUtil.getFont(UIUtil.FontSize.NORMAL, UIUtil.getTreeFont());
    g2d.setFont(font);
    // Request a thumbnail and render it in the space available.

    /// XXX do proper clipping as well to optimize. ?
    g2d.setColor(isSelected ? JBColor.GRAY : JBColor.LIGHT_GRAY);

    if (latestScreenshot != null) {
      imageWidth = (int)(latestScreenshot.image.getWidth() * getDPI());
      imageHeight = (int)(latestScreenshot.image.getHeight() * getDPI());
      g2d.setColor(Color.WHITE);
      g2d.fillRect(screenshotBounds.x, screenshotBounds.y, min(screenshotBounds.width, imageWidth), min(screenshotBounds.height, imageHeight));
      g2d.drawImage(latestScreenshot.image, new AffineTransform(1 / getDPI(), 0f, 0f, 1 / getDPI(), screenshotBounds.x, screenshotBounds.y), null);

      final java.util.List<DiagnosticsNode> nodesToHighlight = getNodesToHighlight();
      if (nodesToHighlight != null && nodesToHighlight.size() > 0) {
        boolean first = true;
        for (DiagnosticsNode box : nodesToHighlight) {
          final TransformedRect transform = box.getTransformToRoot();
          if (transform != null) {
            double x, y;
            final Matrix4 matrix = buildTransformToScreenshot(latestScreenshot);
            matrix.multiply(transform.getTransform());
            Rectangle2D rect = transform.getRectangle();

            Vector3[] points = new Vector3[]{
              matrix.perspectiveTransform(new Vector3(new double[]{rect.getMinX(), rect.getMinY(), 0})),
              matrix.perspectiveTransform(new Vector3(new double[]{rect.getMaxX(), rect.getMinY(), 0})),
              matrix.perspectiveTransform(new Vector3(new double[]{rect.getMaxX(), rect.getMaxY(), 0})),
              matrix.perspectiveTransform(new Vector3(new double[]{rect.getMinX(), rect.getMaxY(), 0}))
            };
            double xs = points[0].getX() - data.data.visibleRect.x;
            double ys = points[0].getY() - data.data.visibleRect.y;
            //System.out.println("XXX point = " + xs + ", " + ys );

            final Polygon polygon = new Polygon();
            for (Vector3 point : points) {
              polygon.addPoint((int)Math.round(point.getX()), (int)Math.round(point.getY()));
            }

            if (first) {
              g2d.setColor(WidgetIndentsHighlightingPass.HIGHLIGHTED_RENDER_OBJECT_BORDER_COLOR);
              g2d.fillPolygon(polygon);
            }
            g2d.setStroke(WidgetIndentsHighlightingPass.SOLID_STROKE);
            g2d.setColor(WidgetIndentsHighlightingPass.HIGHLIGHTED_RENDER_OBJECT_BORDER_COLOR);
            g2d.drawPolygon(polygon);
          }
          first = false;

        }
      }
    } else {
      g2d.setColor(isSelected ? JBColor.GRAY: JBColor.LIGHT_GRAY);
      g2d.fillRect(screenshotBounds.x, screenshotBounds.y, screenshotBounds.width, screenshotBounds.height);
      g2d.setColor(WidgetIndentsHighlightingPass.SHADOW_GRAY);
      WidgetIndentGuideDescriptor descriptor = getDescriptor();
      if (descriptor == null) {
        String message = getInspectorService() == null ? "Run the application to\nactivate device mirror." : "Loading...";
        drawMultilineString(g2d, message, screenshotBounds.x + 4, screenshotBounds.y + + lineHeight - 4, lineHeight);
      } else {
        final int line = descriptor.widget.getLine() + 1;
        final int column = descriptor.widget.getColumn() + 1;
        drawMultilineString(g2d,
       "Widget " + descriptor.outlineNode.getClassName() + ":" + line + ":" + column + "\n"+
                            "not currently active",
                            screenshotBounds.x + 4,
                            screenshotBounds.y + +lineHeight - 4, lineHeight);
      }
    }
    g2d.setClip(clip);

    g2d.dispose();
  }

// TODO(jacobr): perhaps cache and optimize.
  private void drawMultilineString(Graphics2D g, String s, int x, int y, int lineHeight) {
    for (String line : s.split("\n")) {
      g.drawString(line, x, y);
      y += lineHeight;
    }
  }
}
