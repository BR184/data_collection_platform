package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticDetailColumn;
import java.util.ArrayList;
import java.util.List;

final class StatisticIssueDetailColumns {
  private StatisticIssueDetailColumns() {
  }

  static List<StatisticDetailColumn> systemTest(
      String titleLabel,
      String moduleLabel,
      List<StatisticDetailColumn> statusColumns,
      List<StatisticDetailColumn> extraMainColumns,
      List<StatisticDetailColumn> extraExpandColumns) {
    List<StatisticDetailColumn> columns = new ArrayList<>();
    columns.add(issueNumber());
    columns.add(title(titleLabel));
    columns.addAll(statusColumns);
    columns.addAll(extraMainColumns);
    columns.add(updatedAt("更新时间"));
    columns.add(module(moduleLabel));
    columns.addAll(extraExpandColumns);
    return List.copyOf(columns);
  }

  static List<StatisticDetailColumn> customerIssue(
      String titleLabel,
      String moduleLabel,
      List<StatisticDetailColumn> statusColumns,
      List<StatisticDetailColumn> extraMainColumns,
      List<StatisticDetailColumn> extraExpandColumns) {
    List<StatisticDetailColumn> columns = new ArrayList<>();
    columns.add(issueNumber());
    columns.add(title(titleLabel));
    columns.addAll(statusColumns);
    columns.addAll(extraMainColumns);
    columns.add(updatedAt("议题更新时间"));
    columns.add(module(moduleLabel));
    columns.addAll(extraExpandColumns);
    return List.copyOf(columns);
  }

  static StatisticDetailColumn issueNumber() {
    return new StatisticDetailColumn("iid", "议题编号", 120, 120, true);
  }

  static StatisticDetailColumn title(String label) {
    return new StatisticDetailColumn("title", label, null, 260, true);
  }

  static StatisticDetailColumn state(String label) {
    return new StatisticDetailColumn("state", label, 120, 120, true, "tag");
  }

  static StatisticDetailColumn severity(String key, String label, int width) {
    return new StatisticDetailColumn(key, label, width, width, true, "tag");
  }

  static StatisticDetailColumn bugStatus() {
    return new StatisticDetailColumn("bugStatus", "测试状态", 160, 160, true, "tags");
  }

  static StatisticDetailColumn delayCause() {
    return new StatisticDetailColumn("delayCause", "延期原因", 160, 160, true);
  }

  static StatisticDetailColumn author(String label) {
    return new StatisticDetailColumn("authorName", label, 140, 140, true);
  }

  static StatisticDetailColumn assignee(String label, int width) {
    return new StatisticDetailColumn("assigneeName", label, width, width, true);
  }

  static StatisticDetailColumn updatedAt(String label) {
    return new StatisticDetailColumn("updatedAt", label, 180, 180, true);
  }

  static StatisticDetailColumn module(String label) {
    return StatisticDetailColumn.expandOnly("moduleNames", label, null, 180, true, "tags");
  }

  static StatisticDetailColumn project(String label) {
    return StatisticDetailColumn.expandOnly("projectName", label, null, 160, true);
  }

  static StatisticDetailColumn createdAt() {
    return StatisticDetailColumn.expandOnly("createdAt", "议题提交时间", 180, 180, true);
  }

  static StatisticDetailColumn labels() {
    return StatisticDetailColumn.expandOnly("labels", "标签", null, 240, false, "tags");
  }

  static StatisticDetailColumn milestone(String label) {
    return StatisticDetailColumn.expandOnly("milestoneTitle", label, 180, 180, true);
  }
}
