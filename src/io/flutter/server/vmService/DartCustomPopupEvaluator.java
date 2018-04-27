package io.flutter.server.vmService;

import com.intellij.debugger.engine.JavaValue;
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.xdebugger.frame.XFullValueEvaluator;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import io.flutter.run.daemon.FlutterApp;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

public abstract class DartCustomPopupEvaluator<T> extends XFullValueEvaluator {
  public DartCustomPopupEvaluator(@NotNull String linkText, DartVmServiceDebugProcess debugProcess) {
    super(linkText);
    setShowValuePopup(false);
    this.debugProcess = debugProcess;
  }

  private final DartVmServiceDebugProcess debugProcess;
  protected abstract CompletableFuture<T> getData();

  protected abstract JComponent createComponent(T data);

  public void evaluate(@NotNull final XFullValueEvaluationCallback callback) {
    CompletableFuture<T> future = getData();
    if (future == null) {
      future = CompletableFuture.completedFuture(null);
    }
    future.whenCompleteAsync((data, t) -> {
      DebuggerUIUtil.invokeLater(() -> {
        if (callback.isObsolete()) return;
        final JComponent comp = createComponent(data);
        if (comp == null) {
          return;
        }
        Project project = debugProcess.getApp().getProject();
        JBPopup popup = DebuggerUIUtil.createValuePopup(project, comp, null);
        JFrame frame = WindowManager.getInstance().getFrame(project);
        Dimension frameSize = frame.getSize();

        Dimension size = comp.getPreferredSize();

/// XXX        Dimension size = new Dimension(frameSize.width / 2, frameSize.height / 2);
        popup.setSize(size);
        if (comp instanceof Disposable) {
          Disposer.register(popup, (Disposable)comp);
        }
        callback.evaluated("");
        popup.show(new RelativePoint(frame, new Point(size.width / 2, size.height / 2)));
      });
    });
  }

  @Override
  public void startEvaluation(@NotNull XFullValueEvaluationCallback callback) {
    evaluate(callback);
  }
}