/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import io.flutter.run.daemon.FlutterApp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Model shared by all line highlighters rendering perf information for a single file.
 */
public interface FilePerfModel {
  @NotNull
  FilePerfInfo getStats();

  @NotNull
  TextEditor getTextEditor();

  FlutterApp getApp();

  boolean isHoveredOverLineMarkerArea();

  @NotNull
  String getWidgetName(int line, int column);

  void markAppIdle();
}
