package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.SortSupport;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class DefectSummaryBoardSupport {
  private DefectSummaryBoardSupport() {}

  public static List<StatisticRowData> buildRows(
      List<StatisticIssueFactSource> sources, String totalRowLabel, String totalRowKey) {
    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (StatisticIssueFactSource issue : sources) {
      for (String moduleName : issue.moduleNames()) {
        buckets.computeIfAbsent(moduleName, AggregateBucket::new).accept(issue);
      }
    }
    List<StatisticRowData> rows =
        new ArrayList<>(
            buckets.values().stream()
                .map(bucket -> bucket.toRowData(sources.size()))
                .sorted(Comparator.comparing(StatisticRowData::rowLabel, String.CASE_INSENSITIVE_ORDER))
                .toList());
    rows.add(new AggregateBucket(totalRowLabel).acceptAll(sources).toRowData(sources.size(), totalRowKey));
    return rows;
  }

  public static Map<String, Object> toDetailRecord(
      StatisticIssueFactSource source, DateTimeFormatter formatter) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("iid", source.iid());
    record.put("title", source.title());
    record.put("moduleNames", String.join("、", source.moduleNames()));
    record.put("projectName", source.projectName());
    record.put("authorName", source.authorName());
    record.put("state", source.isClosed() ? "已关闭" : "未关闭");
    record.put("labels", String.join(", ", source.labels()));
    record.put(
        "updatedAt",
        source.updatedAt() == null ? "" : formatter.format(source.updatedAt()));
    return record;
  }

  public static Predicate<StatisticIssueFactSource> matchesMetric(String key) {
    return switch (key) {
      case "level1_back" -> StatisticIssueFactSource::isLevel1Back;
      case "level1_hang" -> StatisticIssueFactSource::isLevel1Hang;
      case "level1_other" -> StatisticIssueFactSource::isLevel1Other;
      case "level1_fixed" -> source -> source.isLevel1() && source.isSolvedLike();
      case "level1_total" -> StatisticIssueFactSource::isLevel1;
      case "level2_fixed" -> source -> source.isLevel2() && source.isSolvedLike();
      case "level2_total" -> StatisticIssueFactSource::isLevel2;
      case "level3_fixed" -> source -> source.isLevel3() && source.isSolvedLike();
      case "level3_total" -> StatisticIssueFactSource::isLevel3;
      case "suggestion_total" -> StatisticIssueFactSource::isSuggestion;
      case "p1_count" -> source -> source.isPriority("P1");
      case "p2_count" -> source -> source.isPriority("P2");
      case "p3_count" -> source -> source.isPriority("P3");
      case "solved_count" -> StatisticIssueFactSource::isSolvedLike;
      case "open_count" -> source -> !source.isClosed();
      case "extension_count" -> StatisticIssueFactSource::hasExtensionLabel;
      case "retest_failed_count" -> StatisticIssueFactSource::isRetestFailed;
      case "new_issue_fixed" -> source -> source.isNewIssue() && source.isSolvedLike();
      case "new_issue_total" -> StatisticIssueFactSource::isNewIssue;
      case "level2_legacy_count" -> source -> source.isLevel2() && source.legacy();
      case "level3_legacy_count" -> source -> source.isLevel3() && source.legacy();
      default -> source -> true;
    };
  }

  public static Comparator<StatisticIssueFactSource> buildDetailComparator(
      String sortField, String sortOrder) {
    Comparator<StatisticIssueFactSource> comparator =
        switch (sortField == null || sortField.isBlank() ? "updatedAt" : sortField.trim()) {
          case "iid" -> SortSupport.nullableComparable(StatisticIssueFactSource::iid);
          case "title" -> SortSupport.nullableString(StatisticIssueFactSource::title);
          case "moduleNames" ->
              SortSupport.nullableString(source -> String.join("、", source.moduleNames()));
          case "projectName" -> SortSupport.nullableString(StatisticIssueFactSource::projectName);
          case "authorName" -> SortSupport.nullableString(StatisticIssueFactSource::authorName);
          case "state" ->
              SortSupport.nullableComparable(source -> source.isClosed() ? 1 : 0);
          default -> SortSupport.nullableComparable(StatisticIssueFactSource::updatedAt);
        };
    comparator = comparator.thenComparing(StatisticIssueFactSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  public static List<StatisticRuleFlowStepSample> sample(List<StatisticIssueFactSource> issues) {
    return issues.stream()
        .limit(3)
        .map(
            issue ->
                new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title()
                        + (issue.moduleNames().isEmpty()
                            ? ""
                            : " | 模块: " + String.join("、", issue.moduleNames()))))
        .toList();
  }

  public static List<StatisticRuleMetricDefinition> buildMetricDefinitions() {
    return List.of(
        new StatisticRuleMetricDefinition("level1", "一级缺陷", "一级缺陷基于 severity_level = LEVEL1，再拆分回退、挂起、其他一级。", "一级缺陷修复率 = 一级缺陷已修复数量 / 一级缺陷总数", null),
        new StatisticRuleMetricDefinition("priority-summary", "缺陷级别汇总", "P1/P2/P3 与一级/二级/三级缺陷是两套独立统计体系，直接按 priority_level 聚合。", "Pn 修复率 = 已修复 Pn 数量 / Pn 总数；Pn 关闭率 = 已关闭 Pn 数量 / Pn 总数", null),
        new StatisticRuleMetricDefinition("summary", "综合汇总", "综合区展示模块总缺陷、缺陷占比、延期占比、已修复/未更新、修复率、关闭率、未关闭数量、申请延期和复测未通过。", "修复率 = 已修复/未更新数量 / 模块总缺陷数；缺陷占比 = 当前模块缺陷数 / 当前范围全部缺陷数", null),
        new StatisticRuleMetricDefinition("new-issue", "新发议题", "新发议题按“排除历史遗留”后的议题统计。", "新发议题修复率 = 已修复/未更新的新发议题数量 / 新发议题总数", null),
        new StatisticRuleMetricDefinition("legacy", "遗留率", "遗留区按 issue_fact.is_legacy 字段统计，不再用“未关闭”直接代替历史遗留。", "一级遗留率 = 一级缺陷历史遗留数量 / 一级缺陷总数；二三级遗留率 = (二级历史遗留 + 三级历史遗留) / (二级总数 + 三级总数)", null));
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private static String rate(long numerator, long denominator) {
    return StatisticMetricCalculator.rate(numerator, denominator);
  }

  private static String percent(double value) {
    return StatisticMetricCalculator.percent(value);
  }

  private record AggregateBucket(String rowLabel, List<StatisticIssueFactSource> issues) {
    private AggregateBucket(String rowLabel) {
      this(rowLabel, new ArrayList<>());
    }

    private AggregateBucket acceptAll(List<StatisticIssueFactSource> sourceIssues) {
      issues.addAll(sourceIssues);
      return this;
    }

    private void accept(StatisticIssueFactSource issue) {
      issues.add(issue);
    }

    private StatisticRowData toRowData(long overall) {
      return toRowData(overall, rowLabel);
    }

    private StatisticRowData toRowData(long overall, String rowKey) {
      long total = issues.size();
      long solved = issues.stream().filter(StatisticIssueFactSource::isSolvedLike).count();
      long closed = issues.stream().filter(StatisticIssueFactSource::isClosed).count();
      long open = total - closed;
      long delayed = issues.stream().filter(StatisticIssueFactSource::delayIssue).count();
      long extension = issues.stream().filter(StatisticIssueFactSource::hasExtensionLabel).count();
      long retest = issues.stream().filter(StatisticIssueFactSource::isRetestFailed).count();
      long level1Back = issues.stream().filter(StatisticIssueFactSource::isLevel1Back).count();
      long level1Hang = issues.stream().filter(StatisticIssueFactSource::isLevel1Hang).count();
      long level1Other = issues.stream().filter(StatisticIssueFactSource::isLevel1Other).count();
      long level1 = issues.stream().filter(StatisticIssueFactSource::isLevel1).count();
      long level1Fixed =
          issues.stream().filter(issue -> issue.isLevel1() && issue.isSolvedLike()).count();
      long level1Legacy = issues.stream().filter(issue -> issue.isLevel1() && issue.legacy()).count();
      long level2 = issues.stream().filter(StatisticIssueFactSource::isLevel2).count();
      long level2Fixed =
          issues.stream().filter(issue -> issue.isLevel2() && issue.isSolvedLike()).count();
      long level2Legacy = issues.stream().filter(issue -> issue.isLevel2() && issue.legacy()).count();
      long level3 = issues.stream().filter(StatisticIssueFactSource::isLevel3).count();
      long level3Fixed =
          issues.stream().filter(issue -> issue.isLevel3() && issue.isSolvedLike()).count();
      long level3Legacy = issues.stream().filter(issue -> issue.isLevel3() && issue.legacy()).count();
      long suggestion = issues.stream().filter(StatisticIssueFactSource::isSuggestion).count();
      long p1 = issues.stream().filter(issue -> issue.isPriority("P1")).count();
      long p1Fixed =
          issues.stream().filter(issue -> issue.isPriority("P1") && issue.isSolvedLike()).count();
      long p1Closed =
          issues.stream().filter(issue -> issue.isPriority("P1") && issue.isClosed()).count();
      long p2 = issues.stream().filter(issue -> issue.isPriority("P2")).count();
      long p2Fixed =
          issues.stream().filter(issue -> issue.isPriority("P2") && issue.isSolvedLike()).count();
      long p2Closed =
          issues.stream().filter(issue -> issue.isPriority("P2") && issue.isClosed()).count();
      long p3 = issues.stream().filter(issue -> issue.isPriority("P3")).count();
      long p3Fixed =
          issues.stream().filter(issue -> issue.isPriority("P3") && issue.isSolvedLike()).count();
      long newTotal = issues.stream().filter(StatisticIssueFactSource::isNewIssue).count();
      long newFixed =
          issues.stream().filter(issue -> issue.isNewIssue() && issue.isSolvedLike()).count();
      long newClosed =
          issues.stream()
              .filter(issue -> issue.isNewIssue() && issue.isClosedResolved())
              .count();
      long level23Legacy = level2Legacy + level3Legacy;
      long level23 = level2 + level3;
      double defectRatio = StatisticMetricCalculator.percentageOf(total, overall);
      double delayRatio = StatisticMetricCalculator.percentageOf(delayed, total);
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              cell("level1_back", level1Back, count(level1Back), true, rowKey),
              cell("level1_hang", level1Hang, count(level1Hang), true, rowKey),
              cell("level1_other", level1Other, count(level1Other), true, rowKey),
              cell("level1_fixed", level1Fixed, count(level1Fixed), true, rowKey),
              cell("level1_total", level1, count(level1), true, rowKey),
              cell("level1_rate", level1Fixed, rate(level1Fixed, level1), false, rowKey),
              cell("level2_fixed", level2Fixed, count(level2Fixed), true, rowKey),
              cell("level2_total", level2, count(level2), true, rowKey),
              cell("level2_rate", level2Fixed, rate(level2Fixed, level2), false, rowKey),
              cell("level3_fixed", level3Fixed, count(level3Fixed), true, rowKey),
              cell("level3_total", level3, count(level3), true, rowKey),
              cell("level3_rate", level3Fixed, rate(level3Fixed, level3), false, rowKey),
              cell("suggestion_total", suggestion, count(suggestion), true, rowKey),
              cell("p1_count", p1, count(p1), true, rowKey),
              cell("p1_fix_rate", p1Fixed, rate(p1Fixed, p1), false, rowKey),
              cell("p1_close_rate", p1Closed, rate(p1Closed, p1), false, rowKey),
              cell("p2_count", p2, count(p2), true, rowKey),
              cell("p2_fix_rate", p2Fixed, rate(p2Fixed, p2), false, rowKey),
              cell("p2_close_rate", p2Closed, rate(p2Closed, p2), false, rowKey),
              cell("p3_count", p3, count(p3), true, rowKey),
              cell("p3_fix_rate", p3Fixed, rate(p3Fixed, p3), false, rowKey),
              cell("module_total", total, count(total), true, rowKey),
              cell("defect_ratio", Math.round(defectRatio), percent(defectRatio), false, rowKey),
              cell("delay_defect_ratio", Math.round(delayRatio), percent(delayRatio), false, rowKey),
              cell("solved_count", solved, count(solved), true, rowKey),
              cell("fix_rate", solved, rate(solved, total), false, rowKey),
              cell("close_rate", closed, rate(closed, total), false, rowKey),
              cell("open_count", open, count(open), true, rowKey),
              cell("extension_count", extension, count(extension), true, rowKey),
              cell("retest_failed_count", retest, count(retest), true, rowKey),
              cell("new_issue_fixed", newFixed, count(newFixed), true, rowKey),
              cell("new_issue_total", newTotal, count(newTotal), true, rowKey),
              cell("new_issue_fix_rate", newFixed, rate(newFixed, newTotal), false, rowKey),
              cell("new_issue_close_rate", newClosed, rate(newClosed, newTotal), false, rowKey),
              cell("level1_legacy_rate", level1Legacy, rate(level1Legacy, level1), false, rowKey),
              cell("level2_legacy_count", level2Legacy, count(level2Legacy), true, rowKey),
              cell("level3_legacy_count", level3Legacy, count(level3Legacy), true, rowKey),
              cell("level23_legacy_rate", level23Legacy, rate(level23Legacy, level23), false, rowKey)));
    }

    private StatisticCellData cell(
        String key, long numericValue, String displayValue, boolean drilldown, String rowKey) {
      return new StatisticCellData(
          key,
          numericValue,
          displayValue,
          drilldown,
          drilldown ? "issue-list" : null,
          Map.of("rowKey", rowKey));
    }
  }
}
