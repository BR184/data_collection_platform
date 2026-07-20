package com.data.collection.platform.service.analytics;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

enum QualityRdAnalyticsDetailView {
  INTEGRATION_TEST_RESULTS(
      "integration-test-results",
      "集成测试通过率明细",
      "逐条展示参与平均通过率计算的集成测试记录；执行用例为 0 的记录通过率按 0 参与平均。",
      List.of(
          column("projectName", "项目", "text", 150),
          column("issueIid", "议题编号", "number", 110),
          column("title", "标题", "text", 280),
          column("testingPhase", "测试阶段", "text", 180),
          column("moduleName", "模块", "text", 160),
          column("functionName", "功能", "text", 160),
          column("executor", "执行人", "text", 130),
          column("executeCase", "执行用例数", "number", 120),
          column("passCase", "通过用例数", "number", 120),
          column("notPassCase", "未通过用例数", "number", 130),
          column("passRate", "单条通过率(%)", "number", 140),
          column("updatedAt", "更新时间", "datetime", 180)),
      Set.of(
          "projectName", "issueIid", "title", "issueState", "testingPhase", "moduleName",
          "functionName", "executor",
          "executeCase", "passCase", "notPassCase", "passRate", "updatedAt")),
  RELEASE_LEAKAGE_DEFECTS(
      "release-leakage-defects",
      "发布缺陷遗留率明细",
      "展示发布缺陷遗留率分子和分母的完整有效缺陷集合，已拒绝记录不进入本明细。",
      issueColumns(true),
      issueSortableKeys("leakageState")),
  DEVELOPMENT_LEAKAGE_DEFECTS(
      "development-leakage-defects",
      "开发缺陷遗留率明细",
      "同时展示公式中的集成测试未通过用例记录和系统测试有效缺陷记录。",
      List.of(
          column("recordType", "数据类型", "text", 180),
          column("projectName", "项目", "text", 150),
          column("issueIid", "议题编号", "number", 110),
          column("title", "标题", "text", 280),
          column("testingPhase", "测试阶段", "text", 180),
          column("moduleName", "模块", "text", 160),
          column("personName", "责任人/执行人", "text", 150),
          column("metricContribution", "公式计数贡献", "number", 130),
          column("updatedAt", "更新时间", "datetime", 180)),
      Set.of(
          "recordType", "projectName", "issueIid", "testingPhase", "moduleName",
          "personName", "metricContribution", "updatedAt")),
  FIX_USER_DEFECTS(
      "fix-user-defects",
      "按修复人统计缺陷明细",
      "展示按修复人及缺陷级别聚合图所使用的系统测试缺陷原始记录。",
      issueColumns(false),
      issueSortableKeys("fixUser")),
  ASSIGNEE_REMAINING_DEFECTS(
      "assignee-remaining-defects",
      "指派人剩余缺陷数量",
      "按所选父级测试阶段汇总 CrownCAD 未关闭缺陷；未关闭状态包含 open 与 opened，不排除已拒绝。",
      List.of(
          column("assigneeName", "指派人", "text", 220),
          column("remainingCount", "剩余缺陷数量", "number", 180)),
      Set.of("assigneeName", "remainingCount")),
  QUALITY_CODE_REVIEW_RECORDS(
      "quality-code-review-records",
      "质量看板代码走查明细",
      "展示质量看板当前 CC/DGM 读源和项目范围内的代码走查记录，不使用非法数据页口径。",
      List.of(
          column("source", "数据源", "text", 120),
          column("projectName", "项目", "text", 160),
          column("repositoryName", "仓库", "text", 160),
          column("mergeRequestIid", "合并请求编号", "number", 140),
          column("title", "标题", "text", 300),
          column("authorName", "被走查人/提交人", "text", 170),
          column("reviewerName", "走查人", "text", 160),
          column("targetBranch", "目标分支", "text", 140),
          column("mergeRequestState", "状态", "text", 110),
          column("addedLines", "新增行数", "number", 120),
          column("defectCount", "缺陷数", "number", 110),
          column("defectDensity", "缺陷密度(K/LOC)", "number", 160)),
      Set.of(
          "source", "projectName", "repositoryName", "mergeRequestIid", "title",
          "authorName", "reviewerName", "targetBranch", "mergeRequestState",
          "addedLines", "defectCount", "defectDensity"));

  private final String viewKey;
  private final String title;
  private final String description;
  private final List<AnalyticsDashboardDetailResponse.Column> columns;
  private final Set<String> sortableKeys;

  QualityRdAnalyticsDetailView(
      String viewKey,
      String title,
      String description,
      List<AnalyticsDashboardDetailResponse.Column> columns,
      Set<String> sortableKeys) {
    this.viewKey = viewKey;
    this.title = title;
    this.description = description;
    this.columns = List.copyOf(columns);
    this.sortableKeys = Set.copyOf(sortableKeys);
  }

  String viewKey() {
    return viewKey;
  }

  String title() {
    return title;
  }

  String description() {
    return description;
  }

  List<AnalyticsDashboardDetailResponse.Column> columns() {
    return columns;
  }

  Set<String> sortableKeys() {
    return sortableKeys;
  }

  static QualityRdAnalyticsDetailView fromKey(String viewKey) {
    return Arrays.stream(values())
        .filter(view -> view.viewKey.equals(viewKey))
        .findFirst()
        .orElseThrow(() -> new BizException("研发质量看板不支持该详情: " + viewKey));
  }

  static Set<String> keys() {
    return Arrays.stream(values())
        .map(QualityRdAnalyticsDetailView::viewKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static List<AnalyticsDashboardDetailResponse.Column> issueColumns(
      boolean includeLeakageState) {
    List<AnalyticsDashboardDetailResponse.Column> columns = new java.util.ArrayList<>();
    columns.add(column("issueIid", "议题编号", "number", 110));
    columns.add(column("title", "标题", "text", 300));
    if (includeLeakageState) {
      columns.add(column("leakageState", "遗留状态", "text", 110));
    }
    columns.add(column("issueState", "议题状态", "text", 110));
    columns.add(column("testingPhase", "测试阶段", "text", 180));
    columns.add(column("moduleName", "模块", "text", 170));
    columns.add(column("assigneeName", "指派人", "text", 140));
    columns.add(column("fixUser", "修复人", "text", 140));
    columns.add(column("severityLevel", "严重程度", "text", 120));
    columns.add(column("priorityLevel", "紧急程度", "text", 120));
    columns.add(column("bugStatus", "处理状态", "text", 160));
    columns.add(column("category", "类别", "text", 150));
    columns.add(column("updatedAt", "更新时间", "datetime", 180));
    return List.copyOf(columns);
  }

  private static Set<String> issueSortableKeys(String additionalKey) {
    java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(List.of(
        "issueIid", "title", "issueState", "testingPhase", "moduleName",
        "assigneeName", "fixUser", "severityLevel", "priorityLevel", "bugStatus", "category",
        "updatedAt"));
    keys.add(additionalKey);
    return Set.copyOf(keys);
  }

  private static AnalyticsDashboardDetailResponse.Column column(
      String key, String label, String format, Integer width) {
    return new AnalyticsDashboardDetailResponse.Column(key, label, format, width);
  }
}
