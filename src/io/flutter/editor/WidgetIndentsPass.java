// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * @author max
 */
package io.flutter.editor;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeInsight.highlighting.BraceMatcher;
import com.intellij.codeInsight.highlighting.BraceMatchingUtil;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.lang.ParserDefinition;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.paint.LinePainter2D;
import com.intellij.util.DocumentUtil;
import com.intellij.util.containers.ContainerUtilRt;
import com.intellij.util.containers.Stack;
import com.intellij.util.text.CharArrayUtil;
import com.jetbrains.lang.dart.psi.DartParameterNameReferenceExpression;
import io.flutter.settings.FlutterSettings;
import org.dartlang.analysis.server.protocol.FlutterOutline;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.*;
import java.util.List;

import static java.lang.Math.*;

class BuildMethodLineMarkerRenderer implements LineMarkerRenderer {
  @Override
  public void paint(Editor editor, Graphics graphics, Rectangle rectangle) {
    graphics.setColor(WidgetIndentsPass.BUILD_METHOD_STRIPE_COLOR);
    graphics.fillRect(rectangle.x, rectangle.y, min(2, rectangle.width), rectangle.height);
  }
}

class WidgetIndentSpansFull implements WidgetIndentSpans {
  int[] start;
  int[] end;

  WidgetIndentSpansFull(List<WidgetIndentGuideDescriptor> descriptors, Document document) {
    final int lineCount = document.getLineCount();
    start = new int[lineCount];
    end = new int[lineCount];
    Arrays.fill(start, Integer.MAX_VALUE);
    Arrays.fill(end, Integer.MIN_VALUE);
    // TODO(jacobr): optimize using a more clever data structure.
    for (WidgetIndentGuideDescriptor descriptor : descriptors) {
      final int minIndent = descriptor.indentLevel;
      final int maxIndent = descriptor.getMaxX();
      final int last = min(start.length, descriptor.endLine);
      for (int i = descriptor.startLine; i < descriptor.endLine; i++) {
        start[i] = min(start[i], minIndent);
        end[i] = max(end[i], maxIndent);
      }
    }
  }

  @Override
  public boolean intersects(WidgetIndentGuideDescriptor descriptor) {
    final int indent = descriptor.indentLevel;
    final int last = min(end.length, descriptor.endLine);
    for (int i = descriptor.startLine + 1; i < last; i++) {
      if (indent >= start[i] && indent <= end[i]) {
        return true;
      }
    }
    return false;
  }
}

class WidgetIndentSpansFast implements WidgetIndentSpans {
  private final boolean[] matches;

  WidgetIndentSpansFast(List<WidgetIndentGuideDescriptor> descriptors, Document document) {
    final int lineCount = document.getLineCount();
    matches = new boolean[lineCount];
    // TODO(jacobr): optimize using a more clever data structure.

    // TODO(jacobr): This is
    for (WidgetIndentGuideDescriptor descriptor : descriptors) {
     // if (descriptor.parent)
      {
        final int last = min(matches.length, descriptor.endLine + 1);
        for (int i = max(descriptor.startLine - 1, 0); i < last; i++) {
          matches[i] = true;
        }
      }
    }
  }

  @Override
  public boolean intersects(WidgetIndentGuideDescriptor descriptor) {
    final int indent = descriptor.indentLevel;
    final int last = min(matches.length, descriptor.endLine + 1);
    for (int i = max(descriptor.startLine - 1, 0); i < last; i++) {
      if (matches[i]) {
        return true;
      }
    }
    return false;
  }
}

class WidgetIndentSpansSimple implements WidgetIndentSpans {
  private final int[] start;
  private final int[] end;

  WidgetIndentSpansSimple(List<WidgetIndentGuideDescriptor> descriptors, Document document) {
    final int lineCount = document.getLineCount();
    start = new int[lineCount];
    end = new int[lineCount];
    Arrays.fill(start, Integer.MAX_VALUE);
    Arrays.fill(end, Integer.MIN_VALUE);
    // TODO(jacobr): optimize using a more clever data structure.
    for (WidgetIndentGuideDescriptor descriptor : descriptors) {
      final int minIndent = descriptor.indentLevel;
      final int maxIndent = descriptor.getMaxX();
      final int last = min(start.length, descriptor.endLine + 1);

      for (int i = descriptor.startLine; i < last; i++) {
        start[i] = min(start[i], minIndent);
        end[i] = max(end[i], maxIndent);
      }
    }
  }

  @Override
  public boolean intersects(WidgetIndentGuideDescriptor descriptor) {
    final int indent = descriptor.indentLevel;
    final int last = min(start.length, descriptor.endLine + 1);
    for (int i = descriptor.startLine; i < last; i++) {
      if (indent >= start[i] && indent <= end[i]) {
        return true;
      }
    }
    return false;
  }
}

/**
 * Data for WidgetIndents that is persisted across multiple document versions.
 */
class WidgetIndentsPassData {
  // XXX doubt these volatile keywords are helping anything.
  volatile List<WidgetIndentsPass.TextRangeDescriptorPair> myRangesWidgets = Collections.emptyList();
  volatile List<WidgetIndentsPass.TextRangeDescriptorPair> myRangesSimple = Collections.emptyList();

  volatile List<WidgetIndentGuideDescriptor> myDescriptors = Collections.emptyList();
  volatile List<WidgetIndentGuideDescriptor> mySimpleDescriptors = Collections.emptyList();
  volatile List<StyledTextRange> myChildParameters = Collections.emptyList();
  volatile List<StyledTextRange> myWidgetConstructors = Collections.emptyList();
  volatile List<StyledTextRange> myStyledText = Collections.emptyList();
  volatile WidgetIndentSpans indentSpans = null;
}

public class WidgetIndentsPass extends TextEditorHighlightingPass implements DumbAware {

  final static LineMarkerRenderer lineMarkerRenderer = new BuildMethodLineMarkerRenderer();
  final static TextAttributes childParameterTextAttributes;
  final static TextAttributes widgetConstructorTextAttributes;
  final static Stroke dashed = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{3.0f}, 0);
  final static Stroke solid = new BasicStroke(1);
  final static JBColor veryLightGray = new JBColor(Gray._224, Gray._80);
  final static JBColor shadowGray = new JBColor(Gray._192, Gray._100);
  final static JBColor outlineLineColor = new JBColor(Gray._128, Gray._128);
  final static JBColor outlineShadow1 = new JBColor(new Color(128, 128, 128, 80), new Color(128,128,128,160));
  final static JBColor outlineShadow2 = new JBColor(new Color(128, 128, 128, 40), new Color(128,128,128,80));

  final static Color outlineLineColorPastBlock = new Color(128,128,128, 65);
  final static JBColor childParameterColor = new JBColor(Gray._192, Gray._102);
  final static JBColor BUILD_METHOD_STRIPE_COLOR = new JBColor(new Color(0xc0d8f0), new Color(0xffc66d));
  private static final Key<List<RangeHighlighter>>
    WIDGET_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY = Key.create("WIDGET_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY");
  private static final Key<List<RangeHighlighter>>
    SIMPLE_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY = Key.create("SIMPLE_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY");
  private static final Key<List<RangeHighlighter>>
    PARAMETER_NAME_HIGHLIGHTERS_IN_EDITOR_KEY = Key.create("PARAMETER_NAME_HIGHLIGHTERS_IN_EDITOR_KEY");
  private static final Key<WidgetIndentsPassData> INDENTS_PASS_DATA_KEY = Key.create("INDENTS_PASS_DATA_KEY");
  private static final Key<Long> LAST_TIME_WIDGET_INDENTS_BUILT = Key.create("LAST_TIME_WIDGET_INDENTS_BUILT");
  private static final Key<Long> LAST_TIME_SIMPLE_INDENTS_BUILT = Key.create("LAST_TIME_SIMPLE_INDENTS_BUILT");

  public static class TextRangeDescriptorPair {
    final TextRange range;
    final WidgetIndentGuideDescriptor descriptor;
    TextRangeDescriptorPair(TextRange range, WidgetIndentGuideDescriptor descriptor) {
      this.range = range;
      this.descriptor = descriptor;
    }
  }

  private static class IndentEntry {
    int line;
    int indent;
    List<OutlineLocation> widgetLines;
    FlutterOutline widget;
    public IndentEntry(int line, int indent, FlutterOutline widget) {
      this.line = line;
      this.indent = indent;
      this.widget = widget;
      widgetLines = new ArrayList<>();
    }
  }

  private class WidgetCustomHighlighterRenderer implements CustomHighlighterRenderer {
    /**
     * Whether to for consistency show lines connecting widgets ot the parents
     * even when the line has to go backwards due to improper indentation.
     */
    private static final boolean SHOW_BACKWARDS_LINES = true;

    private final WidgetIndentGuideDescriptor descriptor;

    WidgetCustomHighlighterRenderer(WidgetIndentGuideDescriptor descriptor) {
      this.descriptor = descriptor;
      descriptor.trackLocations(myDocument);
    }

    void dispose() {
      descriptor.dispose();
    }

    @Override
    public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
      final FlutterSettings settings = FlutterSettings.getInstance();
      final boolean showMultipleChildrenGuides = settings.isShowMultipleChildrenGuides();
      final boolean showDashedLinesGuides = settings.isShowDashedLineGuides();

      if (!highlighter.isValid()) {
        return;
      }
      final Graphics2D g2d = (Graphics2D)g.create();
      // TODO(jacobr()
      g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1));

      final int startOffset = highlighter.getStartOffset();
      final Document doc = highlighter.getDocument();
      if (startOffset >= doc.getTextLength()) return;

      final int endOffset = highlighter.getEndOffset();

      int off;
      int startLine = doc.getLineNumber(startOffset);

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

      final boolean selected;
      final IndentGuideDescriptor guide = editor.getIndentsModel().getCaretIndentGuide();
      if (guide != null) {
        final CaretModel caretModel = editor.getCaretModel();
        final int caretOffset = caretModel.getOffset();
        selected =
          caretOffset >= off && caretOffset < endOffset && caretModel.getLogicalPosition().column == indentColumn;
      }
      else {
        selected = false;
      }

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
        final VisualPosition endPositionLastChild = editor.offsetToVisualPosition(childLines.get(childLines.size() - 1).getCurrentOffset());
        if (endPositionLastChild.line == endPosition.line) {
          // The last child is on the same line as the end of the block.
          // This happens if code wasn't formatted with flutter style, for example:
          //  Center(
          //    child: child);

          includeLastLine = true;
          endLine++; // TODO(jacobr): make sure we don't run off the edge of the document.
        }
      }
      // By default we stop at the start of the last line instead of the end of the last line in the range.
      if (includeLastLine) {
        maxY += editor.getLineHeight();
      }

      final Rectangle clip = g2d.getClipBounds();
      if (clip != null) {
        if (clip.y > maxY || clip.y + clip.height < start.y) {
          return;
        }
        maxY = min(maxY, clip.y + clip.height);
      }

      final EditorColorsScheme scheme = editor.getColorsScheme();
      final JBColor lineColor = selected ? JBColor.BLUE : outlineLineColor;
      g2d.setColor(lineColor);
      Color pastBlockColor = selected ? scheme.getColor(EditorColors.SELECTED_INDENT_GUIDE_COLOR) : outlineLineColorPastBlock;

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

/*
      if (selected) {
        // TODO(jacobr): we now have duplicate line drawing code
        LinePainter2D.paint(g2d, start.x + 2, start.y, start.x + 2, maxY - 1);
      }*/


      // XXX optimize and use clipping correctly.
      // else
      {
        int y = start.y;
        int newY = start.y;
        final int maxYWithChildren = y;
        final SoftWrapModel softWrapModel = editor.getSoftWrapModel();
        final int lineHeight = editor.getLineHeight();
        int iChildLine = 0;
        for (int i = max(0, startLine + lineShift); i < endLine && newY < maxY; i++) {
          OutlineLocation childLine = null;
          if (childLines != null) {
            while (iChildLine < childLines.size()) {
              final OutlineLocation currentChildLine = childLines.get(iChildLine);
              if (currentChildLine.isValid()) {
                if (currentChildLine.getCurrentLine() > i) {
                  // We haven't reached child line yet.
                  break;
                }
                if (currentChildLine.getCurrentLine() == i) {
                  childLine = currentChildLine;
                  iChildLine++;
                  if (iChildLine >= childLines.size()) {
                    splitY = newY + (lineHeight * 0.5);
                  }
                  break;
                }
              }/*
              else {
                System.out.println("Caught invalid child!");
              }*/
              iChildLine++;
            }

            if (childLine != null) {
              final int childIndent = childLine.getCurrentIndent();
              // Draw horizontal line to the child.
              final VisualPosition widgetVisualPosition = editor.offsetToVisualPosition(childLine.getCurrentOffset());
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
              else if (SHOW_BACKWARDS_LINES) {
                // Experimental edge case where we draw a backwards line to clarify
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
              drawVerticalLineHelper(g2d, lineColor, start.x, y, newY + lineHeight, childLines, showDashedLinesGuides,
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
            drawVerticalLineHelper(g2d, lineColor, start.x, y, splitY, childLines, showDashedLinesGuides, showMultipleChildrenGuides);
            g2d.setColor(pastBlockColor);
            g2d.drawLine(start.x + 2, (int)splitY + 1, start.x + 2, maxY);
          }
          else {
            g2d.setColor(pastBlockColor);
            g2d.drawLine(start.x + 2, y, start.x + 2, maxY);
          }
        }
      }
      g2d.dispose();
    }

    private void drawVerticalLineHelper(
      Graphics2D g,
      Color lineColor,
      int x,
      double yStart,
      double yEnd,
      ArrayList<OutlineLocation> childLines,
      boolean showDashedLinesGuides,
      boolean showMultipleChildrenGuides
    ) {
      if (childLines != null && childLines.size() >= 2 && showMultipleChildrenGuides) {
        if (showDashedLinesGuides) {
          g.setStroke(dashed);
          g.drawLine((int)x, (int)yStart, x, (int)yEnd+1);
          g.setStroke(solid);
        }
        else {
          g.setStroke(new BasicStroke(1));
          g.setColor(outlineShadow2);
          g.drawLine(x, (int)yStart, x, (int)yEnd+1);
          g.setColor(outlineShadow1);
          g.drawLine(x + 1, (int)yStart, x + 1, (int)yEnd+1);
        }
      }
      g.setColor(lineColor);
      g.drawLine(x + 2, (int)yStart, x + 2, (int)yEnd+1);

    }
  }

  // Forked from IndentsPass.java
  private class IndentsCalculator {
    @NotNull final Map<Language, TokenSet> myComments = ContainerUtilRt.newHashMap();
    @NotNull final int[] lineIndents; // negative value means the line is empty (or contains a comment) and indent
    // (denoted by absolute value) was deduced from enclosing non-empty lines
    @NotNull final CharSequence myChars;

    IndentsCalculator() {
      assert myDocument != null;
      lineIndents = new int[myDocument.getLineCount()];
      myChars = myDocument.getCharsSequence();
    }

    /**
     * Calculates line indents for the {@link #myDocument target document}.
     */
    void calculate() {
      assert myDocument != null;
      final FileType fileType = myFile.getFileType();
      final int tabSize = EditorUtil.getTabSize(myEditor);

      for (int line = 0; line < lineIndents.length; line++) {
        ProgressManager.checkCanceled();
        final int lineStart = myDocument.getLineStartOffset(line);
        final int lineEnd = myDocument.getLineEndOffset(line);
        int offset = lineStart;
        int column = 0;
        outer:
        while (offset < lineEnd) {
          switch (myChars.charAt(offset)) {
            case ' ':
              column++;
              break;
            case '\t':
              column = (column / tabSize + 1) * tabSize;
              break;
            default:
              break outer;
          }
          offset++;
        }
        // treating commented lines in the same way as empty lines
        // Blank line marker
        lineIndents[line] = offset == lineEnd || isComment(offset) ? -1 : column;
      }

      int topIndent = 0;
      for (int line = 0; line < lineIndents.length; line++) {
        ProgressManager.checkCanceled();
        if (lineIndents[line] >= 0) {
          topIndent = lineIndents[line];
        }
        else {
          int startLine = line;
          while (line < lineIndents.length && lineIndents[line] < 0) {
            //noinspection AssignmentToForLoopParameter
            line++;
          }

          int bottomIndent = line < lineIndents.length ? lineIndents[line] : topIndent;

          int indent = Math.min(topIndent, bottomIndent);
          if (bottomIndent < topIndent) {
            final int lineStart = myDocument.getLineStartOffset(line);
            final int lineEnd = myDocument.getLineEndOffset(line);
            final int nonWhitespaceOffset = CharArrayUtil.shiftForward(myChars, lineStart, lineEnd, " \t");
            HighlighterIterator iterator = myEditor.getHighlighter().createIterator(nonWhitespaceOffset);
            if (BraceMatchingUtil.isRBraceToken(iterator, myChars, fileType)) {
              indent = topIndent;
            }
          }

          for (int blankLine = startLine; blankLine < line; blankLine++) {
            assert lineIndents[blankLine] == -1;
            lineIndents[blankLine] = -Math.min(topIndent, indent);
          }

          //noinspection AssignmentToForLoopParameter
          line--; // will be incremented back at the end of the loop;
        }
      }
    }

    private boolean isComment(int offset) {
      final HighlighterIterator it = myEditor.getHighlighter().createIterator(offset);
      IElementType tokenType = it.getTokenType();
      Language language = tokenType.getLanguage();
      TokenSet comments = myComments.get(language);
      if (comments == null) {
        ParserDefinition definition = LanguageParserDefinitions.INSTANCE.forLanguage(language);
        if (definition != null) {
          comments = definition.getCommentTokens();
        }
        if (comments == null) {
          return false;
        }
        else {
          myComments.put(language, comments);
        }
      }
      return comments.contains(tokenType);
    }
  }

  static {
    childParameterTextAttributes = new TextAttributes();
    childParameterTextAttributes.setForegroundColor(childParameterColor);
    childParameterTextAttributes.setFontType(1); // Italic. TODO(jacobr): find an enum entry that indicates this is italic.
    widgetConstructorTextAttributes = new TextAttributes();
    widgetConstructorTextAttributes.setForegroundColor(new JBColor(
      new Color(0x76b4f0),
      new Color(0x76b4f0) // TODO(jacobr): figure out a good dark color.
    ));
  }

  private final EditorEx myEditor;
  private final PsiFile myFile;
  private volatile IndentsCalculator calculator;

  WidgetIndentsPass(@NotNull Project project, @NotNull EditorEx editor, @NotNull PsiFile file) {
    super(project, editor.getDocument(), false);
    myEditor = (EditorEx)editor;
    myFile = file;
  }

  private static int compare(@NotNull TextRangeDescriptorPair r, @NotNull RangeHighlighter h) {
    int answer = r.range.getStartOffset() - h.getStartOffset();
    if (answer != 0) {
      return answer;
    }
    answer = r.range.getEndOffset() - h.getEndOffset();
    if (answer != 0) {
      return answer;
    }
    CustomHighlighterRenderer renderer = h.getCustomRenderer();
    if (renderer instanceof WidgetCustomHighlighterRenderer) {
      WidgetCustomHighlighterRenderer widgetRenderer = (WidgetCustomHighlighterRenderer)renderer;
      return widgetRenderer.descriptor.compareTo(r.descriptor);
    }
    assert (false);
    return 0;
  }

  // Comparator for Parameter name highlighters.
  private static int compare(@NotNull StyledTextRange r, @NotNull RangeHighlighter h) {
    int answer = r.range.getStartOffset() - h.getStartOffset();
    if (answer != 0) {
      return answer;
    }
    answer = r.range.getEndOffset() - h.getEndOffset();
    if (answer != 0) {
      return answer;
    }
    // We reley on identical TextAttributes.
    if (r.attributes == h.getTextAttributes()) {
      return 0;
    }
    if (h.getTextAttributes() == null) return -1;
    answer = r.attributes.hashCode() - h.getTextAttributes().hashCode();
    assert (answer != 0);
    return answer;
  }

  public EditorEx getEditor() {
    return myEditor;
  }

  /**
   * This method must be called on the main UI thread.
   * <p>
   * Some of this logic would appear to be safe to call on a background thread but
   * there are race conditions where the data will be out of order if the document
   * is being edited while the code is executing. This it is much safer to run as
   * on the UI thread.
   */
  public void setOutline(FlutterOutline outline) {
    assert (outline != null);

    if (myDocument.getTextLength() != outline.getLength()) {
      // The outline is unfortunately out of sync with the Document due
      // to edits being made to the document since when the outline was
      // computed. A new outline will be generated shortly which will
      // be consistent with the document. Displaying highlights based on
      // the out of date outline results in confusing rendering errors.

      // service.getConvertedOffset might also provide a solution for
      // this case.
      return;
    }
    final WidgetIndentsPassData data = getIndentsPassData();
    setIndentsPassData(data); // In case we get interupted make sure we have saved the outline.


    // Mark that the current data is now stale.
    myEditor.putUserData(LAST_TIME_WIDGET_INDENTS_BUILT, null);

    calculator = new IndentsCalculator();
    calculator.calculate();
    final ArrayList<WidgetIndentGuideDescriptor> widgetDescriptors = new ArrayList<>();
    final ArrayList<StyledTextRange> widgetConstructors = new ArrayList<>();

    buildWidgetDescriptors(widgetDescriptors, widgetConstructors, outline, calculator, null);
    data.myDescriptors = widgetDescriptors;
    data.myWidgetConstructors = widgetConstructors;
    doCollectInformationUpdateOutline(data);
    // Needed to ensure that we don't have outdated regular indent information.
    doCollectInformationHelper(data, true);
    doApplyIndentInformationToEditor(true, data, false);
    applyChildParamaterNameHighlighters(data);
    // Have to force updates due to outline changes.
    doApplyIndentInformationToEditor(false, data, true);
    setIndentsPassData(data);
  }

  WidgetIndentsPassData getIndentsPassData() {
    WidgetIndentsPassData data = myEditor.getUserData(INDENTS_PASS_DATA_KEY);
    if (data == null) {
      data = new WidgetIndentsPassData();
    }
    return data;
  }

  void setIndentsPassData(WidgetIndentsPassData data) {
    myEditor.putUserData(INDENTS_PASS_DATA_KEY, data);
  }

  private void findChildParameters(ArrayList<StyledTextRange> parameters, PsiElement element) {
    if (element instanceof DartParameterNameReferenceExpression) {
      final DartParameterNameReferenceExpression argument = (DartParameterNameReferenceExpression)element;
      final String text = argument.getText();
      if ("children".equals(text) || "child".equals(text)) {
        final int offset = argument.getTextOffset();
        int endOffset = offset + text.length();
        final PsiElement colon = argument.getNextSibling();
        // Highlight the colon as well.
        if (colon != null && ":".equals(colon.getText())) {
          endOffset = colon.getTextOffset() + 1;
        }
        childParameterTextAttributes.setFontType(2);
        parameters.add(new StyledTextRange(new TextRange(offset, endOffset), childParameterTextAttributes));
      }
    }
    for (PsiElement child : element.getChildren()) {
      findChildParameters(parameters, child);
    }
  }

  void mergeAllStyledText(WidgetIndentsPassData data) {
    final ArrayList<StyledTextRange> styledText = new ArrayList<>();
    if (FlutterSettings.getInstance().isGreyUnimportantProperties()) {
      styledText.addAll(data.myChildParameters);
    }

    styledText.addAll(data.myWidgetConstructors);
    styledText.sort((a, b) -> Segment.BY_START_OFFSET_THEN_END_OFFSET.compare(a.range, b.range));
    data.myStyledText = styledText;
  }

  @Override
  public void doCollectInformation(@NotNull ProgressIndicator progress) {
    WidgetIndentsPassData data = getIndentsPassData();
    doCollectInformationHelper(data, false);
    setIndentsPassData(data);
  }

  private void doCollectInformationHelper(WidgetIndentsPassData data, boolean update) {
    assert myDocument != null;
    final Long stamp = myEditor.getUserData(LAST_TIME_SIMPLE_INDENTS_BUILT);
    if (stamp == null || stamp != nowStamp()) {
      update = true;
      // TODO(jacobr): track the last time we applied simple information.
      buildSimpleDescriptors(data);

      // TODO(jacobr): consider only building the childParameters when
      // FlutterSettings.getInstance().isGreyUnimportantProperties() is true.
      final ArrayList<StyledTextRange> childParameters = new ArrayList<>();
      findChildParameters(childParameters, myFile);
      childParameters.sort((a, b) -> Segment.BY_START_OFFSET_THEN_END_OFFSET.compare(a.range, b.range));
      data.myChildParameters = childParameters;
      mergeAllStyledText(data);
    }
    if (update) {
      addSimpleDescriptors(data);
    }
  }

  public void doCollectInformationUpdateOutline(WidgetIndentsPassData data) {
    assert myDocument != null;
    final Long stamp = myEditor.getUserData(LAST_TIME_WIDGET_INDENTS_BUILT);
    if (stamp != null && stamp == nowStamp()) return;

    if (data.myDescriptors != null) {
      final ArrayList<TextRangeDescriptorPair> ranges = new ArrayList<>();
      for (WidgetIndentGuideDescriptor descriptor : data.myDescriptors) {
        ProgressManager.checkCanceled();
        TextRange range;
        if (descriptor.widget != null) {
          range = new TextRange(descriptor.widget.offset, descriptor.widget.endOffset);
        }
        else {
          int endOffset =
            descriptor.endLine < myDocument.getLineCount() ? myDocument.getLineStartOffset(descriptor.endLine) : myDocument.getTextLength();
          range = new TextRange(myDocument.getLineStartOffset(descriptor.startLine), endOffset);
        }
        ranges.add(new TextRangeDescriptorPair(range, descriptor));
      }
      ranges.sort((a, b) -> Segment.BY_START_OFFSET_THEN_END_OFFSET.compare(a.range, b.range));
      data.myRangesWidgets = ranges;
      data.indentSpans = null;
    }

    mergeAllStyledText(data);
  }

  private void addSimpleDescriptors(WidgetIndentsPassData data) {
    final ArrayList<TextRangeDescriptorPair> ranges = new ArrayList<>();
    WidgetIndentSpans indentSpans = data.indentSpans;
    /* if (indentSpans == null)  */ // ALWAYS RECOMPUTE... THIS IS TOO SLOW. FIX.
    {
      final FlutterSettings settings = FlutterSettings.getInstance();

      indentSpans = settings.isSimpleIndentIntersectionMode() ?
                    new WidgetIndentSpansFast(data.myDescriptors, myDocument) :
                    new WidgetIndentSpansSimple(data.myDescriptors, myDocument);

      data.indentSpans = indentSpans;
    }
    for (WidgetIndentGuideDescriptor descriptor : data.mySimpleDescriptors) {
      ProgressManager.checkCanceled();
      if (indentSpans.intersects(descriptor)) {
        continue;
      }
      final TextRange range;
      final int endOffset =
        descriptor.endLine < myDocument.getLineCount() ? myDocument.getLineStartOffset(descriptor.endLine) : myDocument.getTextLength();
      range = new TextRange(myDocument.getLineStartOffset(descriptor.startLine), endOffset);
      ranges.add(new TextRangeDescriptorPair(range, descriptor));
    }

    ranges.sort((a, b) -> Segment.BY_START_OFFSET_THEN_END_OFFSET.compare(a.range, b.range));
    data.myRangesSimple = ranges;
  }

  private long nowStamp() {
    // We can't show our custom indent guides if regular indent guides are
    // shown as the two will conflict.
    if (myEditor.getSettings().isIndentGuidesShown()) return -1;
    assert myDocument != null;
    return myDocument.getModificationStamp();
  }

  @Override
  public void doApplyInformationToEditor() {
    final Long stamp = myEditor.getUserData(LAST_TIME_SIMPLE_INDENTS_BUILT);
    if (stamp != null && stamp == nowStamp()) return;

    final WidgetIndentsPassData data = getIndentsPassData();
    applyChildParamaterNameHighlighters(data);
    doApplyIndentInformationToEditor(false, data, false);
    setIndentsPassData(data);
  }

  // Helper to force an update of data when settings change.
  public void onSettingsChanged() {
    myEditor.putUserData(LAST_TIME_SIMPLE_INDENTS_BUILT, null);
    final WidgetIndentsPassData data = getIndentsPassData(); // XXX ?new WidgetIndentsPassData();
    doCollectInformationHelper(data, true);
    doApplyInformationToEditor();
    setIndentsPassData(data);
  }

  public void applyChildParamaterNameHighlighters(WidgetIndentsPassData data) {
    final MarkupModel mm = myEditor.getMarkupModel();

    final List<RangeHighlighter> oldHighlighters = myEditor.getUserData(PARAMETER_NAME_HIGHLIGHTERS_IN_EDITOR_KEY);
    final List<RangeHighlighter> newHighlighters = new ArrayList<>();

    int curRange = 0;

    final List<StyledTextRange> styledText = data.myStyledText;
    if (oldHighlighters != null) {
      // after document change some range highlighters could have become invalid, or the order could have been broken
      oldHighlighters.sort(Comparator.comparing((RangeHighlighter h) -> !h.isValid())
                             .thenComparing(Segment.BY_START_OFFSET_THEN_END_OFFSET));
      int curHighlight = 0;
      while (curRange < styledText.size() && curHighlight < oldHighlighters.size()) {
        final StyledTextRange entry = styledText.get(curRange);
        final RangeHighlighter highlighter = oldHighlighters.get(curHighlight);
        if (!highlighter.isValid()) break;

        final int cmp = compare(entry, highlighter);
        if (cmp < 0) {
          newHighlighters.add(createStyledHighlighter(mm, entry));
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
    DocumentUtil.executeInBulk(myDocument, styledText.size() > 10000, () -> {
      for (int i = startRangeIndex; i < styledText.size(); i++) {
        newHighlighters.add(createStyledHighlighter(mm, styledText.get(i)));
      }
    });

    myEditor.putUserData(PARAMETER_NAME_HIGHLIGHTERS_IN_EDITOR_KEY, newHighlighters);
  }

  private RangeHighlighter createStyledHighlighter(MarkupModel mm, StyledTextRange entry) {
    return
      mm.addRangeHighlighter(entry.range.getStartOffset(), entry.range.getEndOffset(), HighlighterLayer.SELECTION, entry.attributes,
                             HighlighterTargetArea.EXACT_RANGE);
  }

  public void doApplyIndentInformationToEditor(boolean updateWidgets, WidgetIndentsPassData data, boolean forceUpdate) {
    final MarkupModel mm = myEditor.getMarkupModel();
    final RangeHighlighter[] existing = mm.getAllHighlighters();

    final Key<Long> timeKey = updateWidgets ? LAST_TIME_WIDGET_INDENTS_BUILT : LAST_TIME_SIMPLE_INDENTS_BUILT;
    final Long stamp = myEditor.getUserData(timeKey);
    if (stamp != null && stamp == nowStamp() && !forceUpdate) return;

    final Key<List<RangeHighlighter>> highlighterKey =
      updateWidgets ? WIDGET_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY : SIMPLE_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY;
    final List<RangeHighlighter> oldHighlighters = myEditor.getUserData(highlighterKey);
    final List<RangeHighlighter> newHighlighters = new ArrayList<>();

    int curRange = 0;

    final List<TextRangeDescriptorPair> ranges = updateWidgets ? data.myRangesWidgets : data.myRangesSimple;
    if (oldHighlighters != null) {
      // after document change some range highlighters could have become invalid, or the order could have been broken
      oldHighlighters.sort(Comparator.comparing((RangeHighlighter h) -> !h.isValid())
                             .thenComparing(Segment.BY_START_OFFSET_THEN_END_OFFSET));
      int curHighlight = 0;
      while (curRange < ranges.size() && curHighlight < oldHighlighters.size()) {
        final TextRangeDescriptorPair entry = ranges.get(curRange);
        final RangeHighlighter highlighter = oldHighlighters.get(curHighlight);

        if (!highlighter.isValid()) break;

        int cmp = compare(entry, highlighter);
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

    myEditor.putUserData(highlighterKey, newHighlighters);
    myEditor.putUserData(timeKey, nowStamp());

    if (!updateWidgets) {
      // Placate the Java type system. TODO(jacobr): figure out how to cast instead.
      final List<IndentGuideDescriptor> simpleDescriptors = new ArrayList<>(data.mySimpleDescriptors);
      myEditor.getIndentsModel().assumeIndents(simpleDescriptors);
    }
  }

  private OutlineLocation computeLocation(FlutterOutline node, IndentsCalculator calculator) {
    final int nodeOffset = node.getOffset();
    assert (myDocument != null);
    final int line = myDocument.getLineNumber(nodeOffset);
    final int lineStartOffset = myDocument.getLineStartOffset(line);
    int column = nodeOffset - lineStartOffset;
    int indent = calculator.lineIndents[line];
    return new OutlineLocation(node, line, column, indent);
  }

  private void buildWidgetDescriptors(
    final List<WidgetIndentGuideDescriptor> widgetDescriptors,
    ArrayList<StyledTextRange> widgetConstructors,
    FlutterOutline outlineNode,
    IndentsCalculator calculator,
    WidgetIndentGuideDescriptor parent
  ) {
    if (outlineNode == null) return;

    final String kind = outlineNode.getKind();
    final boolean widgetConstructor = "NEW_INSTANCE".equals(kind);

    /*
    if (!"COMPILATION_UNIT".equals(kind)) {
      final int offset = outlineNode.getOffset();
      Element element = outlineNode.getDartElement();
      if (element != null && !"COMPILATION_UNIT".equals(element.getKind())) {
        // Highlight these methods like constructor calls.
        widgetConstructors.add(
          new StyledTextRange(new TextRange(offset, offset + element.getLocation().getLength()),
                              widgetConstructorTextAttributes
          ));
      }
    }*/

    final List<FlutterOutline> children = outlineNode.getChildren();
    if (children == null || children.isEmpty()) return;

    if (widgetConstructor) {
      final OutlineLocation location = computeLocation(outlineNode, calculator);

      int minChildIndent = Integer.MAX_VALUE;
      final ArrayList<OutlineLocation> childrenLocations = new ArrayList<>();
      int endLine = location.getCurrentLine();
      for (FlutterOutline child : children) {
        final OutlineLocation childLocation = computeLocation(child, calculator);
        if (childLocation == null || childLocation.getCurrentLine() <= location.getCurrentLine()) {
          // Skip children that don't actually occur on a later line. There is no
          // way for us to draw good looking line art for them.
          // TODO(jacobr): consider adding these children anyway so we can render
          // them if there are edits and they are now properly formatted.
          continue;
        }

        minChildIndent = min(minChildIndent, childLocation.getCurrentIndent());
        endLine = max(endLine, childLocation.getCurrentLine());
        childrenLocations.add(childLocation);
      }
      if (!childrenLocations.isEmpty()) {
        final int lineIndent = Math.min(max(minChildIndent - 2, location.column), location.getCurrentIndent());
        final WidgetIndentGuideDescriptor descriptor = new WidgetIndentGuideDescriptor(
          parent,
          lineIndent,
          location.getCurrentLine(),
          endLine + 1, // XXX can we remove this? this seems off. Calc from offset instead.
          childrenLocations,
          location
        );
        if (!descriptor.childLines.isEmpty()) {
          widgetDescriptors.add(descriptor);
          parent = descriptor;
        }
      }
    }
    for (FlutterOutline child : children) {
      buildWidgetDescriptors(widgetDescriptors, widgetConstructors, child, calculator, parent);
    }
  }

  // This logic is duplicated with the regular indents pass.
  // TODO(jacobr): consider unifying. We've tweaked the code in ways that hurt
  // performance a little. No reason we can't unify.
  private void buildSimpleDescriptors(WidgetIndentsPassData data) {
    calculator = new IndentsCalculator();
    calculator.calculate();

    final int[] lineIndents = calculator.lineIndents;

    final Stack<IndentEntry> stack = new Stack<>();

    final IndentEntry rootEntry = new IndentEntry(0, 0, null);
    stack.push(rootEntry);
    assert myDocument != null;
    final List<WidgetIndentGuideDescriptor> simpleDescriptors = new ArrayList<>();

    for (int line = 1; line < lineIndents.length; line++) {
      ProgressManager.checkCanceled();
      final int curIndent = abs(lineIndents[line]);

      while (!stack.empty() && curIndent <= stack.peek().indent) {
        // strip all closed blocks.
        ProgressManager.checkCanceled();
        final IndentEntry entry = stack.pop();

        final int level = entry.indent;
        final int startLine = entry.line;
        if (level > 0) {
          // only add blocks where at least one intermediate line had a different indent.
          for (int i = startLine; i < line; i++) {
            if (level != abs(lineIndents[i])) {
              simpleDescriptors.add(new WidgetIndentGuideDescriptor(null, level, startLine, line, null, null));
              break;
            }
          }
        }
      }

      final int prevLine = line - 1;
      final int prevIndent = abs(lineIndents[prevLine]);

      if (curIndent - prevIndent > 1) {
        // XXX fill in widget.
        final IndentEntry entry = new IndentEntry(prevLine, prevIndent, null);
        stack.push(entry);
      }
    }

    while (!stack.empty()) {
      ProgressManager.checkCanceled();
      final IndentEntry entry = stack.pop();
      final int level = entry.indent;
      final int startLine = entry.line;
      if (level > 0) {
        simpleDescriptors.add(new WidgetIndentGuideDescriptor(null, level, startLine, myDocument.getLineCount(), null, null));
      }
    }

    data.mySimpleDescriptors = simpleDescriptors;
  }

  /// XXX remove this?
  private WidgetIndentGuideDescriptor createDescriptor(int level,
                                                       int startLine,
                                                       int endLine,
                                                       int[] lineIndents,
                                                       ArrayList<OutlineLocation> widgetLines,
                                                       OutlineLocation widget) {
    while (startLine > 0 && lineIndents[startLine] < 0) startLine--;
    int codeConstructStartLine = findCodeConstructStartLine(startLine);
    return new WidgetIndentGuideDescriptor(null, level, codeConstructStartLine, startLine, endLine, widgetLines, widget);
  }

  private int findCodeConstructStartLine(int startLine) {
    DocumentEx document = myEditor.getDocument();
    CharSequence text = document.getImmutableCharSequence();
    int lineStartOffset = document.getLineStartOffset(startLine);
    int firstNonWsOffset = CharArrayUtil.shiftForward(text, lineStartOffset, " \t");
    FileType type = PsiUtilBase.getPsiFileAtOffset(myFile, firstNonWsOffset).getFileType();
    Language language = PsiUtilCore.getLanguageAtOffset(myFile, firstNonWsOffset);
    BraceMatcher braceMatcher = BraceMatchingUtil.getBraceMatcher(type, language);
    HighlighterIterator iterator = myEditor.getHighlighter().createIterator(firstNonWsOffset);
    if (braceMatcher.isLBraceToken(iterator, text, type)) {
      int codeConstructStart = braceMatcher.getCodeConstructStart(myFile, firstNonWsOffset);
      return document.getLineNumber(codeConstructStart);
    }
    else {
      return startLine;
    }
  }

  @NotNull
  private RangeHighlighter createHighlighter(MarkupModel mm, TextRangeDescriptorPair entry) {
    final TextRange range = entry.range;
    final boolean isWidget = entry.descriptor.widget != null;
    final FlutterSettings settings = FlutterSettings.getInstance();
    final RangeHighlighter highlighter =
      mm.addRangeHighlighter(range.getStartOffset(), range.getEndOffset(), isWidget ? HighlighterLayer.FIRST : HighlighterLayer.LAST, null,
                             HighlighterTargetArea.EXACT_RANGE);
    if (isWidget && entry.descriptor.parent == null && settings.isShowBuildMethodsOnScrollbar()) {
      highlighter.setErrorStripeMarkColor(BUILD_METHOD_STRIPE_COLOR);
      highlighter.setErrorStripeTooltip("Flutter build method");
      highlighter.setThinErrorStripeMark(true);
   //   highlighter.setLineMarkerRenderer(lineMarkerRenderer);
    }
    highlighter.setCustomRenderer(new WidgetCustomHighlighterRenderer(entry.descriptor));
    return highlighter;
  }

  private void disposeHighlighter(RangeHighlighter highlighter) {
    CustomHighlighterRenderer renderer = highlighter.getCustomRenderer();
    if (renderer instanceof WidgetCustomHighlighterRenderer) {
      ((WidgetCustomHighlighterRenderer)renderer).dispose();
    }
    highlighter.dispose();
  }

  public void dispose() {
    ApplicationManager.getApplication().invokeLater(() -> {
      disposeHighlighters(PARAMETER_NAME_HIGHLIGHTERS_IN_EDITOR_KEY);
      disposeHighlighters(WIDGET_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY);
      disposeHighlighters(SIMPLE_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY);
    });
  }

  private void disposeHighlighters(Key<List<RangeHighlighter>> key) {
    final List<RangeHighlighter> oldHighlighters = myEditor.getUserData(key);
    if (oldHighlighters != null) {
      for (RangeHighlighter highlighter : oldHighlighters) {
        highlighter.dispose();
      }
    }
    myEditor.putUserData(key, null);
  }
}

interface WidgetIndentSpans {
  boolean intersects(WidgetIndentGuideDescriptor descriptor);
}
