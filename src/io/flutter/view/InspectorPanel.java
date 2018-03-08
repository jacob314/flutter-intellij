/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.google.common.base.Joiner;
import com.google.gson.JsonObject;
import com.intellij.execution.ui.layout.impl.JBRunnerTabs;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.dualView.TreeTableView;
import com.intellij.ui.speedSearch.SpeedSearchSupply;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.util.ui.tree.WideSelectionTreeUI;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import io.flutter.FlutterBundle;
import io.flutter.editor.FlutterMaterialIcons;
import io.flutter.inspector.*;
import io.flutter.pub.PubRoot;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.utils.AsyncRateLimiter;
import io.flutter.utils.AsyncUtils;
import io.flutter.utils.ColorIconMaker;
import io.flutter.utils.TreeScrollAnimator;
import org.dartlang.vm.service.element.InstanceRef;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.accessibility.AccessibleContext;
import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.plaf.TreeUI;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.flutter.utils.AsyncUtils.whenCompleteUiThread;
import static io.flutter.utils.JsonUtils.getIntMember;

// TODO(devoncarew): Should we filter out the CheckedModeBanner node type?
// TODO(devoncarew): Should we filter out the WidgetInspector node type?

public class InspectorPanel extends JPanel implements Disposable, InspectorService.InspectorServiceClient {

  static DiagnosticsNode maybeGetDiagnostic(DefaultMutableTreeNode treeNode) {
    if (treeNode == null) {
      return null;
    }
    final Object userObject = treeNode.getUserObject();
    return (userObject instanceof DiagnosticsNode) ? (DiagnosticsNode)userObject : null;
  }
  // TODO(jacobr): use a lower frame rate when the panel is hidden.
  /**
   * Maximum frame rate to refresh the inspector panel at to avoid taxing the
   * physical device with too many requests to recompute properties and trees.
   * <p>
   * A value up to around 30 frames per second could be reasonable for
   * debugging highly interactive cases particularly when the user is on a
   * simulator or high powered native device. The frame rate is set low
   * for now mainly to minimize the risk of unintended consequences.
   */
  public static final double REFRESH_FRAMES_PER_SECOND = 5.0;

  static final JBColor VERY_LIGHT_GREY = new JBColor(Gray._220, Gray._65);

  private final TreeDataProvider myRootsTree;
  @Nullable private final PropertiesPanel myPropertiesPanel;
  private final Computable<Boolean> isApplicable;
  private final InspectorService.FlutterTreeType treeType;
  private final FlutterView flutterView;
  @NotNull
  private final FlutterApp flutterApp;
  private final boolean detailsSubtree;
  private final boolean isSummaryTree;
  @NotNull final Splitter treeSplitter;
  @Nullable
  /**
   * Parent InspectorPanel if this is a details subtree
   */
  private final InspectorPanel parentTree;

  private final TreeScrollAnimator scrollAnimator;
  private CompletableFuture<DiagnosticsNode> rootFuture;

  private DiagnosticsNode subtreeRoot;

  private boolean programaticSelectionChangeInProgress = false;
  private boolean programaticExpansionInProgress = false;

  private static final DataKey<Tree> INSPECTOR_KEY = DataKey.create("Flutter.InspectorKey");

  // We have to define this because SimpleTextAttributes does not define a
  // value for warnings. This color looks reasonable for warnings both
  // with the Darculaand the default themes.
  private static final SimpleTextAttributes WARNING_ATTRIBUTES = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE);

  /**
   * Node being highlighted due to the current hover.
   */
  private DefaultMutableTreeNode currentShowNode;

  @NotNull
  public FlutterApp getFlutterApp() {
    return flutterApp;
  }

  private DefaultMutableTreeNode selectedNode;
  private DefaultMutableTreeNode lastExpanded;

  private CompletableFuture<DiagnosticsNode> pendingSelectionFuture;
  @SuppressWarnings("FieldMayBeFinal") private boolean myIsListening = false;

  private final InspectorPanel subtreePanel;

  private boolean isActive = false;

  private final AsyncRateLimiter refreshRateLimiter;

  private static final Logger LOG = Logger.getInstance(InspectorPanel.class);

  private Map<InspectorInstanceRef, DefaultMutableTreeNode> valueToTreeNode = new HashMap<>();
  final JBScrollPane treeScrollPane;

  private InspectorObjectGroup objectGroup;

  private final class InspectorTreeMouseListener extends MouseAdapter {
    private final JTree tree;

    private InspectorTreeMouseListener(JTree tree) {
      this.tree = tree;
    }

    @Override
    public void mouseExited(MouseEvent e) {
      tree.setCursor(Cursor.getDefaultCursor());
      endShowNode();
      if (detailsSubtree) {
        parentTree.endShowNode();
      }
      else if (subtreePanel != null) {
        subtreePanel.endShowNode();
      }
    }

    @Override
    public void mouseClicked(MouseEvent event) {
      final DefaultMutableTreeNode node = getClosestTreeNode(event);
      /// TODO(jacobr): support clicking on a property.
      // It would be reasonable for that to trigger selecting the parent of the
      // property.
      final DiagnosticsNode diagnostic = maybeGetDiagnostic(node);
      if (diagnostic != null && !diagnostic.isProperty()) {
        if (event.getClickCount() == 2) {
          if (isSummaryTree) {

            applyNewSelection(diagnostic, diagnostic, true);
          }
          else if (parentTree != null) {
            parentTree.applyNewSelection(firstAncestorInParentTree(node), diagnostic, true);
          }
        }
      }
      event.consume();
    }

    @Override
    public void mouseMoved(MouseEvent event) {
      final DefaultMutableTreeNode treeNode = getTreeNode(event);
      final DiagnosticsNode node = maybeGetDiagnostic(treeNode);
      if (node != null && !node.isProperty()) {
        tree.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (detailsSubtree && isCreatedByLocalProject(node)) {
          parentTree.highlightShowNode(node.getValueRef());
        }
        else if (subtreePanel != null) {
          subtreePanel.highlightShowNode(node.getValueRef());
        }
        highlightShowNode(treeNode);
        return;
      }
      else {
        tree.setCursor(Cursor.getDefaultCursor());
      }
      if (detailsSubtree) {
        parentTree.endShowNode();
      }
      else if (subtreePanel != null) {
        subtreePanel.endShowNode();
      }
      endShowNode();
    }

    /**
     * Match IntelliJ's fuzzier standards for what it takes to select a node.
     */
    private DefaultMutableTreeNode getClosestTreeNode(MouseEvent event) {

      final Point p = event.getPoint();
      final int row = tree.getClosestRowForLocation(p.x, p.y);

      final TreeCellRenderer r = tree.getCellRenderer();
      if (row == -1 || r == null) {
        return null;
      }
      final TreePath path = tree.getPathForRow(row);
      final DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();

      final Component rComponent = r.getTreeCellRendererComponent(tree, node, tree.isRowSelected(row), tree.isExpanded(row),
                                                                  tree.getModel().isLeaf(node), row, true);
      final Rectangle pathBounds = tree.getPathBounds(path);
      p.translate(-pathBounds.x, -pathBounds.y);
      return node;
    }


    private DefaultMutableTreeNode getTreeNode(MouseEvent event) {
      final Point p = event.getPoint();
      int row = tree.getRowForLocation(p.x, p.y);
      final TreeCellRenderer r = tree.getCellRenderer();
      if (row == -1 || r == null) {
        return null;
      }
      TreePath path = tree.getPathForRow(row);
      return (DefaultMutableTreeNode)path.getLastPathComponent();
    }
  }

  public InspectorPanel(FlutterView flutterView,
                        @NotNull FlutterApp flutterApp,
                        @NotNull Computable<Boolean> isApplicable,
                        @NotNull InspectorService.FlutterTreeType treeType,
                        boolean isSummaryTree) {
    this(flutterView, flutterApp, isApplicable, treeType, false, null, false, isSummaryTree);
  }

  private InspectorPanel(FlutterView flutterView,
                         @NotNull FlutterApp flutterApp,
                         @NotNull Computable<Boolean> isApplicable,
                         @NotNull InspectorService.FlutterTreeType treeType,
                         boolean detailsSubtree,
                         @Nullable InspectorPanel parentTree,
                         boolean rootVisible,
                         boolean isSummaryTree) {
    super(new BorderLayout());

    this.treeType = treeType;
    this.flutterView = flutterView;
    this.flutterApp = flutterApp;
    this.isApplicable = isApplicable;
    this.detailsSubtree = detailsSubtree;
    this.isSummaryTree = isSummaryTree;
    this.parentTree = parentTree;

    objectGroup = new InspectorObjectGroup();

    refreshRateLimiter = new AsyncRateLimiter(REFRESH_FRAMES_PER_SECOND, this::refresh);

    String parentTreeDisplayName = (parentTree != null) ? parentTree.treeType.displayName : null;

    myRootsTree =
      new TreeDataProvider(new DefaultMutableTreeNode(null), treeType.displayName, detailsSubtree, parentTreeDisplayName, rootVisible);
    // We want to reserve double clicking for navigation within the detail
    // tree and in the future for editing values in the tree.
    myRootsTree.setToggleClickCount(0);

    myRootsTree.addTreeExpansionListener(new MyTreeExpansionListener());
    InspectorTreeMouseListener mouseListener = new InspectorTreeMouseListener(myRootsTree);
    myRootsTree.addMouseListener(mouseListener);
    myRootsTree.addMouseMotionListener(mouseListener);

    //getInspectorService().
    if (treeType == InspectorService.FlutterTreeType.widget && isSummaryTree) {
      /// XXX AND inspector service supports getting filtered trees.
      /// TWEAK ORDER
      // We don't yet have a similar notion of platform RenderObject as the render object tree is more low level.
      subtreePanel = new InspectorPanel(flutterView, flutterApp, isApplicable, InspectorService.FlutterTreeType.widget, true, this, true,
                                        false); // XXX rootVisible false?
    }
    else {
      subtreePanel = null;
    }

    initTree(myRootsTree);
    myRootsTree.getSelectionModel().addTreeSelectionListener(e -> selectionChanged());
    treeScrollPane = (JBScrollPane)ScrollPaneFactory.createScrollPane(myRootsTree);
    treeScrollPane.setAutoscrolls(true);

    scrollAnimator = new TreeScrollAnimator(myRootsTree, treeScrollPane);

    if (!detailsSubtree) {
      treeSplitter = new Splitter(detailsSubtree);
      treeSplitter.setProportion(flutterView.getState().getSplitterProportion(detailsSubtree));
      flutterView.getState().addListener(e -> {
        final float newProportion = flutterView.getState().getSplitterProportion(detailsSubtree);
        if (treeSplitter.getProportion() != newProportion) {
          treeSplitter.setProportion(newProportion);
        }
      });
      //noinspection Convert2Lambda
      treeSplitter.addPropertyChangeListener("proportion", new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
          flutterView.getState().setSplitterProportion(treeSplitter.getProportion(), detailsSubtree);
        }
      });

      if (subtreePanel == null) {
        myPropertiesPanel = new PropertiesPanel(flutterApp);
        treeSplitter.setSecondComponent(ScrollPaneFactory.createScrollPane(myPropertiesPanel));
      }
      else {
        myPropertiesPanel = null; /// This InspectorPanel doesn't have its own property panel.
        final JBRunnerTabs tabs = new JBRunnerTabs(flutterView.getProject(), ActionManager.getInstance(), null, this);
        tabs.addTab(new TabInfo(subtreePanel)
                      .append("Properties", SimpleTextAttributes.REGULAR_ATTRIBUTES));

        treeSplitter.setSecondComponent(tabs.getComponent());
      }

      Disposer.register(this, treeSplitter::dispose);
      Disposer.register(this, scrollAnimator::dispose);
      treeSplitter.setFirstComponent(treeScrollPane);
      add(treeSplitter);
    }
    else {
      treeSplitter = null;
      myPropertiesPanel = null;
      add(treeScrollPane);
    }

    this.addComponentListener(new ComponentListener() {
      @Override
      public void componentResized(ComponentEvent e) {
        determineSplitterOrientation();
      }

      @Override
      public void componentMoved(ComponentEvent e) {

      }

      @Override
      public void componentShown(ComponentEvent e) {
        // We should start updating here
        determineSplitterOrientation();
      }

      @Override
      public void componentHidden(ComponentEvent e) {
        // We should pause updating here.
      }
    });
    if (detailsSubtree) {
      treeScrollPane.addComponentListener(new ComponentListener() {
        @Override
        public void componentResized(ComponentEvent e) {
          // TODO(jacobr): determine the height of a single row in the tree and use
          // that instead of hardcoding 20. That ensures that at maximum scroll, a
          // single line is still shown.
          // Note we can be smarter and reduce the borders down if the desired selection target
          // is already indented by enough to be compatible with VERTICAL_FRACTION and HORIZONTAL_FRACTION
         /* final int top = Math.max(0, (int) (treeScrollPane.getHeight() * (1 - VERTICAL_FRACTION)) - 20);
          final int bottom = Math.max(0, (int) (treeScrollPane.getHeight() * (VERTICAL_FRACTION)) - 20);
          final int left = Math.max(0, (int) (treeScrollPane.getWidth() * (1 - HORIZONTAL_FRACTION)));
          final int right = Math.max(0, (int) (treeScrollPane.getWidth() * (HORIZONTAL_FRACTION)));
          myRootsTree.setBorder(JBUI.Borders.empty(top, left, bottom, right));
          XXX
          */
        }

        @Override
        public void componentMoved(ComponentEvent e) {

        }

        @Override
        public void componentShown(ComponentEvent e) {

        }

        @Override
        public void componentHidden(ComponentEvent e) {
          // TODO(jacobr): stop wasting cycles.
        }
      });
    }
    determineSplitterOrientation();
  }

  private void determineSplitterOrientation() {
    if (treeSplitter == null) {
      return;
    }
    final double aspectRatio = (double)getWidth() / (double)getHeight();
    double targetAspectRatio = myPropertiesPanel != null ? 1.4 : 0.666;
    final boolean vertical = aspectRatio < targetAspectRatio;
    if (vertical != treeSplitter.getOrientation()) {
      treeSplitter.setOrientation(vertical);
    }
  }

  protected boolean hasDiagnosticsValue(InspectorInstanceRef ref) {
    return valueToTreeNode.containsKey(ref);
  }

  protected DiagnosticsNode findDiagnosticsValue(InspectorInstanceRef ref) {
    return getDiagnosticNode(valueToTreeNode.get(ref));
  }

  protected void endShowNode() {
    highlightShowNode((DefaultMutableTreeNode)null);
  }


  protected boolean highlightShowNode(InspectorInstanceRef ref) {
    return highlightShowNode(valueToTreeNode.get(ref));
  }

  protected boolean highlightShowNode(DefaultMutableTreeNode node) {
    if (node == null && parentTree != null) {
      // If nothing is highlighted, highlight the node selected in the parent
      // tree so user has context of where the node selected in the parent is
      // in the details tree.
      node = findMatchingTreeNode(parentTree.getSelectedDiagnostic());
    }
    if (currentShowNode == node) {
      return false; // Already shown.
    }
    getTreeModel().nodeChanged(currentShowNode);
    getTreeModel().nodeChanged(node);
    currentShowNode = node;
    return true;
  }

  private DefaultMutableTreeNode findMatchingTreeNode(DiagnosticsNode node) {
    if (node == null) {
      return null;
    }
    return valueToTreeNode.get(node.getValueRef());
  }


  static DiagnosticsNode getDiagnosticNode(TreeNode treeNode) {
    if (!(treeNode instanceof DefaultMutableTreeNode)) {
      return null;
    }
    final Object userData = ((DefaultMutableTreeNode)treeNode).getUserObject();
    return userData instanceof DiagnosticsNode ? (DiagnosticsNode)userData : null;
  }

  private DefaultTreeModel getTreeModel() {
    return (DefaultTreeModel)myRootsTree.getModel();
  }

  private CompletableFuture<?> refresh() {
    // TODO(jacobr): refresh the tree as well as just the properties.
    if (myPropertiesPanel != null) {
      return myPropertiesPanel.refresh();
    }
    return CompletableFuture.completedFuture(null);
  }

  public void onIsolateStopped() {
    // Make sure we cleanup all references to objects from the stopped isolate as they are now obsolete.
    if (rootFuture != null && !rootFuture.isDone()) {
      rootFuture.cancel(true);
    }
    rootFuture = null;

    if (pendingSelectionFuture != null && !pendingSelectionFuture.isDone()) {
      pendingSelectionFuture.cancel(true);
    }
    pendingSelectionFuture = null;

    selectedNode = null;

    getTreeModel().setRoot(new DefaultMutableTreeNode());
    if (myPropertiesPanel != null) {
      myPropertiesPanel.showProperties(null);
    }
  }

  @Override
  public void onForceRefresh() {
    recomputeTreeRoot(null);
    if (myPropertiesPanel != null) {
      myPropertiesPanel.refresh();
    }
  }

  public void onAppChanged() {
    setActivate(isApplicable.compute());
  }

  @Nullable
  private InspectorService getInspectorService() {
    return flutterApp.getInspectorService();
  }

  void setActivate(boolean enabled) {
    if (!enabled) {
      onIsolateStopped();
      isActive = false;
      return;
    }
    if (isActive) {
      // Already activated.
      return;
    }

    isActive = true;
    assert (getInspectorService() != null);
    getInspectorService().addClient(this);
    ArrayList<String> rootDirectories = new ArrayList<>();
    for (PubRoot root : getFlutterApp().getPubRoots()) {
      rootDirectories.add(root.getRoot().getCanonicalPath());
    }
    getInspectorService().setPubRootDirectories(rootDirectories);

    getInspectorService().isWidgetTreeReady().thenAccept((Boolean ready) -> {
      if (ready) {
        recomputeTreeRoot(null);
      }
    });
  }

  private DefaultMutableTreeNode getRootNode() {
    return (DefaultMutableTreeNode)getTreeModel().getRoot();
  }

  private CompletableFuture<DiagnosticsNode> recomputeTreeRoot(DiagnosticsNode newSelection) {
    if (rootFuture != null && !rootFuture.isDone()) {
      rootFuture.cancel(true);
      // XXX is this right???
    }

    InspectorObjectGroup newObjectGroup = new InspectorObjectGroup();
    rootFuture = detailsSubtree
                 ? getInspectorService().getDetailsSubtree(subtreeRoot, newObjectGroup)
                 : getInspectorService().getRoot(treeType, newObjectGroup);
    setupTreeRoot(newSelection, newObjectGroup);
    return rootFuture;
  }

  /**
   * Show the platform subtree given a
   *
   * @param subtreeRoot
   */

  public CompletableFuture<DiagnosticsNode> showDetailSubtrees(DiagnosticsNode subtreeRoot, DiagnosticsNode subtreeSelection) {
    // TODO(jacobr): handle render objects subtree panel and other subtree panels here.
    this.subtreeRoot = subtreeRoot;
    myRootsTree.setHighlightedRoot(getSubtreeRootNode());
    if (subtreePanel != null) {
      return subtreePanel.setSubtreeRoot(subtreeRoot, subtreeSelection);
    }

    return null;
  }

  public InspectorInstanceRef getSubtreeRootValue() {
    return subtreeRoot != null ? subtreeRoot.getValueRef() : null;
  }

  public CompletableFuture<DiagnosticsNode> setSubtreeRoot(DiagnosticsNode node, DiagnosticsNode selection) {
    assert (detailsSubtree);
    if (node != null && treeType == InspectorService.FlutterTreeType.widget && !node.isCreatedByLocalProject()) {
      // This isn't a platform node... the existing platform tree is fine.
      // TODO(jacobr): verify that the platform ndoe is in the currently displayed platform tree
      // otherwise we have an issue
      return null;
    }

    if (node.equals(subtreeRoot)) {
      //  Select the new node in the existing subtree.
      return applyNewSelection(selection, null, false);
    }
    subtreeRoot = node;
    if (rootFuture != null && !rootFuture.isDone()) {
      rootFuture.cancel(false);
    }
    InspectorObjectGroup newObjectGroup = new InspectorObjectGroup();

    recomputeTreeRoot(selection);
    return rootFuture;
  }

  private void setupTreeRoot(DiagnosticsNode selection, InspectorObjectGroup newObjectGroup) {
    whenCompleteUiThread(rootFuture, (final DiagnosticsNode n, Throwable error) -> {
      if (error != null) {
        // It is fine for us to have a cancelled future error.
        final InspectorService inspectorService = getInspectorService();
        if (inspectorService != null) {
          inspectorService.disposeGroup(newObjectGroup);
        }
        return;
      }
      // TODO(jacobr): check if the new tree is identical to the existing tree
      // in which case we should dispose the new tree and keep the old tree.
      // XXX need to do this for better performance.

      /// XXX need to fixup

      getInspectorService().disposeGroup(objectGroup);
      objectGroup = newObjectGroup;
      // TODO(jacobr): be more judicious about nuking the whole tree.
      if (parentTree != null) {
        for (InspectorInstanceRef v : valueToTreeNode.keySet()) {
          parentTree.maybeUpdateValueUI(v);
        }
      }
      valueToTreeNode.clear();
      if (n != null) {
        final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(n);
        getTreeModel().setRoot(rootNode);
        setupTreeNode(rootNode, n, true);
      }
      else {
        getTreeModel().setRoot(null);
      }
      refreshSelection(selection, null, false);
    });
  }

  DefaultMutableTreeNode getSubtreeRootNode() {
    if (subtreeRoot == null) {
      return null;
    }
    return valueToTreeNode.get(subtreeRoot.getValueRef());
  }

  private void refreshSelection(DiagnosticsNode newSelection, DiagnosticsNode detailsSelection, boolean setSubtreeRoot) {
    if (newSelection == null) {
      newSelection = getSelectedDiagnostic();
    }
    setSelectedNode(findMatchingTreeNode(newSelection));
    syncSelectionHelper(setSubtreeRoot, detailsSelection);
    if (subtreePanel != null && subtreeRoot != null && getSubtreeRootNode() == null) {
      System.out.println("XXX subtree root out of scope.. Need to rehome");
      subtreeRoot = newSelection;
      subtreePanel.setSubtreeRoot(newSelection, detailsSelection);
    }
    myRootsTree.setHighlightedRoot(getSubtreeRootNode());

    syncTreeSelection();
  }

  private void syncTreeSelection() {
    programaticSelectionChangeInProgress = true;
    final TreePath path = selectedNode != null ? new TreePath(selectedNode.getPath()) : null;
    myRootsTree.setSelectionPath(path);
    programaticSelectionChangeInProgress = false;
    myRootsTree.expandPath(path);
    scrollAnimator.animateTo(path);
  }


  private void selectAndShowNode(DiagnosticsNode node) {
    if (node == null) {
      return;
    }
    selectAndShowNode(node.getValueRef());
  }

  private void selectAndShowNode(InspectorInstanceRef ref) {
    DefaultMutableTreeNode node = valueToTreeNode.get(ref);
    if (node == null) {
      return;
    }
    setSelectedNode(node);
    syncTreeSelection();
  }

  private TreePath getTreePath(DiagnosticsNode node) {
    if (node == null) {
      return null;
    }
    final DefaultMutableTreeNode treeNode = valueToTreeNode.get(node.getValueRef());
    if (treeNode == null) {
      return null;
    }
    return new TreePath(treeNode.getPath());
  }

  private static void expandAll(JTree tree, TreePath parent) {
    TreeNode node = (TreeNode)parent.getLastPathComponent();
    if (node.getChildCount() >= 0) {
      for (Enumeration e = node.children(); e.hasMoreElements(); ) {
        DefaultMutableTreeNode n = (DefaultMutableTreeNode)e.nextElement();
        if (n.getUserObject() instanceof DiagnosticsNode) {
          final DiagnosticsNode diagonsticsNode = (DiagnosticsNode)n.getUserObject();
          if (!diagonsticsNode.childrenReady()) {
            // Don't force an expand if the children haven't been preloaded.
            continue;
          }
        }
        expandAll(tree, parent.pathByAddingChild(n));
      }
    }
    tree.expandPath(parent);
  }

  protected void maybeUpdateValueUI(InspectorInstanceRef valueRef) {
    DefaultMutableTreeNode node = valueToTreeNode.get(valueRef);
    if (node == null) {
      // The value isn't shown in the parent tree. Nothing to do.
      return;
    }
    getTreeModel().nodeChanged(node);
  }

  void setupTreeNode(DefaultMutableTreeNode node, DiagnosticsNode diagnosticsNode, boolean expandChildren) {
    node.setUserObject(diagnosticsNode);
    node.setAllowsChildren(diagnosticsNode.hasChildren());
    final InspectorInstanceRef valueRef = diagnosticsNode.getValueRef();
    // Properties do not have unique values but that is ok.
    if (valueRef.getId() != null && !diagnosticsNode.isProperty()) {
      valueToTreeNode.put(valueRef, node);
    }
    if (parentTree != null) {
      parentTree.maybeUpdateValueUI(valueRef);
    }
    if (diagnosticsNode.hasChildren() || !diagnosticsNode.getInlineProperties().isEmpty()) {
      if (diagnosticsNode.childrenReady() || !diagnosticsNode.hasChildren()) {
        final CompletableFuture<ArrayList<DiagnosticsNode>> childrenFuture = diagnosticsNode.getChildren();
        assert (childrenFuture.isDone());
        setupChildren(diagnosticsNode, node, childrenFuture.getNow(null), expandChildren);
      }
      else {
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode("Loading..."));
      }
    }
  }

  void setupChildren(DiagnosticsNode parent, DefaultMutableTreeNode treeNode, ArrayList<DiagnosticsNode> children, boolean expandChildren) {
    final DefaultTreeModel model = getTreeModel();
    if (treeNode.getChildCount() > 0) {
      // Only case supported is this is the loading node.
      assert (treeNode.getChildCount() == 1);
      model.removeNodeFromParent((DefaultMutableTreeNode)treeNode.getFirstChild());
    }
    final ArrayList<DiagnosticsNode> inlineProperties = parent.getInlineProperties();
    treeNode.setAllowsChildren(!children.isEmpty() || !inlineProperties.isEmpty());

    if (inlineProperties != null) {
      for (DiagnosticsNode property : inlineProperties) {
        final DefaultMutableTreeNode childTreeNode = new DefaultMutableTreeNode();
        setupTreeNode(childTreeNode, property, false);
        childTreeNode.setAllowsChildren(false);
        model.insertNodeInto(childTreeNode, treeNode, treeNode.getChildCount());
      }
    }
    for (DiagnosticsNode child : children) {
      final DefaultMutableTreeNode childTreeNode = new DefaultMutableTreeNode();
      setupTreeNode(childTreeNode, child, false);
      model.insertNodeInto(childTreeNode, treeNode, treeNode.getChildCount());
    }
    if (expandChildren) {
      programaticExpansionInProgress = true;
      expandAll(myRootsTree, new TreePath(treeNode.getPath()));
      programaticExpansionInProgress = false;
    }
  }

  void maybeLoadChildren(DefaultMutableTreeNode node) {
    if (!(node.getUserObject() instanceof DiagnosticsNode)) {
      return;
    }
    final DiagnosticsNode diagnosticsNode = (DiagnosticsNode)node.getUserObject();
    if (diagnosticsNode.hasChildren() || !diagnosticsNode.getInlineProperties().isEmpty()) {
      if (placeholderChildren(node)) {
        whenCompleteUiThread(diagnosticsNode.getChildren(), (ArrayList<DiagnosticsNode> children, Throwable throwable) -> {
          if (throwable != null) {
            // Display that children failed to load.
            return;
          }
          if (node.getUserObject() != diagnosticsNode) {
            // Node changed, this data is stale.
            return;
          }
          setupChildren(diagnosticsNode, node, children, true);
          if (node == selectedNode || node == lastExpanded) {
            scrollAnimator.animateTo(new TreePath(node.getPath()));
          }
        });
      }
    }
  }

  public void onFlutterFrame() {
    if (rootFuture == null) {
      // This was the first frame.
      recomputeTreeRoot(null);
    }
    refreshRateLimiter.scheduleRequest();
  }

  private boolean identicalDiagnosticsNodes(DiagnosticsNode a, DiagnosticsNode b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    return a.getDartDiagnosticRef().equals(b.getDartDiagnosticRef());
  }

  public void onInspectorSelectionChanged() {
    if (detailsSubtree) {
      // Wait for the master to update.
      return;
    }
    if (pendingSelectionFuture != null) {
      // Pending selection changed is obsolete.
      if (!pendingSelectionFuture.isDone()) {
        pendingSelectionFuture.cancel(true);
        pendingSelectionFuture = null;
      }
    }

    pendingSelectionFuture = getInspectorService().getSelection(getSelectedDiagnostic(), treeType, isSummaryTree, objectGroup);

    CompletableFuture<?> allPending;
    final CompletableFuture<DiagnosticsNode> pendingDetailsFuture =
      isSummaryTree ? getInspectorService().getSelection(getSelectedDiagnostic(), treeType, false, objectGroup) : null;
    if (isSummaryTree) {
      allPending = CompletableFuture.allOf(pendingDetailsFuture, pendingSelectionFuture);
    }
    else {
      allPending = pendingSelectionFuture;
    }
    whenCompleteUiThread(allPending, (ignored, error) -> {
      final DiagnosticsNode newSelection = pendingSelectionFuture.getNow(null);
      pendingSelectionFuture = null;
      if (error != null) {
        LOG.error(error);
        return;
      }

      if (newSelection == null) {
        // Its ok.. nothing was selected.
        return;
      }

      {
        if (newSelection == null) {
          myRootsTree.clearSelection();
          // TODO(jacobr): update subtree?
          return;
        }
        DiagnosticsNode detailsSelection = null;
        if (pendingDetailsFuture != null) {
          detailsSelection = pendingDetailsFuture.getNow(null);
        }
        subtreeRoot = newSelection;
        applyNewSelection(newSelection, detailsSelection, true);
        return;

        // This case only exists for the RenderObject tree which we haven't updated yet to use the new UI style.
        // TODO(jacobr): delete it.
        /*
        whenCompleteUiThread(getInspectorService().getParentChain(newSelection, objectGroup), (ArrayList<DiagnosticsPathNode> path, Throwable ex) -> {
          if (ex != null) {
            LOG.error(ex);
            return;
          }
          DefaultMutableTreeNode treeNode = getRootNode();
          final DefaultTreeModel model = getTreeModel();
          final DefaultMutableTreeNode[] treePath = new DefaultMutableTreeNode[path.size()];
          for (int i = 0; i < path.size(); ++i) {
            treePath[i] = treeNode;
            final DiagnosticsPathNode pathNode = path.get(i);
            final DiagnosticsNode pathDiagnosticNode = pathNode.getNode();
            final ArrayList<DiagnosticsNode> newChildren = pathNode.getChildren();
            final DiagnosticsNode existingNode = getDiagnosticNode(treeNode);
            boolean nodeChanged = false;
            if (!identicalDiagnosticsNodes(pathDiagnosticNode, existingNode)) {
              treeNode.setUserObject(pathDiagnosticNode);
              // Clear children to force an update on this subtree. Not necessarily required.
              nodeChanged = true;
            }
            treeNode.setAllowsChildren(!newChildren.isEmpty());
            for (int j = 0; j < newChildren.size(); ++j) {
              final DiagnosticsNode newChild = newChildren.get(j);
              if (j >= treeNode.getChildCount() || !identicalDiagnosticsNodes(newChild, getDiagnosticNode(treeNode.getChildAt(j)))) {
                final DefaultMutableTreeNode child;
                if (j >= treeNode.getChildCount()) {
                  child = new DefaultMutableTreeNode();
                  treeNode.add(child);
                  nodeChanged = true;
                }
                else {
                  child = (DefaultMutableTreeNode)treeNode.getChildAt(j);
                }
                if (j != pathNode.getChildIndex()) {
                  setupTreeNode(child, newChild, false);
                  model.reload(child);
                }
                else {
                  child.setUserObject(newChild);
                  child.setAllowsChildren(newChild.hasChildren());
                  child.removeAllChildren();
                }

                // TODO(jacobr): this is wrong. We shouldn't always be setting the node as changed.
                nodeChanged = true;
                // TODO(jacobr): we are likely calling the wrong node structure changed APIs.
                // For example, we should be getting these change notifications for free if we
                // switched to call methods on the model object directly to manipulate the tree.
                model.nodeChanged(child);
                model.nodeStructureChanged(child);
              }
              model.reload(treeNode);
            }
            if (i != path.size() - 1) {
              treeNode = (DefaultMutableTreeNode)treeNode.getChildAt(pathNode.getChildIndex());
            }
          }
          final TreePath selectionPath = new TreePath(treePath);
          myRootsTree.setSelectionPath(selectionPath); /// XXX SYNC TREE SELECTION
          myRootsTree.scrollPathToVisible(selectionPath);
          /// XXX
        });
        */
      }
    });
  }

  private CompletableFuture<DiagnosticsNode> applyNewSelection(DiagnosticsNode newSelection,
                                                               DiagnosticsNode detailsSelection,
                                                               boolean setSubtreeRoot) {
    final DefaultMutableTreeNode nodeInTree = findMatchingTreeNode(newSelection);

    if (nodeInTree == null) {
      // The tree has probably changed since we last updated. Do a full refresh
      // so that the tree includes the new node we care about.
      return recomputeTreeRoot(newSelection);
    }

    refreshSelection(newSelection, detailsSelection, setSubtreeRoot);
    return CompletableFuture.completedFuture(newSelection);
  }

  private DiagnosticsNode getSelectedDiagnostic() {
    return getDiagnosticNode(selectedNode);
  }

  private void maybePopulateChildren(DefaultMutableTreeNode treeNode) {
    final Object userObject = treeNode.getUserObject();
    if (userObject instanceof DiagnosticsNode) {
      final DiagnosticsNode diagnostic = (DiagnosticsNode)userObject;
      if (diagnostic.hasChildren() && treeNode.getChildCount() == 0) {
        final CompletableFuture<ArrayList<DiagnosticsNode>> childrenFuture = diagnostic.getChildren();
        whenCompleteUiThread(childrenFuture, (ArrayList<DiagnosticsNode> children, Throwable throwable) -> {
          if (throwable != null) {
            // TODO(jacobr): show an error in the UI that we could not load children.
            return;
          }
          if (treeNode.getChildCount() == 0) {
            setupChildren(diagnostic, treeNode, children, true);
          }
          getTreeModel().nodeStructureChanged(treeNode);
          if (treeNode == selectedNode) {
            myRootsTree.expandPath(new TreePath(treeNode.getPath()));
          }
        });
      }
    }
  }

  private void setSelectedNode(DefaultMutableTreeNode newSelection) {
    if (newSelection == selectedNode) {
      return;
    }
    if (selectedNode != null) {
      if (!detailsSubtree) {
        getTreeModel().nodeChanged(selectedNode.getParent());
      }
    }
    selectedNode = newSelection;
    if (selectedNode != null) {
      scrollAnimator.animateTo(new TreePath(selectedNode.getPath()));
    }

    lastExpanded = null; // New selected node takes prescidence.
    endShowNode();
    if (subtreePanel != null) {
      subtreePanel.endShowNode();
    }
    else if (parentTree != null) {
      parentTree.endShowNode();
    }
  }

  private void selectionChanged() {
    if (programaticSelectionChangeInProgress) {
      return;
    }
    final DefaultMutableTreeNode[] selectedNodes = myRootsTree.getSelectedNodes(DefaultMutableTreeNode.class, null);
    for (DefaultMutableTreeNode node : selectedNodes) {
      maybePopulateChildren(node);
    }
    if (selectedNodes.length > 0) {
      setSelectedNode(selectedNodes[0]);
      final DiagnosticsNode selectedDiagnostic = getSelectedDiagnostic();
      // Don't reroot if the selected value is already visible in the details tree.
      final boolean maybeReroot = isSummaryTree && subtreePanel != null && selectedDiagnostic != null &&
                                  !subtreePanel.hasDiagnosticsValue(selectedDiagnostic.getValueRef());
      syncSelectionHelper(maybeReroot, null);
      if (maybeReroot == false) {
        if (isSummaryTree && subtreePanel != null) {
          subtreePanel.selectAndShowNode(selectedDiagnostic);
        }
        else if (parentTree != null) {
          parentTree.selectAndShowNode(firstAncestorInParentTree(selectedNode));
        }
      }
    }
  }

  DiagnosticsNode firstAncestorInParentTree(DefaultMutableTreeNode node) {
    if (parentTree == null) {
      return getDiagnosticNode(node);
    }
    while (node != null) {
      DiagnosticsNode diagnostic = getDiagnosticNode(node);
      if (diagnostic != null && parentTree.hasDiagnosticsValue(diagnostic.getValueRef())) {
        return parentTree.findDiagnosticsValue(diagnostic.getValueRef());
      }
      node = (DefaultMutableTreeNode)node.getParent();
    }
    return null;
  }

  private void syncSelectionHelper(boolean maybeRerootSubtree, DiagnosticsNode detailsSelection) {
    if (!detailsSubtree && selectedNode != null) {
      // Do we need this?... this is back when we were highlighting.
      getTreeModel().nodeChanged(selectedNode.getParent());
    }
    final DiagnosticsNode diagnostic = getSelectedDiagnostic();
    if (diagnostic != null) {
      if (isCreatedByLocalProject(diagnostic)) {
        diagnostic.getCreationLocation().getXSourcePosition().createNavigatable(getFlutterApp().getProject())
          .navigate(false);
      }
    }
    if (myPropertiesPanel != null) {
      myPropertiesPanel.showProperties(diagnostic);
    }
    if (detailsSubtree || subtreePanel == null) {
      if (getInspectorService() != null && diagnostic != null) {
        DiagnosticsNode toSelect = diagnostic;
        if (diagnostic.isProperty()) {
          // Set the selection to the parent of the property not the property as what we
          // should display on device is the selected widget not the selected property
          // of the widget.
          final TreePath path = new TreePath(selectedNode.getPath());
          // TODO(jacobr): even though it isn't currently an issue, we should
          // search for the first non-diagnostic node parent instead of just
          // assuming the first parent is a regular node.
          toSelect = getDiagnosticNode((DefaultMutableTreeNode)path.getPathComponent(path.getPathCount() - 2));
        }
        getInspectorService().setSelection(toSelect.getValueRef(), detailsSubtree && !maybeRerootSubtree);
      }
    }

    if (maybeRerootSubtree) {
      showDetailSubtrees(diagnostic, detailsSelection);
    }
  }

  private void initTree(final Tree tree) {
    tree.setCellRenderer(new DiagnosticsTreeCellRenderer());
    tree.setShowsRootHandles(true);
    TreeUtil.installActions(tree);

    PopupHandler.installUnknownPopupHandler(tree, createTreePopupActions(), ActionManager.getInstance());

    new TreeSpeedSearch(tree) {
      @Override
      protected String getElementText(Object element) {
        final TreePath path = (TreePath)element;
        final DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
        final Object object = node.getUserObject();
        if (object instanceof DiagnosticsNode) {
          // TODO(pq): consider a specialized String for matching.
          return object.toString();
        }
        return null;
      }
    };
  }

  private ActionGroup createTreePopupActions() {
    final DefaultActionGroup group = new DefaultActionGroup();
    final ActionManager actionManager = ActionManager.getInstance();
    group.add(actionManager.getAction(InspectorActions.JUMP_TO_SOURCE));
    group.add(actionManager.getAction(InspectorActions.JUMP_TO_TYPE_SOURCE));
    return group;
  }

  @Override
  public void dispose() {
    final InspectorService service = getInspectorService();
    if (service != null) {
      service.disposeGroup(objectGroup);
    }
    // TODO(jacobr): verify subpanels are disposed as well.
  }

  public class TreeDataProvider extends Tree implements DataProvider, Disposable {
    public final boolean detailsSubtree;

    private DefaultMutableTreeNode highlightedRoot;

    public DefaultMutableTreeNode getHighlightedRoot() {
      return highlightedRoot;
    }

    public void setHighlightedRoot(DefaultMutableTreeNode value) {
      if (highlightedRoot == value) {
        return;
      }
      highlightedRoot = value;
      // TODO(jacobr): we only really need to repaint the selected subtree.
      repaint();
    }

    @Override
    public void setUI(TreeUI ui) {
      final InspectorTreeUI inspectorTreeUI = ui instanceof InspectorTreeUI ? (InspectorTreeUI)ui : new InspectorTreeUI();
      super.setUI(inspectorTreeUI);
      inspectorTreeUI.setRightChildIndent(JBUI.scale(4));
    }

    private TreeDataProvider(final DefaultMutableTreeNode treemodel,
                             String treeName,
                             boolean detailsSubtree,
                             String parentTreeName,
                             boolean rootVisible) {
      super(treemodel);
      // XXX not needed?
      setUI(new InspectorTreeUI());
      final BasicTreeUI ui = (BasicTreeUI)getUI();
      setBackground(VERY_LIGHT_GREY);
      this.detailsSubtree = detailsSubtree;
      setRootVisible(rootVisible);
      registerShortcuts();
      if (detailsSubtree) {
        getEmptyText().setText(treeName + " subtree of the selected " + parentTreeName);
      }
      else {
        getEmptyText().setText(treeName + " tree for the running app");
      }
    }

    void registerShortcuts() {
      DebuggerUIUtil.registerActionOnComponent(InspectorActions.JUMP_TO_TYPE_SOURCE, this, this);
    }

    @SuppressWarnings("EmptyMethod")
    @Override
    protected void paintComponent(final Graphics g) {
      // TOOD(jacobr): actually perform some custom painting.
      // For example, we should consider custom painting to display offstage objects differently.
      super.paintComponent(g);
    }

    @Override
    public void dispose() {
      // TODO(jacobr): do we have anything to dispose?
    }

    @Nullable
    @Override
    public Object getData(String dataId) {
      if (INSPECTOR_KEY.is(dataId)) {
        return this;
      }
      if (PlatformDataKeys.PREDEFINED_TEXT.is(dataId)) {
        final XValueNodeImpl[] selectedNodes = getSelectedNodes(XValueNodeImpl.class, null);
        if (selectedNodes.length == 1 && selectedNodes[0].getFullValueEvaluator() == null) {
          return DebuggerUIUtil.getNodeRawValue(selectedNodes[0]);
        }
      }
      return null;
    }
  }

  static class PropertyNameColumnInfo extends ColumnInfo<DefaultMutableTreeNode, DiagnosticsNode> {
    private final TableCellRenderer renderer = new PropertyNameRenderer();

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
    private final TableCellRenderer defaultRenderer;

    public PropertyValueColumnInfo(String name) {
      super(name);
      defaultRenderer = new SimplePropertyValueRenderer();
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

  private static class PropertiesPanel extends TreeTableView implements DataProvider {
    /**
     * Diagnostic we are displaying properties for.
     */
    private DiagnosticsNode diagnostic;

    /**
     * Current properties being displayed.
     */
    private ArrayList<DiagnosticsNode> currentProperties;

    private InspectorObjectGroup objectGroup;

    private final FlutterApp flutterApp;

    PropertiesPanel(FlutterApp flutterApp) {
      super(new ListTreeTableModelOnColumns(
        new DefaultMutableTreeNode(),
        new ColumnInfo[]{
          new PropertyNameColumnInfo("Property"),
          new PropertyValueColumnInfo("Value")
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

    @Nullable
    private InspectorService getInspectorService() {
      return flutterApp.getInspectorService();
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
      this.diagnostic = diagnostic;

      if (diagnostic == null) {
        getEmptyText().setText(FlutterBundle.message("app.inspector.nothing_to_show"));
        getTreeModel().setRoot(new DefaultMutableTreeNode());
        if (objectGroup != null) {
          getInspectorService().disposeGroup(objectGroup);
        }
        objectGroup = new InspectorObjectGroup();
        return;
      }
      getEmptyText().setText(FlutterBundle.message("app.inspector.loading_properties"));
      // Use a fresh object group for the new properties.
      InspectorObjectGroup nextObjectGroup = new InspectorObjectGroup();
      whenCompleteUiThread(diagnostic.getProperties(nextObjectGroup), (ArrayList<DiagnosticsNode> properties, Throwable throwable) -> {
        // Don't dispose the old object group until we have updated the UI to show nodes from the new group.
        if (objectGroup != null) {
          getInspectorService().disposeGroup(objectGroup);
        }
        objectGroup = nextObjectGroup;

        if (throwable != null) {
          getTreeModel().setRoot(new DefaultMutableTreeNode());
          getEmptyText().setText(FlutterBundle.message("app.inspector.error_loading_properties"));
          LOG.error(throwable);
          return;
        }
        showPropertiesHelper(properties);
        PopupHandler.installUnknownPopupHandler(this, createTreePopupActions(), ActionManager.getInstance());
      });
    }

    private void showPropertiesHelper(ArrayList<DiagnosticsNode> properties) {
      currentProperties = properties;
      if (properties.size() == 0) {
        getTreeModel().setRoot(new DefaultMutableTreeNode());
        getEmptyText().setText(FlutterBundle.message("app.inspector.no_properties"));
        return;
      }
      whenCompleteUiThread(loadPropertyMetadata(properties), (Void ignored, Throwable errorGettingInstances) -> {
        if (errorGettingInstances != null) {
          // TODO(jacobr): show error message explaining properties could not
          // be loaded.
          getTreeModel().setRoot(new DefaultMutableTreeNode());
          LOG.error(errorGettingInstances);
          getEmptyText().setText(FlutterBundle.message("app.inspector.error_loading_property_details"));
          return;
        }
        setModelFromProperties(properties);
      });
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

    private CompletableFuture<?> refresh() {
      if (diagnostic == null) {
        final CompletableFuture<Void> refreshComplete = new CompletableFuture<>();
        refreshComplete.complete(null);
        return refreshComplete;
      }
      // We don't know whether we will switch to the new group or keep the current group
      // until we have tested whether the properties are identical.
      InspectorObjectGroup candidateObjectGroup = new InspectorObjectGroup();

      final CompletableFuture<ArrayList<DiagnosticsNode>> propertiesFuture = diagnostic.getProperties(candidateObjectGroup);
      whenCompleteUiThread(propertiesFuture, (ArrayList<DiagnosticsNode> properties, Throwable throwable) -> {
        if (throwable != null || propertiesIdentical(properties, currentProperties)) {
          // Dispose the new group as it wasn't used
          getInspectorService().disposeGroup(candidateObjectGroup);
          return;
        }
        getInspectorService().disposeGroup(objectGroup);
        objectGroup = candidateObjectGroup;

        showPropertiesHelper(properties);
      });
      return propertiesFuture;
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
      return INSPECTOR_KEY.is(dataId) ? getTree() : null;
    }
  }

  private static SimpleTextAttributes textAttributesForLevel(DiagnosticLevel level) {
    switch (level) {
      case hidden:
      case fine:
        return SimpleTextAttributes.GRAYED_ATTRIBUTES;
      case warning:
        return WARNING_ATTRIBUTES;
      case error:
        return SimpleTextAttributes.ERROR_ATTRIBUTES;
      case debug:
      case info:
      default:
        return SimpleTextAttributes.REGULAR_ATTRIBUTES;
    }
  }

  private static class PropertyNameRenderer extends ColoredTableCellRenderer {
    @Override
    protected void customizeCellRenderer(JTable table, @Nullable Object value, boolean selected, boolean hasFocus, int row, int column) {
      if (value == null) return;
      final DiagnosticsNode node = (DiagnosticsNode)value;
      // If we should not show a separator then we should show the property name
      // as part of the property value instead of in its own column.
      if (!node.getShowSeparator() || !node.getShowName()) {
        return;
      }
      // Present user defined properties in BOLD.
      final SimpleTextAttributes attributes =
        node.hasCreationLocation() ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES;
      append(node.getName(), attributes);

      // Set property description in tooltip.
      // TODO (pq):
      //  * consider tooltips for values
      //  * consider rich navigation hovers (w/ styling and navigable docs)
      final CompletableFuture<String> propertyDoc = node.getPropertyDoc();
      final String doc = propertyDoc.getNow(null);
      if (doc != null) {
        setToolTipText(doc);
      }
      else {
        // Make sure we see nothing stale while we wait.
        setToolTipText(null);
        AsyncUtils.whenCompleteUiThread(propertyDoc, (String tooltip, Throwable th) -> {
          if (th != null) {
            LOG.error(th);
          }
          setToolTipText(tooltip);
        });
      }
    }
  }

  /**
   * Property value renderer that handles common cases where colored text
   * is sufficient to describe the property value.
   */
  private static class SimplePropertyValueRenderer extends ColoredTableCellRenderer {
    final ColorIconMaker colorIconMaker = new ColorIconMaker();

    private static int getIntProperty(Map<String, InstanceRef> properties, String propertyName) {
      if (properties == null || !properties.containsKey(propertyName)) {
        return 0;
      }
      return Integer.parseInt(properties.get(propertyName).getValueAsString());
    }

    @Override
    protected void customizeCellRenderer(JTable table, @Nullable Object value, boolean selected, boolean hasFocus, int row, int column) {
      setToolTipText(null);
      if (value == null) return;
      final DiagnosticsNode node = (DiagnosticsNode)value;
      final SimpleTextAttributes textAttributes = textAttributesForLevel(node.getLevel());

      boolean appendDescription = true;

      if (node.getTooltip() != null) {
        setToolTipText(node.getTooltip());
      }
      // TODO(jacobr): also provide custom UI display for padding, transform,
      // and alignment properties.
      final CompletableFuture<Map<String, InstanceRef>> propertiesFuture = node.getValueProperties();
      if (propertiesFuture != null && propertiesFuture.isDone() && !propertiesFuture.isCompletedExceptionally()) {
        final Map<String, InstanceRef> properties = propertiesFuture.getNow(null);
        if (node.isEnumProperty() && properties != null) {
          // We can display a better tooltip as we have access to introspection
          // via the observatory service.
          setToolTipText("Allowed values:\n" + Joiner.on('\n').join(properties.keySet()));
        }

        final String propertyType = node.getPropertyType();
        if (propertyType != null) {
          switch (propertyType) {
            case "Color": {
              final int alpha = getIntProperty(properties, "alpha");
              final int red = getIntProperty(properties, "red");
              final int green = getIntProperty(properties, "green");
              final int blue = getIntProperty(properties, "blue");

              //noinspection UseJBColor
              final Color color = new Color(red, green, blue, alpha);
              this.setIcon(colorIconMaker.getCustomIcon(color));
              if (alpha == 255) {
                append(String.format("#%02x%02x%02x", red, green, blue), textAttributes);
              }
              else {
                append(String.format("#%02x%02x%02x%02x", alpha, red, green, blue), textAttributes);
              }

              appendDescription = false;

              break;
            }

            case "IconData": {
              // IconData(U+0E88F)
              final int codePoint = getIntProperty(properties, "codePoint");
              if (codePoint > 0) {
                final Icon icon = FlutterMaterialIcons.getMaterialIconForHex(String.format("%1$04x", codePoint));
                if (icon != null) {
                  this.setIcon(icon);
                  this.setIconOpaque(false);
                  this.setTransparentIconBackground(true);
                }
              }
              break;
            }
          }
        }
      }

      if (appendDescription) {
        append(node.getDescription(), textAttributes);
      }
    }
  }

  /**
   * Duplicates code from ColoredTreeCellRenderer as was we need to customize
   * are not feasible with subclassing.
   */
  private abstract class InspectorColoredTreeCellRenderer extends MultiIconSimpleColoredComponent implements TreeCellRenderer {
    /**
     * Defines whether the tree is selected or not
     */
    protected boolean mySelected;
    /**
     * Defines whether the tree has focus or not
     */
    private boolean myFocused;
    private boolean myFocusedCalculated;

    protected JTree myTree;

    private boolean myOpaque = true;

    @Override
    public final Component getTreeCellRendererComponent(JTree tree,
                                                        Object value,
                                                        boolean selected,
                                                        boolean expanded,
                                                        boolean leaf,
                                                        int row,
                                                        boolean hasFocus) {
      myTree = tree;

      clear();

      mySelected = selected;
      myFocusedCalculated = false;

      // We paint background if and only if tree path is selected and tree has focus.
      // If path is selected and tree is not focused then we just paint focused border.
      if (UIUtil.isFullRowSelectionLAF()) {
        setBackground(selected ? UIUtil.getTreeSelectionBackground() : null);
      }
      else if (WideSelectionTreeUI.isWideSelection(tree)) {
        setPaintFocusBorder(false);
        if (selected) {
          setBackground(hasFocus ? UIUtil.getTreeSelectionBackground() : UIUtil.getTreeUnfocusedSelectionBackground());
        }
      }
      else if (selected) {
        setPaintFocusBorder(true);
        if (isFocused()) {
          setBackground(UIUtil.getTreeSelectionBackground());
        }
        else {
          setBackground(null);
        }
      }
      else {
        setBackground(null);
      }

      if (value instanceof LoadingNode) {
        setForeground(JBColor.GRAY);
        // XXX setIcon(LOADING_NODE_ICON);
      }
      else {
        setForeground(tree.getForeground());
        setIcon(null);
      }

/*      if (UIUtil.isUnderGTKLookAndFeel()){
        super.setOpaque(false);  // avoid nasty background
        super.setIconOpaque(false);
      }
      else if (UIUtil.isUnderNimbusLookAndFeel() && selected && hasFocus) {
        super.setOpaque(false);  // avoid erasing Nimbus focus frame
        super.setIconOpaque(false);
      }
      else if (WideSelectionTreeUI.isWideSelection(tree)) {
        super.setOpaque(false);  // avoid erasing Nimbus focus frame
        super.setIconOpaque(false);
      }
      else {
        */
      super.setOpaque(myOpaque || selected && hasFocus || selected && isFocused()); // draw selection background even for non-opaque tree


      if (tree.getUI() instanceof WideSelectionTreeUI && UIUtil.isUnderAquaBasedLookAndFeel()) {
        setMyBorder(null);
        setIpad(new Insets(0, 2, 0, 2));
      }

      customizeCellRenderer(tree, value, selected, expanded, leaf, row, hasFocus);

      return this;
    }

    public JTree getTree() {
      return myTree;
    }

    protected final boolean isFocused() {
      if (!myFocusedCalculated) {
        myFocused = calcFocusedState();
        myFocusedCalculated = true;
      }
      return myFocused;
    }

    private boolean calcFocusedState() {
      return myTree.hasFocus();
    }

    @Override
    public void setOpaque(boolean isOpaque) {
      myOpaque = isOpaque;
      super.setOpaque(isOpaque);
    }

    protected InspectorTreeUI getUI() {
      return (InspectorTreeUI)myTree.getUI();
    }

    @Override
    public Font getFont() {
      Font font = super.getFont();

      // Cell renderers could have no parent and no explicit set font.
      // Take tree font in this case.
      if (font != null) return font;
      JTree tree = getTree();
      return tree != null ? tree.getFont() : null;
    }

    /**
     * When the item is selected then we use default tree's selection foreground.
     * It guaranties readability of selected text in any LAF.
     */
    @Override
    public void append(@NotNull @Nls String fragment, @NotNull SimpleTextAttributes attributes, boolean isMainText) {
      /* XXX
      Color color = Color.WHITE;
      if (mySelected && isFocused()) {
        super.append(fragment, new SimpleTextAttributes(attributes.getStyle(), Color.BLACK), isMainText);
      }
      else if (mySelected) {
        super.append(fragment, new SimpleTextAttributes(attributes.getStyle(), Color.BLACK), isMainText);
      }
      else */
      {
        super.append(fragment, attributes, isMainText);
      }
    }

    /*@Override*/
    void revalidateAndRepaint() {
      // no need for this in a renderer
    }

    /**
     * This method is invoked only for customization of component.
     * All component attributes are cleared when this method is being invoked.
     */
    public abstract void customizeCellRenderer(@NotNull JTree tree,
                                               Object value,
                                               boolean selected,
                                               boolean expanded,
                                               boolean leaf,
                                               int row,
                                               boolean hasFocus);

    @Override
    public AccessibleContext getAccessibleContext() {
      if (accessibleContext == null) {
        // XXX can't do this
        //  accessibleContext = new com.intellij.ui.ColoredTreeCellRenderer.AccessibleColoredTreeCellRenderer();
      }
      return accessibleContext;
    }

    protected class AccessibleColoredTreeCellRenderer extends AccessibleSimpleColoredComponent {
    }
  }


  private class DiagnosticsTreeCellRenderer extends InspectorColoredTreeCellRenderer {
    /**
     * Split text into two groups, word characters at the start of a string
     * and all other characters. Skip an <code>-</code> or <code>#</code> between the
     * two groups.
     */
    private final Pattern primaryDescriptionPattern = Pattern.compile("(\\w+)[-#]?(.*)");

    private JTree tree;
    private boolean selected;

    final ColorIconMaker colorIconMaker = new ColorIconMaker();

    final JBColor HIGHLIGHT_COLOR = new JBColor(new Color(202, 191, 69), new Color(99, 101, 103));
    final JBColor SHOW_MATCH_COLOR = new JBColor(new Color(225, 225, 0), new Color(90, 93, 96));
    final JBColor LINKED_COLOR = new JBColor(new Color(255, 255, 220), new Color(70, 73, 76));

    public void customizeCellRenderer(
      @NotNull final JTree tree,
      final Object value,
      final boolean selected,
      final boolean expanded,
      final boolean leaf,
      final int row,
      final boolean hasFocus
    ) {
      setOpaque(false);
      setIconOpaque(false);
      setTransparentIconBackground(true);

      this.tree = tree;
      this.selected = selected;

      final Object userObject = ((DefaultMutableTreeNode)value).getUserObject();
      if (userObject instanceof String) {
        appendText((String)userObject, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        return;
      }
      if (!(userObject instanceof DiagnosticsNode)) return;
      final DiagnosticsNode node = (DiagnosticsNode)userObject;

      boolean highlight = selected;
      boolean isLinkedChild = false;
      if (!highlight) {
        if (detailsSubtree && isCreatedByLocalProject(node)) {
          isLinkedChild = parentTree.hasDiagnosticsValue(node.getValueRef());
        }
        else {
          if (subtreePanel != null) {
            isLinkedChild = subtreePanel.hasDiagnosticsValue(node.getValueRef());
          }
        }
      }
      if (highlight) {
        setOpaque(true);
        setIconOpaque(false);
        setTransparentIconBackground(true);
        setBackground(HIGHLIGHT_COLOR);
        //UIUtil.getTreeSelectionBackground());
      }
      else if (isLinkedChild) {
        setOpaque(true);
        setIconOpaque(false);
        setTransparentIconBackground(true);
        if (currentShowNode == value) {
          setBackground(SHOW_MATCH_COLOR);
        }
        else {
          setBackground(LINKED_COLOR);
        }
      }

      final String name = node.getName();
      SimpleTextAttributes textAttributes = textAttributesForLevel(node.getLevel());

      if (node.isProperty()) {
        final String propertyType = node.getPropertyType();
        JsonObject properties = node.getValuePropertiesJson();
        boolean appendDescription = true;

        if (name != null && !name.isEmpty() && node.getShowName()) {
          appendText(name + node.getSeparator() + " ", isCreatedByLocalProject(node) ? textAttributes
            .derive(SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES.getStyle(), null, null, null) : textAttributes);
        }

        String description = node.getDescription();
        if (propertyType != null && properties != null) {
          switch (propertyType) {
            case "Color": {
              final int alpha = getIntMember(properties, "alpha");
              final int red = getIntMember(properties, "red");
              final int green = getIntMember(properties, "green");
              final int blue = getIntMember(properties, "blue");

              if (alpha == 255) {
                description = String.format("#%02x%02x%02x", red, green, blue);
              }
              else {
                description = String.format("#%02x%02x%02x%02x", alpha, red, green, blue);
              }

              //noinspection UseJBColor
              final Color color = new Color(red, green, blue, alpha);
              this.addIcon(colorIconMaker.getCustomIcon(color));
              this.setIconOpaque(false);
              this.setTransparentIconBackground(true);
              break;
            }

            case "IconData": {
              final int codePoint = getIntMember(properties, "codePoint");
              if (codePoint > 0) {
                final Icon icon = FlutterMaterialIcons.getMaterialIconForHex(String.format("%1$04x", codePoint));
                if (icon != null) {
                  this.addIcon(icon);
                  this.setIconOpaque(false);
                  this.setTransparentIconBackground(true);
                }
              }
              break;
            }
          }
        }

        if (appendDescription) {
          // TODO(jacobr): custom display for units, iterables, and padding.
          appendText(description, textAttributes);
        }
      }
      else {
        // Non property, regular node case.
        if (name != null && !name.isEmpty() && node.getShowName()) {
          // color in name?
          if (name.equals("child") || name.startsWith("child ")) {
            appendText(name, SimpleTextAttributes.GRAYED_ATTRIBUTES);
          }
          else {
            appendText(name, textAttributes);
          }

          if (node.getShowSeparator()) {
            // Is this good?
            appendText(node.getSeparator(), SimpleTextAttributes.GRAY_ATTRIBUTES);
          }
          else {
            appendText(" ", SimpleTextAttributes.GRAY_ATTRIBUTES);
          }
        }

        if (isCreatedByLocalProject(node)) {
          textAttributes = textAttributes.derive(SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES.getStyle(), null, null, null);
        }

        // TODO(jacobr): custom display for units, colors, iterables, and icons.
        final String description = node.getDescription();
        final Matcher match = primaryDescriptionPattern.matcher(description);
        if (match.matches()) {
          appendText(" ", SimpleTextAttributes.GRAY_ATTRIBUTES);
          appendText(match.group(1), textAttributes);
          appendText(" ", textAttributes);
          appendText(match.group(2), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        else if (!node.getDescription().isEmpty()) {
          appendText(" ", SimpleTextAttributes.GRAY_ATTRIBUTES);
          appendText(node.getDescription(), textAttributes);
        }

        final Icon icon = node.getIcon();
        if (icon != null) {
          setIcon(icon);
        }
      }
      if (node.hasTooltip()) {
        setToolTipText(node.getTooltip());
      }

      ///setBackground(Color.red); // XXX
    }

    private void appendText(@NotNull String text, @NotNull SimpleTextAttributes attributes) {
      appendFragmentsForSpeedSearch(tree, text, attributes, selected, this);
    }

    // Generally duplicated from SpeedSearchUtil.appendFragmentsForSpeedSearch
    public void appendFragmentsForSpeedSearch(@NotNull JComponent speedSearchEnabledComponent,
                                              @NotNull String text,
                                              @NotNull SimpleTextAttributes attributes,
                                              boolean selected,
                                              @NotNull MultiIconSimpleColoredComponent simpleColoredComponent) {
      final SpeedSearchSupply speedSearch = SpeedSearchSupply.getSupply(speedSearchEnabledComponent);
      if (speedSearch != null) {
        final Iterable<TextRange> fragments = speedSearch.matchingFragments(text);
        if (fragments != null) {
          final Color fg = attributes.getFgColor();
          final Color bg = selected ? UIUtil.getTreeSelectionBackground() : UIUtil.getTreeTextBackground();
          final int style = attributes.getStyle();
          final SimpleTextAttributes plain = new SimpleTextAttributes(style, fg);
          final SimpleTextAttributes highlighted = new SimpleTextAttributes(bg, fg, null, style | SimpleTextAttributes.STYLE_SEARCH_MATCH);
          appendColoredFragments(simpleColoredComponent, text, fragments, plain, highlighted);
          return;
        }
      }
      simpleColoredComponent.append(text, attributes);
    }

    public void appendColoredFragments(final MultiIconSimpleColoredComponent simpleColoredComponent,
                                       final String text,
                                       Iterable<TextRange> colored,
                                       final SimpleTextAttributes plain, final SimpleTextAttributes highlighted) {
      final List<Pair<String, Integer>> searchTerms = new ArrayList<>();
      for (TextRange fragment : colored) {
        searchTerms.add(Pair.create(fragment.substring(text), fragment.getStartOffset()));
      }

      int lastOffset = 0;
      for (Pair<String, Integer> pair : searchTerms) {
        if (pair.second > lastOffset) {
          simpleColoredComponent.append(text.substring(lastOffset, pair.second), plain);
        }

        simpleColoredComponent.append(text.substring(pair.second, pair.second + pair.first.length()), highlighted);
        lastOffset = pair.second + pair.first.length();
      }

      if (lastOffset < text.length()) {
        simpleColoredComponent.append(text.substring(lastOffset), plain);
      }
    }
  }

  boolean isCreatedByLocalProject(DiagnosticsNode node) {
    if (node.isCreatedByLocalProject()) {
      return true;
    }
    // TODO(jacobr): remove the following code once the
    // `setPubRootDirectories` method has been in two revs of the Flutter Alpha
    // channel. The feature is expected to have landed in the Flutter dev
    // chanel on March 2, 2018.
    final Location location = node.getCreationLocation();
    if (location == null) {
      return false;
    }
    final VirtualFile file = location.getFile();
    if (file == null) {
      return false;
    }
    final String filePath = file.getCanonicalPath();
    for (PubRoot root : getFlutterApp().getPubRoots()) {
      if (filePath.startsWith(root.getRoot().getCanonicalPath())) {
        return true;
      }
    }
    return false;
  }

  boolean placeholderChildren(DefaultMutableTreeNode node) {
    return node.getChildCount() == 0 ||
           (node.getChildCount() == 1 && ((DefaultMutableTreeNode)node.getFirstChild()).getUserObject() instanceof String);
  }

  private class MyTreeExpansionListener implements TreeExpansionListener {
    @Override
    public void treeExpanded(TreeExpansionEvent event) {
      TreePath path = event.getPath();
      DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode)event.getPath().getLastPathComponent();
      maybeLoadChildren(treeNode);

      if (!programaticExpansionInProgress) {
        lastExpanded = treeNode;
        scrollAnimator.animateTo(path);
      }
    }

    @Override
    public void treeCollapsed(TreeExpansionEvent event) {
    }
  }

  @Nullable
  public static Tree getTree(final DataContext e) {
    return e.getData(INSPECTOR_KEY);
  }
}
