/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.IndentGuideDescriptor;
import com.intellij.openapi.editor.ex.DocumentEx;

import java.util.ArrayList;

import static java.lang.Math.max;

public class WidgetIndentGuideDescriptor extends IndentGuideDescriptor {
  public final WidgetIndentGuideDescriptor parent;
  public final ArrayList<OutlineLocation> childLines;
  public final OutlineLocation widget;

  public WidgetIndentGuideDescriptor(WidgetIndentGuideDescriptor parent, int indentLevel, int startLine, int endLine, ArrayList<OutlineLocation> childLines, OutlineLocation widget) {
    this(parent, indentLevel, startLine, startLine, endLine, childLines, widget);
  }

  public WidgetIndentGuideDescriptor(WidgetIndentGuideDescriptor parent, int indentLevel, int codeConstructStartLine, int startLine, int endLine, ArrayList<OutlineLocation> childLines, OutlineLocation widget) {
    super(indentLevel, codeConstructStartLine, startLine, endLine);
    this.parent = parent;
    this.childLines = childLines;
    this.widget = widget;
  }

  void dispose() {
    if (childLines == null) return;
    for (OutlineLocation childLine : childLines) {
      childLine.dispose();
    }
  }

  public void trackLocations(Document document) {
    if (childLines == null) return;
    for (OutlineLocation childLine : childLines) {
      childLine.createMarker(document);
    }
  }

  @Override
  public int hashCode() {
    int result = indentLevel;
    result = 31 * result + startLine;
    result = 31 * result + endLine;
    if (childLines != null) {
      for (OutlineLocation location : childLines) {
        result = 31 * result + location.hashCode();
      }
    }
    if (widget != null) {
      result = 31 * result + widget.hashCode();
    }
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    final WidgetIndentGuideDescriptor that = (WidgetIndentGuideDescriptor)o;

    if (endLine != that.endLine) return false;
    if (indentLevel != that.indentLevel) return false;
    if (startLine != that.startLine) return false;

    if (childLines == null || that.childLines == null) {
      return childLines == that.childLines;
    }

    if (childLines.size() != that.childLines.size()) {
      return false;
    }

    for (int i = 0; i < childLines.size(); ++i) {
      if (childLines.get(i).equals(that.childLines.get(i))) {
        return false;
      }
    }

    return true;
  }

  @Override
  public String toString() {
    // TODO(jacobr): add childLines.
    return String.format("%d (%d-%d-%d)", indentLevel, codeConstructStartLine, startLine, endLine);
  }

  public int compareTo(WidgetIndentGuideDescriptor that) {
    int answer = endLine - that.endLine;
    if (answer != 0) {
      return answer;
    }
    answer = indentLevel - that.indentLevel;
    if (answer != 0) {
      return answer;
    }
    answer = startLine - that.startLine;
    if (answer != 0) {
      return answer;
    }

    if (childLines == that.childLines) {
      return 0;
    }

    if (childLines == null || that.childLines == null) {
      return childLines == null ? -1 : 1;
    }

    answer = childLines.size() - that.childLines.size();

    if (answer != 0) {
      return answer;
    }

    for (int i = 0; i < childLines.size(); ++i) {
      answer = childLines.get(i).compareTo(that.childLines.get(i));
      if (answer != 0) {
        return answer;
      }
    }
    return 0;
  }

  public int getMaxX() {
    int indent = indentLevel;
    if (childLines != null) {
      for (OutlineLocation child: childLines) {
        indent = max(indent, child.getCurrentIndent());
      }
    }
    return indent;
  }
}
