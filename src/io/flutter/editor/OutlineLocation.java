/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.google.common.hash.HashCode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.DocumentEx;
import org.dartlang.analysis.server.protocol.FlutterOutline;

import static java.lang.Math.max;

public class OutlineLocation implements Comparable<OutlineLocation> {

  public OutlineLocation(FlutterOutline node, int line, int column, int indent) {
    this.line = line;
    this.column = column;
    // These asserts catch cases where the outline is based on inconsistent
    // state with the document.
    // TODO(jacobr): tweak values so if these errors occur they will not
    // cause exceptions to be thrown in release mode.
    assert(indent >= 0);
    assert(column >= 0);
    // It makes no sense for the indent of the line to be greater than the
    // indent of the actual widget.
    assert(column >= indent);
    assert(line >= 0);
    this.indent = indent;
    this.node = node;
    this.offset = node.getOffset();
    this.endOffset = node.getOffset() + node.getLength();
  }

  public void dispose() {
    if (marker != null) {
      marker.dispose();;
    }
  }

  public void createMarker(Document document) {
    if (indent > column) {
      System.out.println("XXX column and indent out of order");
    }
    final int delta = max(column - indent, 0);
    final int markerEnd = node.getOffset();
    // Create a range marker that goes from the start of the indent for the line
    // to the column of the actual entity;
    marker = document.createRangeMarker(markerEnd - delta, markerEnd + 1);
  }

  @Override
  public int hashCode() {
    int hashCode = line;
    hashCode = hashCode * 31 + column;
    hashCode = hashCode * 31 + indent;
    hashCode = hashCode * 31 + offset;
    return hashCode;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    final OutlineLocation other = (OutlineLocation) o;
    return line == other.line && column == other.column && indent == other.indent && offset == other.offset & getCurrentOffset() == other.getCurrentOffset();
  }

  /**
   * Offset in the document accurate even if the document has been edited.
   */
  public int getCurrentOffset() {
    if (marker == null) return offset;
    return marker.getStartOffset();
  }

  // Sometimes markers stop being valid in which case we need to stop
  // displaying the rendering until they are valid again.
  public boolean isValid() {
    return marker == null || marker.isValid();
  }

  public int getCurrentLine() {
    if (marker == null) return line;
    return marker.getDocument().getLineNumber(marker.getStartOffset());
  }

  public int getCurrentIndent() {
    if (marker == null) return indent;
    final Document document = marker.getDocument();
    final int currentOffset = marker.getStartOffset();
    final int currentLine = document.getLineNumber(currentOffset);
    return currentOffset - document.getLineStartOffset(currentLine);
  }

  /**
   * Line in the document this outline node is at.
   *
   * The line may be wrong if the document has been edited since this was computed.
   */
  private final int line;
  /**
   * Column this outline node is at.
   *
   * The Column may be wrong if the document has been edited since this was computed.
   */
  public final int column;
  /*
   * Indent of the line to use for line visualization.
   */
  private final int indent;

  public final int offset;
  public final int endOffset;

  // For debugging purposes only.
  FlutterOutline node;

  @Override
  public int compareTo(OutlineLocation o) {
    int delta = Integer.compare(line, o.line);
    if (delta != 0) return delta;
    delta = Integer.compare(column, o.column);
    if (delta != 0) return delta;
    return Integer.compare(indent, o.indent);
  }

  RangeMarker marker;
}
