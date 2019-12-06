/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

// This file contains content we would like to add to add to
// package:flutter/src/widgets/widget_inspector.dart
//
// Warning: imports in this file only exists to make it easy to iterate on this
// file with property dart static errors. In practice, at runtime this file will
// have exactly the imports that  package:flutter/src/flutter does until the
// Dart VM is extended to support imports from multiple libraries in expression
// evaluation.

// Warning: each method in this class is a self contained helper and won't
// really depend on any other methods in this class.

// Any classes defined in this class will be ignored and are only used for easy
// static checking while writing this code.

import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui
    show
        ClipOp,
        Image,
        ImageByteFormat,
        Paragraph,
        Picture,
        PictureRecorder,
        PointMode,
        SceneBuilder,
        Vertices;
import 'dart:ui' show Canvas, Offset;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:vector_math/vector_math_64.dart';

import 'package:flutter/src/widgets/app.dart';
import 'package:flutter/src/widgets/basic.dart';
import 'package:flutter/src/widgets/binding.dart';
import 'package:flutter/src/widgets/debug.dart';
import 'package:flutter/src/widgets/framework.dart';
import 'package:flutter/src/widgets/gesture_detector.dart';

import 'package:flutter/src/widgets/widget_inspector.dart';

Rect _calculateSubtreeBounds(RenderObject object) {
  return throw Exception(
      "Stub to keep other methods in this file analysis error clean. This private method exists in widget_inspector.dart");
}

_Location _getCreationLocation(Object object) {
  return throw Exception(
      "Stub to keep other methods in this file analysis error clean. This private method exists in widget_inspector.dart");
}

bool _isLocalCreationLocation(Object object) {
  return throw Exception(
      "Stub to keep other methods in this file analysis error clean. This private method exists in widget_inspector.dart");
}

class _Location {
  const _Location({
    this.file,
    this.line,
    this.column,
    this.name,
    this.parameterLocations,
  });

  /// File path of the location.
  final String file;

  /// 1-based line number.
  final int line;

  /// 1-based column number.
  final int column;

  /// Optional name of the parameter or function at this location.
  final String name;

  /// Optional locations of the parameters of the member at this location.
  final List<_Location> parameterLocations;

  Map<String, Object> toJsonMap() {
    final Map<String, Object> json = <String, Object>{
      'file': file,
      'line': line,
      'column': column,
    };
    if (name != null) {
      json['name'] = name;
    }
    if (parameterLocations != null) {
      json['parameterLocations'] = parameterLocations
          .map<Map<String, Object>>(
              (_Location location) => location.toJsonMap())
          .toList();
    }
    return json;
  }

  @override
  String toString() {
    final List<String> parts = <String>[];
    if (name != null) {
      parts.add(name);
    }
    if (file != null) {
      parts.add(file);
    }
    parts..add('$line')..add('$column');
    return parts.join(':');
  }
}

Map<String, Object> getScreenshotTransformedRect(
    String id, int width, int height, double pixelRatio) {
  Matrix4 buildImageTransform(
      OffsetLayer layer, Rect bounds, double pixelRatio) {
    final Matrix4 transform = Matrix4.translationValues(
      (-bounds.left - layer.offset.dx) * pixelRatio,
      (-bounds.top - layer.offset.dy) * pixelRatio,
      0.0,
    );
    transform.scale(pixelRatio, pixelRatio);
    return transform;
  }

  Map<String, Object> transformedRectToJson(Rect rect, Matrix4 transform) {
    if (rect == null || transform == null) {
      return null;
    }
    return <String, Object>{
      'left': rect.left,
      'top': rect.top,
      'width': rect.width,
      'height': rect.height,
      'transform': transform.storage.toList(),
    };
  }

  Map<String, Object> buildTransformedRect(
      RenderObject renderObject, double maxPixelRatio) {
    if (renderObject == null || !renderObject.attached) {
      return null;
    }
    final Rect renderBounds = _calculateSubtreeBounds(renderObject);

    final double pixelRatio = math.min(
      maxPixelRatio,
      math.min(
        width / renderBounds.width,
        height / renderBounds.height,
      ),
    );

    final layer = renderObject.debugLayer;
    if (layer is OffsetLayer) {
      final OffsetLayer containerLayer = layer;
      return transformedRectToJson(
        renderBounds,
        buildImageTransform(containerLayer,
            renderBounds.shift(-containerLayer.offset), pixelRatio),
      );
    } else {
      return null;
    }
  }

  Object o = WidgetInspectorService.instance.toObject(id);
  if (o == null) return null;
  RenderObject r;
  if (o is RenderObject) {
    r = o;
  } else if (o is Element) {
    r = o.renderObject;
  } else {
    return null;
  }
  return buildTransformedRect(r, pixelRatio);
}

List<Map<String, dynamic>> getElementsAtLocation(
    _Location location, int count, String groupName) {
  bool locationEquals(_Location a, _Location b) {
    if (identical(a, b)) return true;
    if (a == null || b == null) return false;
    return a.file == b.file && a.line == b.line && a.column == b.column;
  }

  List<Element> _getElementsMatchingLocation(
      Element element, _Location location) {
    final List<Element> matches = <Element>[];

    void addMatchesCallback(Element element) {
      if (locationEquals(_getCreationLocation(element),location)) {
        matches.add(element);
      }
      element.visitChildren(addMatchesCallback);
    }

    if (element != null) {
      addMatchesCallback(element);
    }
    return matches;
  }

  List<Element> getActiveElementsForLocation(_Location location) {
    return _getElementsMatchingLocation(
        WidgetsBinding.instance?.renderViewElement, location);
  }

  final List<Element> active = getActiveElementsForLocation(location);
  final List<DiagnosticsNode> nodes =
      active.map((Element element) => element.toDiagnosticsNode()).toList();
  return DiagnosticsNode.toJsonList(
    nodes,
    null,
    InspectorSerializationDelegate(
      groupName: groupName,
      service: WidgetInspectorService.instance,
    ),
  );
}

bool setSelectionByLocation(_Location location) {
  bool locationEquals(_Location a, _Location b) {
    if (identical(a, b)) return true;
    if (a == null || b == null) return false;
    return a.file == b.file && a.line == b.line && a.column == b.column;
  }

  List<Element> _getElementsMatchingLocation(
      Element element, _Location location) {
    final List<Element> matches = <Element>[];

    void addMatchesCallback(Element element) {
      if (locationEquals(_getCreationLocation(element),location)) {
        matches.add(element);
      }
      element.visitChildren(addMatchesCallback);
    }

    if (element != null) {
      addMatchesCallback(element);
    }
    return matches;
  }

  List<Element> getActiveElementsForLocation(_Location location) {
    return _getElementsMatchingLocation(
        WidgetsBinding.instance?.renderViewElement, location);
  }

  final List<Element> active = getActiveElementsForLocation(location);
  if (active.isEmpty) return false;

  if (WidgetInspectorService.instance.selection.currentElement == active.first) {
    return false;
  }
  WidgetInspectorService.instance.selection.clear();
  WidgetInspectorService.instance.selection.currentElement = active.first;
  if(WidgetInspectorService.instance.selectionChangedCallback != null) {
    WidgetInspectorService.instance.selectionChangedCallback();
  }
  return true;
}

// This implements everything in screenshotAtLocation except for the actual
// screenshot which we cannot fetch here due to current eval limitations causing crashes
Map<String, Object> getScreenshotAtLocation(_Location location, int count, double width, double height, double maxPixelRatio, String groupName) {
  List<Map<String, dynamic>> _getBoundingBoxesHelper(
      Element rootElement, Element targetElement, String groupName) {
    Map<String, Object> transformedRectToJson(Rect rect, Matrix4 transform) {
      if (rect == null || transform == null) {
        return null;
      }
      return <String, Object>{
        'left': rect.left,
        'top': rect.top,
        'width': rect.width,
        'height': rect.height,
        'transform': transform.storage.toList(),
      };
    }

    bool _isAncestor(RenderObject renderer, RenderObject ancestor) {
      if (renderer == null ||
          ancestor == null ||
          !renderer.attached ||
          !ancestor.attached) return false;
      while (renderer != null) {
        if (identical(renderer, ancestor)) return true;
        renderer = renderer.parent as RenderObject;
      }
      return false;
    }

    bool _isAncestorElement(Element e1, Element e2) {
      if (identical(e1, e2)) return true;
      if (!(e1.renderObject?.attached ?? false) ||
          !(e2.renderObject?.attached ?? false)) return false;
      bool matches = false;
      e1.visitAncestorElements((Element ancestor) {
        if (identical(ancestor, e2)) {
          matches = true;
          return false;
        }
        return true;
      });
      return matches;
    }

    RenderObject rootRenderObject = rootElement?.renderObject;
    final RenderObject targetRenderObject = targetElement?.renderObject;
    if (rootRenderObject == null ||
        rootRenderObject.attached == false ||
        targetRenderObject == null ||
        targetRenderObject.attached == null) return null;

    List<Element> _getElementsMatchingLocation(
        Element element, _Location location) {
      final List<Element> matches = <Element>[];

      void addMatchesCallback(Element element) {
        if (_getCreationLocation(element) == location) {
          matches.add(element);
        }
        element.visitChildren(addMatchesCallback);
      }

      if (element != null) {
        addMatchesCallback(element);
      }
      return matches;
    }

    bool isAncestorOf(Element element, Element target) {
      bool matches = false;
      if (element.renderObject?.attached != true ||
          target.renderObject?.attached != true) {
        return false;
      }
      element.visitAncestorElements((Element ancestor) {
        if (identical(ancestor, target)) {
          matches = true;
          return false;
        }
        return true;
      });
      return matches;
    }

    double _scoreElement(Element element, Element target) {
      if (identical(element, target)) return double.maxFinite;
      return (isAncestorOf(element, target) || isAncestorOf(target, element))
          ? 1
          : 0;
    }

    Iterable<Element> getActiveElementsForLocation(_Location location,
        {@required Element closestTo}) {
      final List<Element> matches = _getElementsMatchingLocation(
          WidgetsBinding.instance?.renderViewElement, location);
      if (matches.length > 1 && closestTo != null) {
        final Map<Element, double> scores = Map<Element, double>.identity();
        for (Element element in matches) {
          scores[element] = _scoreElement(element, closestTo);
        }
        matches.sort((Element a, Element b) => scores[b].compareTo(scores[a]));
      }
      return matches;
    }

    final Iterable<Element> active = getActiveElementsForLocation(
        _getCreationLocation(targetElement),
        closestTo: _isAncestor(targetRenderObject, rootRenderObject)
            ? targetElement
            : null);
    final List<DiagnosticsNode> nodes = active
        .where((Element element) => _isAncestorElement(element, rootElement))
        .map((Element element) => element.toDiagnosticsNode())
        .toList();

    rootRenderObject = rootRenderObject?.parent as RenderObject;

    Map<String, Object> addTransformProperties(
        DiagnosticsNode node, InspectorSerializationDelegate delegate) {
      final Object value = node.value;
      RenderObject renderObject;
      if (value is Element) {
        renderObject = value.renderObject;
      } else if (value is RenderObject) {
        renderObject = value;
      }
      if (renderObject != null && _isAncestor(renderObject, rootRenderObject)) {
        return {
          'transformToRoot': transformedRectToJson(renderObject.semanticBounds,
              renderObject.getTransformTo(rootRenderObject))
        };
      } else {
        return <String, Object>{};
      }
    }

    return DiagnosticsNode.toJsonList(
      nodes,
      null,
      InspectorSerializationDelegate(
        groupName: groupName,
        service: WidgetInspectorService.instance,
        includeProperties: false,
        addAdditionalPropertiesCallback: addTransformProperties,
      ),
    );
  }

  List<Element> _getElementsMatchingLocation(Element element,
      _Location location) {
    final List<Element> matches = <Element>[];

    void addMatchesCallback(Element element) {
      if (_getCreationLocation(element) == location) {
        matches.add(element);
      }
      element.visitChildren(addMatchesCallback);
    }

    if (element != null) {
      addMatchesCallback(element);
    }
    return matches;
  }

  bool isAncestorOf(Element element, Element target) {
    bool matches = false;
    if (element.renderObject?.attached != true ||
        target.renderObject?.attached != true) {
      return false;
    }
    element.visitAncestorElements((Element ancestor) {
      if (identical(ancestor, target)) {
        matches = true;
        return false;
      }
      return true;
    });
    return matches;
  }

  Iterable<Element> getActiveElementsForLocation(_Location location,
      {@required Element closestTo}) {
    double _scoreElement(Element element, Element target) {
      if (identical(element, target)) return double.maxFinite;
      return (isAncestorOf(element, target) || isAncestorOf(target, element))
          ? 1
          : 0;
    }

    final List<Element> matches = _getElementsMatchingLocation(
        WidgetsBinding.instance?.renderViewElement, location);
    if (matches.length > 1 && closestTo != null) {
      final Map<Element, double> scores = Map<Element, double>.identity();
      for (Element element in matches) {
        scores[element] = _scoreElement(element, closestTo);
      }
      matches.sort((Element a, Element b) => scores[b].compareTo(scores[a]));
    }
    return matches;
  }

  Element _elementForScreenshot() {
    Element root = WidgetsBinding.instance?.renderViewElement;
    Element match = null;
    Element inspector = null;
    /*
    void findMatch(Element e) {
      if (e.widget is WidgetInspector) {
        inspector = e;
      }
      if (inspector != null && match != null && e.renderObject is RenderPointerListener) {
        match = e;
      }
      if (match != null) {
        e.visitChildren(findMatch);
      }
    }
    findMatch(root);
*/
    return match ?? root;
  }

  Matrix4 buildImageTransform(OffsetLayer layer, Rect bounds,
      double pixelRatio) {
    final Matrix4 transform = Matrix4.translationValues(
      (-bounds.left - layer.offset.dx) * pixelRatio,
      (-bounds.top - layer.offset.dy) * pixelRatio,
      0.0,
    );
    transform.scale(pixelRatio, pixelRatio);
    return transform;
  }

  Map<String, Object> transformedRectToJson(Rect rect, Matrix4 transform) {
    if (rect == null || transform == null) {
      return null;
    }
    return <String, Object>{
      'left': rect.left,
      'top': rect.top,
      'width': rect.width,
      'height': rect.height,
      'transform': transform.storage.toList(),
    };
  }

  Map<String, Object> buildTransformedRect(RenderObject renderObject,
      double maxPixelRatio) {
    if (renderObject == null || !renderObject.attached) {
      return null;
    }
    final Rect renderBounds = _calculateSubtreeBounds(renderObject);

    final double pixelRatio = math.min(
      maxPixelRatio,
      math.min(
        width / renderBounds.width,
        height / renderBounds.height,
      ),
    );

    final layer = renderObject.debugLayer;
    if (layer is OffsetLayer) {
      final OffsetLayer containerLayer = layer;
      return transformedRectToJson(
        renderBounds,
        buildImageTransform(containerLayer,
            renderBounds.shift(-containerLayer.offset), pixelRatio),
      );
    } else {
      return null;
    }
  }

  List<Element> elements;
  if (location != null) {
    elements = getActiveElementsForLocation(location, closestTo: null)
        .take(count)
        .toList();
  } else {
    elements = <Element>[_elementForScreenshot()];
  }
  var target = WidgetInspectorService.instance.selection.currentElement;


  if (elements.isEmpty) {
    return <String, Object>{'result': null};
  }
  final Element element = elements.first;

  if (element.renderObject == null || !element.renderObject.attached) {
    return <String, Object>{'result': null};
  }

  final RenderObject renderObject = element.renderObject;
  var boxes = _getBoundingBoxesHelper(element, target, groupName);

  var screenshotJson = <String, Object>{
    'image': null,
    'transformedRect': buildTransformedRect(renderObject, maxPixelRatio),
  };

  final List<DiagnosticsNode> nodes = elements.take(count).map((
      Element element) => element.toDiagnosticsNode()).toList();

  return <String, Object>{
    'result': <String, Object>{
      'screenshot': screenshotJson,
      'boxes': boxes,
      'elements': DiagnosticsNode.toJsonList(
        nodes,
        null,
        InspectorSerializationDelegate(
          groupName: groupName,
          service: WidgetInspectorService.instance,
        ),
      ),
    },
  };
}

List<Map<String, Object>> hitTest(
  String id,
  String file,
  int startLine,
  int endLine,
  Offset offset,
  String groupName
) {
  final WidgetInspectorService service = WidgetInspectorService.instance;
  List<Map<String, Object>> _getBoundingBoxesHelper(
      Element rootElement, Element targetElement, String groupName) {
    Map<String, Object> transformedRectToJson(Rect rect, Matrix4 transform) {
      if (rect == null || transform == null) {
        return null;
      }
      return <String, Object>{
        'left': rect.left,
        'top': rect.top,
        'width': rect.width,
        'height': rect.height,
        'transform': transform.storage.toList(),
      };
    }

    bool _isAncestor(RenderObject renderer, RenderObject ancestor) {
      if (ancestor == null) return true;
      if (renderer == null ||
          !renderer.attached ||
          !ancestor.attached) return false;
      while (renderer != null) {
        if (identical(renderer, ancestor)) return true;
        renderer = renderer.parent as RenderObject;
      }
      return false;
    }

    bool _isAncestorElement(Element e1, Element e2) {
      if (e2 == null) return true;
      if (identical(e1, e2)) return true;
      if (e1.renderObject?.attached != true ||
          e2.renderObject?.attached != true) return false;
      bool matches = false;
      e1.visitAncestorElements((Element ancestor) {
        if (identical(ancestor, e2)) {
          matches = true;
          return false;
        }
        return true;
      });
      return matches;
    }

    RenderObject rootRenderObject = rootElement?.renderObject;
    final RenderObject targetRenderObject = targetElement?.renderObject;
    if (rootRenderObject == null ||
        rootRenderObject.attached == false ||
        targetRenderObject == null ||
        targetRenderObject.attached == false) return null;

    List<Element> _getElementsMatchingLocation(
        Element element, _Location location) {
      final List<Element> matches = <Element>[];

      void addMatchesCallback(Element element) {
        if (_getCreationLocation(element) == location) {
          matches.add(element);
        }
        element.visitChildren(addMatchesCallback);
      }

      if (element != null) {
        addMatchesCallback(element);
      }
      return matches;
    }

    bool isAncestorOf(Element element, Element target) {
      bool matches = false;
      if (element.renderObject?.attached != true ||
          target.renderObject?.attached != true) {
        return false;
      }
      element.visitAncestorElements((Element ancestor) {
        if (identical(ancestor, target)) {
          matches = true;
          return false;
        }
        return true;
      });
      return matches;
    }

    double _scoreElement(Element element, Element target) {
      if (identical(element, target)) return double.maxFinite;
      return (isAncestorOf(element, target) || isAncestorOf(target, element))
          ? 1
          : 0;
    }

    Iterable<Element> getActiveElementsForLocation(_Location location) {
      final List<Element> matches = _getElementsMatchingLocation(
          WidgetsBinding.instance?.renderViewElement, location);
      return matches;
    }

    final Iterable<Element> active = getActiveElementsForLocation(
        _getCreationLocation(targetElement));
    final List<DiagnosticsNode> nodes = active
        .where((Element element) => _isAncestorElement(element, rootElement))
        .map((Element element) => element.toDiagnosticsNode())
        .toList();

    if (rootRenderObject?.parent != null) {
      rootRenderObject = rootRenderObject?.parent as RenderObject;
    }

    Map<String, Object> addTransformProperties(
        DiagnosticsNode node, InspectorSerializationDelegate delegate) {
      final Object value = node.value;
      RenderObject renderObject;
      if (value is Element) {
        renderObject = value.renderObject;
      } else if (value is RenderObject) {
        renderObject = value;
      }
      if (renderObject != null && _isAncestor(renderObject, rootRenderObject)) {
        return {
          'transformToRoot': transformedRectToJson(renderObject.semanticBounds,
              renderObject.getTransformTo(rootRenderObject))
        };
      } else {
        return <String, Object>{};
      }
    }

    return DiagnosticsNode.toJsonList(
      nodes,
      null,
      InspectorSerializationDelegate(
        groupName: groupName,
        service: WidgetInspectorService.instance,
        includeProperties: false,
        addAdditionalPropertiesCallback: addTransformProperties,
      ),
    );
  }

  Element _findFirstMatchingElement(RenderObject hit, String file, int startLine, int endLine, Element root) {
    bool withinRange(Element element) {
      final _Location location = _getCreationLocation(element);
      if (file == null) {
        return _isLocalCreationLocation(location);
      }
      return location != null && location.file == file && (startLine == null || location.line >= startLine && location.line <= endLine);
    }
    Element element = hit.debugCreator.element as Element;

    if (withinRange(element)) {
      return element;
    }
    Element match;
    element.visitAncestorElements((Element ancestor) {
      if (withinRange(ancestor)) {
        match = ancestor;
        return false;
      }
      return !identical(ancestor, root);
    });
    return match;
  }

  List<RenderObject> _hitTestRenderObject(Offset position, RenderObject root, Matrix4 transform) {
    final List<RenderObject> regularHits = <RenderObject>[];
    final List<RenderObject> edgeHits = <RenderObject>[];
    bool _hitTestHelper(
        List<RenderObject> hits,
        List<RenderObject> edgeHits,
        Offset position,
        RenderObject object,
        Matrix4 transform,
        ) {
      bool hit = false;
      final Matrix4 inverse = Matrix4.tryInvert(transform);
      if (inverse == null) {
        return false;
      }
      final Offset localPosition = MatrixUtils.transformPoint(inverse, position);

      final List<DiagnosticsNode> children = object.debugDescribeChildren();
      for (int i = children.length - 1; i >= 0; i -= 1) {
        final DiagnosticsNode diagnostics = children[i];
        assert(diagnostics != null);
        if (diagnostics.style == DiagnosticsTreeStyle.offstage ||
            diagnostics.value is! RenderObject)
          continue;
        final RenderObject child = diagnostics.value as RenderObject;
        final Rect paintClip = object.describeApproximatePaintClip(child);
        if (paintClip != null && !paintClip.contains(localPosition))
          continue;

        final Matrix4 childTransform = transform.clone();
        object.applyPaintTransform(child, childTransform);
        if (_hitTestHelper(hits, edgeHits, position, child, childTransform))
          hit = true;
      }

      final Rect bounds = object.semanticBounds;
      if (bounds.contains(localPosition)) {
        hit = true;
        if (!bounds.deflate(2.0).contains(localPosition))
          edgeHits.add(object);
      }
      if (hit)
        hits.add(object);
      return hit;
    }

    _hitTestHelper(regularHits, edgeHits, position, root, transform);
    final Map<RenderObject, double> scores = {};
    for (RenderObject object in regularHits) {
      Element element = object.debugCreator?.element as Element;
      double score = 0;
      if (element != null) {
        int depthToSummary = 0;
        int summaryDepth = 0;
        int depth = 0;
        Element nearestLocal;
        if (element?.renderObject?.attached ?? false) {
          element.visitAncestorElements((Element ancestor) {
            if (_isLocalCreationLocation(ancestor)) {
              if (nearestLocal == null) {
                nearestLocal = ancestor;
                depthToSummary = depth;
              }
              summaryDepth++;
            }
            depth++;
            return true;
          });
        }
        score = summaryDepth.toDouble();
      }
      scores[object] = score;
    }
    double _area(RenderObject object) {
      final Size size = object.semanticBounds?.size;
      return size == null ? double.maxFinite : size.width * size.height;
    }
    regularHits.sort((RenderObject a, RenderObject b) => scores[b].compareTo(scores[a]));
    return regularHits;
  }

  Element element;
  final Object target = service.toObject(id);
  RenderObject root;
  if (target is Element) {
    root = target.renderObject;
    final List<RenderObject> hits = _hitTestRenderObject(offset, root, Matrix4.identity());
    if (hits.isNotEmpty) {
      for (RenderObject hit in hits) {
        element = _findFirstMatchingElement(hit, file, startLine, endLine, target);
        if (element != null) break;
      }
      element ??= hits.first.debugCreator.element as Element;
    }
    return _getBoundingBoxesHelper(target, element, groupName);
  }
  return null;
}
