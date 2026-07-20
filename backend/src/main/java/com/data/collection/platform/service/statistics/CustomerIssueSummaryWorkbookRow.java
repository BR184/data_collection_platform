package com.data.collection.platform.service.statistics;

import java.util.List;

/** Immutable 29-column projection of the legacy customer-summary issue workbook. */
record CustomerIssueSummaryWorkbookRow(
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
    String functionName,
    String fixStatus,
    String majorCause,
    String secondCause,
    String specificReason,
    String modification,
    String causedByOther,
    String effectFunction,
    String hasTested,
    String potentialImpact,
    String relationTableUpdated,
    String closeTime) {

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
          "功能名称",
          "修复状态",
          "一级缺陷原因",
          "二级缺陷原因",
          "具体原因",
          "修改方案",
          "由修改其他缺陷造成的",
          "修改该缺陷可能影响的功能",
          "是否对可能影响的功能进行了测试",
          "有无遗留问题或潜在的影响",
          "是否更新了关联关系表",
          "议题关闭时间");

  List<String> values() {
    return List.of(
        text(updatedDate),
        text(submissionDate),
        text(moduleName),
        text(issueReference),
        text(issueTitle),
        text(author),
        text(handler),
        text(status),
        text(bugStatus),
        text(testingPhase),
        text(severityLevel),
        text(category),
        text(milestone),
        text(assignee),
        text(urgency),
        text(delayCause),
        text(fixUser),
        text(functionName),
        text(fixStatus),
        text(majorCause),
        text(secondCause),
        text(specificReason),
        text(modification),
        text(causedByOther),
        text(effectFunction),
        text(hasTested),
        text(potentialImpact),
        text(relationTableUpdated),
        text(closeTime));
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
