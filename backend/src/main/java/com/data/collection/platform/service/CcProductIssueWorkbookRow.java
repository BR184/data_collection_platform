package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CC_PRODUCT 议题 Excel 的展示投影。
 *
 * <p>导出只消费记录页响应中的事实字段，禁止重新解析标签或标题，确保 Excel 与页面使用同一数据来源。</p>
 */
record CcProductIssueWorkbookRow(
    String issueReference,
    String moduleName,
    String functionName,
    String issueTitle,
    String customerNames,
    String author,
    String handler,
    String assignee,
    String issueState,
    String bugStatus,
    String testingPhase,
    String severityLevel,
    String priorityLevel,
    String category,
    String milestone,
    String delayCause,
    String fixUser,
    String plannedResolutionTime,
    String plannedMergeVersionBranch,
    String submissionDate,
    String retentionHours,
    String updatedDate) {

  static final List<String> HEADERS =
      List.of(
          "议题编号",
          "模块名",
          "功能名称",
          "议题标题",
          "客户",
          "议题提交人",
          "议题处理人",
          "议题指派人",
          "议题状态",
          "测试状态",
          "测试阶段",
          "严重程度",
          "缺陷优先级",
          "议题类别",
          "里程碑",
          "延期原因",
          "缺陷修复人",
          "计划解决时间",
          "计划合并版本分支",
          "提交时间",
          "缺陷滞留时长（小时）",
          "更新时间");

  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  static CcProductIssueWorkbookRow from(CustomerIssueRecordRowResponse row) {
    return new CcProductIssueWorkbookRow(
        issueReference(row.issueIid()),
        text(row.moduleNames()),
        text(row.functionName()),
        text(row.title()),
        text(row.customerNames()),
        text(row.authorName()),
        text(row.handlerName()),
        text(row.assigneeName()),
        CustomerIssueRecordWorkbookExportSupport.displayIssueState(row.issueState(), row.closedAt()),
        text(row.bugStatus()),
        text(row.testingPhase()),
        text(row.severityLevel()),
        text(row.priorityLevel()),
        text(row.category()),
        text(row.milestoneTitle()),
        text(row.delayCause()),
        text(row.fixUser()),
        text(row.plannedResolutionText()),
        text(row.plannedMergeVersionBranch()),
        date(row.createdAt()),
        number(row.retentionHours()),
        date(row.updatedAt()));
  }

  List<String> values() {
    return List.of(
        issueReference,
        moduleName,
        functionName,
        issueTitle,
        customerNames,
        author,
        handler,
        assignee,
        issueState,
        bugStatus,
        testingPhase,
        severityLevel,
        priorityLevel,
        category,
        milestone,
        delayCause,
        fixUser,
        plannedResolutionTime,
        plannedMergeVersionBranch,
        submissionDate,
        retentionHours,
        updatedDate);
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

  private static String number(Long value) {
    return value == null ? "" : String.valueOf(value);
  }
}
