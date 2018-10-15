/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.openapi.Disposable;
import io.flutter.perf.FlutterWidgetPerfManager;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.utils.EventStream;
import io.flutter.utils.StreamSubscription;

import javax.swing.*;

public class ExtensionCommandCheckbox implements Disposable {

  private final EventStream<Boolean> currentValue;
  private StreamSubscription<Boolean> currentValueSubscription;

  private final JCheckBox checkbox;

  ExtensionCommandCheckbox(FlutterApp app, String extensionCommand, String label, String tooltip) {
    checkbox = new JCheckBox(label);
    checkbox.setHorizontalAlignment(JLabel.LEFT);
    checkbox.setToolTipText(tooltip);
    currentValue = app.getVMServiceManager().getServiceExtensionState(extensionCommand);
    app.hasServiceExtension(extensionCommand, (enabled) -> {
      checkbox.setEnabled(enabled);
    }, this);

    app.hasServiceExtension(extensionCommand, checkbox::setEnabled, this);

    checkbox.addChangeListener((l) -> {
      final boolean newValue = checkbox.isSelected();
      currentValue.setValue(newValue);
      if (app.isSessionActive()) {
        app.callBooleanExtension(extensionCommand, newValue);
      }
    });

    currentValueSubscription = currentValue.listen(checkbox::setSelected, true);
  }

  JCheckBox getComponent() {
    return checkbox;
  }

  @Override
  public void dispose() {
    if (currentValueSubscription != null) {
      currentValueSubscription.dispose();;
      currentValueSubscription = null;
    }
  }
}
