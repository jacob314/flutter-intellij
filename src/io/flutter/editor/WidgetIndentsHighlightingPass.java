/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

package io.flutter.editor;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.ui.paint.LinePainter2D;
import com.intellij.util.DocumentUtil;
import com.intellij.util.text.CharArrayUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import com.jetbrains.lang.dart.psi.*;
import io.flutter.dart.FlutterDartAnalysisServer;
import io.flutter.inspector.*;
import io.flutter.settings.FlutterSettings;
import io.flutter.utils.AsyncRateLimiter;
import org.dartlang.analysis.server.protocol.FlutterOutline;
import org.dartlang.analysis.server.protocol.FlutterOutlineAttribute;
import org.dartlang.analysis.server.protocol.FlutterWidgetProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static io.flutter.inspector.InspectorService.toSourceLocationUri;
import static java.lang.Math.*;

// Instructions for how this code should be tested:
// This code could be tested by true integration tests or better yet by
// unittests that are able to create Editor object instances. Testing this
// code does not require running a Flutter application but it does require
// creating Editor object instances and would benefit from creating a live
// Dart analysis server to communicate with.
//
// Suggested steps to test this code:
// Create a representative Dart file containing a a couple build methods with
// deeply nested widget trees.
// Create an Editor instance from the dart file.
// Get a Flutter outline from that file or inject a snapshotted Flutter outline
// iof it isn't feasible to get a true Flutter outline.
// Verify that calling
// pass = new WidgetIndentsHighlightingPass(project, editor);
// pass.setOutline(flutterOutline);
// results in adding highlights to the expected ranges. Highlighters can be
// found querying the editor directly or calling.

// final CustomHighlighterRenderer renderer = highlighter.getCustomRenderer();
//      ((WidgetCustomHighlighterRenderer)renderer).dispose();
// You could then even call the render method on a highlighter if you wanted
// a golden image that just contained the widget indent tree diagram. In
// practice it would be sufficient to get the WidgetIndentGuideDescriptor from
// the renderer and verify that the child locations are correct. The
// important piece to test is that the child widget locations are acurate even
// after making some edits to the document which brings to the next step:
// Make a couple edits to the document and verify that the widget indents are
// still accurate even after the change. The machinery in Editor will track the
// edits and update the widget indents appropriately even before a new
// FlutterOutline is available.
//
// Final step: create a new FlutterOutline and verify passing it in updates the
// widget guides removing guides not part of the outline. For example, Add a
// character to a constructor name so the constructor is not a Widget subclass.
// That will cause the outermost guide in the tree to be removed. Alternately,
// add another widget to the list of children for a widget.
//
// You could also performa golden image integration test to verify that the
// actual render of the text editor matched what was expected but changes
// in font rendering would make that tricky.

/**
 * A WidgetIndentsHighlightingPass drawsg UI as Code Guides for a code editor using a
 * FlutterOutline.
 * <p>
 * This class is similar to a TextEditorHighlightingPass but doesn't actually
 * implement TextEditorHighlightingPass as it is driven by changes to the
 * FlutterOutline which is only available when the AnalysisServer computes a
 * new outline while TextEditorHighlightingPass assumes all information needed
 * is available immediately.
 */
public class WidgetIndentsHighlightingPass {
  private static final Logger LOG = Logger.getInstance(WidgetIndentsHighlightingPass.class);

  private final static Color TOOLTIP_BACKGROUND_COLOR = new Color(60, 60, 60, 230);
  private final static Color HIGHLIGHTED_RENDER_OBJECT_FILL_COLOR = new Color( 128, 128, 255, 128);
  private final static Color HIGHLIGHTED_RENDER_OBJECT_BORDER_COLOR = new Color(64, 64, 128, 128);

  private final static Stroke SOLID_STROKE = new BasicStroke(1);
  private final static JBColor VERY_LIGHT_GRAY = new JBColor(Gray._224, Gray._80);
  private final static JBColor SHADOW_GRAY = new JBColor(Gray._192, Gray._100);
  private final static JBColor OUTLINE_LINE_COLOR = new JBColor(Gray._128, Gray._128);
  private final static JBColor OUTLINE_LINE_COLOR_PAST_BLOCK = new JBColor(new Color(128, 128, 128, 65), new Color(128, 128, 128, 65));
  private final static JBColor BUILD_METHOD_STRIPE_COLOR = new JBColor(new Color(0xc0d8f0), new Color(0x8d7043));

  private static final Key<WidgetIndentsPassData> INDENTS_PASS_DATA_KEY = Key.create("INDENTS_PASS_DATA_KEY");

  /**
   * When this debugging flag is true, problematic text ranges are reported.
   */
  private final static boolean DEBUG_WIDGET_INDENTS = false;

  public static class WidgetCustomHighlighterRenderer implements CustomHighlighterRenderer {
    private final WidgetIndentGuideDescriptor descriptor;
    private final RangeHighlighter highlighter;
    private final Document document;
    private boolean isSelected = false;
    private final WidgetIndentsPassData data; // XXX for visilbe rect only.
    private final FlutterDartAnalysisServer flutterDartAnalysisService;
    private final EditorEx editor;
    // XXX remove and use the better GroupManager abstraction everywhere eliminating all futures stored here.
    private InspectorService.ObjectGroup group;
    private InspectorObjectGroupManager hover;

    static final int PREVIEW_WIDTH = 240;
    static final int PREVIEW_HEIGHT = 320;
    private JBPopup popup;
    private Point lastPoint;
    private ArrayList<DiagnosticsNode> currentHits;

    private AsyncRateLimiter mouseRateLimiter;
    public static final double MOUSE_FRAMES_PER_SECOND = 10.0;
    private boolean controlDown;
    private boolean shiftDown;

    AsyncRateLimiter getMouseRateLimiter() {
      if (mouseRateLimiter != null) return mouseRateLimiter;
      mouseRateLimiter = new AsyncRateLimiter(MOUSE_FRAMES_PER_SECOND, () -> { return updateMouse(false); });
      return mouseRateLimiter;
    }

    public InspectorObjectGroupManager getHover() {
      if (hover != null && hover.getInspectorService() == getInspectorService()) {
        return hover;
      }
      if (getInspectorService() == null) return null;
      hover = new InspectorObjectGroupManager(getInspectorService(), "hover");
      return hover;
    }

    double getDPI() { return 2.0; }
    int toPixels(int value) { return (int)(value * getDPI()); }
    boolean visible = false;
    private CompletableFuture<Screenshot> screenshot;
    CompletableFuture<ArrayList<DiagnosticsNode>> nodes;
    Screenshot lastScreenshot;
    Rectangle lastScreenshotBounds;
    private ArrayList<DiagnosticsNode> boxes;
    private DiagnosticsNode root;
    int maxHeight;

    WidgetCustomHighlighterRenderer(WidgetIndentGuideDescriptor descriptor, Document document, WidgetIndentsPassData data, FlutterDartAnalysisServer flutterDartAnalysisService, EditorEx editor, RangeHighlighter highlighter) {
      this.descriptor = descriptor;
      this.document = document;
      this.data = data;
      this.flutterDartAnalysisService = flutterDartAnalysisService;
      this.editor = editor;
      this.highlighter = highlighter;
      descriptor.trackLocations(document);
      updateVisibleArea(data.visibleRect);
    }

    void dispose() {
      // Descriptors must be disposed so they stop getting notified about
      // changes to the Editor.
      descriptor.dispose();
      if (group != null) {
        group.dispose();
      }
    }

    boolean setSelection(boolean value) {
      if (value == isSelected) return false;
      isSelected = value;
      if (value) {
        computeActiveElements();
        if (nodes != null) {
          group.safeWhenComplete(nodes, (matching, error) -> {
            InspectorService service = getInspectorService();
            if (service == null || error != null || matching == null || matching.isEmpty()) return;
            if (group == null) return;
            group.setSelection(matching.get(0).getValueRef(), false, true);
          });
        }

        final List<FlutterWidgetProperty> properties =
          this.flutterDartAnalysisService.getWidgetDescription(this.editor.getVirtualFile(), descriptor.widget.getOffset());
        /* XXX display properties UI.
        if (properties != null && !properties.isEmpty()) {
          System.out.println("XXX properties=" + properties);
        } else {
          System.out.println("XXX no properties");
        }

         */
      }
      return true;

    }

    void updateSelected(Caret carat) {
      if (updateSelectedHelper(carat)) {
        forceRender();
      }
    }

    boolean updateSelectedHelper(Caret carat) {
      if (carat == null) {
        return setSelection(false);
      }
      final CaretModel caretModel = editor.getCaretModel();
      final int startOffset = highlighter.getStartOffset();
      final Document doc = highlighter.getDocument();
      final int caretOffset = carat.getOffset();

      if (startOffset >= doc.getTextLength()) {
        return setSelection(false);
      }

      final int endOffset = highlighter.getEndOffset();

      int off = startOffset;
      int startLine = doc.getLineNumber(startOffset);
      {
        final CharSequence chars = doc.getCharsSequence();
        do {
          final int start = doc.getLineStartOffset(startLine);
          final int end = doc.getLineEndOffset(startLine);
          off = CharArrayUtil.shiftForward(chars, start, end, " \t");
          startLine--;
        }
        while (startLine > 1 && off < doc.getTextLength() && chars.charAt(off) == '\n');
      }

      final VisualPosition startPosition = editor.offsetToVisualPosition(off);
      final int indentColumn = startPosition.column;

      final LogicalPosition logicalPosition = caretModel.getLogicalPosition();
      if (logicalPosition.line == startLine + 1 && descriptor.widget != null) {
        // Be more permissive about what constitutes selection for the first
        // line within a widget constructor.
        return setSelection(caretModel.getLogicalPosition().column >= indentColumn);
      }
      return setSelection(
        caretOffset >= off && caretOffset < endOffset && caretModel.getLogicalPosition().column == indentColumn);
    }

    // XXX optimize
    private static float computeStringWidth(Editor editor, String text, Font font) {
      if (StringUtil.isEmpty(text)) return 0;
      final FontMetrics metrics = editor.getComponent().getFontMetrics(font);

      final FontRenderContext fontRenderContext = metrics.getFontRenderContext();
      return (float)font.getStringBounds(text, fontRenderContext).getWidth();
    }

    Point offsetToPoint(int offset) {
      return editor.visualPositionToXY( editor.offsetToVisualPosition(offset));
    }

    public void computeScreenshotBounds() {
      lastScreenshotBounds = null;
      maxHeight = 320; // XXX
      if (descriptor == null || descriptor.parent != null) return;

      final int startOffset = highlighter.getStartOffset();
      final Document doc = highlighter.getDocument();
      final int textLength = doc.getTextLength();
      if (startOffset >= textLength) return;

      final int endOffset = min(highlighter.getEndOffset(), textLength);

      int off;
      int startLine = doc.getLineNumber(startOffset);
      final int lineHeight = editor.getLineHeight();

      int widgetOffset = descriptor.widget.getOffset();
      int widgetLine = doc.getLineNumber(widgetOffset);
      int lineEndOffset = doc.getLineEndOffset(widgetLine);

      // Request a thumbnail and render it in the space available.
      VisualPosition visualPosition = editor.offsetToVisualPosition(lineEndOffset); // e
      visualPosition = new VisualPosition(max(visualPosition.line, 0), max(visualPosition.column + 30, 81));
      final Point start = editor.visualPositionToXY(visualPosition);
      final Point endz = offsetToPoint(endOffset);
      int endY = endz.y;
      int visibleEndX = data.visibleRect.x + data.visibleRect.width;
      int width = max(0, visibleEndX - 20 - start.x);
      int height = max(0, endY - start.y);
      int previewStartY = start.y;
      int previewStartX = start.x;
      assert (data.visibleRect != null);
      int visibleStart = data.visibleRect.y;
      int visibleEnd = (int)data.visibleRect.getMaxY();

      Screenshot latestScreenshot = getScreenshotNow();
      int previewWidth = PREVIEW_WIDTH;
      int previewHeight = PREVIEW_HEIGHT;
      if (latestScreenshot != null) {
        previewWidth = (int)(latestScreenshot.image.getWidth() / getDPI());
        previewHeight = (int)(latestScreenshot.image.getHeight() / getDPI());
      }
      previewStartX = max(previewStartX, visibleEndX - previewWidth - 20);
      previewHeight = min(previewHeight, height);

      maxHeight = endz.y - start.y;
      if (start.y <= visibleEnd && endY >= visibleStart) {
        if (visibleStart > previewStartY) {
          previewStartY = max(previewStartY, visibleStart);
          previewStartY = min(previewStartY, min(endY - previewHeight, visibleEnd - previewHeight));
        }
        lastScreenshotBounds = new Rectangle(previewStartX, previewStartY, previewWidth, previewHeight);
      }
      if (visible) {
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
        if (popup != null && !popup.isDisposed()) {
          popup.dispose();
          popup = null;
        }
      }
    }

    public Screenshot getScreenshotNow() {
      Screenshot image = null;
      if (screenshot != null) {
        image = screenshot.getNow(null);
      }
      if (image == null) {
        // TODO(jacobr): warn that it might be stale.
        image = lastScreenshot;
      }

      lastScreenshot = image;
      return image;
    }
    @Override
    public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
      if (!highlighter.isValid()) {
        return;
      }
      if (!descriptor.widget.isValid()) {
        return;
      }
      final FlutterSettings settings = FlutterSettings.getInstance();
      final boolean showMultipleChildrenGuides = settings.isShowMultipleChildrenGuides();

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

      int off;
      int startLine = doc.getLineNumber(startOffset);
      final int lineHeight = editor.getLineHeight();
      final Rectangle clip = g2d.getClipBounds();

      computeScreenshotBounds();

      if (lastScreenshotBounds != null) {
        final Font font = UIUtil.getFont(UIUtil.FontSize.NORMAL, UIUtil.getTreeFont());
        g2d.setFont(font);
        // Request a thumbnail and render it in the space available.

        /// XXX do proper clipping as well to optimize. ?
        g2d.setColor(isSelected ? JBColor.GRAY: JBColor.LIGHT_GRAY);

        final Screenshot latestScreenshot = getScreenshotNow();
        if (latestScreenshot != null) {
          lastScreenshot = latestScreenshot;
          int imageWidth = (int)(latestScreenshot.image.getWidth() * getDPI());
          int imageHeight = (int)(latestScreenshot.image.getHeight() * getDPI());
          g2d.setColor(Color.WHITE);
        //  g2d.clipRect(lastScreenshotBounds.x, lastScreenshotBounds.y, min(lastScreenshotBounds.width, imageWidth), min(lastScreenshotBounds.height, imageHeight));
          g2d.fillRect(lastScreenshotBounds.x, lastScreenshotBounds.y, min(lastScreenshotBounds.width, imageWidth), min(lastScreenshotBounds.height, imageHeight));
          g2d.drawImage(latestScreenshot.image, new AffineTransform(1/getDPI(), 0f, 0f, 1/getDPI(), lastScreenshotBounds.x, lastScreenshotBounds.y), null);
          final List<DiagnosticsNode> nodesToHighlight = getNodesToHighlight();
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


                final Polygon polygon = new Polygon();
                for (Vector3 point : points) {
                  polygon.addPoint((int)Math.round(point.getX()), (int)Math.round(point.getY()));
                }

                if (first) {
                  g2d.setColor(HIGHLIGHTED_RENDER_OBJECT_BORDER_COLOR);
                  g2d.fillPolygon(polygon);
                }
                g2d.setStroke(SOLID_STROKE);
                g2d.setColor(HIGHLIGHTED_RENDER_OBJECT_BORDER_COLOR);
                g2d.drawPolygon(polygon);
              }
              first = false;

            }
          }
        } else {
          g2d.setColor(isSelected ? JBColor.GRAY: JBColor.LIGHT_GRAY);
          g2d.fillRect(lastScreenshotBounds.x, lastScreenshotBounds.y, lastScreenshotBounds.width, lastScreenshotBounds.height);
        }

        {
          g2d.setColor(descriptor.parent == null ? JBColor.blue : JBColor.red);
          final int widgetOffset = descriptor.widget.getOffset();
          final int widgetLine = doc.getLineNumber(widgetOffset);
          final int lineEndOffset = doc.getLineEndOffset(widgetLine);
          VisualPosition visualPosition = editor.offsetToVisualPosition(lineEndOffset); // e
          visualPosition = new VisualPosition(visualPosition.line, max(visualPosition.column + 1, 4));
          // final VisualPosition startPosition = editor.offsetToVisualPosition(off);
          final Point start = editor.visualPositionToXY(visualPosition);
          if (start.y + lineHeight > clip.y && start.y < clip.y + clip.height) {
            String message = "";
            if (nodes != null) {
              final ArrayList<DiagnosticsNode> matching = nodes.getNow(null);
              if (matching == null) {
                message = "Fetching...";
              } else {
                if (matching.isEmpty()) {
                  message = "Widget inactive";
                } else {
                  final DiagnosticsNode first = matching.get(0);
                  message = first.getDescription();
                  if (matching.size() > 1) {
                    message += " (" + matching.size() + " matching)";
                  }
                }
              }
            }
            g2d.drawString(message, start.x, start.y + lineHeight - 4);
          }
        }
  // XXX
        //      Rectangle bounds = g2d.getClipBounds();
//        g2d.setClip(bounds.x, bounds.y, 800, bounds.height);
        for (WidgetIndentGuideDescriptor.WidgetPropertyDescriptor property : descriptor.properties) {
          int propertyEndOffset = property.getEndOffset();
          int propertyLine = doc.getLineNumber(propertyEndOffset);
          int lineEndOffset = doc.getLineEndOffset(propertyLine);

          VisualPosition visualPosition = editor.offsetToVisualPosition(lineEndOffset); // e
          visualPosition = new VisualPosition(visualPosition.line, max(visualPosition.column + 1, 4));
          // final VisualPosition startPosition = editor.offsetToVisualPosition(off);
          final Point start = editor.visualPositionToXY(visualPosition);
          if (start.y + lineHeight > clip.y && start.y < clip.y + clip.height) {

            String text;
            String value;
            FlutterOutlineAttribute attribute = property.getAttribute();
            boolean constValue = false;
            if (attribute.getLiteralValueBoolean() != null) {
              value = attribute.getLiteralValueBoolean().toString();
              constValue = true;
            }
            else if (attribute.getLiteralValueInteger() != null) {
              value = attribute.getLiteralValueInteger().toString();
              constValue = true;
            }
            else if (attribute.getLiteralValueString() != null) {
              value = '"' + attribute.getLiteralValueString() + '"';
              constValue = true;
            }
            else {
              value = attribute.getLabel();
              if (value == null) {
                value = "<loading value>";
              }
            }
            if (property.getName().equals("data")) {
              text = value;
            }
            else {
              text = property.getName() + ": " + value;
            }

            // TODO(jacobr): detect other const like things and hide them.
            if (constValue == false) {
              float width = computeStringWidth(editor, text, font);
              //          g2d.setColor(JBColor.LIGHT_GRAY);
              //        g2d.fillRect(start.x, start.y, (int)width + 8, lineHeight);
              g2d.setColor(SHADOW_GRAY);
             // XXX g2d.drawString(text, start.x + 4, start.y + lineHeight - 4);
            }
          }
        }
      }


      final CharSequence chars = doc.getCharsSequence();
      do {
        final int start = doc.getLineStartOffset(startLine);
        final int end = doc.getLineEndOffset(startLine);
        off = CharArrayUtil.shiftForward(chars, start, end, " \t");
        startLine--;
      }
      while (startLine > 1 && off < doc.getTextLength() && chars.charAt(off) == '\n');

      final VisualPosition startPosition = editor.offsetToVisualPosition(off);
      int indentColumn = startPosition.column;

      // It's considered that indent guide can cross not only white space but comments, javadoc etc. Hence, there is a possible
      // case that the first indent guide line is, say, single-line comment where comment symbols ('//') are located at the first
      // visual column. We need to calculate correct indent guide column then.
      int lineShift = 1;
      if (indentColumn <= 0 && descriptor != null) {
        indentColumn = descriptor.indentLevel;
        lineShift = 0;
      }
      if (indentColumn <= 0) return;

      final FoldingModel foldingModel = editor.getFoldingModel();
      if (foldingModel.isOffsetCollapsed(off)) return;

      final FoldRegion headerRegion = foldingModel.getCollapsedRegionAtOffset(doc.getLineEndOffset(doc.getLineNumber(off)));
      final FoldRegion tailRegion = foldingModel.getCollapsedRegionAtOffset(doc.getLineStartOffset(doc.getLineNumber(endOffset)));

      if (tailRegion != null && tailRegion == headerRegion) return;

      final CaretModel caretModel = editor.getCaretModel();
      final int caretOffset = caretModel.getOffset();
      //      updateSelected(editor, highlighter, caretOffset);
      final boolean selected = isSelected;

      final Point start = editor.visualPositionToXY(new VisualPosition(startPosition.line + lineShift, indentColumn));

      final VisualPosition endPosition = editor.offsetToVisualPosition(endOffset);
      final ArrayList<OutlineLocation> childLines = descriptor.childLines;
      final Point end = editor.visualPositionToXY(endPosition);
      double splitY = -1;
      int maxY = end.y;
      boolean includeLastLine = false;
      if (endPosition.line == editor.offsetToVisualPosition(doc.getTextLength()).line) {
        includeLastLine = true;
      }

      int endLine = doc.getLineNumber(endOffset);
      if (childLines != null && childLines.size() > 0) {
        final VisualPosition endPositionLastChild = editor.offsetToVisualPosition(childLines.get(childLines.size() - 1).getOffset());
        if (endPositionLastChild.line == endPosition.line) {
          // The last child is on the same line as the end of the block.
          // This happens if code wasn't formatted with flutter style, for example:
          //  Center(
          //    child: child);

          includeLastLine = true;
          // TODO(jacobr): make sure we don't run off the edge of the document.
          if ((endLine + 1) < document.getLineCount()) {
            endLine++;
          }
        }
      }
      // By default we stop at the start of the last line instead of the end of the last line in the range.
      if (includeLastLine) {
        maxY += editor.getLineHeight();
      }

      if (clip != null) {
        if (clip.y > maxY || clip.y + clip.height < start.y) {
          return;
        }
        maxY = min(maxY, clip.y + clip.height);
      }

      final EditorColorsScheme scheme = editor.getColorsScheme();
      final JBColor lineColor = selected ? JBColor.BLUE : OUTLINE_LINE_COLOR;
      g2d.setColor(lineColor);
      final Color pastBlockColor = selected ? scheme.getColor(EditorColors.SELECTED_INDENT_GUIDE_COLOR) : OUTLINE_LINE_COLOR_PAST_BLOCK;

      // TODO(jacobr): this logic for softwraps is duplicated for the FliteredIndentsHighlightingPass
      // and may be more conservative than sensible for WidgetIndents.

      // There is a possible case that indent line intersects soft wrap-introduced text. Example:
      //     this is a long line <soft-wrap>
      // that| is soft-wrapped
      //     |
      //     | <- vertical indent
      //
      // Also it's possible that no additional intersections are added because of soft wrap:
      //     this is a long line <soft-wrap>
      //     |   that is soft-wrapped
      //     |
      //     | <- vertical indent
      // We want to use the following approach then:
      //     1. Show only active indent if it crosses soft wrap-introduced text;
      //     2. Show indent as is if it doesn't intersect with soft wrap-introduced text;

      int y = start.y;
      int newY = start.y;
      final int maxYWithChildren = y;
      final SoftWrapModel softWrapModel = editor.getSoftWrapModel();
      int iChildLine = 0;
      for (int i = max(0, startLine + lineShift); i < endLine && newY < maxY; i++) {
        OutlineLocation childLine = null;
        if (childLines != null) {
          while (iChildLine < childLines.size()) {
            final OutlineLocation currentChildLine = childLines.get(iChildLine);
            if (currentChildLine.isValid()) {
              if (currentChildLine.getLine() > i) {
                // We haven't reached child line yet.
                break;
              }
              if (currentChildLine.getLine() == i) {
                childLine = currentChildLine;
                iChildLine++;
                if (iChildLine >= childLines.size()) {
                  splitY = newY + (lineHeight * 0.5);
                }
                break;
              }
            }
            iChildLine++;
          }

          if (childLine != null) {
            final int childIndent = childLine.getIndent();
            // Draw horizontal line to the child.
            final VisualPosition widgetVisualPosition = editor.offsetToVisualPosition(childLine.getOffset());
            final Point widgetPoint = editor.visualPositionToXY(widgetVisualPosition);
            final int deltaX = widgetPoint.x - start.x;
            // We add a larger amount of panding at the end of the line if the indent is larger up until a max of 6 pixels which is the max
            // amount that looks reasonable. We could remove this and always used a fixed padding.
            final int padding = max(min(abs(deltaX) / 3, 6), 2);
            if (deltaX > 0) {
              // This is the normal case where we draw a foward line to the connected child.
              LinePainter2D.paint(
                g2d,
                start.x + 2,
                newY + lineHeight * 0.5,
                //start.x + charWidth  * childIndent - padding,
                widgetPoint.x - padding,
                newY + lineHeight * 0.5
              );
            }
            else {
              // Edge case where we draw a backwards line to clarify
              // that the node is still a child even though the line is in
              // the wrong direction. This is mainly for debugging but could help
              // users fix broken UI.
              // We draw this line so it is inbetween the lines of text so it
              // doesn't get in the way.
              final int loopBackLength = 6;

              //              int endX = start.x + charWidth  * (childIndent -1) - padding - loopBackLength;
              final int endX = widgetPoint.x - padding;
              LinePainter2D.paint(
                g2d,
                start.x + 2,
                newY,
                endX,
                newY
              );
              LinePainter2D.paint(
                g2d,
                endX,
                newY,
                endX,
                newY + lineHeight * 0.5
              );
              LinePainter2D.paint(
                g2d,
                endX,
                newY + lineHeight * 0.5,
                endX + loopBackLength,
                newY + lineHeight * 0.5
              );
            }
          }
        }

        final List<? extends SoftWrap> softWraps = softWrapModel.getSoftWrapsForLine(i);
        int logicalLineHeight = softWraps.size() * lineHeight;
        if (i > startLine + lineShift) {
          logicalLineHeight += lineHeight; // We assume that initial 'y' value points just below the target line.
        }
        if (!softWraps.isEmpty() && softWraps.get(0).getIndentInColumns() < indentColumn) {
          if (y < newY || i > startLine + lineShift) { // There is a possible case that soft wrap is located on indent start line.
            drawVerticalLineHelper(g2d, lineColor, start.x, y, newY + lineHeight, childLines,
                                   showMultipleChildrenGuides);
          }
          newY += logicalLineHeight;
          y = newY;
        }
        else {
          newY += logicalLineHeight;
        }

        final FoldRegion foldRegion = foldingModel.getCollapsedRegionAtOffset(doc.getLineEndOffset(i));
        if (foldRegion != null && foldRegion.getEndOffset() < doc.getTextLength()) {
          i = doc.getLineNumber(foldRegion.getEndOffset());
        }
      }

      if (childLines != null && iChildLine < childLines.size() && splitY == -1) {
        // Clipped rectangle is all within the main body.
        splitY = maxY;
      }
      if (y < maxY) {
        if (splitY != -1) {
          drawVerticalLineHelper(g2d, lineColor, start.x, y, splitY, childLines, showMultipleChildrenGuides);
          g2d.setColor(pastBlockColor);
          g2d.drawLine(start.x + 2, (int)splitY + 1, start.x + 2, maxY);
        }
        else {
          g2d.setColor(pastBlockColor);
          g2d.drawLine(start.x + 2, y, start.x + 2, maxY);
        }
      }
      g2d.dispose();
    }

    private ArrayList<DiagnosticsNode> getNodesToHighlight() {
      return currentHits != null && currentHits.size() > 0 ? currentHits : boxes;
    }

    @NotNull
    /**
     * Builds a transform that
     */
    private Matrix4 buildTransformToScreenshot(Screenshot latestScreenshot) {
      final Matrix4 matrix = Matrix4.identity();
      matrix.translate(lastScreenshotBounds.x, lastScreenshotBounds.y, 0);
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

    public void onVisibileAreaChanged(Rectangle oldRectangle, Rectangle newRectangle) {
      updateVisibleArea(newRectangle);
    }

    public void updateVisibleArea(Rectangle newRectangle) {
      if (descriptor.parent != null) return;

      final Point start = offsetToPoint(highlighter.getStartOffset());
      final Point end = offsetToPoint(highlighter.getEndOffset());
      final boolean nowVisible = newRectangle.y <= end.y && newRectangle.y + newRectangle.height >= start.y;
      if (visible != nowVisible) {
        visible = nowVisible;
        onVisibleChanged();
      }
    }

    InspectorService getInspectorService() {
      if (data ==null ) return null;
      return data.inspectorService;
    }

    void computeActiveElements() {
      nodes = null;
      InspectorService service = getInspectorService();
      if (service == null) return;
      if (group != null) {
        group.dispose();
        group = null;
        nodes = null;
        // XXX be smart based on if the element actually changed. The ValueId should work for this.
        //        screenshot = null;
      }
      group = service.createObjectGroup("editor");
      // XXX
      final String file = toSourceLocationUri(this.editor.getVirtualFile().getPath());
      int line =  descriptor.widget.getLine();
      int column =  descriptor.widget.getColumn();
      final RangeMarker marker = descriptor.widget.getMarker();
      if (marker.isValid()) {
        int offset = marker.getStartOffset();
        // FIXup handling of Foo.bar named constructors.
        int documentEnd = document.getTextLength();
        int constructorStart = marker.getEndOffset() -1;
        int candidateEnd = min(documentEnd, constructorStart + 1000); // XX hack.
        final String text = document.getText(new TextRange(constructorStart, candidateEnd));
        for (int i = 0; i < text.length(); ++i) {
          char c = text.charAt(i);
          if (c == '.') {
            int offsetKernel = constructorStart + i + 1;
            line = marker.getDocument().getLineNumber(offsetKernel);
            column = descriptor.widget.getColumnForOffset(offsetKernel);
            break;
          }
          if (c == '(') break;
        }
      }
      nodes = group.getElementsAtLocation(file, line + 1, column + 1, 10);
    }

    // XXX break this pipeline down and avoid fetching screenshots when not needed.
    public void onVisibleChanged() {
      if (!visible) {
        if (popup != null && !popup.isDisposed()) {
          popup.dispose();
          System.out.println("XXX kill popup");
          popup = null;
        }
      }
      if (visible && data != null && data.inspectorService != null) {
        computeScreenshotBounds();
        computeActiveElements();
        if (nodes != null) {
          group.safeWhenComplete(nodes, (matching, error) -> {
            if (error != null) {
              System.out.println("XXX error=" + error);
            }
            if (matching == null || error != null) return;

            if (!matching.isEmpty()) {
              int height = PREVIEW_HEIGHT;
              if (lastScreenshotBounds != null) {
                // Unless something went horribly wrong
                height = max(lastScreenshotBounds.height, 80); // XXX arbitrary.
              }
              root = matching.get(0);
              if (data.inspectorSelection != null) {
                group.safeWhenComplete(group.getBoundingBoxes(root, data.inspectorSelection), (boxes, selectionError) -> {
                  if (selectionError != null) {
                    this.boxes = null;
                    return;
                  }
                  this.boxes = boxes;
                  forceRender();
                });
              }
              if (screenshot == null) {
                screenshot = group.getScreenshot(root.getValueRef(), toPixels(PREVIEW_WIDTH), toPixels(height), getDPI());
                group.safeWhenComplete(screenshot, (s, e2) -> {
                  if (e2 != null) return;
                  lastScreenshot = s;
                  forceRender();
                });
              }
            }
          });
        }
      } else {
        /// XXX this is a bit aggressive. We might want to wait until the file has closed or X seconds have passed.
        if (group != null) {
          group.dispose();
        }
      }
    }

    private void forceRender() {
      // TODO(jacobr): add a version that only forces what is needed for the screenshot.
      editor.repaint(highlighter.getStartOffset(), highlighter.getEndOffset());
    }

    public void onInspectorDataChange(boolean invalidateScreenshot) {
      if (group != null) {
        group.dispose();
      }
      group = null;
      if (invalidateScreenshot) {
        screenshot = null;
      }
      nodes = null;
      // XX this might be too much.
      onVisibleChanged();
    }

    public void onInspectorAvailable() {
      onVisibleChanged();
    }

    public void onSelectionChanged() {
      onInspectorDataChange(false); // XXX a bit smarter and don't kill the screenshot.
    }

    public CompletableFuture<?> updateMouse(boolean navigateTo) {
      final Screenshot latestScreenshot = getScreenshotNow();
      if (lastScreenshotBounds == null || latestScreenshot == null || lastPoint == null || !lastScreenshotBounds.contains(lastPoint) || root == null) return CompletableFuture.completedFuture(null);
      InspectorObjectGroupManager g = getHover();
      g.cancelNext();
      final InspectorService.ObjectGroup nextGroup = g.getNext();
      final Matrix4 matrix = buildTransformToScreenshot(latestScreenshot);
      matrix.invert();
      final Vector3 point = matrix.perspectiveTransform(new Vector3(lastPoint.getX(), lastPoint.getY(), 0));
      final String file;
      final int startLine, endLine;
      if (controlDown) {
        file = null;
      } else {
        file = toSourceLocationUri(this.editor.getVirtualFile().getPath());
      }
      if (controlDown || shiftDown) {
        startLine = -1;
        endLine = -1;
      } else {
        startLine = document.getLineNumber(highlighter.getStartOffset());
        endLine = document.getLineNumber(highlighter.getEndOffset());
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
//            System.out.println("XXX hits = " + hits);
        currentHits = hits;
        forceRender();
        g.promoteNext();
        if (navigateTo && hits.size() > 0) {
          group.setSelection(hits.get(0).getValueRef(), false, false);
        }
      });
      return hitResults;
    }

    public void updateMouseCursor() {
      if (lastScreenshotBounds == null) return;
      if (lastPoint != null && lastScreenshotBounds.contains(lastPoint)) {
        // TODO(jacobr): consider CROSSHAIR_CURSOR instead which gives more of
        //  a pixel selection feel.
        editor.setCustomCursor(this, Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
      }
      else {
        editor.setCustomCursor(this, null);
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
      registerLastEvent(event);
      getMouseRateLimiter().scheduleRequest();
    }

    public void onMousePressed(MouseEvent event) {
      registerLastEvent(event);

      if (lastScreenshotBounds == null) return;
      if (lastScreenshotBounds.contains(event.getPoint())) {
        System.out.println("XXX got mouse press within area!!!");
        if (popup != null && !popup.isDisposed()) {
          popup.dispose();
        }
        updateMouse(true);
        // XXX only show popup after load of properties?
        final Point topRight = event.getLocationOnScreen();
        PropertyEditorPanel panel = new PropertyEditorPanel();
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
      updateMouseCursor();
    }

    public void _mouseOutOfScreenshot() {
      hover = getHover();
      if (hover != null) {
        hover.cancelNext();
        lastPoint = null;
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
  }

  public static class PropertyEditorPanel extends JBPanel {
    public PropertyEditorPanel() {
      super(new VerticalLayout(5));
      setBorder(JBUI.Borders.empty(5));
      add(new JBLabel("Properties"));
      add(new JBTextField("COLOR"));

    }
  }

  private final EditorEx myEditor;
  private final Document myDocument;
  private final Project myProject;
  private final VirtualFile myFile;
  private final PsiFile psiFile;
  private final FlutterDartAnalysisServer flutterDartAnalysisService;

  WidgetIndentsHighlightingPass(@NotNull Project project, @NotNull EditorEx editor, FlutterDartAnalysisServer flutterDartAnalysisService, InspectorService inspectorService) {
    this.myDocument = editor.getDocument();
    this.myEditor = editor;
    this.myProject = project;
    this.myFile = editor.getVirtualFile();
    this.flutterDartAnalysisService = flutterDartAnalysisService;
    psiFile = PsiDocumentManager.getInstance(myProject).getPsiFile(myDocument);
    final WidgetIndentsPassData data = getIndentsPassData();
    data.inspectorService = inspectorService;
    /// XXX cleanup
    setIndentsPassData(editor, data);
  }

  private static void drawVerticalLineHelper(
    Graphics2D g,
    Color lineColor,
    int x,
    double yStart,
    double yEnd,
    ArrayList<OutlineLocation> childLines,
    boolean showMultipleChildrenGuides
  ) {
    if (childLines != null && childLines.size() >= 2 && showMultipleChildrenGuides) {
      // TODO(jacobr): optimize this code a bit. This is a sloppy way to draw these lines.
      g.setStroke(SOLID_STROKE);
      g.setColor(lineColor);
      g.drawLine(x + 1, (int)yStart, x + 1, (int)yEnd + 1);
      g.drawLine(x + 2, (int)yStart, x + 2, (int)yEnd + 1);
    }
    else {
      g.setColor(lineColor);
      g.drawLine(x + 2, (int)yStart, x + 2, (int)yEnd + 1);
    }
  }

  public static int compare(@NotNull TextRangeDescriptorPair r, @NotNull RangeHighlighter h) {
    int answer = r.range.getStartOffset() - h.getStartOffset();
    if (answer != 0) {
      return answer;
    }
    answer = r.range.getEndOffset() - h.getEndOffset();
    if (answer != 0) {
      return answer;
    }
    final CustomHighlighterRenderer renderer = h.getCustomRenderer();
    if (renderer instanceof WidgetCustomHighlighterRenderer) {
      final WidgetCustomHighlighterRenderer widgetRenderer = (WidgetCustomHighlighterRenderer)renderer;
      return widgetRenderer.descriptor.compareTo(r.descriptor);
    }
    return -1;
  }

  /**
   * Indent guides are hidden if they overlap with a widget indent guide.
   */
  public static boolean isIndentGuideHidden(@NotNull Editor editor, @NotNull LineRange lineRange) {
    final WidgetIndentsPassData data = getIndentsPassData(editor);
    return data != null && isIndentGuideHidden(data.hitTester, lineRange);
  }

  public static boolean isIndentGuideHidden(WidgetIndentHitTester hitTester, @NotNull LineRange lineRange) {
    return hitTester != null && hitTester.intersects(lineRange);
  }

  public static void onCaretPositionChanged(EditorEx editor, Caret caret) {
    final WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null || data.highlighters == null) return;
    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.updateSelected(caret);
    }
  }

  public static void onInspectorDataChange(EditorEx editor, boolean force) {
    final WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null) {
      return;
    }
    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.onInspectorDataChange(force); /// XXX lazily invalidate.
    }
  }

  public static void onVisibleAreaChanged(EditorEx editor, Rectangle oldRectangle, Rectangle newRectangle) {
    WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null) {
      data = new WidgetIndentsPassData();
      setIndentsPassData(editor, data);
    }
    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.onVisibileAreaChanged(oldRectangle, newRectangle);
    }
    data.visibleRect = newRectangle;
  }

  public static void onInspectorAvaiable(EditorEx editor, InspectorService inspectorService) {
    WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null) {
      data = new WidgetIndentsPassData();
      setIndentsPassData(editor, data);
    }
    data.inspectorService = inspectorService;
    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.onInspectorAvailable();
    }
  }

  public static void onSelectionChanged(EditorEx editor, DiagnosticsNode selection) {
    WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null) {
      data = new WidgetIndentsPassData();
      setIndentsPassData(editor, data);
    }
    data.inspectorSelection = selection;
    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.onSelectionChanged();
    }
  }


  public static void onMouseMoved(EditorEx editor, MouseEvent event) {
    final WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null || data.highlighters == null) return;

    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.onMouseMoved(event);
      if (event.isConsumed()) break;
    }

  }

  public static void onMousePressed(EditorEx editor, MouseEvent event) {
    final WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null || data.highlighters == null) return;

    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.onMousePressed(event);
      if (event.isConsumed()) break;
    }
  }

  public static void onMouseEntered(EditorEx editor, MouseEvent event) {
    final WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null || data.highlighters == null) return;

    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.onMouseEntered(event);
    }
  }

  public static void onMouseExited(EditorEx editor, MouseEvent event) {
    final WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null || data.highlighters == null) return;

    for (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer : data.getRenderers()) {
      renderer.onMouseExited(event);
    }
  }

  private static WidgetIndentsPassData getIndentsPassData(Editor editor) {
    if (editor == null) return null;
    return editor.getUserData(INDENTS_PASS_DATA_KEY);
  }

  public static void disposeHighlighter(RangeHighlighter highlighter) {
    final CustomHighlighterRenderer renderer = highlighter.getCustomRenderer();
    if (renderer instanceof WidgetCustomHighlighterRenderer) {
      ((WidgetCustomHighlighterRenderer)renderer).dispose();
    }
    highlighter.dispose();
  }

  public static void cleanupHighlighters(Editor editor) {
    final WidgetIndentsPassData data = getIndentsPassData(editor);
    if (data == null) return;

    List<RangeHighlighter> oldHighlighters = data.highlighters;
    if (oldHighlighters != null) {
      for (RangeHighlighter highlighter : oldHighlighters) {
        disposeHighlighter(highlighter);
      }
    }

    /*
     oldHighlighters = data.propertyHighlighters;
    if (oldHighlighters != null) {
      for (RangeHighlighter highlighter : oldHighlighters) {
        disposeHighlighter(highlighter);
      }
    }

     */
    setIndentsPassData(editor, null);
  }

  public static void run(@NotNull Project project,
                         @NotNull EditorEx editor,
                         @NotNull FlutterOutline outline,
                         FlutterDartAnalysisServer flutterDartAnalysisService,
                         @Nullable InspectorService inspectorService
                         ) {
    final WidgetIndentsHighlightingPass widgetIndentsHighlightingPass = new WidgetIndentsHighlightingPass(project, editor, flutterDartAnalysisService, inspectorService);
    widgetIndentsHighlightingPass.setOutline(outline);
  }

  /**
   * This method must be called on the main UI thread.
   * <p>
   * Some of this logic would appear to be safe to call on a background thread but
   * there are race conditions where the data will be out of order if the document
   * is being edited while the code is executing.
   * <p>
   * If there are performance concerns we can work to perform more of this
   * computation on a separate thread.
   */
  public void setOutline(FlutterOutline outline) {
    assert (outline != null);

    final WidgetIndentsPassData data = getIndentsPassData();
    if (data.outline == outline) {
      // The outline has not changed. There is nothing we need to do.
      return;
    }

    final ArrayList<WidgetIndentGuideDescriptor> descriptors = new ArrayList<>();

    buildWidgetDescriptors(descriptors, outline, null);
    for (int i = 0; i< descriptors.size() - 1; i++) {
      descriptors.get(i).nextSibling = descriptors.get(i+1);
    }
    updateHitTester(new WidgetIndentHitTester(descriptors, myDocument), data);
    // TODO(jacobr): we need to trigger a rerender of highlighters that will render differently due to the changes in highlighters?
    data.myDescriptors = descriptors;
    doCollectInformationUpdateOutline(data);
    doApplyIndentInformationToEditor(data);
    // XXXdoApplyPropertyInformationToEditor(data);
    setIndentsPassData(data);
  }

  private void updateHitTester(WidgetIndentHitTester hitTester, WidgetIndentsPassData data) {
    if (Objects.equals(data.hitTester, hitTester)) {
      return;
    }
    FliteredIndentsHighlightingPass.onWidgetIndentsChanged(myEditor, data.hitTester, hitTester);
    data.hitTester = hitTester;
  }

  private WidgetIndentsPassData getIndentsPassData() {
    WidgetIndentsPassData data = getIndentsPassData(myEditor);
    if (data == null) {
      data = new WidgetIndentsPassData();
    }
    return data;
  }

  static void setIndentsPassData(Editor editor, WidgetIndentsPassData data) {
    editor.putUserData(INDENTS_PASS_DATA_KEY, data);
  }

  void setIndentsPassData(WidgetIndentsPassData data) {
    setIndentsPassData(myEditor, data);
  }

  public void doCollectInformationUpdateOutline(WidgetIndentsPassData data) {
    assert myDocument != null;

    if (data.myDescriptors != null) {
      final ArrayList<TextRangeDescriptorPair> ranges = new ArrayList<>();
      for (WidgetIndentGuideDescriptor descriptor : data.myDescriptors) {
        ProgressManager.checkCanceled();
        final TextRange range;
        if (descriptor.widget != null) {
          range = descriptor.widget.getTextRange();
        }
        else {
          final int endOffset =
            descriptor.endLine < myDocument.getLineCount() ? myDocument.getLineStartOffset(descriptor.endLine) : myDocument.getTextLength();
          range = new TextRange(myDocument.getLineStartOffset(descriptor.startLine), endOffset);
        }
        ranges.add(new TextRangeDescriptorPair(range, descriptor));
      }
      ranges.sort((a, b) -> Segment.BY_START_OFFSET_THEN_END_OFFSET.compare(a.range, b.range));
      data.myRangesWidgets = ranges;
    }
  }

  public void doApplyIndentInformationToEditor(WidgetIndentsPassData data) {
    final MarkupModel mm = myEditor.getMarkupModel();

    final List<RangeHighlighter> oldHighlighters = data.highlighters;
    final List<RangeHighlighter> newHighlighters = new ArrayList<>();

    int curRange = 0;

    final List<TextRangeDescriptorPair> ranges = data.myRangesWidgets;
    if (oldHighlighters != null) {
      // after document change some range highlighters could have become
      // invalid, or the order could have been broken.
      // This is similar to logic in FliteredIndentsHighlightingPass.java that also attempts to
      // only update highlighters that have actually changed.
      oldHighlighters.sort(Comparator.comparing((RangeHighlighter h) -> !h.isValid())
                             .thenComparing(Segment.BY_START_OFFSET_THEN_END_OFFSET));
      int curHighlight = 0;
      // It is fine if we cleanupHighlighters and update some old highlighters that are
      // still valid but it is not ok if we leave even one highlighter that
      // really changed as that will cause rendering artifacts.
      while (curRange < ranges.size() && curHighlight < oldHighlighters.size()) {
        final TextRangeDescriptorPair entry = ranges.get(curRange);
        final RangeHighlighter highlighter = oldHighlighters.get(curHighlight);

        if (!highlighter.isValid()) break;

        final int cmp = compare(entry, highlighter);
        if (cmp < 0) {
          newHighlighters.add(createHighlighter(mm, entry, data));
          curRange++;
        }
        else if (cmp > 0) {
          disposeHighlighter(highlighter);
          curHighlight++;
        }
        else {
          newHighlighters.add(highlighter);
          curHighlight++;
          curRange++;
        }
      }

      for (; curHighlight < oldHighlighters.size(); curHighlight++) {
        final RangeHighlighter highlighter = oldHighlighters.get(curHighlight);
        if (!highlighter.isValid()) break;
        disposeHighlighter(highlighter);
      }
    }


    final int startRangeIndex = curRange;
    assert myDocument != null;
    DocumentUtil.executeInBulk(myDocument, ranges.size() > 10000, () -> {
      for (int i = startRangeIndex; i < ranges.size(); i++) {
        newHighlighters.add(createHighlighter(mm, ranges.get(i), data));
      }
    });

    data.highlighters = newHighlighters;
  }

  public void doApplyPropertyInformationToEditor(WidgetIndentsPassData data) {
    /*
    final MarkupModel mm = myEditor.getMarkupModel();

    final List<RangeHighlighter> oldHighlighters = data.propertyHighlighters;
    final List<RangeHighlighter> newHighlighters = new ArrayList<>();

    int curRange = 0;

    final List<TextRangeDescriptorPair> ranges = data.myRangesWidgets;
    if (oldHighlighters != null) {
      // after document change some range highlighters could have become
      // invalid, or the order could have been broken.
      // This is similar to logic in FliteredIndentsHighlightingPass.java that also attempts to
      // only update highlighters that have actually changed.
      oldHighlighters.sort(Comparator.comparing((RangeHighlighter h) -> !h.isValid())
                             .thenComparing(Segment.BY_START_OFFSET_THEN_END_OFFSET));
      int curHighlight = 0;
      // It is fine if we cleanupHighlighters and update some old highlighters that are
      // still valid but it is not ok if we leave even one highlighter that
      // really changed as that will cause rendering artifacts.
      while (curRange < ranges.size() && curHighlight < oldHighlighters.size()) {
        final TextRangeDescriptorPair entry = ranges.get(curRange);
        final RangeHighlighter highlighter = oldHighlighters.get(curHighlight);

        if (!highlighter.isValid()) break;

        final int cmp = compare(entry, highlighter);
        if (cmp < 0) {
          newHighlighters.add(createHighlighter(mm, entry));
          curRange++;
        }
        else if (cmp > 0) {
          disposeHighlighter(highlighter);
          curHighlight++;
        }
        else {
          newHighlighters.add(highlighter);
          curHighlight++;
          curRange++;
        }
      }

      for (; curHighlight < oldHighlighters.size(); curHighlight++) {
        final RangeHighlighter highlighter = oldHighlighters.get(curHighlight);
        if (!highlighter.isValid()) break;
        disposeHighlighter(highlighter);
      }
    }


    final int startRangeIndex = curRange;
    assert myDocument != null;
    DocumentUtil.executeInBulk(myDocument, ranges.size() > 10000, () -> {
      for (int i = startRangeIndex; i < ranges.size(); i++) {
        newHighlighters.add(createHighlighter(mm, ranges.get(i)));
      }
    });

    data.highlighters = newHighlighters;

     */
  }

  DartAnalysisServerService getAnalysisService() {
    // TODO(jacobr): cache this?
    return DartAnalysisServerService.getInstance(myProject);
  }

  private OutlineLocation computeLocation(FlutterOutline node) {
    assert (myDocument != null);
    final int documentLength = myDocument.getTextLength();
    final int rawOffset = getAnalysisService().getConvertedOffset(myFile, node.getOffset());
    final int nodeOffset = min(rawOffset, documentLength);
    final int line = myDocument.getLineNumber(nodeOffset);
    final int lineStartOffset = myDocument.getLineStartOffset(line);

    final int column = nodeOffset - lineStartOffset;
    int indent = 0;
    final CharSequence chars = myDocument.getCharsSequence();

    // TODO(jacobr): we only really want to include the previous token (e.g.
    // "child: " instead of the entire line). That won't matter much but could
    // lead to slightly better results on code edits.
    for (indent = 0; indent < column; indent++) {
      if (!Character.isWhitespace(chars.charAt(lineStartOffset + indent))) {
        break;
      }
    }

    return new OutlineLocation(node, line, column, indent, myFile, getAnalysisService());
  }

  DartCallExpression getCallExpression(PsiElement element) {
    if (element == null) { return null; }
    if (element instanceof DartCallExpression) {
      return (DartCallExpression) element;
    }

    return getCallExpression(element.getParent());
  }
  private void buildWidgetDescriptors(
    final List<WidgetIndentGuideDescriptor> widgetDescriptors,
    FlutterOutline outlineNode,
    WidgetIndentGuideDescriptor parent
  ) {
    if (outlineNode == null) return;

    final String kind = outlineNode.getKind();
    final boolean widgetConstructor = "NEW_INSTANCE".equals(kind) || (parent != null && ("VARIABLE".equals(kind)));

    final List<FlutterOutline> children = outlineNode.getChildren();
//    if (children == null || children.isEmpty()) return;

    if (widgetConstructor) {
      final OutlineLocation location = computeLocation(outlineNode);
      int minChildIndent = Integer.MAX_VALUE;
      final ArrayList<OutlineLocation> childrenLocations = new ArrayList<>();
      int endLine = location.getLine();

      if (children != null) {
        for (FlutterOutline child : children) {
          final OutlineLocation childLocation = computeLocation(child);
          if (childLocation.getLine() <= location.getLine()) {
            // Skip children that don't actually occur on a later line. There is no
            // way for us to draw good looking line art for them.
            // TODO(jacobr): consider adding these children anyway so we can render
            // them if there are edits and they are now properly formatted.
            continue;
          }

          minChildIndent = min(minChildIndent, childLocation.getIndent());
          endLine = max(endLine, childLocation.getLine());
          childrenLocations.add(childLocation);
        }
      }
      final Set<Integer> childrenOffsets = new HashSet<Integer>();
      for (OutlineLocation childLocation : childrenLocations) {
        childrenOffsets.add(childLocation.getOffset());
      }

      final PsiElement element=  psiFile.findElementAt(location.getOffset());
      final ArrayList<WidgetIndentGuideDescriptor.WidgetPropertyDescriptor> trustedAttributes = new ArrayList<>();
      final List<FlutterOutlineAttribute> attributes = outlineNode.getAttributes();
      if (attributes != null) {
        for (FlutterOutlineAttribute attribute : attributes) {
          trustedAttributes.add(new WidgetIndentGuideDescriptor.WidgetPropertyDescriptor(attribute));
        }
      }

      // XXX if (!childrenLocations.isEmpty())
      {
        // The indent is only used for sorting and disambiguating descriptors
        // as at render time we will pick the real indent for the outline based
        // on local edits that may have been made since the outline was computed.
        final int lineIndent = location.getIndent();
        final WidgetIndentGuideDescriptor descriptor = new WidgetIndentGuideDescriptor(
          parent,
          lineIndent,
          location.getLine(),
          endLine + 1,
          childrenLocations,
          location,
          trustedAttributes
        );
        // if (!descriptor.childLines.isEmpty())
        {
          widgetDescriptors.add(descriptor);
          parent = descriptor;
        }
      }
    }
    if (children != null) {
      for (FlutterOutline child : children) {
        buildWidgetDescriptors(widgetDescriptors, child, parent);
      }
    }
  }

  @NotNull
  private RangeHighlighter createHighlighter(MarkupModel mm, TextRangeDescriptorPair entry, WidgetIndentsPassData data) {
    final TextRange range = entry.range;
    final FlutterSettings settings = FlutterSettings.getInstance();
    if (range.getEndOffset() >= myDocument.getTextLength() && DEBUG_WIDGET_INDENTS) {
      LOG.info("Warning: highlighter extends past the end of document.");
    }
    final RangeHighlighter highlighter =
      mm.addRangeHighlighter(
        Math.max(range.getStartOffset(), 0),
        Math.min(range.getEndOffset(), myDocument.getTextLength()),
        HighlighterLayer.FIRST,
        null,
        HighlighterTargetArea.EXACT_RANGE
      );
    if (entry.descriptor.parent == null && settings.isShowBuildMethodsOnScrollbar()) {
      highlighter.setErrorStripeMarkColor(BUILD_METHOD_STRIPE_COLOR);
      highlighter.setErrorStripeTooltip("Flutter build method");
      highlighter.setThinErrorStripeMark(true);
    }
    highlighter.setCustomRenderer(new WidgetCustomHighlighterRenderer(entry.descriptor, myDocument, data, flutterDartAnalysisService, myEditor, highlighter));
    return highlighter;
  }

  /*
  @NotNull
  private RangeHighlighter createPropertyHighlighter(MarkupModel mm, TextRangeDescriptorPair entry) {
    final TextRange range = entry.range;
    final FlutterSettings settings = FlutterSettings.getInstance();
    if (range.getEndOffset() >= myDocument.getTextLength() && DEBUG_WIDGET_INDENTS) {
      LOG.info("Warning: highlighter extends past the end of document.");
    }
    final RangeHighlighter highlighter =
      mm.addRangeHighlighter(
        Math.max(range.getStartOffset(), 0),
        Math.min(range.getEndOffset(), myDocument.getTextLength()),
        HighlighterLayer.FIRST,
        null,
        HighlighterTargetArea.EXACT_RANGE
      );
    highlighter.setCustomRenderer(new PropertyValueRenderer(entry.descriptor, myDocument));
    return highlighter;
  }

   */
}

/**
 * Data describing widget indents for an editor that is persisted across
 * multiple runs of the WidgetIndentsHighlightingPass.
 */
class WidgetIndentsPassData {
  public Rectangle visibleRect;
  public InspectorService inspectorService;
  public DiagnosticsNode inspectorSelection;
  /**
   * Descriptors describing the data model to render the widget indents.
   * <p>
   * This data is computed from the FlutterOutline and contains additional
   * information to manage how the locations need to be updated to reflect
   * edits to the documents.
   */
  List<WidgetIndentGuideDescriptor> myDescriptors = Collections.emptyList();

  /**
   * Descriptors combined with their current locations in the possibly modified document.
   */
  List<TextRangeDescriptorPair> myRangesWidgets = Collections.emptyList();

  /**
   * Highlighters that perform the actual rendering of the widget indent
   * guides.
   */
  List<RangeHighlighter> highlighters;

  /// XXX remove
  // List<RangeHighlighter> propertyHighlighters;

  /**
   * Source of truth for whether other UI overlaps with the widget indents.
   */
  WidgetIndentHitTester hitTester;

  /**
   * Outline the widget indents are based on.
   */
  FlutterOutline outline;

  Iterable<WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer> getRenderers() {
    final ArrayList<WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer> renderers = new ArrayList<>();
    if (highlighters != null) {
      for (RangeHighlighter h : highlighters) {
        if (h.getCustomRenderer() instanceof WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer) {
          final WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer renderer =
            (WidgetIndentsHighlightingPass.WidgetCustomHighlighterRenderer)h.getCustomRenderer();
          renderers.add(renderer);
        }
      }
    }
    return renderers;
  }
}

class TextRangeDescriptorPair {
  final TextRange range;
  final WidgetIndentGuideDescriptor descriptor;

  TextRangeDescriptorPair(TextRange range, WidgetIndentGuideDescriptor descriptor) {
    this.range = range;
    this.descriptor = descriptor;
  }
}