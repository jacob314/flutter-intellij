/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.google.common.base.Joiner;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColorChooserService;
import com.intellij.ui.ColorPickerListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.PositionTracker;
import com.jetbrains.lang.dart.assists.AssistUtils;
import com.jetbrains.lang.dart.assists.DartSourceEditException;
import io.flutter.FlutterMessages;
import io.flutter.dart.FlutterDartAnalysisServer;
import io.flutter.hotui.StableWidgetTracker;
import io.flutter.inspector.DiagnosticsNode;
import io.flutter.inspector.InspectorService;
import io.flutter.preview.PreviewView;
import io.flutter.preview.WidgetEditToolbar;
import io.flutter.run.FlutterReloadManager;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.utils.ColorIconMaker;
import io.flutter.utils.EventStream;
import io.flutter.view.ColorPicker;
import net.miginfocom.swing.MigLayout;
import org.dartlang.analysis.server.protocol.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.*;

class EnumValueWrapper {
  final FlutterWidgetPropertyValueEnumItem item;
  final String expression;

  public EnumValueWrapper(FlutterWidgetPropertyValueEnumItem item) {
    this.item = item;
    this.expression = item.getName();
    assert (this.expression != null);
  }

  public EnumValueWrapper(String expression) {
    this.expression = expression;
    item = null;
  }

  @Override
  public String toString() {
    if (expression != null) {
      return expression;
    }
    return item != null ? item.getName() : "[null]";
  }
}

class PropertyEnumComboBoxModel extends AbstractListModel<EnumValueWrapper>
  implements ComboBoxModel<EnumValueWrapper> {
  private final List<EnumValueWrapper> myList;
  private EnumValueWrapper mySelected;
  private String expression;

  public PropertyEnumComboBoxModel(FlutterWidgetProperty property) {
    final FlutterWidgetPropertyEditor editor = property.getEditor();
    assert (editor != null);
    myList = new ArrayList<>();
    for (FlutterWidgetPropertyValueEnumItem item : editor.getEnumItems()) {
      myList.add(new EnumValueWrapper(item));
    }
    String expression = property.getExpression();
    if (expression == null) {
      mySelected = null;
      expression = "";
      return;
    }
    if (property.getValue() != null) {
      FlutterWidgetPropertyValue value = property.getValue();
      FlutterWidgetPropertyValueEnumItem enumValue = value.getEnumValue();
      if (enumValue != null) {
        for (EnumValueWrapper e : myList) {
          if (e != null && e.item != null && Objects.equals(e.item.getName(), enumValue.getName())) {
            mySelected = e;
          }
        }
      }
    }
    else {
      final EnumValueWrapper newItem = new EnumValueWrapper(expression);
      myList.add(newItem);
      mySelected = newItem;
    }
    final String kind = editor.getKind();
  }

  @Override
  public int getSize() {
    return myList.size();
  }

  @Override
  public EnumValueWrapper getElementAt(int index) {
    return myList.get(index);
  }

  @Override
  public EnumValueWrapper getSelectedItem() {
    return mySelected;
  }

  @Override
  public void setSelectedItem(Object item) {
    if (item instanceof String) {
      String expression = (String)item;
      for (EnumValueWrapper e : myList) {
        if (Objects.equals(e.expression, expression)) {
          mySelected = e;
          return;
        }
      }
      EnumValueWrapper wrapper = new EnumValueWrapper(expression);
      myList.add(wrapper);
      this.fireIntervalAdded(this, myList.size() - 1, myList.size());
      setSelectedItem(wrapper);
      return;
    }
    setSelectedItem((EnumValueWrapper)item);
  }

  public void setSelectedItem(EnumValueWrapper item) {
    mySelected = item;
    fireContentsChanged(this, 0, getSize());
  }
}

public class PropertyEditorPanel extends SimpleToolWindowPanel implements Disposable {
  final DiagnosticsNode node;
  private final InspectorService.Location position;
  private final FlutterDartAnalysisServer flutterDartAnalysisService;
  private final Project project;
  private final InspectorService inspectorService;
  private final JBLabel descriptionLabel;
  private final WidgetEditToolbar widgetEditToolbar;
  private JBPopup popup;

  private java.util.List<FlutterWidgetProperty> properties;
  private Map<String, JComponent> fields = new HashMap<>();
  private Map<String, FlutterWidgetProperty> propertyMap = new HashMap<>();
  final StableWidgetTracker tracker;
  private FlutterOutline outline;
  /// XXX remove?
  private Color widgetColor;

  int getOffset() {
    return tracker.isValid() ? tracker.getOffset() : position.getOffset();
  }

  void lookupWidgetDescription() {
    properties =
      flutterDartAnalysisService.getWidgetDescription(position.getFile(), getOffset());
    propertyMap.clear();
    if (properties != null) {
      for (FlutterWidgetProperty property : properties) {
        final String name = property.getName();
        propertyMap.put(name, property);
      }
    }
    final String description = getDescription();
    if (!description.isEmpty()) {
      descriptionLabel.setText(description);
    }
  }

  public void outlinesChanged(List<FlutterOutline> outlines) {
    final FlutterOutline nextOutline = outlines.isEmpty() ? null : outlines.get(0);
    if (nextOutline == outline) return;
    this.outline = nextOutline;
    lookupWidgetDescription();
    // TODO(jacobr): update widget properties if the values have changed.
  }

  public PropertyEditorPanel(@Nullable InspectorService inspectorService,
                             Project project,
                             DiagnosticsNode node,
                             InspectorService.Location location,
                             FlutterDartAnalysisServer flutterDartAnalysisService) {
    super(true, true);
    this.inspectorService = inspectorService;
    this.project = project;
    this.node = node;
    this.position = location;
    this.flutterDartAnalysisService = flutterDartAnalysisService;
    descriptionLabel = new JBLabel();
    tracker = new StableWidgetTracker(location, flutterDartAnalysisService, inspectorService, project, this);
    tracker.getCurrentOutlines().listen(this::outlinesChanged, true);

    final EventStream<VirtualFile> activeFile = new EventStream<>();
    activeFile.setValue(location.getFile());
    widgetEditToolbar = new WidgetEditToolbar(true, tracker.getCurrentOutlines(), activeFile, project, flutterDartAnalysisService);


    lookupWidgetDescription();

    final MigLayout manager = new MigLayout("insets 0", // Layout Constraints
                                            "[::120]5[:150:400]", // Column constraints
                                            "[]0[]");
    setLayout(manager);

    add(descriptionLabel, "span, growx");
    int added = 0;
    if (node != null) {
      final InspectorService.ObjectGroup group = node.getInspectorService().getNow(null);

      group.safeWhenComplete(node.getProperties(group), (diagnosticProperties, error) -> {
        if (error != null || diagnosticProperties == null) {
          return;
        }
        for (DiagnosticsNode prop : diagnosticProperties) {
          final String name = prop.getName();
          if (fields.containsKey(name)) {
            JComponent field = fields.get(name);
            field.setToolTipText("Runtime value:" + prop.getDescription());
            if (name.equals("color")) {
              JBLabel textField = (JBLabel)field;
              String value = "";
              if (prop.getDescription() != null) {
                value = prop.getDescription();
              }
              textField.setText(value);
            }
          }
        }
      });

      if (node.getWidgetRuntimeType().equals("Text")) {
        final String fieldName = "color";
        FlutterWidgetProperty property = propertyMap.get(fieldName);

        String documentation = "Set color with live updates";
        String expression = "";
        if (property != null) {
          documentation = property.getDocumentation();
          expression = property.getExpression();
        }
        JBLabel label = new JBLabel("color");
        JBLabel field;
        add(label, "right");
        field = new JBLabel(expression);
        if (documentation != null) {
          field.setToolTipText(documentation);
          label.setToolTipText(documentation);
        }
        fields.put(fieldName, field);
        add(field, "wrap, growx");
        field.addMouseListener(new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            ColorChooserService service = ColorChooserService.getInstance();

            List<ColorPickerListener> listeners = new ArrayList<>();
            listeners.add(new ColorPickerListener() {
              @Override
              public void colorChanged(Color color) {
                ColorIconMaker maker = new ColorIconMaker();

                widgetColor = color;
                long value =
                  ((long)color.getAlpha() << 24) | ((long)color.getRed() << 16) | (long)(color.getGreen() << 8) | (long)color.getBlue();
                group.getInspectorService().createObjectGroup("setprop").setProperty(node, "color", "" + value);
                field.setIcon(maker.getCustomIcon(color));
                String textV;
                int alpha = color.getAlpha();
                int red = color.getRed();
                int green = color.getGreen();
                int blue = color.getBlue();
                if (alpha == 255) {
                  textV = String.format("#%02x%02x%02x", red, green, blue);
                }
                else {
                  textV = String.format("#%02x%02x%02x%02x", alpha, red, green, blue);
                }

                field.setText(textV);
              }

              @Override
              public void closed(@Nullable Color color) {

              }
            });
            ColorPicker picker = new ColorPicker(new Disposable() {
              @Override
              public void dispose() {
                System.out.println("XXX disposed?");
              }
            }, Color.RED, true, true, listeners, false);
            ComponentPopupBuilder builder = JBPopupFactory.getInstance().createComponentPopupBuilder(picker, PropertyEditorPanel.this);
            builder.setMovable(true);
            builder.setFocusable(true);
            builder.setTitle("Select color");
            builder.createPopup().show(new RelativePoint(new Point(0, 0)));
          }
        });
      }
    }
    if (properties != null) {
      for (FlutterWidgetProperty property : properties) {
        String name = property.getName();
        if (name.startsWith("on") || name.endsWith("Callback")) {
          continue;
        }
        if (name.equals("key")) {
          continue;
        }
        if (name.equals("child") || name.equals("children")) {
          continue;
        }
        if (name.equals("Container")) {
          List<FlutterWidgetProperty> containerProperties = property.getChildren();
          continue;
        }
        // Text widget properties to demote.
        if (name.equals("strutStyle") || name.equals("locale") || name.equals("semanticsLabel")) {
          continue;
        }
        //        if (property.getEditor() == null) continue;
        final String documentation = property.getDocumentation();
        JComponent field = null;

        if (property.getEditor() == null) {
          continue;
          // TODO(jacobr): also consider displaying a readonly property like this.
          ///          field = new JBTextField(property.getExpression());
        }
        else {
          FlutterWidgetPropertyEditor editor = property.getEditor();
          if (editor.getEnumItems() != null) {
            final ComboBox comboBox = new ComboBox();
            comboBox.setEditable(true);
            PropertyEnumComboBoxModel model = new PropertyEnumComboBoxModel(property);
            comboBox.setModel(model);

            field = comboBox;
            comboBox.addItemListener(e -> {
              if (e.getStateChange() == ItemEvent.SELECTED) {
                EnumValueWrapper wrapper = (EnumValueWrapper)e.getItem();
                if (wrapper.item != null) {
                  setPropertyValue(name, new FlutterWidgetPropertyValue(null, null, null, null, wrapper.item, null));
                }
                else {
                  setPropertyValue(name, new FlutterWidgetPropertyValue(null, null, null, null, null, wrapper.expression));
                }
                System.out.println("XXX new item = " + e.getItem());
              }
            });
          }
          else {
            // TODO(jacobr): we should probably use if (property.isSafeToUpdate())
            // but that currently it seems to have a bunch of false positives.
            final String kind = property.getEditor().getKind();
            if (Objects.equals(kind, FlutterWidgetPropertyEditorKind.BOOL)) {
              // TODO(jacobr): show as boolean.
            }
            final JBTextField textField = new JBTextField(property.getExpression());
            field = textField;
            textField.addActionListener(new ActionListener() {
              @Override
              public void actionPerformed(ActionEvent e) {
                setPropertyValue(name, new FlutterWidgetPropertyValue(null, null, null, null, null, textField.getText()));
              }
            });
            textField.addFocusListener(new FocusListener() {
              @Override
              public void focusGained(FocusEvent e) {

              }

              @Override
              public void focusLost(FocusEvent e) {
                setPropertyValue(name, new FlutterWidgetPropertyValue(null, null, null, null, null, textField.getText()));
              }
            });
          }
        }
        if (field == null) continue;

        if (name.equals("data")) {
          if (documentation != null) {
            field.setToolTipText(documentation);
            //            field.getEmptyText().setText(documentation);
          }
          else {
            field.setToolTipText("data");
          }
          add(field, "span, growx");
        }
        else {
          JBLabel label = new JBLabel(property.getName());
          add(label, "right");
          if (documentation != null) {
            label.setToolTipText(documentation);
          }
          add(field, "wrap, growx");
        }
        if (documentation != null) {
          field.setToolTipText(documentation);
        }
        fields.put(name, field);
        added++;
      }
    }
    if (added == 0) {
      add(new JBLabel("No editable properties"));
    }

    ActionToolbar toolbar = widgetEditToolbar.getToolbar();
    toolbar.setShowSeparatorTitles(true);
    setToolbar(toolbar.getComponent());
  }


  public static Balloon showPopup(InspectorService inspectorService,
                                  EditorEx editor,
                                  DiagnosticsNode node,
                                  @NotNull InspectorService.Location location,
                                  FlutterDartAnalysisServer service,
                                  Point point) {
    final Balloon balloon = showPopupHelper(inspectorService, editor.getProject(), node, location, service);
    if (point != null) {
      balloon.show(new PropertyBalloonPositionTrackerScreenshot(editor, point), Balloon.Position.below);
    }
    else {
      final int offset = location.getOffset();
      final TextRange textRange = new TextRange(offset, offset + 1);
      balloon.show(new PropertyBalloonPositionTracker(editor, textRange), Balloon.Position.below);
    }
    return balloon;
  }

  public static Balloon showPopup(InspectorService inspectorService,
                                  Project project,
                                  Component component,
                                  @Nullable DiagnosticsNode node,
                                  @NonNls InspectorService.Location location,
                                  FlutterDartAnalysisServer service,
                                  Point point) {
    final Balloon balloon = showPopupHelper(inspectorService, project, node, location, service);
    balloon.show(new RelativePoint(component, point), Balloon.Position.above);
    return balloon;
  }

  public static Balloon showPopupHelper(InspectorService inspectorService,
                                        Project project,
                                        @Nullable DiagnosticsNode node,
                                        @NotNull InspectorService.Location location,
                                        FlutterDartAnalysisServer service) {
    //   assert (node != null || position != null);
    final Color GRAPHITE_COLOR = new JBColor(new Color(236, 236, 236, 215), new Color(60, 63, 65, 215));

    final PropertyEditorPanel panel =
      new PropertyEditorPanel(inspectorService, project, node, location, service);
    panel.setBackground(GRAPHITE_COLOR);
    panel.setOpaque(false);
    final BalloonBuilder balloonBuilder = JBPopupFactory.getInstance().createBalloonBuilder(panel);
    balloonBuilder.setFadeoutTime(0);
    balloonBuilder.setFillColor(GRAPHITE_COLOR);
    balloonBuilder.setAnimationCycle(0);
    balloonBuilder.setHideOnClickOutside(true);
    balloonBuilder.setHideOnKeyOutside(false);
    balloonBuilder.setHideOnAction(false);
    balloonBuilder.setCloseButtonEnabled(false);
    balloonBuilder.setBlockClicksThroughBalloon(true);
    balloonBuilder.setRequestFocus(true);
    balloonBuilder.setShadow(true);
    Balloon balloon = balloonBuilder.createBalloon();
    Disposer.register(balloon, panel);

    return balloon;
  }

  public String getDescription() {
    final List<String> parts = new ArrayList<>();
    if (outline != null && outline.getClassName() != null) {
      parts.add(outline.getClassName());
    }
    parts.add("Properties");
    return Joiner.on(" ").join(parts);
  }

  private void setPropertyValue(String propertyName, FlutterWidgetPropertyValue value) {
    // Treat an empty expression and empty value objects as omitted values
    // indicating the property should be removed.
    final FlutterWidgetPropertyValue emptyValue = new FlutterWidgetPropertyValue(null, null, null, null, null, null);

    if (value != null && Objects.equals(value.getExpression(), "") || emptyValue.equals(value)) {
      // Normalize empty expressions to simplify calling this api.
      value = null;
    }
    final FlutterWidgetProperty property = propertyMap.get(propertyName);
    if (property == null) {
      FlutterMessages.showInfo("Widget location changed", "Close the property window and try again.");
      return;
    }

    if (Objects.equals(property.getValue(), value)) {
      // Short circuit as nothing changed.
      return;
    }

    final SourceChange change;
    try {
      change = flutterDartAnalysisService
        .setWidgetPropertyValue(property.getId(), value);
    }
    catch (Exception e) {
      if (value != null && value.getExpression() != null) {
        FlutterMessages.showInfo("Invalid property value", value.getExpression());
      }
      else {
        FlutterMessages.showError("Unable to set propery value", e.getMessage());
      }
      return;
    }

    if (change != null) {
      ApplicationManager.getApplication().runWriteAction(() -> {
        try {
          AssistUtils.applySourceChange(project, change, false);
          if (inspectorService != null) {
            // XXX handle the app loading part way through better.
            ArrayList<FlutterApp> l = new ArrayList<>();
            l.add(inspectorService.getApp());
            FlutterReloadManager.getInstance(project).saveAllAndReloadAll(l, "Property Editor");
          }
        }
        catch (DartSourceEditException exception) {
          FlutterMessages.showInfo("Failed to apply code change", exception.getMessage());
        }
      });
    }
  }

  @Override
  public void dispose() {
  }
}

class PropertyBalloonPositionTracker extends PositionTracker<Balloon> {
  private final Editor myEditor;
  private final TextRange myRange;

  PropertyBalloonPositionTracker(Editor editor, TextRange range) {
    super(editor.getContentComponent());
    myEditor = editor;
    myRange = range;
  }

  static boolean insideVisibleArea(Editor e, TextRange r) {
    int textLength = e.getDocument().getTextLength();
    if (r.getStartOffset() > textLength) return false;
    if (r.getEndOffset() > textLength) return false;
    Rectangle visibleArea = e.getScrollingModel().getVisibleArea();
    Point point = e.logicalPositionToXY(e.offsetToLogicalPosition(r.getStartOffset()));

    return visibleArea.contains(point);
  }

  @Override
  public RelativePoint recalculateLocation(final Balloon balloon) {
    int startOffset = myRange.getStartOffset();
    int endOffset = myRange.getEndOffset();

    //This might be interesting or might be an unrelated use case.
    /*
    if (!insideVisibleArea(myEditor, myRange)) {
      if (!balloon.isDisposed()) {
        Disposer.dispose(balloon);
      }

      VisibleAreaListener visibleAreaListener = new VisibleAreaListener() {
        @Override
        public void visibleAreaChanged(@NotNull VisibleAreaEvent e) {
          if (insideVisibleArea(myEditor, myRange)) {
//            showBalloon(myProject, myEditor, myRange);
            final VisibleAreaListener visibleAreaListener = this;
            myEditor.getScrollingModel().removeVisibleAreaListener(visibleAreaListener);
          }
        }
      };
      myEditor.getScrollingModel().addVisibleAreaListener(visibleAreaListener);
    }
*/
    Point startPoint = myEditor.visualPositionToXY(myEditor.offsetToVisualPosition(startOffset));
    Point endPoint = myEditor.visualPositionToXY(myEditor.offsetToVisualPosition(endOffset));
    Point point = new Point((startPoint.x + endPoint.x) / 2, startPoint.y + myEditor.getLineHeight());

    return new RelativePoint(myEditor.getContentComponent(), point);
  }
}

class PropertyBalloonPositionTrackerScreenshot extends PositionTracker<Balloon> {
  private final Editor myEditor;
  private final Point point;

  PropertyBalloonPositionTrackerScreenshot(Editor editor, Point point) {
    super(editor.getComponent());
    myEditor = editor;
    this.point = point;
  }

  @Override
  public RelativePoint recalculateLocation(final Balloon balloon) {
    return new RelativePoint(myEditor.getComponent(), point);
  }
}

