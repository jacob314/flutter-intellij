/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.perf;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.intellij.openapi.fileEditor.TextEditor;
import io.flutter.inspector.DiagnosticsNode;
import io.netty.util.collection.IntObjectHashMap;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class WidgetPerfLinter {
  final FlutterWidgetPerf widgetPerf;
  private final WidgetPerfProvider perfProvider;

  private ArrayList<PerfTip> lastTips;
  private Set<Location> lastCandidateLocations;
  private Multimap<Integer, DiagnosticsNode> nodesForLocation;

  WidgetPerfLinter(FlutterWidgetPerf widgetPerf, WidgetPerfProvider perfProvider) {
    this.widgetPerf = widgetPerf;
    this.perfProvider = perfProvider;
  }

  public CompletableFuture<ArrayList<PerfTip>> getTipsFor(TextEditor textEditor) {
    assert (textEditor != null);
    final FilePerfInfo fileStats = widgetPerf.buildSummaryStats(textEditor);
    final ArrayList<PerfTipRule> candidateRules = new ArrayList<>();
    final Set<Location> candidateLocations = new HashSet<>();
    for (PerfTipRule rule : PerfTipRule.getAllTips()) {
      for (SummaryStats stats : fileStats.getStats()) {
        if (rule.maybeMatches(stats)) {
          candidateRules.add(rule);
          candidateLocations.add(stats.getLocation());
          break;
        }
      }
    }
    if (candidateRules.isEmpty()) {
      return CompletableFuture.completedFuture(new ArrayList<>());
    }

    if (candidateLocations.equals(lastCandidateLocations)) {
      // No need to load the widget tree again if the list of locations matching
      // rules has not changed.
      return CompletableFuture.completedFuture(computeMatches(candidateRules, fileStats));
    }

    lastCandidateLocations = candidateLocations;
    return perfProvider.getWidgetTree().thenApplyAsync((treeRoot) -> {
      if (treeRoot != null) {
        nodesForLocation = LinkedListMultimap.create();
        addNodesToMap(treeRoot);
        return computeMatches(candidateRules, fileStats);
      } else {
        return new ArrayList<>();
      }
    });
  }

  private ArrayList<PerfTip> computeMatches(ArrayList<PerfTipRule> candidateRules, FilePerfInfo fileStats) {
    final ArrayList<PerfTip> matches = new ArrayList<>();
    final Map<PerfReportKind, IntObjectHashMap<SummaryStats>> maps = new HashMap<>();
    for (SummaryStats stats : fileStats.getStats()) {
      IntObjectHashMap<SummaryStats> map = maps.get(stats.getKind());
      if (map == null) {
        map = new IntObjectHashMap<>();
        maps.put(stats.getKind(), map);
      }
      map.put(stats.getLocation().id, stats);
    }

    for (PerfTipRule rule : candidateRules) {
      final IntObjectHashMap<SummaryStats> map = maps.get(rule.kind);
      if (map != null) {
        final ArrayList<Location> matchingLocations = new ArrayList<>();
        for (SummaryStats stats : fileStats.getStats()) {
          final Collection<DiagnosticsNode> nodes = nodesForLocation.get(stats.getLocation().id);
          if (nodes == null || nodes.isEmpty()) {
            // This indicates a mismatch between the current inspector tree
            // and the stats we are using.
            continue;
          }
          if (rule.matches(stats, nodes, map)) {
            matchingLocations.add(stats.getLocation());
          }
        }
        if (matchingLocations.size() > 0) {
          matches.add(new PerfTip(rule, matchingLocations, 1.0 / rule.priority));
        }
      }
    }
    matches.sort(Comparator.comparingDouble(a -> -a.getConfidence()));
    return matches;
  }

  private void addNodesToMap(DiagnosticsNode node) {
    final int id = node.getLocationId();
    if (id >= 0) {
      nodesForLocation.put(id, node);
    }
    final ArrayList<DiagnosticsNode> children = node.getChildren().getNow(null);
    if (children != null) {
      for (DiagnosticsNode child : children) {
        addNodesToMap(child);
      }
    }
  }
}
