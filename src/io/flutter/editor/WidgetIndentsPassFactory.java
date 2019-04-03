// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package io.flutter.editor;

import com.intellij.openapi.editor.colors.EditorFontCache;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.intellij.codeHighlighting.Pass;
import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.*;
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.options.FontSize;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import io.flutter.FlutterUtils;
import io.flutter.dart.FlutterDartAnalysisServer;
import io.flutter.dart.FlutterOutlineListener;
import io.flutter.settings.FlutterSettings;
import org.dartlang.analysis.server.protocol.*;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.*;
import java.util.List;

import static com.intellij.util.TimeoutUtil.sleep;

class FontPreferencesImplLigatures extends  FontPreferencesImpl {
  @Override
  public void setUseLigatures(boolean useLigatures) {
    useLigatures = true;
  }

  @Override
  public boolean useLigatures() {
    return true;
  }
}

public class WidgetIndentsPassFactory implements TextEditorHighlightingPassFactory {
  private final Project myProject;
  public static boolean registered = false;
  private final FlutterDartAnalysisServer flutterDartAnalysisService;

  private static boolean SIMULATE_SLOW_ANALYSIS_UPDATES = false;

  private final Map<String, FlutterOutline> currentOutlines;
  private final Map<EditorEx, WidgetIndentsPass> passes;
  private final SetMultimap<String, EditorEx> editorsForFile;
  private final Map<String, FlutterOutlineListener> outlineListeners = new HashMap<>();

  private static final String FLUTTER_FONT = "Flutconsolata";
  private boolean isShowMultipleChildrenGuides;
  private boolean isShowBuildMethodGuides;
  private boolean isGreyUnimportantProperties;
  private boolean isUseLigaturesFont;
  private boolean isShowDashedLineGuides;
  private boolean isSimpleIndentIntersectionMode;

  private void syncSettings(FlutterSettings settings) {
    isShowBuildMethodGuides = settings.isShowBuildMethodGuides();
    isShowMultipleChildrenGuides = settings.isShowMultipleChildrenGuides() && isShowBuildMethodGuides;
    isGreyUnimportantProperties = settings.isGreyUnimportantProperties() && isShowBuildMethodGuides;
    isUseLigaturesFont = settings.isUseLigaturesFont() && isShowBuildMethodGuides;
    isShowDashedLineGuides = settings.isShowDashedLineGuides() && isShowBuildMethodGuides;
    isSimpleIndentIntersectionMode = settings.isSimpleIndentIntersectionMode() && isShowBuildMethodGuides;
  }

  public WidgetIndentsPassFactory(Project project) { //, TextEditorHighlightingPassRegistrar highlightingPassRegistrar) {
    myProject = project;
    final TextEditorHighlightingPassRegistrar highlightingPassRegistrar = TextEditorHighlightingPassRegistrar.getInstance(project);
    //      TextEditorHighlightingPassRegistrarEx registrar = (TextEditorHighlightingPassRegistrarEx) TextEditorHighlightingPassRegistrar.getInstance(project);
    highlightingPassRegistrar
      .registerTextEditorHighlightingPass(this, TextEditorHighlightingPassRegistrar.Anchor.AFTER, Pass.UPDATE_FOLDING, false, false);
    registered = true;
    currentOutlines = new HashMap<>();
    passes = new HashMap<>();
    editorsForFile = HashMultimap.create();
    flutterDartAnalysisService = FlutterDartAnalysisServer.getInstance(project);

    syncSettings(FlutterSettings.getInstance());

    final FlutterSettings.Listener settingsListener = () -> {
      final FlutterSettings settings = FlutterSettings.getInstance();
      if (isShowBuildMethodGuides == settings.isShowBuildMethodGuides() &&
          isShowMultipleChildrenGuides == settings.isShowMultipleChildrenGuides() &&
          isGreyUnimportantProperties == settings.isGreyUnimportantProperties() &&
          isUseLigaturesFont == settings.isUseLigaturesFont() &&
          isShowDashedLineGuides == settings.isShowDashedLineGuides() &&
          isSimpleIndentIntersectionMode == settings.isSimpleIndentIntersectionMode()) {
        // Change doesn't matter for us.
        return;
      }
      syncSettings(settings);

      for (WidgetIndentsPass pass : passes.values()) {
        if (!pass.getEditor().isDisposed()) {
          pass.onSettingsChanged();
        }
      }
      for (EditorEx editor : editorsForFile.values()) {
        if (!editor.isDisposed()) {
          updateEditorSettings(editor);
          // Avoid rendering artfacts when settings were changed that only impact rendering.
          editor.repaint(0, editor.getDocument().getTextLength());
        }
      }
    };
    FlutterSettings.getInstance().addListener(settingsListener);

    /*
    EditorEventMulticaster eventMulticaster = EditorFactory.getInstance().getEventMulticaster();
    eventMulticaster.addCaretListener(new CaretListener() {
      @Override
      public void caretPositionChanged(@NotNull CaretEvent event) {

      }
    }, null);
    */
  }

  static boolean MESS_WITH_COLOR_SCHEME = false;
  private void updateEditorSettings(EditorEx editor) {
    editor.getSettings().setIndentGuidesShown(!isShowBuildMethodGuides);
    if (MESS_WITH_COLOR_SCHEME) {
      final ModifiableFontPreferences myFontPreferences = new FontPreferencesImplLigatures();
      editor.getColorsScheme().getFontPreferences().copyTo(myFontPreferences);

      myFontPreferences.setUseLigatures(isUseLigaturesFont);
      if (isUseLigaturesFont) {
        EditorFontCache fontCache = EditorFontCache.getInstance();
        fontCache.reset();
        final ArrayList<String> effectiveFonts = new ArrayList<>(myFontPreferences.getEffectiveFontFamilies());
        final ArrayList<String> realFonts = new ArrayList<>(myFontPreferences.getRealFontFamilies());
        if (effectiveFonts.isEmpty() || !effectiveFonts.get(0).equals(FLUTTER_FONT)) {
          effectiveFonts.add(0, FLUTTER_FONT);
        }
        if (realFonts.isEmpty() || !realFonts.get(0).equals(FLUTTER_FONT)) {
          realFonts.add(0, FLUTTER_FONT);
        }
        myFontPreferences.setEffectiveFontFamilies(effectiveFonts);
        myFontPreferences.setRealFontFamilies(realFonts);
        myFontPreferences.setFontSize(FLUTTER_FONT, myFontPreferences.getSize(FLUTTER_FONT));
        final EditorColorsScheme baseScheme = EditorColorsManager.getInstance().getSchemeForCurrentUITheme();

        // Lock my font preferences.
        final EditorColorsScheme scheme = new EditorColorsScheme() {
          @Override
          public void setName(String s) {
            baseScheme.setName(s);
          }

          @Override
          public void setAttributes(@NotNull TextAttributesKey key, TextAttributes attributes) {
            baseScheme.setAttributes(key, attributes);
          }

          @NotNull
          @Override
          public Color getDefaultBackground() {
            return baseScheme.getDefaultBackground();
          }

          @NotNull
          @Override
          public Color getDefaultForeground() {
            return baseScheme.getDefaultBackground();
          }

          @Nullable
          @Override
          public Color getColor(ColorKey key) {
            return baseScheme.getColor(key);
          }

          @Override
          public void setColor(ColorKey key, Color color) {
            baseScheme.setColor(key, color);
          }

          @NotNull
          @Override
          public FontPreferences getFontPreferences() {
            return myFontPreferences;
          }

          @Override
          public void setFontPreferences(@NotNull FontPreferences preferences) {
            // ignored..
            System.out.println("XXX tried to set fonts");
          }

          @Override
          public String getEditorFontName() {
            return FLUTTER_FONT;
          }

          @Override
          public void setEditorFontName(String s) {
            System.out.println("XXX tried to set font name");
          }

          @Override
          public int getEditorFontSize() {
            return baseScheme.getEditorFontSize();
          }

          @Override
          public void setEditorFontSize(int i) {
            baseScheme.setEditorFontSize(i);
          }

          @Override
          public FontSize getQuickDocFontSize() {
            return baseScheme.getQuickDocFontSize();
          }

          @Override
          public void setQuickDocFontSize(@NotNull FontSize size) {
            baseScheme.setQuickDocFontSize(size);
          }

          @NotNull
          @Override
          public Font getFont(EditorFontType type) {
            return baseScheme.getFont(type);
          }

          @Override
          public void setFont(EditorFontType type, Font font) {
            baseScheme.setFont(type, font);
          }

          @Override
          public float getLineSpacing() {
            return baseScheme.getLineSpacing();
          }

          @Override
          public void setLineSpacing(float v) {
            baseScheme.setLineSpacing(v);
          }

          @NotNull
          @Override
          public FontPreferences getConsoleFontPreferences() {
            return baseScheme.getConsoleFontPreferences();
          }

          @Override
          public void setConsoleFontPreferences(@NotNull FontPreferences preferences) {
            baseScheme.setConsoleFontPreferences(preferences);
          }

          @Override
          public String getConsoleFontName() {
            return baseScheme.getConsoleFontName();
          }

          @Override
          public void setConsoleFontName(String s) {
            baseScheme.setConsoleFontName(s);
          }

          @Override
          public int getConsoleFontSize() {
            return baseScheme.getConsoleFontSize();
          }

          @Override
          public void setConsoleFontSize(int i) {
            baseScheme.setConsoleFontSize(i);
          }

          @Override
          public float getConsoleLineSpacing() {
            return baseScheme.getConsoleLineSpacing();
          }

          @Override
          public void setConsoleLineSpacing(float v) {
            baseScheme.setConsoleLineSpacing(v);
          }

          @Override
          public void readExternal(Element element) {
            baseScheme.readExternal(element);
          }

          @Override
          public TextAttributes getAttributes(TextAttributesKey key) {
            return baseScheme.getAttributes(key);
          }

          @NotNull
          @Override
          public String getName() {
            return baseScheme.getName();
          }

          @NotNull
          @Override
          public Properties getMetaProperties() {
            return baseScheme.getMetaProperties();
          }

          @Override
          public Object clone() {
            return null;
          }
        };

        scheme.setEditorFontName(FLUTTER_FONT);
        //      final DelegateColorScheme delegateScheme =editor.getColorsScheme().clone();
        final String name = editor.getColorsScheme().getEditorFontName();
        editor.setColorsScheme(scheme);
      }
      else {
        editor.setColorsScheme(EditorColorsManager.getInstance().getSchemeForCurrentUITheme());

        // TODO(jacobr): swap the user back to their regular font?
      }
    }
  }

  private static class PlaceholderHighlightingPass extends TextEditorHighlightingPass {
    PlaceholderHighlightingPass(Project project, Document document, boolean isRunIntentionPassAfter) {
      super(project, document, isRunIntentionPassAfter);
    }

    public void doCollectInformation(@NotNull ProgressIndicator indicator) {
    }

    @Override
    public void doApplyInformationToEditor() {
    }
  }

  @Override
  @NotNull
  public TextEditorHighlightingPass createHighlightingPass(@NotNull PsiFile file, @NotNull final Editor e) {
    final EditorEx editor = (EditorEx)e;
    VirtualFile virtualFile = editor.getVirtualFile();
    final String path = virtualFile.getPath();

    if (!FlutterSettings.getInstance().isShowBuildMethodGuides() || !FlutterUtils.isDartFile(virtualFile)) {
      final WidgetIndentsPass existingPass = passes.get(editor);
      if (existingPass != null) {
        existingPass.dispose();
        passes.remove(editor);
      }
      editorsForFile.remove(path, editor);
      if (!editorsForFile.containsKey(path)) {
        // Removed the last listener for this file.
        final FlutterOutlineListener listener = outlineListeners.remove(path);
        if (listener != null) {
          // TODO(jacobr): not the only time we should remove outline listeners.
          flutterDartAnalysisService.removeOutlineListener(path, listener);
        }
      }

      // Return a placeholder editor highlighting pass.
      return new PlaceholderHighlightingPass(myProject, editor.getDocument(), false);
    }

    // XXX this shouldn't be needed.
    /*
    if (editor.getSettings().isIndentGuidesShown()) {
      // The regular indent guide ui conflicts with this rendering so we need
      // to disable it and duplicate the regular indent guide ui for all cases
      // where we are not inside a Flutter build method.
      editor.getSettings().setIndentGuidesShown(false);
    }*/

    synchronized (passes) {
      if (!editorsForFile.containsKey(path)) {
       final FlutterOutlineListener listener =
          (filePath, outline, instrumentedCode) -> {
            if (SIMULATE_SLOW_ANALYSIS_UPDATES) {
              sleep(3000);
              // Simulate slow UI. TODO(jacobr): we appear to be delaying all
              // analysis by doing this. Instead we should just delay a few
              // seconds before handling this message.
            }
            ApplicationManager.getApplication().invokeLater(() -> {
              final List<WidgetIndentsPass> passesForFile = new ArrayList<>();
              synchronized (passes) {
                currentOutlines.put(path, outline);
                for (EditorEx candidate : editorsForFile.get(path)) {
                  if (!candidate.isDisposed() && Objects.equals(candidate.getVirtualFile().getCanonicalPath(), path)) {
                    if (candidate.getDocument().getTextLength() != outline.getLength()) {
                      // Outline is out of date. That is ok. Ignore it for now.
                      // An up to date outline will arrive shortly. Showing an
                      // outline from data inconsistent with the current
                      // content will show annoying flicker. It is better to
                      // instead
                      continue;
                    }
                    passesForFile.add(passes.get(candidate));
                  }
                }
              }
              for (WidgetIndentsPass pass : passesForFile) {
                pass.setOutline(outline);
              }
            });
          };
        outlineListeners.put(path, listener);

        flutterDartAnalysisService.addOutlineListener(FileUtil.toSystemDependentName(path), listener);
      }
    }
    final FlutterOutline outline;
    final WidgetIndentsPass pass = new WidgetIndentsPass(myProject, (EditorEx)editor, file);

    synchronized (passes) {
      outline = currentOutlines.get(path);

      if (!editorsForFile.containsEntry(path, editor)) {
        updateEditorSettings(editor);
        editorsForFile.put(path, (EditorEx)editor);
        if (outline != null) {
          pass.setOutline(outline);
        }
      }
      passes.put((EditorEx)editor, pass);
    }
    return pass;
  }
}
