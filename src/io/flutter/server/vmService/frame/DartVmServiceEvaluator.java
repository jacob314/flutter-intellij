package io.flutter.server.vmService.frame;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.ExpressionInfo;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XValue;
import io.flutter.inspector.InspectorService;
import io.flutter.server.vmService.DartVmServiceDebugProcess;
import com.jetbrains.lang.dart.psi.*;
import com.jetbrains.lang.dart.util.DartResolveUtil;
import gnu.trove.THashSet;
import io.flutter.utils.AsyncUtils;
import io.flutter.view.FlutterView;
import org.dartlang.vm.service.consumer.GetObjectConsumer;
import org.dartlang.vm.service.element.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DartVmServiceEvaluator extends XDebuggerEvaluator {
  private static final Pattern ERROR_PATTERN = Pattern.compile("Error:.* line \\d+ pos \\d+: (.+)");

  @NotNull protected final DartVmServiceDebugProcess myDebugProcess;

  public DartVmServiceEvaluator(@NotNull final DartVmServiceDebugProcess debugProcess) {
    myDebugProcess = debugProcess;
  }

  @Override
  public void evaluate(@NotNull final String expression,
                       @NotNull final XEvaluationCallback callback,
                       @Nullable final XSourcePosition expressionPosition) {
    final String isolateId = myDebugProcess.getCurrentIsolateId();
    final Project project = myDebugProcess.getSession().getProject();
    final FileEditorManager manager = FileEditorManager.getInstance(project);
    final List<VirtualFile> libraryFiles = new ArrayList<>();
    if (isolateId == null) {
      callback.errorOccurred("No running isolate.");
      return;
    }
    // Turn off pausing on exceptions as it is confusing to mouse over an expression
    // and to have that trigger pausing at an exception.
    if (myDebugProcess == null || myDebugProcess.getVmServiceWrapper() == null) {
      callback.errorOccurred("Device disconnected");
      return;
    }
    myDebugProcess.getVmServiceWrapper().setExceptionPauseMode(ExceptionPauseMode.None);
    final XEvaluationCallback wrappedCallback = new XEvaluationCallback() {
      @Override
      public void evaluated(@NotNull XValue result) {
        myDebugProcess.getVmServiceWrapper().setExceptionPauseMode(myDebugProcess.getBreakOnExceptionMode());
        callback.evaluated(result);
      }

      @Override
      public void errorOccurred(@NotNull String errorMessage) {
        myDebugProcess.getVmServiceWrapper().setExceptionPauseMode(myDebugProcess.getBreakOnExceptionMode());
        callback.errorOccurred(errorMessage);
      }
    };
    final PsiElement element = findElement(expressionPosition, project, manager);
    PsiFile psiFile = element != null ? element.getContainingFile() : null;

    if (psiFile != null) {
      libraryFiles.addAll(DartResolveUtil.findLibrary(psiFile));
    }
    final DartClass dartClass = element != null ? PsiTreeUtil.getParentOfType(element, DartClass.class) : null;
    final String dartClassName = dartClass != null ? dartClass.getName() : null;

    myDebugProcess.getVmServiceWrapper().getCachedIsolate(isolateId).whenComplete((isolate, error) -> {
      if (error != null) {
        wrappedCallback.errorOccurred(error.getMessage());
        return;
      }
      if (isolate == null) {
        wrappedCallback.errorOccurred("No running isolate.");
        return;
      }

      LibraryRef libraryRef = findMatchingLibrary(isolate, libraryFiles);
      if (psiFile != null && element != null) {
        final String rawText = psiFile.getText();
        final int startOffset = element.getTextOffset();
        final int endOffset =  startOffset + expression.length();
        final InspectorService service = myDebugProcess.getApp().getInspectorService().getNow(null);
        // Test if the user is trying to evaluate text that exactly matches
        // code in the Dart source file. If there is an existing Element in
        // the running application that matches the widget we should show it.
        if (service != null && endOffset <= rawText.length() && rawText.substring(startOffset, endOffset).equals(expression)) {
          final Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
          if (document != null) {

            // No need to dispose this group as no persistent references are created.
            InspectorService.ObjectGroup group = service.createObjectGroup("temporary");

            final int startLine = document.getLineNumber(startOffset);
            final int startColumn = startOffset - document.getLineStartOffset(startLine);
            final int endLine = document.getLineNumber(endOffset);
            final int endColumn = endOffset - document.getLineStartOffset(endLine);

            CompletableFuture<InstanceRef> refFuture = group
              .findMatchingElementsForSourceLocation(psiFile.getVirtualFile().getPath(), startLine, startColumn, endLine, endColumn);
            AsyncUtils.whenCompleteUiThread(refFuture, (instanceRef, t) -> {
              if (t != null || instanceRef == null || instanceRef.getKind() == InstanceKind.Null) {
                // Fallback to regular expression evaluation if there is not a
                // matching widget.
                evaluateHelper(expression, isolateId, wrappedCallback, dartClassName, libraryRef);
                return;
              }
              // TODO(jacobr): we need an option in the inspector to control
              // whether setting the selection to match the current hovered
              // widget is optional.
              if (FlutterView.isActive(project)) {
                // If the inspector is active, set its selection to this widget.
                group.setSelection(instanceRef, false, false);
              }
              myDebugProcess.getVmServiceWrapper().createVmServiceValue(
                isolateId, "widgetFromRunningApp", instanceRef, null, null, false, expression).whenCompleteAsync(
                (v, ignored) -> {
                  callback.evaluated(v);
                }
              );
            });
          }
          return;
        }
      }

      evaluateHelper(expression, isolateId, wrappedCallback, dartClassName, libraryRef);
    });
  }

  private void evaluateHelper(@NotNull String expression,
                              String isolateId,
                              XEvaluationCallback wrappedCallback,
                              String dartClassName, LibraryRef libraryRef) {
    if (dartClassName != null) {
      myDebugProcess.getVmServiceWrapper().getObject(isolateId, libraryRef.getId(), new GetObjectConsumer() {

        @Override
        public void onError(RPCError error) {
          wrappedCallback.errorOccurred(error.getMessage());
        }

        @Override
        public void received(Obj response) {
          Library library = (Library)response;
          for (ClassRef classRef : library.getClasses()) {
            if (classRef.getName().equals(dartClassName)) {
              myDebugProcess.getVmServiceWrapper().evaluateInTargetContext(isolateId, classRef.getId(), expression, wrappedCallback);
              return;
            }
          }

          // Class not found so just use the library.
          myDebugProcess.getVmServiceWrapper().evaluateInTargetContext(isolateId, libraryRef.getId(), expression, wrappedCallback);
        }

        @Override
        public void received(Sentinel response) {
          wrappedCallback.errorOccurred(response.getValueAsString());
        }
      });
    }
    else {
      myDebugProcess.getVmServiceWrapper().evaluateInTargetContext(isolateId, libraryRef.getId(), expression, wrappedCallback);
    }
  }

  private PsiElement findElement(XSourcePosition expressionPosition,
                                 Project project,
                                 FileEditorManager manager) {
    PsiFile psiFile;
    if (expressionPosition != null) {
      psiFile = PsiManager.getInstance(project).findFile(expressionPosition.getFile());
      if (psiFile != null) {
        return psiFile.findElementAt(expressionPosition.getOffset());
      }
    }
    else {
      // TODO(jacobr): we could use the most recently selected Dart file instead
      // of using the selected file.
      final Editor editor = manager.getSelectedTextEditor();
      if (editor instanceof TextEditor) {
        final TextEditor textEditor = (TextEditor)editor;
        final FileEditorLocation fileEditorLocation = textEditor.getCurrentLocation();
        final VirtualFile virtualFile = textEditor.getFile();
        if (virtualFile != null) {
          psiFile = PsiManager.getInstance(project).findFile(virtualFile);
          if (psiFile != null && fileEditorLocation instanceof TextEditorLocation) {
            TextEditorLocation textEditorLocation = (TextEditorLocation)fileEditorLocation;
            return psiFile.findElementAt(textEditor.getEditor().logicalPositionToOffset(textEditorLocation.getPosition()));
          }
        }
      }
    }
    return null;
  }

  private LibraryRef findMatchingLibrary(Isolate isolate, List<VirtualFile> libraryFiles) {
    if (libraryFiles != null && !libraryFiles.isEmpty()) {
      Set<String> uris = new THashSet<>();

      for (VirtualFile libraryFile : libraryFiles) {
        uris.addAll(myDebugProcess.getUrisForFile(libraryFile));
      }

      for (LibraryRef library : isolate.getLibraries()) {
        if (uris.contains(library.getUri())) {
          return library;
        }
      }
    }
    return isolate.getRootLib();
  }

  @Nullable
  @Override
  public ExpressionInfo getExpressionInfoAtOffset(@NotNull final Project project,
                                                  @NotNull final Document document,
                                                  final int offset,
                                                  final boolean sideEffectsAllowed) {
    final PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
    final PsiElement contextElement = psiFile == null ? null : psiFile.findElementAt(offset);
    return contextElement == null ? null : getExpressionInfo(contextElement);
  }

  @NotNull
  public static String getPresentableError(@NotNull final String rawError) {
    //Error: Unhandled exception:
    //No top-level getter 'foo' declared.
    //
    //NoSuchMethodError: method not found: 'foo'
    //Receiver: top-level
    //Arguments: [...]
    //#0      NoSuchMethodError._throwNew (dart:core-patch/errors_patch.dart:176)
    //#1      _startIsolate.<anonymous closure> (dart:isolate-patch/isolate_patch.dart:260)
    //#2      _RawReceivePortImpl._handleMessage (dart:isolate-patch/isolate_patch.dart:142)

    //Error: '': error: line 1 pos 9: receiver 'this' is not in scope
    //() => 1+this.foo();
    //        ^
    final List<String> lines = StringUtil.split(StringUtil.convertLineSeparators(rawError), "\n");

    if (!lines.isEmpty()) {
      if ((lines.get(0).equals("Error: Unhandled exception:") || lines.get(0).equals("Unhandled exception:")) && lines.size() > 1) {
        return lines.get(1);
      }
      final Matcher matcher = ERROR_PATTERN.matcher(lines.get(0));
      if (matcher.find()) {
        return matcher.group(1);
      }
    }

    return "Cannot evaluate";
  }

  @Nullable
  public static ExpressionInfo getExpressionInfo(@NotNull final PsiElement contextElement) {
    // todo if sideEffectsAllowed return method call like "foo()", not only "foo"
    /* WEB-11715
     dart psi: notes.text

     REFERENCE_EXPRESSION
     REFERENCE_EXPRESSION "notes"
     PsiElement(.) "."
     REFERENCE_EXPRESSION "text"
     */
    // find topmost reference, but stop if argument list found
    DartReference reference = null;
    PsiElement element = contextElement;
    while (true) {
      if (element instanceof DartReference) {
        reference = (DartReference)element;
      }
      if (element instanceof DartNewExpression) {
        break;
      }

      element = element.getParent();
      if (element == null ||
          // int.parse(slider.value) - we must return reference expression "slider.value", but not the whole expression
          element instanceof DartArgumentList ||
          // "${seeds} seeds" - we must return only "seeds"
          element instanceof DartLongTemplateEntry ||
          element instanceof DartCallExpression ||
          element instanceof DartFunctionBody || element instanceof IDartBlock) {
        break;
      }
    }

    if (reference != null) {
      if (reference instanceof DartNewExpression) {
        // We don't want to evaluate Dart constructors so we just want the
        // actual class being constructed as that will also match the location
        PsiElement typeElement = reference.getLastChild().getPrevSibling();
        return new ExpressionInfo(typeElement.getTextRange(), typeElement.getText(), "class " + typeElement.getText());
      } else {
        TextRange textRange = reference.getTextRange();
        // note<CURSOR>s.text - the whole reference expression is notes.txt, but we must return only notes
        int endOffset = contextElement.getTextRange().getEndOffset();
        if (textRange.getEndOffset() != endOffset) {
          textRange = new TextRange(textRange.getStartOffset(), endOffset);
        }
        return new ExpressionInfo(textRange);
      }
    }

    PsiElement parent = contextElement.getParent();
    return parent instanceof DartId ? new ExpressionInfo(parent.getTextRange()) : null;
  }
}
