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
    columns.add(new StatisticDetailColumn("moduleNames", moduleLabel, 160, 160, true, "tags"));
    columns.add(title(titleLabel));
    columns.addAll(statusColumns);
    columns.addAll(extraMainColumns);
    columns.addAll(customerIssueLegacyExpandColumns(moduleLabel, statusColumns, extraMainColumns, extraExpandColumns));
    return List.copyOf(columns);
  }

  static List<StatisticDetailColumn> moduleTableLegacyDetail() {
    List<StatisticDetailColumn> columns = new ArrayList<>();
    columns.add(new StatisticDetailColumn("iid", "议题编号", 120, 120, true));
    columns.add(new StatisticDetailColumn("moduleNames", "模块名", 160, 160, true, "tags"));
    columns.add(new StatisticDetailColumn("title", "议题标题", 360, 260, true));
    columns.add(new StatisticDetailColumn("state", "议题状态", 120, 120, true, "tag"));
    columns.add(new StatisticDetailColumn("severityLevel", "严重程度", 140, 140, true, "tag"));
    columns.add(new StatisticDetailColumn("bugStatus", "测试状态", 180, 180, true, "tags"));
    columns.add(StatisticDetailColumn.expandOnly("updatedAt", "议题更新时间", 180, 180, true));
    columns.add(StatisticDetailColumn.expandOnly("createdAt", "议题提交时间", 180, 180, true));
    columns.add(StatisticDetailColumn.expandOnly("moduleNames", "模块名", null, 180, true, "tags"));
    columns.add(StatisticDetailColumn.expandOnly("iid", "议题编号", 120, 120, true));
    columns.add(StatisticDetailColumn.expandOnly("title", "议题标题", 420, 260, true));
    columns.add(StatisticDetailColumn.expandOnly("authorName", "议题提交人", 140, 140, true));
    columns.add(StatisticDetailColumn.expandOnly("assigneeName", "议题处理人", 140, 140, true));
    columns.add(StatisticDetailColumn.expandOnly("state", "议题状态", 120, 120, true, "tag"));
    columns.add(StatisticDetailColumn.expandOnly("bugStatus", "测试状态", 180, 180, true, "tags"));
    columns.add(StatisticDetailColumn.expandOnly("severityLevel", "议题严重程度", 140, 140, true, "tag"));
    return List.copyOf(columns);
  }

  private static List<StatisticDetailColumn> customerIssueLegacyExpandColumns(
      String moduleLabel,
      List<StatisticDetailColumn> statusColumns,
      List<StatisticDetailColumn> extraMainColumns,
      List<StatisticDetailColumn> extraExpandColumns) {
    List<StatisticDetailColumn> columns = new ArrayList<>();
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("updatedAt", "议题更新时间", 180, 180, true));
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("createdAt", "议题提交时间", 180, 180, true));
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("moduleNames", moduleLabel, null, 180, true, "tags"));
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("iid", "议题编号", 120, 120, true));
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("title", "议题标题", 420, 260, true));
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("authorName", "议题提交人", 140, 140, true));
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("assigneeName", "议题处理人", 140, 140, true));
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("state", "议题状态", 120, 120, true, "tag"));
    addExpandColumn(columns, StatisticDetailColumn.expandOnly("bugStatus", "测试状态", 180, 180, true, "tags"));
    statusColumns.forEach(column -> addExpandColumn(columns, toExpandOnly(column)));
    extraMainColumns.forEach(column -> addExpandColumn(columns, toExpandOnly(column)));
    extraExpandColumns.forEach(column -> addExpandColumn(columns, column));
    return columns;
  }

  private static void addExpandColumn(List<StatisticDetailColumn> columns, StatisticDetailColumn column) {
    if (columns.stream().noneMatch(existing -> existing.key().equals(column.key()))) {
      columns.add(column);
    }
  }

  private static StatisticDetailColumn toExpandOnly(StatisticDetailColumn column) {
    if (column.expandOnly()) {
      return column;
    }
    return StatisticDetailColumn.expandOnly(
        column.key(),
        column.label(),
        column.width(),
        column.minWidth(),
        column.sortable(),
        column.type());
  }

  static StatisticDetailColumn issueNumber() {
    return new StatisticDetailColumn("iid", "议题编号", 120, 120, true);
  }

  static StatisticDetailColumn title(String label) {
    return new StatisticDetailColumn("title", label, 360, 260, true);
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
