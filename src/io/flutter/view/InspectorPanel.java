/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.google.common.base.Joiner;
import com.intellij.execution.ui.layout.impl.JBRunnerTabs;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.dualView.TreeTableView;
import com.intellij.ui.speedSearch.SpeedSearchUtil;
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
import org.dartlang.vm.service.element.InstanceRef;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.accessibility.AccessibleContext;
import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.plaf.TreeUI;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.flutter.utils.AsyncUtils.whenCompleteUiThread;

// TODO(devoncarew): Should we filter out the CheckedModeBanner node type?
// TODO(devoncarew): Should we filter out the WidgetInspector node type?

public class InspectorPanel extends JPanel implements Disposable, InspectorService.InspectorServiceClient {

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

  static final double HORIZONTAL_FRACTION = 0.65;
  static final double VERTICAL_FRACTION = 0.6;

  private final TreeDataProvider myRootsTree;
  @Nullable private final PropertiesPanel myPropertiesPanel;
  private final Computable<Boolean> isApplicable;
  private final InspectorService.FlutterTreeType treeType;
  private final FlutterView flutterView;
  @NotNull
  private final FlutterApp flutterApp;
  private final boolean detailsSubtree;
  private final boolean isSummaryTree;
  @NotNull
  final Splitter treeSplitter;
  @Nullable
  /**
   * Parent InspectorPanel if this is a details subtree
   */
  private final InspectorPanel parentTree;

  private CompletableFuture<DiagnosticsNode> rootFuture;
  /* Only valid if detailsSubtree is true. */
  private DiagnosticsNode subtreeRoot;

  private static final DataKey<Tree> INSPECTOR_KEY = DataKey.create("Flutter.InspectorKey");

  // We have to define this because SimpleTextAttributes does not define a
  // value for warnings. This color looks reasonable for warnings both
  // with the Darculaand the default themes.
  private static final SimpleTextAttributes WARNING_ATTRIBUTES = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color.ORANGE);

  @NotNull
  public FlutterApp getFlutterApp() {
    return flutterApp;
  }

  private DefaultMutableTreeNode selectedNode;

  private CompletableFuture<DiagnosticsNode> pendingSelectionFuture;
  @SuppressWarnings("FieldMayBeFinal") private boolean myIsListening = false;

  private final InspectorPanel detailsWidgetSubtreePanel;
  private final InspectorPanel detailsRenderObjectSubtreePanel;

  private boolean isActive = false;

  private final AsyncRateLimiter refreshRateLimiter;

  private static final Logger LOG = Logger.getInstance(InspectorPanel.class);

  private Map<InspectorInstanceRef, DefaultMutableTreeNode> valueToTreeNode = new HashMap<>();
  final JScrollPane treeScrollPane;

  private InspectorObjectGroup objectGroup;

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

    myRootsTree = new TreeDataProvider(new DefaultMutableTreeNode(null), treeType.displayName, detailsSubtree, parentTreeDisplayName, rootVisible);

    myRootsTree.addTreeExpansionListener(new MyTreeExpansionListener());

    //getInspectorService().
    if (treeType == InspectorService.FlutterTreeType.widget && isSummaryTree) {
      /// XXX AND inspector service supports getting filtered trees.
      /// TWEAK ORDER
      // We don't yet have a similar notion of platform RenderObject as the render object tree is more low level.
      detailsWidgetSubtreePanel = new InspectorPanel(flutterView, flutterApp, isApplicable, InspectorService.FlutterTreeType.widget, true, this, true, false); // XXX rootVisible false?
      detailsRenderObjectSubtreePanel = new InspectorPanel(flutterView, flutterApp, isApplicable, InspectorService.FlutterTreeType.renderObject, true, this, true, false);
    } else {
      detailsWidgetSubtreePanel = null;
      detailsRenderObjectSubtreePanel = null;
    }

    initTree(myRootsTree);
    myRootsTree.getSelectionModel().addTreeSelectionListener(e -> selectionChanged());

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

    if (detailsWidgetSubtreePanel == null) {
      myPropertiesPanel = new PropertiesPanel(flutterApp);
      treeSplitter.setSecondComponent(ScrollPaneFactory.createScrollPane(myPropertiesPanel));
    } else {
      myPropertiesPanel = null; /// This InspectorPanel doesn't have its own property panel.
      final JBRunnerTabs tabs = new JBRunnerTabs(flutterView.getProject(), ActionManager.getInstance(), null, this);
      tabs.addTab(new TabInfo(detailsWidgetSubtreePanel)
                    .append("Widget Subtree Details", SimpleTextAttributes.REGULAR_ATTRIBUTES));
      tabs.addTab(new TabInfo(detailsRenderObjectSubtreePanel)
        .append("Rendering Details", SimpleTextAttributes.REGULAR_ATTRIBUTES));

      treeSplitter.setSecondComponent(tabs.getComponent());
    }

    // TODO(jacobr): surely there is more we should be disposing.
    Disposer.register(this, treeSplitter::dispose);
    treeScrollPane = ScrollPaneFactory.createScrollPane(myRootsTree);
    treeSplitter.setFirstComponent(treeScrollPane);
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
          final int top = Math.max(0, (int) (treeScrollPane.getHeight() * (1 - VERTICAL_FRACTION)) - 20);
          final int bottom = Math.max(0, (int) (treeScrollPane.getHeight() * (VERTICAL_FRACTION)) - 20);
          final int left = Math.max(0, (int) (treeScrollPane.getWidth() * (1 - HORIZONTAL_FRACTION)));
          final int right = Math.max(0, (int) (treeScrollPane.getWidth() * (HORIZONTAL_FRACTION)));
          myRootsTree.setBorder(JBUI.Borders.empty(top, left, bottom, right));
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
    add(treeSplitter);
    determineSplitterOrientation();
  }

  private void determineSplitterOrientation() {
    final double aspectRatio = (double)getWidth() / (double)getHeight();
    double targetAspectRatio = myPropertiesPanel != null ? 1.4 : 0.666;
    final boolean vertical = aspectRatio < targetAspectRatio;
    if (vertical != treeSplitter.getOrientation()) {
      treeSplitter.setOrientation(vertical);
    }
  }

  protected boolean hasValue(InspectorInstanceRef ref) {
    return valueToTreeNode.containsKey(ref);
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
    recomputeTreeRoot();
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
        recomputeTreeRoot();
      }
    });
  }

  private DefaultMutableTreeNode getRootNode() {
    return (DefaultMutableTreeNode)getTreeModel().getRoot();
  }

  private CompletableFuture<DiagnosticsNode> recomputeTreeRoot() {
    if (rootFuture != null && !rootFuture.isDone()) {
      return rootFuture;
    }

    /// XXX this is lousy
    InspectorObjectGroup newObjectGroup = new InspectorObjectGroup();

    rootFuture = getInspectorService().getRoot(treeType, newObjectGroup);

    setupTreeRoot(null, newObjectGroup);
    return rootFuture;
  }
  /**
   * Show the platform subtree given a
   * @param node
   */

  public CompletableFuture<DiagnosticsNode> showDetailSubtrees(DiagnosticsNode node) {
    // XXX handle render objects subtree panel. Need 
    if (detailsWidgetSubtreePanel != null) {
      return detailsWidgetSubtreePanel.setSubtreeRoot(node, node);
    }
    return null;
  }

  public CompletableFuture<DiagnosticsNode> setSubtreeRoot(DiagnosticsNode node, DiagnosticsNode selection) {
    assert(detailsSubtree);
    if (node != null && treeType == InspectorService.FlutterTreeType.widget && !node.isCreatedByLocalProject()) {
      // This isn't a platform node... the existing platform tree is fine.
      // TODO(jacobr): verify that the platform ndoe is in the currently displayed platform tree
      // otherwise we have an issue
      return null;
    }

    if (node == subtreeRoot) {
      /// Selection not changed.
      return CompletableFuture.completedFuture(null);
    }
    subtreeRoot = node;
    if (rootFuture != null && !rootFuture.isDone()) {
      rootFuture.cancel(false);
    }
    InspectorObjectGroup newObjectGroup = new InspectorObjectGroup();

    rootFuture = getInspectorService().getDetailsSubtree(node, newObjectGroup);

    setupTreeRoot(selection, newObjectGroup);
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
        if (!n.childrenReady()) {
          maybeLoadChildren(rootNode);
        }
      } else {
        getTreeModel().setRoot(null);
      }
      if (selection != null) {
        selectedNode = findMatchingTreeNode(selection);
      } else {
        selectedNode = findMatchingTreeNode(getSelectedDiagnostic());
      }
      syncSelectionHelper(false);

      final TreePath path = selectedNode != null ? new TreePath(selectedNode.getPath()) : null;
      myRootsTree.setSelectionPath(path);
      scrollToNode(path);
    });
  }

  private TreePath getTreePath(DiagnosticsNode node) {
    if (node == null) {
      return null;
    }
    DefaultMutableTreeNode treeNode = valueToTreeNode.get(node.getValueRef());
    if (treeNode == null) {
      return null;
    }
    return new TreePath(treeNode.getPath());
  }

  private void scrollToNode(TreePath path ) {
    if (path == null) {
      return;
    }
    if (detailsSubtree) {
      // Start by scrolling to the top.
      myRootsTree.scrollRectToVisible(new Rectangle(0, 0, 0,0 ));

      Rectangle bounds = myRootsTree.getPathBounds(path);
      // Center the target widget directly in the center of the view. This is an extreme option optimized for
      // stability. For example, a max of 150 or 200 pixels from the LHS might make more sense.
      bounds.translate((int)(treeScrollPane.getWidth() * HORIZONTAL_FRACTION - bounds.getWidth()), (int)(treeScrollPane.getHeight() *
                                                                                                         VERTICAL_FRACTION));
      myRootsTree.scrollRectToVisible(bounds);
    } else {
      myRootsTree.scrollPathToVisible(path);
    }
  }


  private static void expandAll(JTree tree, TreePath parent) {
    TreeNode node = (TreeNode) parent.getLastPathComponent();
    if (node.getChildCount() >= 0) {
      for (Enumeration e = node.children(); e.hasMoreElements();) {
        DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
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
    if (valueRef.getId() != null) {
      valueToTreeNode.put(valueRef, node);
    }
    if (parentTree != null) {
      parentTree.maybeUpdateValueUI(valueRef);
    }
    if (diagnosticsNode.hasChildren()) {
      if (diagnosticsNode.childrenReady()) {
        final CompletableFuture<ArrayList<DiagnosticsNode>> childrenFuture = diagnosticsNode.getChildren();
        assert (childrenFuture.isDone());
        setupChildren(node, childrenFuture.getNow(null), expandChildren);
      }
      else {
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode("Loading..."));
      }
    }
    /// XXX NEEDED?
    // getTreeModel().nodeStructureChanged(node);
  }

  void setupChildren(DefaultMutableTreeNode treeNode, ArrayList<DiagnosticsNode> children, boolean expandChildren ) {
    final DefaultTreeModel model = getTreeModel();
    if (treeNode.getChildCount() > 0) {
      // Only case supported is this is the loading node.
      assert(treeNode.getChildCount() == 1);
      model.removeNodeFromParent((DefaultMutableTreeNode) treeNode.getFirstChild());
    }
    treeNode.setAllowsChildren(!children.isEmpty());
    for (DiagnosticsNode child : children) {
      final DefaultMutableTreeNode childTreeNode = new DefaultMutableTreeNode();
      setupTreeNode(childTreeNode, child, false);
      model.insertNodeInto(childTreeNode, treeNode, treeNode.getChildCount());
    }
    if (expandChildren) {
      expandAll(myRootsTree, new TreePath(treeNode.getPath()));
    }
  }

  void maybeLoadChildren(DefaultMutableTreeNode node) {
    if (!(node.getUserObject() instanceof DiagnosticsNode)) {
      return;
    }
    final DiagnosticsNode diagnosticsNode = (DiagnosticsNode)node.getUserObject();
    if (diagnosticsNode.hasChildren()) {
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
          setupChildren(node, children, true);
        });
      }
    }
  }

  public void onFlutterFrame() {
    if (rootFuture == null) {
      // This was the first frame.
      recomputeTreeRoot();
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
      // TODO(jacobr): should we clear and wait for our master to update us?
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
    whenCompleteUiThread(pendingSelectionFuture, (DiagnosticsNode newSelection, Throwable error) -> {
      pendingSelectionFuture = null;
      if (error != null) {
        LOG.error(error);
        return;
      }
      if (newSelection != getSelectedDiagnostic()) {
        if (newSelection == null) {
          myRootsTree.clearSelection();
          return;
        }
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
          myRootsTree.setSelectionPath(selectionPath);
          myRootsTree.scrollPathToVisible(selectionPath);
          /// XXX
        });
      }
    });
  }

  private DiagnosticsNode getSelectedDiagnostic() {
    if (selectedNode == null) {
      return null;
    }
    final Object userObject = selectedNode.getUserObject();
    return (userObject instanceof DiagnosticsNode) ? (DiagnosticsNode)userObject : null;
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
            setupChildren(treeNode, children, true);
          }
          getTreeModel().nodeStructureChanged(treeNode);
          // TODO(jacobr): do we need to do anything else to mark the tree as dirty?
        });
      }
    }
  }

  private void selectionChanged() {
    final DefaultMutableTreeNode[] selectedNodes = myRootsTree.getSelectedNodes(DefaultMutableTreeNode.class, null);
    for (DefaultMutableTreeNode node : selectedNodes) {
      maybePopulateChildren(node);
    }

    /// XXX maybe that node is not in the tree anymore??
    if (selectedNode != null) {
      TreeNode candidate = selectedNode.getParent();
      if (!detailsSubtree) {
        getTreeModel().nodeChanged(selectedNode.getParent());
      }
    }

    if (selectedNodes.length > 0) {
      selectedNode = selectedNodes[0];
      syncSelectionHelper(true);
    }
  }

  private void syncSelectionHelper(boolean updateDetailsSubtree) {
    if (!detailsSubtree && selectedNode != null) {
      // Do we need this?
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
    if (detailsSubtree || detailsWidgetSubtreePanel == null) {
      if (getInspectorService() != null && diagnostic != null) {
        getInspectorService().setSelection(diagnostic.getValueRef(), false);
      }
      if (updateDetailsSubtree) {
        showDetailSubtrees(diagnostic);
      }
    }
    // TODO(jacobr): we only really need to repaint the selected subtree.
    myRootsTree.repaint();
  }

  private void initTree(final Tree tree) {
    tree.setCellRenderer(new DiagnosticsTreeCellRenderer());
    tree.setShowsRootHandles(true);
    // XXX UIUtil.setLineStyleAngled(tree);

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

  private class TreeDataProvider extends Tree implements DataProvider, Disposable {
    public final boolean detailsSubtree;

    @Override
    public void setUI(TreeUI ui) {
      TreeUI actualUI = ui;
      if (!(ui instanceof InspectorTreeUI)) {
        actualUI = new InspectorTreeUI(UIUtil.isUnderDarcula());
        // TODO(jacobr): modify the inspector tree ui depending on whether the selected ui is
        // dark or light themed.
      }
      super.setUI(actualUI);
    }

    public TreePath getLastExpandedDescendant(TreePath path) {
      while (isExpanded(path)) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.isLeaf()) {
          break;
        }
        path = path.pathByAddingChild(node.getLastChild());
      }
      return path;
    }

    Rectangle getSubtreeBounds(DiagnosticsNode subtreeRoot) {
      final TreePath path = getTreePath(subtreeRoot);
      if (path == null) {
        // Node isn't in tree.
        return null;
      }
      Rectangle subtreeBounds = null;

      final Stack<TreePath> candidates =  new Stack<>();
      candidates.add(path);
      while (!candidates.isEmpty()) {
        TreePath candidate = candidates.pop();
        if (isExpanded(candidate)) {
          final DefaultMutableTreeNode node = (DefaultMutableTreeNode)candidate.getLastPathComponent();
          for (int i = 0; i < node.getChildCount(); ++i) {
            candidates.push(candidate.pathByAddingChild(node.getChildAt(i)));
          }
        }
        Rectangle bounds = getPathBounds(candidate);
        if (subtreeBounds == null) {
          subtreeBounds = bounds;
        } else {
          subtreeBounds = subtreeBounds.union(bounds);
        }
      }
      return subtreeBounds;
    }

    @Override
    public void paint(Graphics g) {
      if (rootFuture != null && !rootFuture.isDone()) {
        // Avoid flashing the UI when the state is updating.
        // TODO(jacobr): show a nice status message?
        g.setColor(new Color(255, 0, 0));
        g.fillRect(0, 0, 10, getHeight());
        return;
      }
      if (detailsSubtree) {
        ; // XXX tweak based on context.
        final Rectangle bounds = getSubtreeBounds(subtreeRoot);
        if (bounds!= null) {
          g.setColor(new Color(240, 240, 240));
          // TODO(jacobr): be smarter about not filling outside the viewport.
          g.fillRect(0, 0, getWidth(), (int)bounds.getY());
          g.fillRect(0, (int)bounds.getY(), (int)bounds.getX(), getHeight()-(int)bounds.getY());
          g.fillRect((int)bounds.getX(), (int)bounds.getMaxY(), getWidth() - (int)bounds.getX(), getHeight() - (int)bounds.getMaxY());
        }
      }
      /*
      DUPLICATED CODE. XXX
       */

      // WAY DUPLICATED
      if (!detailsSubtree) {
        if (selectedNode != null) {
          if (selectedNode.getUserObject() instanceof DiagnosticsNode) {
            DiagnosticsNode diagnosticsNode = (DiagnosticsNode)selectedNode.getUserObject();
            final Rectangle bounds = getSubtreeBounds(diagnosticsNode);
            if (bounds != null) {
              g.setColor(new Color(240, 240, 240));
              // TODO(jacobr): be smarter about not filling outside the viewport.
              g.fillRect(0, 0, getWidth(), (int)bounds.getY());
              g.fillRect(0, (int)bounds.getY(), (int)bounds.getX(), getHeight() - (int)bounds.getY());
              g.fillRect((int)bounds.getX(), (int)bounds.getMaxY(), getWidth() - (int)bounds.getX(), getHeight() - (int)bounds.getMaxY());
            }
          }
        }
      }

      if (false && !detailsSubtree) {
        if (selectedNode != null) {
          if (selectedNode.getUserObject() instanceof DiagnosticsNode) {
            DiagnosticsNode diagnosticsNode = (DiagnosticsNode) selectedNode.getUserObject();
            final Rectangle bounds = getSubtreeBounds(diagnosticsNode);
            if (bounds!= null) {
              // TODO(jacobr): be smarter about not filling outside the viewport.
              //g.setColor(new Color(240, 240, 240));
              g.setColor(new Color(240, 240, 240));
              g.fillRect((int)bounds.getX(), (int)bounds.getY(), (int)bounds.getWidth(), (int)bounds.getHeight());
              //g.fillRect((int)bounds.getX(), (int)bounds.getY(), (int)bounds.getWidth(), (int)bounds.getHeight());
            }

          }
        }
      }

      super.paint(g);
      /*
      if (!detailsSubtree) {
        if (selectedNode != null) {
          if (selectedNode.getUserObject() instanceof DiagnosticsNode) {
            DiagnosticsNode diagnosticsNode = (DiagnosticsNode) selectedNode.getUserObject();
            final Rectangle bounds = getSubtreeBounds(diagnosticsNode);
            if (bounds!= null) {
              // TODO(jacobr): be smarter about not filling outside the viewport.
              //g.setColor(new Color(240, 240, 240));
              g.setColor(new Color(100, 100, 255));
              g.drawRect((int)bounds.getX(), (int)bounds.getY(), (int)bounds.getWidth(), (int)bounds.getHeight());
              //g.fillRect((int)bounds.getX(), (int)bounds.getY(), (int)bounds.getWidth(), (int)bounds.getHeight());
            }

          }
        }
      }*/
    }

    private TreeDataProvider(final DefaultMutableTreeNode treemodel, String treeName, boolean detailsSubtree, String parentTreeName, boolean rootVisible) {
      super(treemodel);
      // XXX not needed.
      setUI(new InspectorTreeUI(UIUtil.isUnderDarcula()));

      this.detailsSubtree = detailsSubtree;
      setRootVisible(rootVisible);
      registerShortcuts();
      if (detailsSubtree) {
        getEmptyText().setText(treeName + " subtree of the selected " + parentTreeName);
      } else {
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
  private abstract class InspectorColoredTreeCellRenderer extends SimpleColoredComponent implements TreeCellRenderer {
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
                                                        boolean hasFocus){
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
        setIpad(new Insets(0, 2,  0, 2));
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
      return (InspectorTreeUI) myTree.getUI();
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
          isLinkedChild = parentTree.hasValue(node.getValueRef());
        } else {
          if (detailsWidgetSubtreePanel != null) {
            isLinkedChild = detailsWidgetSubtreePanel.hasValue(node.getValueRef());
          }
        }
      }
      /// XXX DISABLE LINKED CHILDREN
      isLinkedChild = false;

      if (highlight) {
        setOpaque(true);
        setIconOpaque(false);
        setTransparentIconBackground(true);
        setBackground(getUI().isUnderDarcula() ?
           // new Color(123,94,87) :  // light
                        //          new Color(109,109,109) :
          new Color(99, 101, 103) :
          new Color(202,191,69));
        //UIUtil.getTreeSelectionBackground());
      } else if (isLinkedChild) {
        setOpaque(true);
        setIconOpaque(false);
        setTransparentIconBackground(true);
//        setBackground(new Color(170,182,254)); // Light
        setBackground(getUI().isUnderDarcula() ?
                      // new Color(38,14,4) :
                      // new Color(27,27,27) :
                      new Color(70,73, 76) :
                      new Color(255,255,168)); // Light
      }
/*      if (selected) {
        setForeground(Colors.DARK_RED); // UIUtil.getTreeSelectionForeground());
      }
      */

      final String name = node.getName();
      SimpleTextAttributes textAttributes = textAttributesForLevel(node.getLevel());

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
      else if (!node.getDescription().isEmpty()){
        appendText(" ", SimpleTextAttributes.GRAY_ATTRIBUTES);
        appendText(node.getDescription(), textAttributes);
      }

      if (node.hasTooltip()) {
        setToolTipText(node.getTooltip());
      }

      final Icon icon = node.getIcon();
      if (icon != null) {
        setIcon(icon);
      }
      ///setBackground(Color.red); // XXX
    }

    private void appendText(@NotNull String text, @NotNull SimpleTextAttributes attributes) {
      SpeedSearchUtil.appendFragmentsForSpeedSearch(tree, text, attributes, selected, this);
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
      maybeLoadChildren((DefaultMutableTreeNode)event.getPath().getLastPathComponent());
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
