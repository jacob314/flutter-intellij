/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.server.vmService;

import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import io.flutter.server.vmService.frame.DartVmServiceValue;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public class ChildrenListBuilder {
  private final XValueChildrenList childrenList;

  private final ArrayList<CompletableFuture<XNamedValue>> futures;

  public ChildrenListBuilder(int initialCapacity) {
    childrenList = new XValueChildrenList(initialCapacity);
    futures = new ArrayList<>(initialCapacity);
  }

  public void add(CompletableFuture<DartVmServiceValue> future) {
    // Placate JS type system that won't cast from CompletableFuture<DartVmServiceValue> to CompleteableFuture<XNamedValue>.
    // TODO(jacobr): is it better to static cast instead?
    futures.add(future.thenApply((v) -> v));
  }

  public void add(XNamedValue value) {
    futures.add(CompletableFuture.completedFuture(value));
  }

  public int size() {
    return futures.size();
  }
  /**
   * Adds all children to the node when they are available.
   */
  public CompletableFuture<?> build(@NotNull final XCompositeNode node, boolean last) {
    final CompletableFuture[] futuresArray = new CompletableFuture[futures.size()];
    futures.toArray(futuresArray);

    CompletableFuture<Void> allComplete = CompletableFuture.allOf(futuresArray);
    CompletableFuture<?> ret = new CompletableFuture<>();
    allComplete.whenCompleteAsync((v, t) -> {
      if (t != null) {
        ret.complete(null);
        return;
      }
      // XXX need to run on UI thread.
      for (CompletableFuture<XNamedValue> future : futures) {
        final XNamedValue value = future.getNow(null);
        if (value != null) {
          childrenList.add(value);
        }
      }
      node.addChildren(childrenList, true);
      ret.complete(null);
    });
    return ret;
  }

}
