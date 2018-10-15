/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.dualView.TreeTableView;
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.JBUI;
import io.flutter.FlutterBundle;
import io.flutter.inspector.*;
import io.flutter.perf.FlutterWidgetPerfManager;
import io.flutter.run.FlutterLaunchMode;
import io.flutter.run.daemon.FlutterApp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static io.flutter.view.PerformanceOverlayAction.SHOW_PERFORMANCE_OVERLAY;
import static io.flutter.view.RepaintRainbowAction.SHOW_REPAINT_RAINBOW;

public class InspectorPerfTab extends JBPanel implements InspectorTabPanel {
  private static final boolean ENABLE_TRACK_REPAINTS = false;

  private final Disposable parentDisposable;
  private final @NotNull FlutterApp app;
  private final ExtensionCommandCheckbox showPerfOverlay;
  private final ExtensionCommandCheckbox showRepaintRainbow;

  private JCheckBox trackRebuildsCheckbox;
  private JCheckBox trackRepaintsCheckbox;
  private WidgetPerfPanel widgetPerfPanel;

  InspectorPerfTab(Disposable parentDisposable, @NotNull FlutterApp app) {
    this.app = app;
    this.parentDisposable = parentDisposable;

    showPerfOverlay = new ExtensionCommandCheckbox(app, SHOW_PERFORMANCE_OVERLAY, "Show Performance Overlay", "");
    showRepaintRainbow = new ExtensionCommandCheckbox(app, SHOW_REPAINT_RAINBOW, "Show Repaint Rainbow", "");

    buildUI();

    final FlutterWidgetPerfManager widgetPerfManager = FlutterWidgetPerfManager.getInstance(app.getProject());
    trackRebuildsCheckbox.setSelected(widgetPerfManager.isTrackRebuildWidgets());
    if (ENABLE_TRACK_REPAINTS) {
      trackRepaintsCheckbox.setSelected(widgetPerfManager.isTrackRepaintWidgets());
    }

    app.hasServiceExtension(FlutterWidgetPerfManager.TRACK_REBUILD_WIDGETS, trackRebuildsCheckbox::setEnabled, parentDisposable);
    if (ENABLE_TRACK_REPAINTS) {
      app.hasServiceExtension(FlutterWidgetPerfManager.TRACK_REPAINT_WIDGETS, trackRepaintsCheckbox::setEnabled, parentDisposable);
    }

    trackRebuildsCheckbox.addChangeListener((l) -> {
      setTrackRebuildWidgets(trackRebuildsCheckbox.isSelected());
    });

    if (ENABLE_TRACK_REPAINTS) {
      trackRepaintsCheckbox.addChangeListener((l) -> {
        setTrackRepaintWidgets(trackRepaintsCheckbox.isSelected());
      });
    }
  }

  private void buildUI() {
    setLayout(new GridBagLayout());
    setBorder(JBUI.Borders.empty(5));

    final GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weightx = 1.0;
    constraints.fill = GridBagConstraints.BOTH;

    // header
    final JPanel headerPanel = new JPanel(new BorderLayout(0, 3));
    headerPanel.add(new JBLabel("Running in " + app.getLaunchMode() + " mode"), BorderLayout.NORTH);
    if (app.getLaunchMode() == FlutterLaunchMode.DEBUG) {
      headerPanel.add(
        new JBLabel("<html><body><p style='color:red'>Note: for best results, re-run in profile mode</p></body></html>"),
        BorderLayout.SOUTH
      );
    }
    headerPanel.setBorder(JBUI.Borders.empty(5));
    add(headerPanel, constraints);

    // FPS
    final JPanel fpsPanel = new JPanel(new BorderLayout());
    final JPanel fpsDisplay = FPSDisplay.createJPanelView(parentDisposable, app);
    fpsPanel.add(fpsDisplay, BorderLayout.CENTER);
    fpsPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "FPS"));
    constraints.gridy = 1;
    add(fpsPanel, constraints);

    // Memory
    final JPanel memoryPanel = new JPanel(new BorderLayout());
    final JPanel heapDisplay = HeapDisplay.createJPanelView(parentDisposable, app);
    memoryPanel.add(heapDisplay, BorderLayout.CENTER);
    memoryPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Memory"));
    constraints.gridy = 2;
    add(memoryPanel, constraints);

    // Widgets stats (experimental)
    final Box perfSettings = Box.createVerticalBox();
    trackRebuildsCheckbox = new JCheckBox("Show widget rebuild indicators");
    trackRebuildsCheckbox.setHorizontalAlignment(JLabel.LEFT);
    trackRebuildsCheckbox.setToolTipText("Rebuild Indicators appear on each line of code where the widget is being rebuilt by Flutter. Rebuilding widgets is generally very cheap. You should only worry about optimizing code to reduce the number of widget rebuilds if you notice that the frame rate is below 60fps or if widgets that you did not expect to be rebuilt are rebuilt a very large number of times.");
    perfSettings.add(trackRebuildsCheckbox);
    if (ENABLE_TRACK_REPAINTS) {
      trackRepaintsCheckbox = new JCheckBox("Show widget repaint indicators");
      perfSettings.add(trackRepaintsCheckbox);
    }

    perfSettings.add(showRepaintRainbow.getComponent());
    perfSettings.add(showPerfOverlay.getComponent());
    widgetPerfPanel = new WidgetPerfPanel(parentDisposable, app);
    perfSettings.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Diagnostic settings"));
    constraints.gridy = 3;
    constraints.weighty = 1.0;
    add(perfSettings, constraints);

    constraints.gridy = 4;
    constraints.weighty = 1.0;
    add(widgetPerfPanel, constraints);
  }

  private void setTrackRebuildWidgets(boolean selected) {
    final FlutterWidgetPerfManager widgetPerfManager = FlutterWidgetPerfManager.getInstance(app.getProject());
    widgetPerfManager.setTrackRebuildWidgets(selected);
    // Update default so next app launched will match the existing setting.
    FlutterWidgetPerfManager.trackRebuildWidgetsDefault = selected;
  }

  private void setTrackRepaintWidgets(boolean selected) {
    final FlutterWidgetPerfManager widgetPerfManager = FlutterWidgetPerfManager.getInstance(app.getProject());
    widgetPerfManager.setTrackRepaintWidgets(selected);
    // Update default so next app launched will match the existing setting.
    FlutterWidgetPerfManager.trackRepaintWidgetsDefault = selected;
  }

  public WidgetPerfPanel getWidgetPerfPanel() {
    return widgetPerfPanel;
  }

  @Override
  public void setVisibleToUser(boolean visible) {
    assert app.getVMServiceManager() != null;

    widgetPerfPanel.setVisibleToUser(visible);

    if (visible) {
      app.getVMServiceManager().addPollingClient();
    }
    else {
      app.getVMServiceManager().removePollingClient();
    }
  }
}

class WidgetPerfTable extends TreeTableView implements DataProvider {
  private final FlutterApp flutterApp;

  private ArrayList<DiagnosticsNode> currentProperties;

  static class WidgetNameRenderer extends ColoredTableCellRenderer {
    @Override
    protected void customizeCellRenderer(JTable table, @Nullable Object value, boolean selected, boolean hasFocus, int row, int column) {
// XXX BAD TYPE..
      if (value == null) return;
      final DiagnosticsNode node = (DiagnosticsNode)value;
      // If we should not show a separator then we should show the property name
      // as part of the property value instead of in its own column.
      if (!node.getShowSeparator() || !node.getShowName()) {
        return;
      }
      // Present user defined properties in BOLD.
      final SimpleTextAttributes attributes = SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES;
      append(node.getName(), attributes);
    }
  }
  static class PropertyNameColumnInfo extends ColumnInfo<DefaultMutableTreeNode, DiagnosticsNode> {
    private final TableCellRenderer renderer = new WidgetNameRenderer();

    public PropertyNameColumnInfo(String name) {
      super(name);
    }

    @Nullable
    @Override
    public DiagnosticsNode valueOf(DefaultMutableTreeNode node) {
      return (DiagnosticsNode)node.getUserObject();
    }

    @Override
    public TableCellRenderer getRenderer(DefaultMutableTreeNode item) {
      return renderer;
    }
  }

  static class PropertyValueColumnInfo extends ColumnInfo<DefaultMutableTreeNode, DiagnosticsNode> {
    private  TableCellRenderer defaultRenderer;

    public PropertyValueColumnInfo(String name) {
      super(name);
// XXX      defaultRenderer = new SimplePropertyValueRenderer();
    }

    @Nullable
    @Override
    public DiagnosticsNode valueOf(DefaultMutableTreeNode node) {
      return (DiagnosticsNode)node.getUserObject();
    }

    @Override
    public TableCellRenderer getRenderer(DefaultMutableTreeNode item) {
      return defaultRenderer;
    }
  }
  WidgetPerfTable(FlutterApp flutterApp) {
    super(new ListTreeTableModelOnColumns(
      new DefaultMutableTreeNode(),
      new ColumnInfo[]{
        new InspectorPanel.PropertyNameColumnInfo("Property"),
        new InspectorPanel.PropertyValueColumnInfo("Value")
      }
    ));
    this.flutterApp = flutterApp;
    setRootVisible(false);

    setStriped(true);
    setRowHeight(getRowHeight() + JBUI.scale(4));

    final JTableHeader tableHeader = getTableHeader();
    tableHeader.setPreferredSize(new Dimension(0, getRowHeight()));

    getColumnModel().getColumn(0).setPreferredWidth(120);
    getColumnModel().getColumn(1).setPreferredWidth(200);
  }

  private ActionGroup createTreePopupActions() {
    final DefaultActionGroup group = new DefaultActionGroup();
    final ActionManager actionManager = ActionManager.getInstance();
    group.add(actionManager.getAction(InspectorActions.JUMP_TO_SOURCE));
    // TODO(pq): implement
    //group.add(new JumpToPropertyDeclarationAction());
    return group;
  }

  ListTreeTableModelOnColumns getTreeModel() {
    return (ListTreeTableModelOnColumns)getTableModel();
  }

  public void showProperties(DiagnosticsNode diagnostic) {
    getEmptyText().setText(FlutterBundle.message("app.inspector.loading_properties"));
    // XXX
    PopupHandler.installUnknownPopupHandler(this, createTreePopupActions(), ActionManager.getInstance());
  }

  private void showPropertiesHelper(ArrayList<DiagnosticsNode> properties) {
    currentProperties = properties;
    setModelFromProperties(properties);
  }

  private void setModelFromProperties(ArrayList<DiagnosticsNode> properties) {
    final ListTreeTableModelOnColumns model = getTreeModel();
    final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    for (DiagnosticsNode property : properties) {
      if (property.getLevel() != DiagnosticLevel.hidden) {
        root.add(new DefaultMutableTreeNode(property));
      }
    }
    getEmptyText().setText(FlutterBundle.message("app.inspector.all_properties_hidden"));
    model.setRoot(root);
  }

  private CompletableFuture<Void> loadPropertyMetadata(ArrayList<DiagnosticsNode> properties) {
    // Preload all information we need about each property before instantiating
    // the UI so that the property display UI does not have to deal with values
    // that are not yet available. As the number of properties is small this is
    // a reasonable tradeoff.
    final CompletableFuture[] futures = new CompletableFuture[properties.size()];
    int i = 0;
    for (DiagnosticsNode property : properties) {
      futures[i] = property.getValueProperties();
      ++i;
    }
    return CompletableFuture.allOf(futures);
  }

  private void refresh() {
  }

  private boolean propertiesIdentical(ArrayList<DiagnosticsNode> a, ArrayList<DiagnosticsNode> b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); ++i) {
      if (!a.get(i).identicalDisplay(b.get(i))) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  @Override
  public Object getData(String dataId) {
    return InspectorTree.INSPECTOR_KEY.is(dataId) ? getTree() : null;
  }
}
