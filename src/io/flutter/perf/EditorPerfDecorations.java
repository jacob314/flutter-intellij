/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

class EditorPerfDecorations implements Disposable {
  private static final VirtualFileManager virtualFileManager = VirtualFileManager.getInstance();

  private static final int HIGHLIGHTER_LAYER = HighlighterLayer.SELECTION - 1;

  @NotNull
  private final FileEditor fileEditor;

  private boolean hasDecorations = false;

  EditorPerfDecorations(@NotNull FileEditor fileEditor) {
    this.fileEditor = fileEditor;

    addBlankMarker();
  }

  private void addBlankMarker() {
    ApplicationManager.getApplication().invokeLater(() -> {
      final Editor editor = ((TextEditor)fileEditor).getEditor();
      final MarkupModel markupModel = editor.getMarkupModel();

      if (hasDecorations) {
        removeHighlightersFromEditor(markupModel);
      }

      if (editor.getDocument().getTextLength() > 0) {
        final RangeHighlighter rangeHighlighter =
          markupModel.addLineHighlighter(0, HIGHLIGHTER_LAYER, new TextAttributes());
        rangeHighlighter.setLineMarkerRenderer(new BlankLineMarkerRenderer());

        hasDecorations = true;
      }
    });
  }

  public void updateFromPerfSourceReport(
    @NotNull VirtualFile file,
    @NotNull PerfSourceReport report
  ) {
    final FilePerfInfo perfInfo = new FilePerfInfo(file);

    for (PerfSourceReport.Entry entry : report.getEntries()) {
      perfInfo.addCounts(entry.line, entry.timeStamps);
    }

    // Calculate coverage info for file, display in the UI.
    ApplicationManager.getApplication().invokeLater(() -> {
      final TextAttributes rebuiltAttributes = new TextAttributes();

      assert fileEditor instanceof TextEditor;
      final Editor editor = ((TextEditor)fileEditor).getEditor();
      final MarkupModel markupModel = editor.getMarkupModel();

      removeHighlightersFromEditor(markupModel);

      final long timestamp = System.currentTimeMillis();
      perfInfo.getCounts(timestamp, (int line, int count) -> {
        final RangeHighlighter rangeHighlighter = markupModel.addLineHighlighter(line - 1, HIGHLIGHTER_LAYER, rebuiltAttributes);

        final CountLineMarkerRenderer renderer =
          new CountLineMarkerRenderer(count);
        // TODO(jacobr): consider removing these 3 lines?
        rangeHighlighter.setErrorStripeMarkColor(CountLineMarkerRenderer.coveredColor);
        rangeHighlighter.setThinErrorStripeMark(true);
        rangeHighlighter.setLineMarkerRenderer(renderer);
      });

      hasDecorations = true;
    });
  }

  private void removeHighlightersFromEditor(MarkupModel markupModel) {
    final List<RangeHighlighter> highlighters = new ArrayList<>();

    for (RangeHighlighter highlighter : markupModel.getAllHighlighters()) {
      if (highlighter.getLineMarkerRenderer() instanceof FlutterLineMarkerRenderer) {
        highlighters.add(highlighter);
      }
    }

    for (RangeHighlighter highlighter : highlighters) {
      markupModel.removeHighlighter(highlighter);
    }

    hasDecorations = false;
  }

  public void flushDecorations() {
    if (hasDecorations && fileEditor.isValid()) {
      hasDecorations = false;

      ApplicationManager.getApplication().invokeLater(() -> {
        final MarkupModel markupModel = ((TextEditor)fileEditor).getEditor().getMarkupModel();
        removeHighlightersFromEditor(markupModel);
      });
    }
  }

  @Override
  public void dispose() {
    flushDecorations();
  }
}

abstract class FlutterLineMarkerRenderer implements LineMarkerRenderer, LineMarkerRendererEx {
  static final int curvature = 2;

  @NotNull
  @Override
  public LineMarkerRendererEx.Position getPosition() {
    return LineMarkerRendererEx.Position.LEFT;
  }
}

class CountLineMarkerRenderer extends FlutterLineMarkerRenderer {
  static final Color coveredColor = new JBColor(new Color(0x0091ea), new Color(0x0091ea));

  private final int count;

  CountLineMarkerRenderer(int count) {
    this.count = count;
  }

  @Override
  public void paint(Editor editor, Graphics g, Rectangle r) {
    final int height = r.height;

    GraphicsUtil.setupAAPainting(g);

    final Font font = UIUtil.getFont(UIUtil.FontSize.MINI, UIUtil.getButtonFont());
    g.setFont(font);

    final String text = Integer.toString(count);

    final Rectangle2D bounds = g.getFontMetrics().getStringBounds(text, g);

    final int width = Math.max(r.width, (int)bounds.getWidth() + 4);

    final Color backgroundColor = Color.getHSBColor((float)25.0 / 360.0f, 1.0f, 1.0f);
    g.setColor(backgroundColor);

    g.fillRect(r.x + 2, r.y, width, height);
    g.setColor(JBColor.white);
    g.drawString(text, r.x + 4, r.y + r.height - 4);
  }
}

class BlankLineMarkerRenderer extends FlutterLineMarkerRenderer {
  BlankLineMarkerRenderer() {
  }

  @Override
  public void paint(Editor editor, Graphics g, Rectangle r) {
  }
}
