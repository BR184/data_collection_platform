package com.data.collection.platform.entity.statistics;

import com.data.collection.platform.entity.OptionItemResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SystemTestIssueMultiBoardResponse(
    Scope scope,
    List<OptionItemResponse> projectOptions,
    List<OptionItemResponse> testingPhaseOptions,
    List<Rule> rules,
    List<SummaryCard> summaryCards,
    List<Chart> charts) {

  public record Scope(
      Long projectId,
      String projectName,
      String testingPhase,
      List<String> expandedTestingPhases,
      String scopeLabel) {}

  public record SummaryCard(
      String key,
      String label,
      String value,
      String tone,
      String ruleKey) {}

  public record Rule(
      String key,
      String title,
      String formula,
      String scope,
      String target,
      String description) {}

  public record Chart(
      String key,
      String title,
      String description,
      String chartType,
      String ruleKey,
      String detailViewKey,
      Map<String, String> detailParams,
      String exportName,
      List<String> categories,
      List<Series> series,
      List<Point> points,
      Map<String, String> metadata) {}

  public record Series(
      String name,
      List<Point> data) {}

  public record Point(
      String name,
      BigDecimal value,
      String pointKey,
      String detailViewKey,
      Map<String, String> detailParams) {}
}
