/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.impl.XSourcePositionImpl;
import io.flutter.dart.FlutterDartAnalysisServer;
import io.flutter.inspector.DiagnosticsNode;
import net.miginfocom.swing.MigLayout;
import org.dartlang.analysis.server.protocol.FlutterWidgetProperty;

import java.awt.*;

public class PropertyEditorPanel extends JBPanel {
  final DiagnosticsNode node;
  private final XSourcePosition position;
  private final FlutterDartAnalysisServer flutterDartAnalysisService;

  public PropertyEditorPanel(DiagnosticsNode node, XSourcePosition p, FlutterDartAnalysisServer flutterDartAnalysisService) {
    super();
    this.node = node;
    this.position= node != null ? node.getCreationLocation().getXSourcePosition() : p;
    this.flutterDartAnalysisService = flutterDartAnalysisService;
    final java.util.List<FlutterWidgetProperty> properties = flutterDartAnalysisService.getWidgetDescription(position.getFile(), position.getOffset());

    MigLayout manager = new MigLayout("fill", // Layout Constraints
                                      "[]20[grow]", // Column constraints
                                      "");
    setLayout(manager);
    setBorder(JBUI.Borders.empty(5));
    if (properties != null && !properties.isEmpty()) {
      for (FlutterWidgetProperty property : properties) {
        add(new JBLabel(property.getName()), "right");
        add(new JBTextField(property.getExpression()), "wrap, growx");
      }
    } else {
      System.out.println("XXX no properties");
    }
/*      add(new JColorChooser(), "span");

    JBLabel label = new JBLabel("COLOR");
    add(label, "right");
    add(new JBTextField("#0FFFFF"), "wrap, growx");

     label = new JBLabel("Very long label");
    add(label, "right");
    add(new JBTextField("#099"), "wrap, growx");
    */

  }

  public static JBPopup showPopup(DiagnosticsNode node, XSourcePositionImpl position, FlutterDartAnalysisServer service, Point point) {
    PropertyEditorPanel panel =
      new PropertyEditorPanel(node, position, service);
    JBPopup popup = JBPopupFactory.getInstance()
      .createComponentPopupBuilder(panel, panel)
      .setMovable(true)
      .setAlpha(0.0f)
      .setMinSize(new Dimension(200, 5))
      .setTitle(panel.getDescription())
      .setCancelOnWindowDeactivation(true)
      .setRequestFocus(true)
      .createPopup();
    point = new Point(point);
    point.y += 20;
    popup.show(RelativePoint.fromScreen(point));
    return popup;
  }

  public String getDescription() {
    if (node != null) {
      return node.getDescription() + " Properties";
    } else {
      // XXX extract the name by guessing.
      return position.toString() + " Properties";
    }
  }
}
