/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.flutter.inspector.*;
import io.flutter.utils.AsyncRateLimiter;
import io.flutter.utils.math.Matrix4;
import io.flutter.utils.math.Vector3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static io.flutter.inspector.InspectorService.toSourceLocationUri;
import static java.lang.Math.*;

/**
 * Controller that displays interactive screen mirrors for specific widget
 * subtrees or the entire app.
 *
 * This controller abstracts away from the rendering code enough that it can be
 * used.
 */
public class PreviewViewController extends WidgetViewController {
  public static final int PREVIEW_PADDING_X = 20;
  public static final double MOUSE_FRAMES_PER_SECOND = 10.0;
  public static final double SCREENSHOT_FRAMES_PER_SECOND = 10.0;
  static final int PREVIEW_MAX_WIDTH = 280;
  static final int PREVIEW_MAX_HEIGHT = 520;
  static final Color SHADOW_COLOR = new Color(0, 0, 0, 64);
  static final int defaultLineHeight = 20;
  private final boolean drawBackground;
  Rectangle relativeRect;
  Rectangle lastLockedRectangle;
  Rectangle screenshotBounds;
  // Screenshot bounds in absolute window coordinates.
  Rectangle lastScreenshotBoundsWindow;
  int maxHeight;
  boolean _mouseInScreenshot = false;
  float previewWidthScale = 0.7f;
  Rectangle screenshotBoundsOverride;
  private Balloon popup;
  private Point lastPoint;
  private AsyncRateLimiter mouseRateLimiter;
  private AsyncRateLimiter screenshotRateLimiter;
  private boolean controlDown;
  private ArrayList<DiagnosticsNode> currentHits;
  private InspectorObjectGroupManager hover;
  private InspectorService.ObjectGroup screenshotGroup;
  private boolean screenshotDirty = false;
  private int extraHeight = 0;
  private boolean screenshotLoading;
  private boolean popopOpenInProgress;
  private boolean altDown;
  private Screenshot screenshot;
  private ArrayList<DiagnosticsNode> boxes;
  // XXX add in provider for getting the widget.
  public PreviewViewController(WidgetViewModelData data, boolean drawBackground) {
    super(data);
    this.drawBackground = drawBackground;
  }

  public void dispose() {
    if (isDisposed ) {
      return;
    }
    if (popup != null) {
      popup.dispose();;
      popup = null;
    }
    if (hover != null) {
      hover.clear(false);
      hover = null;
    }
    screenshot = null;

    super.dispose();
  }

  AsyncRateLimiter getMouseRateLimiter() {
    if (mouseRateLimiter != null) return mouseRateLimiter;
    mouseRateLimiter = new AsyncRateLimiter(MOUSE_FRAMES_PER_SECOND, () -> {
      if (!isValid()) {
        return CompletableFuture.completedFuture(null);
      }
      return updateMouse(false);
    }, this);
    return mouseRateLimiter;
  }

  AsyncRateLimiter getScreenshotRateLimiter() {
    if (screenshotRateLimiter != null) return screenshotRateLimiter;
    screenshotRateLimiter = new AsyncRateLimiter(SCREENSHOT_FRAMES_PER_SECOND, () -> {
      if (!isValid()) {
        return CompletableFuture.completedFuture(null);
      }
      return updateScreenshot();
    }, this);
    return screenshotRateLimiter;
  }

  public InspectorObjectGroupManager getHovers() {
    if (hover != null && hover.getInspectorService() == getInspectorService()) {
      return hover;
    }
    if (getInspectorService() == null) return null;
    hover = new InspectorObjectGroupManager(getInspectorService(), "hover");
    return hover;
  }

  public @Nullable InspectorService.ObjectGroup getScreenshotGroup() {
    if (screenshotGroup != null && screenshotGroup.getInspectorService() == getInspectorService()) {
      return screenshotGroup;
    }
    if (getInspectorService() == null) return null;
    screenshotGroup =  getInspectorService().createObjectGroup("screenshot");
    return screenshotGroup;
  }

  protected double getDPI() {
    Component comp = data.context.editor != null ? data.context.editor.getComponent() : data.context.component;
    return JBUI.pixScale(comp);
  }

  protected int toPixels(int value) { return (int)(value * getDPI()); }

  Rectangle getScreenshotBoundsTight() {
    // TODO(jacobr): cache this.
    if (screenshotBounds == null || extraHeight == 0) return screenshotBounds;
    Rectangle bounds = new Rectangle(screenshotBounds);
    bounds.height -= extraHeight;
    bounds.y += extraHeight;
    return bounds;
  }

  @Override
  public void onSelectionChanged(DiagnosticsNode selection) {
    super.onSelectionChanged(selection);
    screenshotDirty = true;
  }

  public CompletableFuture<?> updateMouse(boolean navigateTo) {
    final InspectorObjectGroupManager hoverGroups = getHovers();
    if ((hoverGroups == null || popupActive() || popopOpenInProgress) && !navigateTo ) {
      return CompletableFuture.completedFuture(null);
    }
    final Screenshot latestScreenshot = getScreenshotNow();
    if (screenshotBounds == null || latestScreenshot == null || lastPoint == null || !getScreenshotBoundsTight().contains(lastPoint) || getSelectedElement() == null) return CompletableFuture.completedFuture(null);
    hoverGroups.cancelNext();
    final InspectorService.ObjectGroup nextGroup = hoverGroups.getNext();
    final Matrix4 matrix = buildTransformToScreenshot(latestScreenshot);
    matrix.invert();
    final Vector3 point = matrix.perspectiveTransform(new Vector3(lastPoint.getX(), lastPoint.getY(), 0));
    final String file;
    final int startLine, endLine;
    if (controlDown || data.context.editor == null) {
      file = null;
    } else {
      file = toSourceLocationUri(data.context.editor.getVirtualFile().getPath());
    }
    if (controlDown || data.descriptor == null || data.context.document == null) {
      startLine = -1;
      endLine = -1;
    } else {
      final TextRange marker = data.getMarker();
      if (marker == null) {
        // XXX is this right?
        return CompletableFuture.completedFuture(null);
      }
      startLine = data.context.document.getLineNumber(marker.getStartOffset());
      endLine = data.context.document.getLineNumber(marker.getEndOffset());
    }

    final CompletableFuture<ArrayList<DiagnosticsNode>> hitResults = nextGroup.hitTest(getSelectedElement(), point.getX(), point.getY(), file, startLine, endLine);
    nextGroup.safeWhenComplete(hitResults, (hits, error) -> {
      if (isDisposed) return;
      if (error != null || hits == null) {
        return;
      }
      currentHits = hits;
      hoverGroups.promoteNext();
      // XXX remove navigateTo?
      if (navigateTo && popopOpenInProgress) {
        final DiagnosticsNode node = hits.size() > 0 ? hits.get(0) : null;
        if (node == null) return;
        final TransformedRect transform = node.getTransformToRoot();
        if (transform != null) {
          double x, y;
          final Matrix4 transformMatrix = buildTransformToScreenshot(latestScreenshot);
          transformMatrix.multiply(transform.getTransform());
          final Rectangle2D rect = transform.getRectangle();
          final Vector3 transformed = transformMatrix.perspectiveTransform(new Vector3(new double[]{rect.getCenterX(), rect.getMinY(), 0}));
          Point pendingPopupOpenLocation = new Point((int)Math.round(transformed.getX()), (int)Math.round(transformed.getY() + 1));
          if (data.context.editor != null) {
            pendingPopupOpenLocation =
              SwingUtilities.convertPoint(
                data.context.editor.getContentComponent(),
                pendingPopupOpenLocation,
                data.context.editor.getComponent()
              );
          }
          if (data.context.editor != null) {
            popup = PropertyEditorPanel
              .showPopup(inspectorService, data.context.editor, node, node.getCreationLocation().getLocation(), data.context.flutterDartAnalysisService, pendingPopupOpenLocation);
          } else {
            popup = PropertyEditorPanel
              .showPopup(inspectorService, data.context.project, data.context.component, node, node.getCreationLocation().getLocation(), data.context.flutterDartAnalysisService, pendingPopupOpenLocation);
          }
        }
        popopOpenInProgress =false;
      }
      if (navigateTo && hits.size() > 0) {
        /// XXX kindof the wrong group.
        getGroups().getCurrent().setSelection(hits.get(0).getValueRef(), false, false);
      }
      forceRender();
    });
    return hitResults;
  }

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
      setCustomCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
      setMouseInScreenshot(true);
      if (!getScreenshotBoundsTight().contains(lastPoint)) {
        cancelHovers();
      }
    }
    else {
      setCustomCursor(null);
      _mouseOutOfScreenshot();
    }
  }

  void setCustomCursor(@Nullable Cursor cursor) {
    if (data.context.editor == null) {
      // TODO(jacobr): customize the cursor when there is not an associated editor.
      return;
    }
    data.context.editor.setCustomCursor(this, cursor);
  }

  void registerLastEvent(MouseEvent event) {
    lastPoint = event.getPoint();
    controlDown = event.isControlDown();
    altDown = event.isAltDown();
    updateMouseCursor();
  }

  public void onMouseMoved(MouseEvent event) {
    assert (!event.isConsumed());
    registerLastEvent(event);
    if (_mouseInScreenshot) {
      event.consume();
      if (getScreenshotBoundsTight().contains(lastPoint) && !popupActive()) {
        getMouseRateLimiter().scheduleRequest();
      }
    }
  }

  boolean popupActive() {
    return popup != null && !popup.isDisposed() || popopOpenInProgress;
  }

  private void _hidePopup() {
    if (popup != null && !popup.isDisposed()) {
      popup.dispose();
      popup = null;
    }
  }

  public void onMousePressed(MouseEvent event) {
    registerLastEvent(event);
    final Point point = event.getPoint();
    if (screenshotBounds != null && screenshotBounds.contains(point)) {
      event.consume();
      if (event.isPopupTrigger()) {
        _hidePopup();
        popopOpenInProgress = true;
        updateMouse(true);
      }
    }
  }

  @Override
  public void onMouseReleased(MouseEvent event) {
    registerLastEvent(event);

    if (screenshotBounds == null) return;
    final Point point = event.getPoint();
    if (screenshotBounds.contains(point)) {
      Rectangle tightBounds = getScreenshotBoundsTight();
      event.consume();
      if (tightBounds.contains(point)) {
        if (event.isPopupTrigger()) {
          _hidePopup();
          popopOpenInProgress = true;
        }
        updateMouse(true);
      } else {
        // Title hit.
        _hidePopup();
        if (elements != null) {
          if (elements.size() > 1) {
            int newIndex = this.activeIndex + 1;
            if (newIndex >= elements.size()) {
              newIndex = 1; // Wrap around hack case because the first index is out of order.
            }
            // TODO(jacobr): we could update activeIndex now instead of waitng for the UI to update.
            getGroups().getCurrent().setSelection(elements.get(newIndex).getValueRef(), false, true);
          } else if (elements.size() == 1) {
            // Select current.
            getGroups().getCurrent().setSelection(elements.get(0).getValueRef(), false, true);
          }
        }
      }
    }
  }

  public void onMouseExited(MouseEvent event) {
    lastPoint = null;
    controlDown = false;
    altDown = false;
    setMouseInScreenshot(false);
    updateMouseCursor();
  }

  @Override
  public void updateSelected(Caret carat) {

  }

  @Override
  public void requestRepaint(boolean force) {
    onFlutterFrame();
  }

  @Override
  public void onFlutterFrame() {
    fetchScreenshot(false);
  }

  public void _mouseOutOfScreenshot() {
    setMouseInScreenshot(false);
    lastPoint = null;
    cancelHovers();
  }

  public void cancelHovers() {
    hover = getHovers();
    if (hover != null) {
      hover.cancelNext();
      controlDown = false;
      altDown = false;
    }
    if (currentHits != null) {
      currentHits = null;
      forceRender();
    }
  }

  public void onMouseEntered(MouseEvent event) {
    onMouseMoved(event);
  }

  void clearState() {
    screenshotDirty = true;
    currentHits = new ArrayList<>();
    if (hover != null) {
      hover.clear(true);
    }
    screenshot = null;
  }

  @Override
  public void onInspectorAvailable(InspectorService inspectorService) {
    if (inspectorService != this.inspectorService) {
      clearState();
    }
    super.onInspectorAvailable(inspectorService);
  }

  public void onVisibleChanged() {
    if (!visible) {
      _hidePopup();
    }
    if (visible) {
      computeScreenshotBounds();
      if (getInspectorService() != null) {
        if (screenshot == null || screenshotDirty) {
          fetchScreenshot(false);
        }
      }
    }
  }

  public void setScreenshotBounds(Rectangle bounds) {
    final boolean sizeChanged = screenshotBounds != null && (screenshotBounds.width != bounds.width || screenshotBounds.height != bounds.height);
    screenshotBoundsOverride = bounds;
    screenshotBounds = bounds;
    if (sizeChanged) {
      // Get a new screenshot as the existing screenshot isn't the right resolution.
      // TODO(jacobr): only bother if the resolution is sufficiently different.
      fetchScreenshot(false);
    }
  }

  // XXX move this into a subclass for rendering in the code editor.
  public void computeScreenshotBounds() {
    final Rectangle previousScreenshotBounds = screenshotBounds;
    if (screenshotBoundsOverride != null) {
      screenshotBounds = screenshotBoundsOverride;
      return;
    }
    screenshotBounds = null;
    maxHeight = Math.round(PREVIEW_MAX_HEIGHT * 0.16f);
    final WidgetIndentGuideDescriptor descriptor = getDescriptor();

    final int lineHeight = data.context.editor != null ? data.context.editor.getLineHeight() : defaultLineHeight;
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

    final TextRange marker = data.getMarker();
    if (marker == null) return;
    final int startOffset = marker.getStartOffset();
    final Document doc = data.context.document;
    final int textLength = doc.getTextLength();
    if (startOffset >= textLength) return;

    final int endOffset = min(marker.getEndOffset(), textLength);

    int off;
    int startLine = doc.getLineNumber(startOffset);

    int widgetOffset = data.descriptor.widget.getGuideOffset();
    int widgetLine = doc.getLineNumber(widgetOffset);
    int lineEndOffset = doc.getLineEndOffset(widgetLine);

    // Request a thumbnail and render it in the space available.
    VisualPosition visualPosition = data.context.editor.offsetToVisualPosition(lineEndOffset); // e
    visualPosition = new VisualPosition(max(visualPosition.line, 0), 81);
    final Point start = data.context.editor.visualPositionToXY(visualPosition);
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
    }
    if (popupActive()) {
      lastLockedRectangle = new Rectangle(visibleRect);
    }
  }

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
    if (lastLockedRectangle != null && visible && visibleRect != null) {
      return visibleRect.intersects(lastLockedRectangle);
    }
    return false;
  }

  public Screenshot getScreenshotNow() {
    return screenshot;
  }

  /**
   * Builds a transform that
   */
  private Matrix4 buildTransformToScreenshot(Screenshot latestScreenshot) {
    final Matrix4 matrix = Matrix4.identity();
    matrix.translate(screenshotBounds.x, screenshotBounds.y + extraHeight, 0);
    final Rectangle2D imageRect = latestScreenshot.transformedRect.getRectangle();
    final double centerX = imageRect.getCenterX();
    final double centerY = imageRect.getCenterY();
    matrix.translate(-centerX, -centerY, 0);
    matrix.scale(1/getDPI(), 1/getDPI(), 1/getDPI());
    matrix.translate(centerX * getDPI(), centerY * getDPI(), 0);
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
  public void setElements(ArrayList<DiagnosticsNode> elements) {
    super.setElements(elements);
    currentHits = null;
    boxes = null;
  }

  boolean hasCurrentHits() {
    return currentHits != null && !currentHits.isEmpty();
  }

  @Override
  public void onMaybeFetchScreenshot() {
    if (screenshot == null || screenshotDirty) {
      fetchScreenshot(false);;
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

  void fetchScreenshot(boolean mightBeIncompatible) {
    if (mightBeIncompatible) {
      screenshotLoading = true;
    }
    screenshotDirty = true;
    if (!visible) return;

    // XXX
    /*
    InspectorObjectGroupManager groups = getGroups();
    if (isNodesEmpty() || groups == null) {
      clearScreenshot();
      return;
    }*/

    getScreenshotRateLimiter().scheduleRequest();
  }

  CompletableFuture<InspectorService.InteractiveScreenshot> updateScreenshot() {
    final InspectorService.ObjectGroup group = getScreenshotGroup();
    if (group == null ) {
      return CompletableFuture.completedFuture(null);
    }
    int previewWidth;
    int previewHeight;
    if (screenshotBoundsOverride != null) {
     previewWidth = screenshotBoundsOverride.width;
     previewHeight = screenshotBoundsOverride.height;
    } else {
      previewWidth = PREVIEW_MAX_WIDTH;
      previewHeight = PREVIEW_MAX_HEIGHT;

      if (getDescriptor() == null) {
        previewWidth = round(previewWidth * previewWidthScale);
        previewHeight = round(previewHeight * previewWidthScale);
      }
    }

    long startTime = System.currentTimeMillis();
    // 0.7 is a tweak to ensure we do not try to download enormous screenshots.
    CompletableFuture<InspectorService.InteractiveScreenshot> screenshotFuture =
      group.getScreenshotAtLocation(getLocation(), 10, toPixels(previewWidth), toPixels(previewHeight), getDPI() * 0.7);
    group.safeWhenComplete(
      screenshotFuture,
      (pair, e2) -> {
        if (e2 != null) {
          System.out.println("XXX skipping due to " + e2);
        }
        if (e2 != null || isDisposed || inspectorService == null) return;
        long endTime = System.currentTimeMillis();
        final String path =  data.context.editor != null ? data.context.editor.getVirtualFile().getPath() : "";
        System.out.println("XXX took " + (endTime-startTime) + "ms to fetch screenshot for  -- " +path );
        if (pair == null) {
          System.out.println("XXX null");
        }
        if (pair == null) {
          setElements(null);
          screenshot = null;
          boxes = null;
        } else {
          setElements(pair.elements);
          screenshot = pair.screenshot;
          boxes = pair.boxes;
        }
        screenshotDirty = false;
        screenshotLoading = false;
        // This calculation might be out of date due to the new screenshot.
        computeScreenshotBounds();
        forceRender();
      }
    );
    return screenshotFuture;
  }

  @Override
  public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
    if (data.context.editor.isPurePaintingMode()) {
      // Don't show previews in pure mode.
      return;
    }
    if (!highlighter.isValid()) {
      return;
    }
    if (data.descriptor != null && !data.descriptor.widget.isValid()) {
      return;
    }
    final int lineHeight = editor.getLineHeight();
    paint(g, lineHeight);
  }

  public void paint(@NotNull Graphics g, int lineHeight) {
    final WidgetIndentGuideDescriptor descriptor = getDescriptor();
    final Graphics2D g2d = (Graphics2D)g.create();
    // Required to render colors with an alpha channel. Rendering with an
    // alpha chanel makes it easier to keep relationships between shadows
    // and lines looking consistent when the background color changes such
    // as in the case of selection or a different highlighter turning the
    // background yellow.
    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1));
    final Rectangle clip = g2d.getClipBounds();
    computeScreenshotBounds();
    if (screenshotBounds == null || !clip.intersects(screenshotBounds)) {
      return;
    }

    final Screenshot latestScreenshot = getScreenshotNow();
    int imageWidth = screenshotBounds.width;
    int imageHeight = screenshotBounds.height;

    if (drawBackground) {
      /// XXX hack to draw a shadow.
      final int SHADOW_HEIGHT = 5;
      for (int h = 1; h < SHADOW_HEIGHT; h++) {
        g2d.setColor(new Color(43, 43, 43, 100 - h * 20));
        g2d.fillRect(screenshotBounds.x - h + 1, screenshotBounds.y + min(screenshotBounds.height, imageHeight) + h,
                     min(screenshotBounds.width, imageWidth) + h - 1,
                     1);
        g2d.fillRect(screenshotBounds.x - h + 1,
                     screenshotBounds.y - h,
                     min(screenshotBounds.width, imageWidth) + h - 1,
                     1);
        g2d.fillRect(screenshotBounds.x - h, screenshotBounds.y - h, 1, imageHeight + 2 * h);
      }
    }
    g2d.clip(screenshotBounds);

    final Font font = UIUtil.getFont(UIUtil.FontSize.MINI, UIUtil.getTreeFont());
    g2d.setFont(font);
    // Request a thumbnail and render it in the space available.

    g2d.setColor(isSelected ? JBColor.GRAY : JBColor.LIGHT_GRAY);

    if (latestScreenshot != null) {
      imageWidth = (int)(latestScreenshot.image.getWidth() * getDPI());
      imageHeight = (int)(latestScreenshot.image.getHeight() * getDPI());
      if (extraHeight > 0) {
        if (drawBackground) {
          g2d.setColor(JBColor.LIGHT_GRAY);
          g2d.fillRect(screenshotBounds.x, screenshotBounds.y, min(screenshotBounds.width, imageWidth),
                       min(screenshotBounds.height, extraHeight));
        }
        if (descriptor != null) {
          final int line = descriptor.widget.getLine() + 1;
          final int column = descriptor.widget.getColumn() + 1;
          int numActive = elements != null ? elements.size() : 0;
          String message = descriptor.outlineNode.getClassName() + " " ;//+ " Widget ";
          if (numActive == 0) {
            message += "(inactive)";
          } else if (numActive == 1) {
//            message += "(active)";
          } else {
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
      g2d.clip(getScreenshotBoundsTight());


      g2d.drawImage(latestScreenshot.image, new AffineTransform(1 / getDPI(), 0f, 0f, 1 / getDPI(), screenshotBounds.x, screenshotBounds.y + extraHeight), null);

      final java.util.List<DiagnosticsNode> nodesToHighlight = getNodesToHighlight();
      // Sometimes it is fine to display even if we are loading.
      // TODO(jacobr): be smarter and track if the highlights are associated with a different screenshot.
      if (nodesToHighlight != null && nodesToHighlight.size() > 0) { //&& !screenshotLoading) {
        boolean first = true;
        for (DiagnosticsNode box : nodesToHighlight) {
          final TransformedRect transform = box.getTransformToRoot();
          if (transform != null) {
            double x, y;
            final Matrix4 matrix = buildTransformToScreenshot(latestScreenshot);
            matrix.multiply(transform.getTransform());
            final Rectangle2D rect = transform.getRectangle();

            final Vector3[] points = new Vector3[]{
              matrix.perspectiveTransform(new Vector3(new double[]{rect.getMinX(), rect.getMinY(), 0})),
              matrix.perspectiveTransform(new Vector3(new double[]{rect.getMaxX(), rect.getMinY(), 0})),
              matrix.perspectiveTransform(new Vector3(new double[]{rect.getMaxX(), rect.getMaxY(), 0})),
              matrix.perspectiveTransform(new Vector3(new double[]{rect.getMinX(), rect.getMaxY(), 0}))
            };

            final Polygon polygon = new Polygon();
            for (Vector3 point : points) {
              polygon.addPoint((int)Math.round(point.getX()), (int)Math.round(point.getY()));
            }

            if (first && elements.size() > 0 && !Objects.equals(box.getValueRef(), elements.get(0).getValueRef())) {
              g2d.setColor(FlutterEditorColors.HIGHLIGHTED_RENDER_OBJECT_BORDER_COLOR);
              g2d.fillPolygon(polygon);
            }
            g2d.setStroke(FlutterEditorColors.SOLID_STROKE);
            g2d.setColor(FlutterEditorColors.HIGHLIGHTED_RENDER_OBJECT_BORDER_COLOR);
            g2d.drawPolygon(polygon);
          }
          first = false;

        }
      }
    } else {
      if (drawBackground) {
        g2d.setColor(isSelected ? JBColor.GRAY : JBColor.LIGHT_GRAY);
        g2d.fillRect(screenshotBounds.x, screenshotBounds.y, screenshotBounds.width, screenshotBounds.height);
        g2d.setColor(FlutterEditorColors.SHADOW_GRAY);
      }
      g2d.setColor(JBColor.BLACK);
      if (descriptor == null) {
        String message = getInspectorService() == null ? "Run the application to\nactivate device mirror." : "Loading...";
        drawMultilineString(g2d, message, screenshotBounds.x + 4, screenshotBounds.y + + lineHeight - 4, lineHeight);
      } else {
        final int line = descriptor.widget.getLine() + 1;
        final int column = descriptor.widget.getColumn() + 1;
        drawMultilineString(g2d, descriptor.outlineNode.getClassName() + " Widget " + line + ":" + column + "\n"+
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
