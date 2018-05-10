package com.jetbrains.lang.dart.ide.runner.server.vmService.frame;

import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.jetbrains.lang.dart.ide.runner.server.vmService.FlutterVmServiceDebugProcess;
import com.jetbrains.lang.dart.ide.runner.server.vmService.IsolatesInfo;
import org.dartlang.vm.service.element.Frame;
import org.dartlang.vm.service.element.InstanceRef;
import org.dartlang.vm.service.element.IsolateRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FlutterVmServiceSuspendContext extends XSuspendContext {
  @NotNull private final FlutterVmServiceDebugProcess myDebugProcess;
  @NotNull private final FlutterVmServiceExecutionStack myActiveExecutionStack;

  private List<XExecutionStack> myExecutionStacks;
  private final boolean myAtAsyncSuspension;

  public FlutterVmServiceSuspendContext(@NotNull final FlutterVmServiceDebugProcess debugProcess,
                                        @NotNull final IsolateRef isolateRef,
                                        @NotNull final Frame topFrame,
                                        @Nullable final InstanceRef exception,
                                        boolean atAsyncSuspension) {
    myDebugProcess = debugProcess;
    myActiveExecutionStack = new FlutterVmServiceExecutionStack(debugProcess, isolateRef.getId(), isolateRef.getName(), topFrame, exception);
    myAtAsyncSuspension = atAsyncSuspension;
  }

  @NotNull
  @Override
  public XExecutionStack getActiveExecutionStack() {
    return myActiveExecutionStack;
  }

  public boolean getAtAsyncSuspension() {
    return myAtAsyncSuspension;
  }

  @Override
  public void computeExecutionStacks(@NotNull final XExecutionStackContainer container) {
    if (myExecutionStacks == null) {
      final Collection<IsolatesInfo.IsolateInfo> isolateInfos = myDebugProcess.getIsolateInfos();
      myExecutionStacks = new ArrayList<>(isolateInfos.size());
      for (IsolatesInfo.IsolateInfo isolateInfo : isolateInfos) {
        if (isolateInfo.getIsolateId().equals(myActiveExecutionStack.getIsolateId())) {
          myExecutionStacks.add(myActiveExecutionStack);
        }
        else {
          myExecutionStacks
            .add(new FlutterVmServiceExecutionStack(myDebugProcess, isolateInfo.getIsolateId(), isolateInfo.getIsolateName(), null, null));
        }
      }
    }

    container.addExecutionStack(myExecutionStacks, true);
  }
}
