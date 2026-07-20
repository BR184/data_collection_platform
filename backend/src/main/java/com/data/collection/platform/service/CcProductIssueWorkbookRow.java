package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Strongly typed projection of the old platform {@code ProjectIssueInfoExcelBo} contract.
 * Page-facing display values intentionally stay outside this projection.
 */
record CcProductIssueWorkbookRow(
    String updatedDate,
    String submissionDate,
    String moduleName,
    String issueReference,
    String issueTitle,
    String author,
    String handler,
    String status,
    String bugStatus,
    String testingPhase,
    String severityLevel,
    String category,
    String milestone,
    String assignee,
    String urgency,
    String delayCause,
    String fixUser,
    String functionName) {

  static final List<String> HEADERS =
      List.of(
          "议题更新时间",
          "议题提交时间",
          "模块名",
          "议题编号",
          "议题标题",
          "议题提交人",
          "议题处理人",
          "议题状态",
          "测试状态",
          "测试阶段",
          "议题严重程度",
          "议题类别",
          "里程碑",
          "议题指派人",
          "优先级",
          "延期原因",
          "缺陷修复人",
          "功能名称");

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  static CcProductIssueWorkbookRow from(CustomerIssueRecordRowResponse row) {
    List<String> labels = row.labels() == null ? List.of() : row.labels();
    Map<String, List<String>> oldPlatformLabels =
        IssueLabelRules.parseOldPlatformChineseColonLabelMap(labels);
    return new CcProductIssueWorkbookRow(
        date(row.updatedAt()),
        date(row.createdAt()),
        IssueLabelRules.oldPlatformCombinedIssueModuleName(labels),
        issueReference(row.issueIid()),
        text(row.title()),
        text(row.authorName()),
        text(row.assigneeName()),
        issueState(row.issueState()),
        IssueLabelRules.oldPlatformLabelValue(oldPlatformLabels, "状态", "未设定议题状态"),
        IssueLabelRules.oldPlatformLabelValue(oldPlatformLabels, "测试阶段", "未设定测试阶段"),
        IssueLabelRules.oldPlatformLabelValue(oldPlatformLabels, "严重程度", "未设定严重程度"),
        IssueLabelRules.oldPlatformLabelValue(oldPlatformLabels, "类别", "未设定类别"),
        text(row.milestoneTitle()),
        text(row.assigneeName()),
        IssueLabelRules.oldPlatformLabelValue(oldPlatformLabels, "紧急程度", "未设定紧急程度"),
        IssueLabelRules.oldPlatformLabelValue(oldPlatformLabels, "延期原因", "未设定类别"),
        text(row.fixUser()),
        functionName(row.title()));
  }

  List<String> values() {
    return List.of(
        updatedDate,
        submissionDate,
        moduleName,
        issueReference,
        issueTitle,
        author,
        handler,
        status,
        bugStatus,
        testingPhase,
        severityLevel,
        category,
        milestone,
        assignee,
        urgency,
        delayCause,
        fixUser,
        functionName);
  }

  private static String issueState(String state) {
    if ("closed".equalsIgnoreCase(state)) {
      return "CLOSED";
    }
    if ("opened".equalsIgnoreCase(state) || "open".equalsIgnoreCase(state)) {
      return "OPEN";
    }
    return text(state);
  }

  private static String functionName(String title) {
    if (title == null || !title.startsWith("【")) {
      return "";
    }
    int endIndex = title.indexOf("】");
    return endIndex < 0 ? "" : title.substring(1, endIndex);
  }

  private static String issueReference(Integer iid) {
    return iid == null ? "" : "#" + iid;
  }

  private static String date(LocalDateTime time) {
    return time == null ? "" : DATE_FORMATTER.format(time);
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
