/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.intellij.debugger.SourcePosition;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import icons.FlutterIcons;
import io.flutter.run.FlutterReloadManager;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.utils.AnimatedIcon;
import io.flutter.view.FlutterView;
import io.flutter.view.InspectorPerfTab;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE;

class EditorPerfDecorations implements Disposable, EditorMouseListener, FilePerfModel {
  private static final int HIGHLIGHTER_LAYER = HighlighterLayer.SELECTION - 1;

  @NotNull
  private final TextEditor textEditor;

  /// XXX we can likely remove this Timer unless highlighting content of lines instead of gutter icons is importante stuff is important.
  private final Timer timer;

  private final FlutterApp app;
  private final FilePerfInfo stats;

  private boolean hasDecorations = false;
  private boolean hoveredOverLineMarkerArea = false;

  EditorPerfDecorations(@NotNull TextEditor textEditor, FlutterApp app) {
    this.textEditor = textEditor;
    this.app = app;
    textEditor.getEditor().addEditorMouseListener(this);
    // TODO(jacobr): 30 FPS is excessive for this case.
    timer = new Timer(1000 / 30, this::onFrame);
    final VirtualFile file = textEditor.getFile();
    stats = new FilePerfInfo();
  }

  @Override
  public boolean isHoveredOverLineMarkerArea() {
    return hoveredOverLineMarkerArea;
  }

  @NotNull
  @Override
  public FilePerfInfo getStats() {
    return stats;
  }

  @NotNull
  @Override
  public TextEditor getTextEditor() {
    return textEditor;
  }

  @NotNull
  @Override
  public FlutterApp getApp() {
    return app;
  }

  void setHasDecorations(boolean value) {
    if (value != hasDecorations) {
      hasDecorations = value;
      if (value) {
        timer.start();
      }
      else {
        timer.stop();
      }
    }
  }

  @NotNull
  @Override
  public String getWidgetName(int line, int column) {
    PsiElement element = getElement(line, column);
    return element != null ? element.getText() : "Widget";
  }

  @Nullable
  private PsiElement getElement(int line, int column) {
    if (textEditor.isModified()) {
      return null;
    }

    final Document document = textEditor.getEditor().getDocument();
    if (document.isLineModified(line-1)) {
      return null;
    }
    final PsiFile psiFile = PsiDocumentManager.getInstance(app.getProject()).getPsiFile(document);
    if (psiFile == null) {
      return null;
    }
    final PsiFile originalFile = psiFile.getOriginalFile();
    XSourcePosition pos = XDebuggerUtil.getInstance().createPosition(originalFile.getVirtualFile(), line - 1, column-1);

    final int offset = pos.getOffset();
    return psiFile.getOriginalFile().findElementAt(offset);
  }

  public void updateFromPerfSourceReports(
    @NotNull VirtualFile file,
    List<PerfSourceReport> reports
  ) {
    // Lookup performance stats for file, display in the UI.
    ApplicationManager.getApplication().invokeLater(() -> {
      stats.clear();
      for (PerfSourceReport report : reports) {
        for (PerfSourceReport.Entry entry : report.getEntries()) {
          final PsiElement element = getElement(entry.line, entry.column);
          if (element == null) {
            continue;
          }
          final TextRange range = element.getTextRange();
          if (range != null) {
            stats.add(range, new SlidingWindowStats(report.getKind(), entry.total, entry.pastSecond, element.getText()));
          }
        }
      }

      final Editor editor = textEditor.getEditor();
      final MarkupModel markupModel = editor.getMarkupModel();

      removeHighlightersFromEditor(markupModel);

      final long timestamp = System.currentTimeMillis() - FlutterWidgetPerf.getTimeSkew();
      for (TextRange range : stats.getLines()) {
        addRangeHighlighter(range, markupModel);
      }
      setHasDecorations(true);
    });
  }

  @Override
  public void markAppIdle() {
    ApplicationManager.getApplication().invokeLater(() -> {
      stats.markAppIdle();
      updateIconUI();
    });
  }

  private void addRangeHighlighter(TextRange textRange, MarkupModel markupModel) {
    final RangeHighlighter rangeHighlighter = markupModel.addRangeHighlighter(
      textRange.getStartOffset(), textRange.getEndOffset(), HIGHLIGHTER_LAYER, new TextAttributes(), EXACT_RANGE);

    final PerfGutterIconRenderer renderer = new PerfGutterIconRenderer(
      textRange,
      this,
      rangeHighlighter
    );
    rangeHighlighter.setGutterIconRenderer(renderer);
    rangeHighlighter.setThinErrorStripeMark(true);
  }

  private void onFrame(ActionEvent event) {
    if (app.isReloading()) {
      return;
    }
    if (!hasDecorations) {
      timer.stop();
      return;
    }

    updateIconUI();
    // TODO(jacobr): stop timer when the ui has stabalized.
  }

  private void updateIconUI() {
    final Editor editor = textEditor.getEditor();
    final MarkupModel markupModel = editor.getMarkupModel();
    if (markupModel instanceof MarkupModelEx) {
      for (RangeHighlighter highlighter : markupModel.getAllHighlighters()) {
        if (highlighter.getGutterIconRenderer() instanceof PerfGutterIconRenderer) {
          final PerfGutterIconRenderer iconRenderer = (PerfGutterIconRenderer)highlighter.getGutterIconRenderer();
          iconRenderer.updateUI();
          ((MarkupModelEx)markupModel).fireAttributesChanged((RangeHighlighterEx)highlighter, true, true);
        }
      }
    }
  }

  private void removeHighlightersFromEditor(MarkupModel markupModel) {
    final List<RangeHighlighter> highlighters = new ArrayList<>();

    for (RangeHighlighter highlighter : markupModel.getAllHighlighters()) {
      if (highlighter.getGutterIconRenderer() instanceof PerfGutterIconRenderer) {
        highlighters.add(highlighter);
      }
    }

    for (RangeHighlighter highlighter : highlighters) {
      markupModel.removeHighlighter(highlighter);
    }

    setHasDecorations(false);
  }

  public void flushDecorations() {
    if (hasDecorations && textEditor.isValid()) {
      setHasDecorations(false);

      ApplicationManager.getApplication().invokeLater(() -> {
        final MarkupModel markupModel = ((TextEditor)textEditor).getEditor().getMarkupModel();
        removeHighlightersFromEditor(markupModel);
      });
    }
  }

  @Override
  public void dispose() {
    textEditor.getEditor().removeEditorMouseListener(this);
    flushDecorations();
  }

  @Override
  public void mousePressed(EditorMouseEvent e) {
  }

  @Override
  public void mouseClicked(EditorMouseEvent e) {
  }

  @Override
  public void mouseReleased(EditorMouseEvent e) {
  }

  @Override
  public void mouseEntered(EditorMouseEvent e) {
    final EditorMouseEventArea area = e.getArea();
    if (!hoveredOverLineMarkerArea &&
        area == EditorMouseEventArea.LINE_MARKERS_AREA ||
        area == EditorMouseEventArea.FOLDING_OUTLINE_AREA ||
        area == EditorMouseEventArea.LINE_NUMBERS_AREA) {
      // Hover is over the gutter area.
      setHoverState(true);
    }
  }

  @Override
  public void mouseExited(EditorMouseEvent e) {
    final EditorMouseEventArea area = e.getArea();
    setHoverState(false);
    // TODO(jacobr): hovers over a tooltip triggered by a gutter icon should
    // be considered a hover of the gutter but this logic does not handle that
    // case correctly yet.
  }

  private void setHoverState(boolean value) {
    if (value != hoveredOverLineMarkerArea) {
      hoveredOverLineMarkerArea = value;
      updateIconUI();
    }
  }

  public void clear() {
    ApplicationManager.getApplication().invokeLater(() -> {
      stats.clear();
      final Editor editor = textEditor.getEditor();
      final MarkupModel markupModel = editor.getMarkupModel();
      removeHighlightersFromEditor(markupModel);
    });
  }
}

class PerfGutterIconRenderer extends GutterIconRenderer {
  static final AnimatedIcon RED_PROGRESS = new RedProgress();
  static final AnimatedIcon NORMAL_PROGRESS = new AnimatedIcon.Grey();

  private static final Icon EMPTY_ICON = new EmptyIcon(FlutterIcons.CustomInfo);
  private static final int HIGH_LOAD_THRESHOLD = 100;
  private static final double ANIMATION_SPEED = 4.0;

  private final RangeHighlighter highlighter;
  private final TextRange range;
  private final FilePerfModel perfModelForFile;

  PerfGutterIconRenderer(TextRange range,
                         EditorPerfDecorations perfModelForFile,
                         RangeHighlighter highlighter) {
    this.highlighter = highlighter;
    this.range = range;
    this.perfModelForFile = perfModelForFile;
    updateUI();
  }

  public boolean isNavigateAction() {
    return isActive();
  }

  private FlutterApp getApp() {
    return perfModelForFile.getApp();
  }

  private int getCountPastSecond() {
    return perfModelForFile.getStats().getCountPastSecond(range);
  }

  private boolean isActive() {
    return perfModelForFile.isHoveredOverLineMarkerArea() || getCountPastSecond() > 0;
  }

  /**
   * Returns the action executed when the icon is left-clicked.
   *
   * @return the action instance, or null if no action is required.
   */
  @Nullable
  public AnAction getClickAction() {
    return new AnAction() {
      @Override
      public void actionPerformed(AnActionEvent e) {
        if (isActive()) {

          final ToolWindowManagerEx toolWindowManager = ToolWindowManagerEx.getInstanceEx(getApp().getProject());
          final ToolWindow flutterToolWindow = toolWindowManager.getToolWindow(FlutterView.TOOL_WINDOW_ID);
          if (flutterToolWindow.isVisible()) {
            showPerfViewMessage();
            return;
          }
          flutterToolWindow.show(() -> showPerfViewMessage());
        }
      }
    };
  }

  void showPerfViewMessage() {
    final FlutterView flutterView = ServiceManager.getService(getApp().getProject(), FlutterView.class);
    final InspectorPerfTab inspectorPerfTab = flutterView.showPerfTab(getApp());
    final StringBuilder sb = new StringBuilder("<html><body>");
    final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    sb.append("<h2>Widget rebuild stats for ");
    sb.append(Objects.requireNonNull(perfModelForFile.getTextEditor().getFile()).getName());
    sb.append(" at ");
    sb.append(formatter.format(LocalDateTime.now()));
    sb.append("</h2>");
    for (String line : getTooltipLines()) {
      sb.append("<h3>");
      sb.append(line);
      sb.append("</h3>");
    }

    sb.append("<p>");
    sb.append("Rebuilding widgets is generally very cheap. You should only worry " +
              "about optimizing code to reduce the the number of widget rebuilds " +
              "if you notice that the frame rate is bellow 60fps or if widgets " +
              "that you did not expect to be rebuilt are rebuilt a very large " +
              "number of times.");
    sb.append("</p>");
    sb.append("</body></html>");
    inspectorPerfTab.getWidgetPerfPanel().setPerfMessage(perfModelForFile.getTextEditor(), range, sb.toString());
  }

  @NotNull
  @Override
  public Alignment getAlignment() {
    return Alignment.LEFT;
  }

  @NotNull
  @Override
  public Icon getIcon() {
    final int count = getCountPastSecond();
    if (count == 0) {
      return perfModelForFile.isHoveredOverLineMarkerArea() ? FlutterIcons.CustomInfo : EMPTY_ICON;
    }
    if (count > HIGH_LOAD_THRESHOLD) {
      return RED_PROGRESS;
    }
    return NORMAL_PROGRESS;
  }

  Color getErrorStripeMarkColor() {
    final int count = getCountPastSecond();
    if (count == 0) {
      return null;
    }
    if (count > HIGH_LOAD_THRESHOLD) {
      return JBColor.RED;
    }
    return JBColor.YELLOW;
  }

  public void updateUI() {
    Color backgroundColor = null;
    final int count = getCountPastSecond();
    if (count > 0) {
      final TextAttributes textAttributes = highlighter.getTextAttributes();
      final Color targetColor = getErrorStripeMarkColor();
      textAttributes.setEffectType(EffectType.BOLD_DOTTED_LINE);
      double animateTime = (double)(System.currentTimeMillis()) * 0.001;
      // TODO(jacobr): consider tracking a start time for the individual
      // animation instead of having all animations running in sync.
      final double balance = (1.0 - Math.cos(animateTime * ANIMATION_SPEED)) * 0.5;
      backgroundColor = ColorUtil.mix(JBColor.WHITE, targetColor, balance);
      if (!backgroundColor.equals(textAttributes.getEffectColor())) {
        textAttributes.setEffectColor(backgroundColor);
      }
    }
    highlighter.setErrorStripeMarkColor(getErrorStripeMarkColor());
    highlighter.setErrorStripeTooltip(getTooltipText());
  }

  List<String> getTooltipLines() {
    final List<String> lines = new ArrayList<>();
    for (SlidingWindowStats stats : perfModelForFile.getStats().getLineStats(range)) {
      if (stats.getKind() == PerfReportKind.rebuild) {
        lines.add(
          stats.getDescription() +
          " was rebuilt " +
          stats.getPastSecond() +
          " times in the past second and " +
          stats.getTotal() +
          " times overall."
        );
      } else if (stats.getKind() == PerfReportKind.repaint) {
        lines.add(
          "RenderObjects created by " +
          stats.getDescription() +
          " were repainted " +
          stats.getPastSecond() +
          " times in the past second and " +
          stats.getTotal() +
          " times overall."
        );
      }
    }
    if (lines.isEmpty()) {
      lines.add("No widget rebuilds or repaints detected for line.");
    }
    return lines;
  }

  @Override
  public String getTooltipText() {
    return "<html><body><b>" + StringUtil.join(getTooltipLines(), "<br>") + " </b></body></html>";
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof PerfGutterIconRenderer)) {
      return false;
    }
    final PerfGutterIconRenderer other = (PerfGutterIconRenderer)obj;
    return other.getCountPastSecond() == getCountPastSecond();
  }

  @Override
  public int hashCode() {
    return getCountPastSecond();
  }

  private static class EmptyIcon implements Icon {
    final Icon iconForSize;

    EmptyIcon(Icon iconForSize) {
      this.iconForSize = iconForSize;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
    }

    @Override
    public int getIconWidth() {
      return iconForSize.getIconWidth();
    }

    @Override
    public int getIconHeight() {
      return iconForSize.getIconHeight();
    }
  }

  // Progress icon
  public static final class RedProgress extends AnimatedIcon {
    public RedProgress() {
      super(150,
            FlutterIcons.State.RedProgr_1,
            FlutterIcons.State.RedProgr_2,
            FlutterIcons.State.RedProgr_3,
            FlutterIcons.State.RedProgr_4,
            FlutterIcons.State.RedProgr_5,
            FlutterIcons.State.RedProgr_6,
            FlutterIcons.State.RedProgr_7,
            FlutterIcons.State.RedProgr_8);
    }
  }
}
