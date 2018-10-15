/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.google.common.base.Objects;
import io.flutter.inspector.DiagnosticsNode;
import io.netty.util.collection.IntObjectHashMap;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.intellij.icons.AllIcons.Actions;

public class PerfTipRule {
  private static List<PerfTipRule> tips;
  final String hackFileName;
  final String message;
  final String url;
  final int minSinceNavigate;
  final int minPerSecond;
  final PerfReportKind kind;
  final int priority;
  final int minProblemLocationsInSubtree;
  final Icon icon;
  WidgetPattern pattern;

  PerfTipRule(
    PerfReportKind kind,
    int priority,
    String hackFileName,
    String message,
    String url,
    WidgetPattern pattern,
    int minProblemLocationsInSubtree,
    int minSinceNavigate,
    int minPerSecond,
    Icon icon
  ) {
    this.kind = kind;
    this.priority = priority;
    this.hackFileName = hackFileName;
    this.message = message;
    this.url = url;
    this.pattern = pattern;
    this.minProblemLocationsInSubtree = minProblemLocationsInSubtree;
    this.minSinceNavigate = minSinceNavigate;
    this.minPerSecond = minPerSecond;
    this.icon = icon;
  }

  static public WidgetPattern matchParent(String name) {
    return new WidgetPattern(name, null);
  }

  static public WidgetPattern matchWidget(String name) {
    return new WidgetPattern(null, name);
  }

  static List<PerfTipRule> getAllTips() {
    if (tips != null) {
      return tips;
    }
    tips = new ArrayList<>();
    tips.add(new PerfTipRule(
      PerfReportKind.rebuild,
      3,
      "perf_diagnosis_demo/lib/clock_demo.dart",
      "Performance considerations of StatefulWidget",
      "https://master-docs-flutter-io.firebaseapp.com/flutter/widgets/StatefulWidget-class.html#performance-considerations",
      matchParent("StatefulWidget"),
      4, // Only relevant if the build method is somewhat large.
      50,
      20,
      Actions.IntentionBulb
    ));
    tips.add(new PerfTipRule(
      PerfReportKind.rebuild,
      1,
      "perf_diagnosis_demo/lib/list_demo.dart",
      "Using ListView to load items efficiently",
      "https://master-docs-flutter-io.firebaseapp.com/flutter/widgets/ListView-class.html#child-elements-lifecycle",
      matchParent("ListView"),
      1,
      40,
      -1,
      Actions.IntentionBulb
    ));

    tips.add(new PerfTipRule(
      PerfReportKind.rebuild,
      1,
      "perf_diagnosis_demo/lib/spinning_box_demo.dart",
      "Performance optimizations when using AnimatedBuilder",
      "https://master-docs-flutter-io.firebaseapp.com/flutter/widgets/AnimatedBuilder-class.html#performance-optimizations",
      matchParent("AnimatedBuilder"),
      1,
      50,
      20,
      Actions.IntentionBulb
    ));


    tips.add(new PerfTipRule(
      PerfReportKind.rebuild,
      2,
      "perf_diagnosis_demo/lib/scorecard_demo.dart",
      "Performance considerations of Opacity animations",
      "https://master-docs-flutter-io.firebaseapp.com/flutter/widgets/Opacity-class.html#opacity-animation",
      matchParent("Opacity"),
      1,
      20,
      8,
      Actions.IntentionBulb
    ));
    return tips;
  }

  public String getMessage() {
    return message;
  }

  public String getUrl() {
    return url;
  }

  public Icon getIcon() {
    return icon;
  }

  String getHtmlFragmentDescription() {
    return "<p><a href='" + url + "'>" + message + "</a></p>";
  }

  boolean maybeMatches(SummaryStats summary) {
    boolean matchesFrequency = false;
    if (minSinceNavigate > 0 && summary.getTotalSinceNavigation() >= minSinceNavigate) {
      matchesFrequency = true;
    }
    if (minPerSecond > 0 && summary.getPastSecond() >= minPerSecond) {
      matchesFrequency = true;
    }
    if (!matchesFrequency) {
      return false;
    }
    if (pattern.widget != null && !pattern.widget.equals(summary.getDescription())) {
      return false;
    }
    return true;
  }

  boolean matches(SummaryStats summary, Collection<DiagnosticsNode> candidates, IntObjectHashMap<SummaryStats> statsInFile) {
    if (!maybeMatches(summary)) {
      return false;
    }
    if (pattern.parentWidget != null) {
      final boolean patternIsStateful = Objects.equal(pattern.parentWidget, "StatefulWidget");
      for (DiagnosticsNode candidate : candidates) {
        if (ancestorMatches(statsInFile, patternIsStateful, candidate, candidate.getParent())) {
          return true;
        }
      }
      return false;
    }
    if (pattern.widget != null) {
      for (DiagnosticsNode candidate : candidates) {
        if (pattern.widget.equals(candidate.getWidgetRuntimeType())) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ancestorMatches(IntObjectHashMap<SummaryStats> statsInFile,
                                  boolean patternIsStateful,
                                  DiagnosticsNode candidate,
                                  DiagnosticsNode parent) {
    if (parent == null) {
      return false;
    }
    // TODO(jacobr): count number of children that also maybeMatches.
    // XXX
    if ((parent.isStateful() && patternIsStateful) || (pattern.parentWidget.equals(parent.getWidgetRuntimeType()))) {
      return minProblemLocationsInSubtree <= 1 || minProblemLocationsInSubtree <= countSubtreeMatches(candidate, statsInFile);
    }
    parent = parent.getParent();
    if (parent != null && Objects.equal(parent.getCreationLocation().getPath(), candidate.getCreationLocation().getPath())) {
      // Keep walking up the tree until we hit a different file.
      // TODO(jacobr): this is an ugly heuristic.
      return ancestorMatches(statsInFile, patternIsStateful, candidate, parent);
    }
    return false;
  }

  // TODO(jacobr): this method could be slow in degenerate cases. We could
  // memoize match counts to avoid a possible O(n^2) algorithm worst case.
  private int countSubtreeMatches(DiagnosticsNode candidate, IntObjectHashMap<SummaryStats> statsInFile) {
    final int id = candidate.getLocationId();
    int matches = 0;
    if (id >= 0) {
      final SummaryStats stats = statsInFile.get(id);
      if (stats != null && maybeMatches(stats)) {
        matches += 1;
      }
    }
    final ArrayList<DiagnosticsNode> children = candidate.getChildren().getNow(null);
    if (children != null) {
      for (DiagnosticsNode child : children) {
        matches += countSubtreeMatches(child, statsInFile);
      }
    }
    return matches;
  }

  static public class WidgetPattern {
    final String parentWidget;
    final String widget;

    WidgetPattern(String parentWidget, String widget) {
      this.parentWidget = parentWidget;
      this.widget = widget;
    }
  }
}
