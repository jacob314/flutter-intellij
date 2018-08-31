package io.flutter.server.vmService.frame;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.Colors;
import com.intellij.ui.LayeredIcon;
import com.intellij.util.ui.JBUI;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XKeywordValuePresentation;
import com.intellij.xdebugger.frame.presentation.XNumericValuePresentation;
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import icons.FlutterIcons;
import io.flutter.editor.FlutterMaterialIcons;
import io.flutter.inspector.DebuggerMetadata;
import io.flutter.inspector.InspectorInstanceRef;
import io.flutter.inspector.InspectorService;
import io.flutter.server.vmService.*;
import io.flutter.utils.ColorIconMaker;
import io.flutter.utils.IconSlice;
import io.flutter.view.FlutterView;
import org.dartlang.vm.service.consumer.GetObjectConsumer;
import org.dartlang.vm.service.element.*;
import org.intellij.images.ui.ImageComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

import static io.flutter.inspector.ScreenshotAction.createScreenshotComponent;

// TODO: implement some combination of XValue.getEvaluationExpression() /
// XValue.calculateEvaluationExpression() in order to support evaluate expression in variable values.
// See https://youtrack.jetbrains.com/issue/WEB-17629.

public class DartVmServiceValue extends XNamedValue {

  private static final LayeredIcon FINAL_FIELD_ICON = new LayeredIcon(AllIcons.Nodes.Field, AllIcons.Nodes.FinalMark);
  private static final LayeredIcon STATIC_FIELD_ICON = new LayeredIcon(AllIcons.Nodes.Field, AllIcons.Nodes.StaticMark);
  private static final LayeredIcon STATIC_FINAL_FIELD_ICON =
    new LayeredIcon(AllIcons.Nodes.Field, AllIcons.Nodes.StaticMark, AllIcons.Nodes.FinalMark);

  @NotNull private final DartVmServiceDebugProcess myDebugProcess;
  static final ColorIconMaker colorIconMaker = new ColorIconMaker();

  @NotNull private final String myIsolateId;
  @NotNull private final InstanceRef myInstanceRef;
  @Nullable private final LocalVarSourceLocation myLocalVarSourceLocation;
  @Nullable private final FieldRef myFieldRef;
  private final boolean myIsException;

  private final Ref<Integer> myCollectionChildrenAlreadyShown = new Ref<>(0);
  private final DebuggerMetadata myInspectorMedata;
  private final String myEvaluationExpression;

  public DartVmServiceValue(@NotNull final DartVmServiceDebugProcess debugProcess,
                            @NotNull final String isolateId,
                            @NotNull final String name,
                            @NotNull final InstanceRef instanceRef,
                            @Nullable final LocalVarSourceLocation localVarSourceLocation,
                            @Nullable final FieldRef fieldRef,
                            @Nullable DebuggerMetadata inspectorMetadata,
                            boolean isException,
                            @Nullable String evaluationExpression) {
    super(name);
    myDebugProcess = debugProcess;
    myIsolateId = isolateId;
    myInstanceRef = instanceRef;
    myLocalVarSourceLocation = localVarSourceLocation;
    myFieldRef = fieldRef;
    myIsException = isException;
    myInspectorMedata = inspectorMetadata;
    myEvaluationExpression = evaluationExpression;
  }

  /**
   * @return expression which evaluates to the current value
   */
  @Nullable
  public String getEvaluationExpression() {
    return myEvaluationExpression;
  }

  @Override
  public boolean canNavigateToSource() {
    if (myInspectorMedata != null && myInspectorMedata.hasCreationLocation()) {
      return true;
    }
    final InspectorService service = getInspectorService();
    return myLocalVarSourceLocation != null || myFieldRef != null;
  }

  @Override
  public void computeSourcePosition(@NotNull final XNavigatable navigatable) {
    if (myInspectorMedata != null && myInspectorMedata.hasCreationLocation()) {
      final InspectorService inspectorService = getInspectorService();
      if (inspectorService != null) {
        inspectorService.createObjectGroup("dummy").getCreationLocation(myInstanceRef).
        whenCompleteAsync(((location, throwable) -> {
          if (throwable != null || location == null) {
            navigatable.setSourcePosition(null);
            return;
          }
          navigatable.setSourcePosition(location.getXSourcePosition());
        }));
        return;
      }
    }
    if (myLocalVarSourceLocation != null) {
      reportSourcePosition(myDebugProcess, navigatable, myIsolateId, myLocalVarSourceLocation.myScriptRef,
                           myLocalVarSourceLocation.myTokenPos);
    }
    else if (myFieldRef != null) {
      doComputeSourcePosition(myDebugProcess, navigatable, myIsolateId, myFieldRef);
    }
    else {
      navigatable.setSourcePosition(null);
    }
  }

  static void doComputeSourcePosition(@NotNull final DartVmServiceDebugProcess debugProcess,
                                      @NotNull final XNavigatable navigatable,
                                      @NotNull final String isolateId,
                                      @NotNull final FieldRef fieldRef) {
    debugProcess.getVmServiceWrapper().getObject(isolateId, fieldRef.getId(), new GetObjectConsumer() {
      @Override
      public void received(final Obj field) {
        final SourceLocation location = ((Field)field).getLocation();
        reportSourcePosition(debugProcess, navigatable, isolateId,
                             location == null ? null : location.getScript(),
                             location == null ? -1 : location.getTokenPos());
      }

      @Override
      public void received(final Sentinel sentinel) {
        navigatable.setSourcePosition(null);
      }

      @Override
      public void onError(final RPCError error) {
        navigatable.setSourcePosition(null);
      }
    });
  }

  @Override
  public boolean canNavigateToTypeSource() {
    return true;
  }

  @Override
  public void computeTypeSourcePosition(@NotNull final XNavigatable navigatable) {
    myDebugProcess.getVmServiceWrapper().getObject(myIsolateId, myInstanceRef.getClassRef().getId(), new GetObjectConsumer() {
      @Override
      public void received(final Obj classObj) {
        final SourceLocation location = ((ClassObj)classObj).getLocation();
        reportSourcePosition(myDebugProcess, navigatable, myIsolateId,
                             location == null ? null : location.getScript(),
                             location == null ? -1 : location.getTokenPos());
      }

      @Override
      public void received(final Sentinel response) {
        navigatable.setSourcePosition(null);
      }

      @Override
      public void onError(final RPCError error) {
        navigatable.setSourcePosition(null);
      }
    });
  }

  private static void reportSourcePosition(@NotNull final DartVmServiceDebugProcess debugProcess,
                                           @NotNull final XNavigatable navigatable,
                                           @NotNull final String isolateId,
                                           @Nullable final ScriptRef script,
                                           final int tokenPos) {
    if (script == null || tokenPos <= 0) {
      navigatable.setSourcePosition(null);
      return;
    }

    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      final XSourcePosition sourcePosition = debugProcess.getSourcePosition(isolateId, script, tokenPos);
      ApplicationManager.getApplication().runReadAction(() -> navigatable.setSourcePosition(sourcePosition));
    });
  }

  @Override
  public void computePresentation(@NotNull final XValueNode node, @NotNull final XValuePlace place) {
    if (computeVarHavingStringValuePresentation(node)) return;
    if (computeRegExpPresentation(node)) return;
    if (computeMapPresentation(node)) return;
    if (computeListPresentation(node)) return;
    computeDefaultPresentation(node);
    // todo handle other special kinds: Type, TypeParameter, Pattern, may be some others as well
  }

  private Icon getIcon() {
    if (myIsException) return AllIcons.Debugger.Db_exception_breakpoint;

    if (myFieldRef != null) {
      if (myFieldRef.isStatic() && (myFieldRef.isFinal() || myFieldRef.isConst())) {
        return STATIC_FINAL_FIELD_ICON;
      }
      if (myFieldRef.isStatic()) {
        return STATIC_FIELD_ICON;
      }
      if (myFieldRef.isFinal() || myFieldRef.isConst()) {
        return FINAL_FIELD_ICON;
      }
      return AllIcons.Nodes.Field;
    }

    final InstanceKind kind = myInstanceRef.getKind();

    if (kind == InstanceKind.Map || isListKind(kind)) return AllIcons.Debugger.Db_array;

    if (kind == InstanceKind.Null ||
        kind == InstanceKind.Bool ||
        kind == InstanceKind.Double ||
        kind == InstanceKind.Int ||
        kind == InstanceKind.String) {
      return AllIcons.Debugger.Db_primitive;
    }

    return AllIcons.Debugger.Value;
  }

  /**
   * May return null if the InspectorService is not yet available due to
   * the application load still being in progress or the current isolate not
   * being the Flutter UI isolate.
   */
  private InspectorService getInspectorService() {
    InspectorService service = myDebugProcess.getApp().getInspectorService().getNow(null);
    return myIsolateId.equals(service.getIsolateId()) ? service : null;
  }

  private boolean computeVarHavingStringValuePresentation(@NotNull final XValueNode node) {
    // getValueAsString() is provided for the instance kinds: Null, Bool, Double, Int, String (value may be truncated), Float32x4, Float64x2, Int32x4, StackTrace
    switch (myInstanceRef.getKind()) {
      case Null:
      case Bool:
        node.setPresentation(getIcon(), new XKeywordValuePresentation(myInstanceRef.getValueAsString()), false);
        break;
      case Double:
      case Int:
        node.setPresentation(getIcon(), new XNumericValuePresentation(myInstanceRef.getValueAsString()), false);
        break;
      case String:
        final String presentableValue = StringUtil.replace(myInstanceRef.getValueAsString(), "\"", "\\\"");
        node.setPresentation(getIcon(), new XStringValuePresentation(presentableValue), false);

        if (myInstanceRef.getValueAsStringIsTruncated()) {
          addFullStringValueEvaluator(node, myInstanceRef);
        }
        break;
      case Float32x4:
      case Float64x2:
      case Int32x4:
      case StackTrace:
        node.setFullValueEvaluator(new ImmediateFullValueEvaluator("Click to see stack trace...", myInstanceRef.getValueAsString()));
        node.setPresentation(getIcon(), myInstanceRef.getClassRef().getName(), "", true);
        break;
      default:
        ClassRef classRef = myInstanceRef.getClassRef();
        switch (classRef.getName()) {
          case "Color": {
            int red = getIntProperty("red");
            int green = getIntProperty("green");
            int blue = getIntProperty("blue");
            int alpha = getIntProperty("alpha");
            final Color color = new Color(red, green, blue, alpha);
            String value = alpha == 255 ? String.format("#%02x%02x%02x", red, green, blue) : String.format("#%02x%02x%02x%02x", alpha, red, green, blue);
            node.setPresentation(new IconSlice(getIcon(), colorIconMaker.getCustomIcon(color), 0, 21), myInstanceRef.getClassRef().getName(), value, true);
            return true;
          }

          case "IconData": {
            // IconData(U+0E88F)
            final int codePoint = getIntProperty("codePoint");
            if (codePoint > 0) {
              final Icon icon = FlutterMaterialIcons.getMaterialIconForHex(String.format("%1$04x", codePoint));
              if (icon != null) {
                node.setPresentation(new IconSlice(getIcon(), icon, 0, 21), myInstanceRef.getClassRef().getName(), "SOME ICON", true);
                return true;
              }
            }
            break;
          }
        }
        return false;
    }
    return true;
  }

  private int getIntProperty(String propertyName) {
    if (myInspectorMedata == null || !myInspectorMedata.containsKey(propertyName)) {
      return 0;
    }
    try {
      return Integer.parseInt(myInspectorMedata.get(propertyName).getValueAsString());
    } catch (Exception e) {
      return 0;
    }
  }

  private void addFullStringValueEvaluator(@NotNull final XValueNode node, @NotNull final InstanceRef stringInstanceRef) {
    assert stringInstanceRef.getKind() == InstanceKind.String : stringInstanceRef;
    node.setFullValueEvaluator(new XFullValueEvaluator() {
      @Override
      public void startEvaluation(@NotNull final XFullValueEvaluationCallback callback) {
        myDebugProcess.getVmServiceWrapper().getObject(myIsolateId, stringInstanceRef.getId(), new GetObjectConsumer() {
          @Override
          public void received(Obj instance) {
            assert instance instanceof Instance && ((Instance)instance).getKind() == InstanceKind.String : instance;
            callback.evaluated(((Instance)instance).getValueAsString());
          }

          @Override
          public void received(Sentinel response) {
            callback.errorOccurred(response.getValueAsString());
          }

          @Override
          public void onError(RPCError error) {
            callback.errorOccurred(error.getMessage());
          }
        });
      }
    });
  }

  private boolean computeRegExpPresentation(@NotNull final XValueNode node) {
    if (myInstanceRef.getKind() == InstanceKind.RegExp) {
      // The pattern is always an instance of kind String.
      final InstanceRef pattern = myInstanceRef.getPattern();
      assert pattern.getKind() == InstanceKind.String : pattern;

      final String patternString = StringUtil.replace(pattern.getValueAsString(), "\"", "\\\"");
      node.setPresentation(getIcon(), new XStringValuePresentation(patternString) {
        @Nullable
        @Override
        public String getType() {
          return myInstanceRef.getClassRef().getName();
        }
      }, true);

      if (pattern.getValueAsStringIsTruncated()) {
        addFullStringValueEvaluator(node, pattern);
      }

      return true;
    }
    return false;
  }

  private boolean computeMapPresentation(@NotNull final XValueNode node) {
    if (myInstanceRef.getKind() == InstanceKind.Map) {
      final String value = "size = " + myInstanceRef.getLength();
      node.setPresentation(getIcon(), myInstanceRef.getClassRef().getName(), value, myInstanceRef.getLength() > 0);
      return true;
    }
    return false;
  }

  private boolean computeListPresentation(@NotNull final XValueNode node) {
    if (isListKind(myInstanceRef.getKind())) {
      final String value = "size = " + myInstanceRef.getLength();
      node.setPresentation(getIcon(), myInstanceRef.getClassRef().getName(), value, myInstanceRef.getLength() > 0);
      return true;
    }
    return false;
  }

  private void computeDefaultPresentation(@NotNull final XValueNode node) {
    myDebugProcess.getVmServiceWrapper()
      .evaluateInTargetContext(myIsolateId, myInstanceRef.getId(), "toString()", new VmServiceConsumers.EvaluateConsumerWrapper() {
        @Override
        public void received(final InstanceRef toStringInstanceRef) {
          if (toStringInstanceRef.getKind() == InstanceKind.String) {
            final String string = toStringInstanceRef.getValueAsString();
            // default toString() implementation returns "Instance of 'ClassName'" - no interest to show
            if (string.equals("Instance of '" + myInstanceRef.getClassRef().getName() + "'")) {
              noGoodResult();
            }
            else {
              node.setPresentation(getIcon(), myInstanceRef.getClassRef().getName(), string, true);
            }
          }
          else {
            noGoodResult(); // unlikely possible
          }
        }

        @Override
        public void noGoodResult() {
          node.setPresentation(getIcon(), myInstanceRef.getClassRef().getName(), "", true);
        }
      });
  }

  @Override
  public void computeChildren(@NotNull final XCompositeNode node) {
    if (myInstanceRef.getKind() == InstanceKind.Null) {
      node.addChildren(XValueChildrenList.EMPTY, true);
      return;
    }

    if ((isListKind(myInstanceRef.getKind()) || myInstanceRef.getKind() == InstanceKind.Map)) {
      computeCollectionChildren(node);
    }
    else {
      ClassRef classRef = myInstanceRef.getClassRef();
      switch (classRef.getName()) {
        case "Color": {
          int red = getIntProperty("red");
          int green = getIntProperty("green");
          int blue = getIntProperty("blue");
          int alpha = getIntProperty("alpha");
          final Color color = new Color(red, green, blue, alpha);
          String value = alpha == 255 ? String.format("#%02x%02x%02x", red, green, blue) : String.format("#%02x%02x%02x%02x", alpha, red, green, blue);
          node.addChildren(XValueChildrenList.singleton(new XNamedValue("") {
            @Override
            public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
              node.setPresentation(new IconSlice(getIcon(), colorIconMaker.getCustomIcon(color), 1, 21), new XValuePresentation() {
                @Override
                public void renderValue(@NotNull XValueTextRenderer renderer) {
                }

                @Override
                public String getSeparator() {
                  return " ";
                }
              }, false);
            }
          }), false);

          break;
        }

        case "IconData": {
          // IconData(U+0E88F)
          final int codePoint = getIntProperty("codePoint");
          if (codePoint > 0) {
            final Icon icon = FlutterMaterialIcons.getMaterialIconForHex(String.format("%1$04x", codePoint));
            if (icon != null) {
              node.addChildren(XValueChildrenList.singleton(new XNamedValue("") {

                @Override
                public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
                  node.setPresentation(new IconSlice(getIcon(), icon, 1, 21), new XValuePresentation() {
                    @Override
                    public void renderValue(@NotNull XValueTextRenderer renderer) {
                    }

                    @Override
                    public String getSeparator() {
                      return " ";
                    }
                  }, false);
                }
              }), false);
              break;
            }
          }
          break;
        }
      }
      InspectorService inspectorService = getInspectorService();
      CompletableFuture<?> readyForMoreChildren = CompletableFuture.completedFuture(null);
      if (myInstanceRef.getClassRef().getName().equals("AnimationController") ||
          myInstanceRef.getClassRef().getName().equals("CurvedAnimation")) {
        node.addChildren(XValueChildrenList.singleton(new XNamedValue("animateTo") {

          @Override
          public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
            node.setPresentation(FlutterIcons.Animation, new XValuePresentation() {
              @Override
              public void renderValue(@NotNull XValueTextRenderer renderer) {
              }

              @Override
              public String getSeparator() {
                return " ";
              }
            }, false);
            node.setFullValueEvaluator(new DartCustomPopupEvaluator<Double>("set", myDebugProcess) {
              @Override
              protected CompletableFuture<Double> getData() {
                return inspectorService.getScrollControllerOffset(myInstanceRef);
              }

              @Override
              protected JComponent createComponent(Double currentValue) {
                final JSlider slider = new JSlider(0, 100, (int)Math.round(currentValue * 100));
                slider.setPreferredSize(new Dimension(200, 30));
                slider.addChangeListener(new ChangeListener() {
                  @Override
                  public void stateChanged(ChangeEvent e) {
                    final double value = (double)slider.getValue() * 0.01;
                    inspectorService.animateScrollController(myInstanceRef, value);
                  }
                });
                return slider;
              }
            });
          }
        }), false);
      }
      if (myInspectorMedata != null) {
        if (myInspectorMedata.isInspectable()) {
          node.addChildren(XValueChildrenList.singleton(new XNamedValue(myInspectorMedata.getKind() == DebuggerMetadata.Kind.Element ? "Widget members" : "") {
            @Override

            public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
              node.setPresentation(FlutterIcons.Flutter, new XValuePresentation() {
                @Override
                public void renderValue(@NotNull XValueTextRenderer renderer) {
                }

                @Override
                public String getSeparator() {
                  return "";
                }
              }, false);
              node.setFullValueEvaluator(new XFullValueEvaluator("Reveal in Flutter Inspector Window") {
                @Override
                public void startEvaluation(@NotNull XFullValueEvaluationCallback callback) {
                  FlutterView.autoActivateToolWindow(myDebugProcess.getApp().getProject());
                  inspectorService.createObjectGroup("ignored").setSelection(getInstanceRef(), false, false);
                  // TODO(jacobr): is there a better client we can use than XFullValueEvaluator given
                  callback.evaluated("Revealed in Inspector Panel");
                }
                public boolean isShowValuePopup() {
                  // TODO(jacobr): we should show a popup explaining why the value can't be
                  // inspected if something went wrong such as the Widget no longer being part
                  // of the tree.
                  return false;
                }
              });
            }
          }), false);
          if (myInspectorMedata.getKind() == DebuggerMetadata.Kind.Element) {
            readyForMoreChildren = myDebugProcess.getVmServiceWrapper().getInstance(myInspectorMedata.getWidget(), myIsolateId).thenComposeAsync((instance) -> {
               return addFields(node, instance.getFields(), false);
            });
          }
          if (myInspectorMedata.getKind() == DebuggerMetadata.Kind.Element || myInspectorMedata.getKind() == DebuggerMetadata.Kind.RenderObject) {
            InspectorService.ObjectGroup group = inspectorService.createObjectGroup("node_screenshots");
            CompletableFuture<BufferedImage> imageFuture = group.toInspectorInstanceRef(myInstanceRef)
              .thenComposeAsync((InspectorInstanceRef inspectorRef) -> group.getScreenshot(inspectorRef, 100, 100, false, JBUI.pixScale()));

            readyForMoreChildren = CompletableFuture.allOf(readyForMoreChildren, imageFuture).thenApplyAsync((ignored) -> {
              // it is fine if readyForMoreChildren completed exceptionally.
              final BufferedImage image = imageFuture.getNow(null);

              if (image != null && myInspectorMedata != null && myInspectorMedata.isInspectable()) {
                node.addChildren(XValueChildrenList.singleton(new XNamedValue("SCREENYXXX") {

                  @Override
                  public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
                    node.setPresentation(FlutterIcons.Assets, new XValuePresentation() {
                      @Override
                      public void renderValue(@NotNull XValueTextRenderer renderer) {
                      }

                      @Override
                      public String getSeparator() {
                        return "";
                      }
                    }, false);
                    node.setFullValueEvaluator(new DartCustomPopupEvaluator<BufferedImage>("show large screenshot", myDebugProcess) {
                      @Override
                      protected CompletableFuture<BufferedImage> getData() {
                        // XXX there is hella duplicated code here.
                        InspectorService.ObjectGroup group = inspectorService.createObjectGroup("node_screenshots");
                        return group.toInspectorInstanceRef(myInstanceRef).thenComposeAsync((InspectorInstanceRef inspectorRef) -> group.getScreenshot(inspectorRef, 1000, 1000, true, JBUI.pixScale()));
                      }

                      @Override
                      protected JComponent createComponent(BufferedImage image) {
                        if (image == null) {
                          return null;
                        }
                        createScreenshotComponent(image, JBUI.pixScale());
                        final ImageComponent imageComponent = new ImageComponent();
                        imageComponent.setAutoscrolls(true);
                        imageComponent.setTransparencyChessboardBlankColor(Colors.DARK_BLUE);
                        imageComponent.getDocument().setValue(image);
                        imageComponent.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
                        return imageComponent;
                      }
                    });
                  }
                }), false);
              }
              return null;
            });
          }
        }
      }

      readyForMoreChildren.whenCompleteAsync((v, t) -> {
        myDebugProcess.getVmServiceWrapper().getObject(myIsolateId, myInstanceRef.getId(), new GetObjectConsumer() {
          @Override
          public void received(Obj instance) {
            addFields(node, ((Instance)instance).getFields(), true);
          }

          @Override
          public void received(Sentinel sentinel) {
            node.setErrorMessage(sentinel.getValueAsString());
          }

          @Override
          public void onError(RPCError error) {
            node.setErrorMessage(error.getMessage());
          }
        });
      });
    }
  }

  private void computeCollectionChildren(@NotNull final XCompositeNode node) {
    final int offset = myCollectionChildrenAlreadyShown.get();
    final int count = Math.min(myInstanceRef.getLength() - offset, XCompositeNode.MAX_CHILDREN_TO_SHOW);

    myDebugProcess.getVmServiceWrapper().getCollectionObject(myIsolateId, myInstanceRef.getId(), offset, count, new GetObjectConsumer() {
      @Override
      public void received(Obj instance) {
        CompletableFuture<?> childrenReady = CompletableFuture.completedFuture(null);
        if (isListKind(myInstanceRef.getKind())) {
          childrenReady = addListChildren(node, ((Instance)instance).getElements());
        }
        else if (myInstanceRef.getKind() == InstanceKind.Map) {
          addMapChildren(node, ((Instance)instance).getAssociations());
        }
        else {
          assert false : myInstanceRef.getKind();
        }

        childrenReady.whenCompleteAsync((v, t) -> {
          myCollectionChildrenAlreadyShown.set(myCollectionChildrenAlreadyShown.get() + count);

          if (offset + count < myInstanceRef.getLength()) {
            node.tooManyChildren(myInstanceRef.getLength() - offset - count);
          }
        });
      }

      @Override
      public void received(Sentinel sentinel) {
        node.setErrorMessage(sentinel.getValueAsString());
      }

      @Override
      public void onError(RPCError error) {
        node.setErrorMessage(error.getMessage());
      }
    });
  }

  private CompletableFuture<?> addListChildren(@NotNull final XCompositeNode node, @Nullable final ElementList<InstanceRef> listElements) {
    if (listElements == null) {
      node.addChildren(XValueChildrenList.EMPTY, true);
      return CompletableFuture.completedFuture(null);
    }

    final ChildrenListBuilder childrenList = new ChildrenListBuilder(listElements.size());
    int index = myCollectionChildrenAlreadyShown.get();

    final VmServiceWrapper vmService = myDebugProcess.getVmServiceWrapper();
    for (InstanceRef listElement : listElements) {
      childrenList.add(vmService.createVmServiceValue(myIsolateId, String.valueOf(index++), listElement, null, null, false));
    }
    return childrenList.build(node, true);
  }

  private void addMapChildren(@NotNull final XCompositeNode node, @NotNull final ElementList<MapAssociation> mapAssociations) {
    final XValueChildrenList childrenList = new XValueChildrenList(mapAssociations.size());
    int index = myCollectionChildrenAlreadyShown.get();
    for (MapAssociation mapAssociation : mapAssociations) {
      final InstanceRef keyInstanceRef = mapAssociation.getKey();
      final InstanceRef valueInstanceRef = mapAssociation.getValue();

      childrenList.add(String.valueOf(index++), new XValue() {
        @Override
        public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
          final String value = getShortPresentableValue(keyInstanceRef) + " -> " + getShortPresentableValue(valueInstanceRef);
          node.setPresentation(AllIcons.Debugger.Value, "map entry", value, true);
        }

        @Override
        public void computeChildren(@NotNull XCompositeNode node) {
          final VmServiceWrapper vmService = myDebugProcess.getVmServiceWrapper();

          final CompletableFuture<DartVmServiceValue> key = vmService.createVmServiceValue(myIsolateId, "key", keyInstanceRef, null, null, false);
          final CompletableFuture<DartVmServiceValue> value =
            vmService.createVmServiceValue(myIsolateId, "value", valueInstanceRef, null, null, false);
          CompletableFuture.allOf(key, value).whenCompleteAsync((v, t) -> {
            if (t != null) {
              return;
            }
            node.addChildren(XValueChildrenList.singleton(key.getNow(null)), false);
            node.addChildren(XValueChildrenList.singleton(value.getNow(null)), true);
          });
        }
      });
    }

    node.addChildren(childrenList, true);
  }

  private CompletableFuture<?> addFields(@NotNull final XCompositeNode node, @NotNull final ElementList<BoundField> fields, boolean last) {
    final ChildrenListBuilder childrenList = new ChildrenListBuilder(fields.size());
    final VmServiceWrapper vmService = myDebugProcess.getVmServiceWrapper();
    for (BoundField field : fields) {
      final InstanceRef value = field.getValue();
      if (value != null) {
        childrenList
          .add(vmService.createVmServiceValue(myIsolateId, field.getDecl().getName(), value, null, field.getDecl(), false));
      }
    }
    return childrenList.build(node, last);
  }

  @NotNull
  private static String getShortPresentableValue(@NotNull final InstanceRef instanceRef) {
    // getValueAsString() is provided for the instance kinds: Null, Bool, Double, Int, String (value may be truncated), Float32x4, Float64x2, Int32x4, StackTrace
    switch (instanceRef.getKind()) {
      case String:
        String string = instanceRef.getValueAsString();
        if (string.length() > 103) string = string.substring(0, 100) + "...";
        return "\"" + StringUtil.replace(string, "\"", "\\\"") + "\"";
      case Null:
      case Bool:
      case Double:
      case Int:
      case Float32x4:
      case Float64x2:
      case Int32x4:
        // case StackTrace:  getValueAsString() is too long for StackTrace
        return instanceRef.getValueAsString();
      default:
        return "[" + instanceRef.getClassRef().getName() + "]";
    }
  }

  private static boolean isListKind(@NotNull final InstanceKind kind) {
    // List, Uint8ClampedList, Uint8List, Uint16List, Uint32List, Uint64List, Int8List, Int16List, Int32List, Int64List, Float32List, Float64List, Int32x4List, Float32x4List, Float64x2List
    return kind == InstanceKind.List ||
           kind == InstanceKind.Uint8ClampedList ||
           kind == InstanceKind.Uint8List ||
           kind == InstanceKind.Uint16List ||
           kind == InstanceKind.Uint32List ||
           kind == InstanceKind.Uint64List ||
           kind == InstanceKind.Int8List ||
           kind == InstanceKind.Int16List ||
           kind == InstanceKind.Int32List ||
           kind == InstanceKind.Int64List ||
           kind == InstanceKind.Float32List ||
           kind == InstanceKind.Float64List ||
           kind == InstanceKind.Int32x4List ||
           kind == InstanceKind.Float32x4List ||
           kind == InstanceKind.Float64x2List;
  }

  @NotNull
  public InstanceRef getInstanceRef() {
    return myInstanceRef;
  }

  public static class LocalVarSourceLocation {
    @NotNull private final ScriptRef myScriptRef;
    private final int myTokenPos;

    public LocalVarSourceLocation(@NotNull final ScriptRef scriptRef, final int tokenPos) {
      myScriptRef = scriptRef;
      myTokenPos = tokenPos;
    }
  }
}
