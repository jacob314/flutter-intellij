/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;

public class StyledTextRange {
  StyledTextRange(TextRange range, TextAttributes attributes) {
    this.range = range;
    this.attributes = attributes;
  }
  TextAttributes attributes;
  TextRange range;
}
