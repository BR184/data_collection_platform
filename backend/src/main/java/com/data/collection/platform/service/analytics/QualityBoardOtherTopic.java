package com.data.collection.platform.service.analytics;

import com.data.collection.platform.common.exception.BizException;
import java.util.Arrays;

enum QualityBoardOtherTopic {
  FUNCTION_DEFECT_COUNT(
      "function-defect-count",
      "other-function-defect-count",
      "function-defect-count-excel",
      "functionCountProjectName",
      "功能缺陷数量",
      "按功能统计系统测试缺陷数量",
      "功能名称",
      "缺陷数量",
      "缺陷数量",
      null),
  FUNCTION_DEFECT_DENSITY(
      "function-defect-density",
      "other-function-defect-density",
      "function-defect-density-excel",
      "functionDensityProjectName",
      "功能缺陷密度",
      "系统测试缺陷数与 CC 代码新增行的比值",
      "功能名称",
      "系统测试缺陷数",
      "新增代码行数",
      "%"),
  QUALITY_RANKING(
      "quality-ranking",
      "other-quality-ranking",
      "quality-ranking-excel",
      "qualityRankingProjectName",
      "质量达人榜",
      "按成员各测试阶段平均千行缺陷密度排名",
      "修复人",
      "系统测试缺陷数",
      "新增代码行数",
      "KLOC"),
  MEMBER_UNRESOLVED_RATE(
      "member-unresolved-rate",
      "other-member-unresolved-rate",
      "member-unresolved-rate-excel",
      "memberUnresolvedProjectName",
      "成员未修复缺陷率",
      "个人未修复缺陷数占个人缺陷总数的比例",
      "修复人",
      "未修复缺陷数",
      "个人缺陷总数",
      "%"),
  RELEASE_LEAKAGE_RATE(
      "release-leakage-rate",
      "other-release-leakage-rate",
      "release-leakage-rate-excel",
      null,
      "发布缺陷遗留率",
      "各发布版本未关闭系统测试缺陷占比",
      "发布版本",
      "未关闭缺陷数",
      "系统测试缺陷总数",
      "%"),
  DEVELOPMENT_LEAKAGE_RATE(
      "development-leakage-rate",
      "other-development-leakage-rate",
      "development-leakage-rate-excel",
      null,
      "开发缺陷遗留率",
      "各发布版本未关闭系统测试缺陷占比",
      "发布版本",
      "未关闭缺陷数",
      "系统测试缺陷总数",
      "%");

  private final String chartKey;
  private final String viewKey;
  private final String exportKey;
  private final String scopeParameterKey;
  private final String title;
  private final String subtitle;
  private final String nameLabel;
  private final String numeratorLabel;
  private final String denominatorLabel;
  private final String unit;

  QualityBoardOtherTopic(
      String chartKey,
      String viewKey,
      String exportKey,
      String scopeParameterKey,
      String title,
      String subtitle,
      String nameLabel,
      String numeratorLabel,
      String denominatorLabel,
      String unit) {
    this.chartKey = chartKey;
    this.viewKey = viewKey;
    this.exportKey = exportKey;
    this.scopeParameterKey = scopeParameterKey;
    this.title = title;
    this.subtitle = subtitle;
    this.nameLabel = nameLabel;
    this.numeratorLabel = numeratorLabel;
    this.denominatorLabel = denominatorLabel;
    this.unit = unit;
  }

  static QualityBoardOtherTopic fromViewKey(String viewKey) {
    return Arrays.stream(values())
        .filter(topic -> topic.viewKey.equals(viewKey))
        .findFirst()
        .orElseThrow(() -> new BizException("其他看板不支持该详情: " + viewKey));
  }

  static QualityBoardOtherTopic fromExportKey(String exportKey) {
    return Arrays.stream(values())
        .filter(topic -> topic.exportKey.equals(exportKey))
        .findFirst()
        .orElseThrow(() -> new BizException("其他看板不支持该导出: " + exportKey));
  }

  String chartKey() {
    return chartKey;
  }

  String viewKey() {
    return viewKey;
  }

  String exportKey() {
    return exportKey;
  }

  String scopeParameterKey() {
    return scopeParameterKey;
  }

  String title() {
    return title;
  }

  String subtitle() {
    return subtitle;
  }

  String nameLabel() {
    return nameLabel;
  }

  String numeratorLabel() {
    return numeratorLabel;
  }

  String denominatorLabel() {
    return denominatorLabel;
  }

  String unit() {
    return unit;
  }

  String filename(String scope) {
    return switch (this) {
      case FUNCTION_DEFECT_COUNT -> scope + "-功能缺陷数量统计.xlsx";
      case FUNCTION_DEFECT_DENSITY -> scope + "-功能缺陷密度统计.xlsx";
      case QUALITY_RANKING -> scope + "质量达人榜统计.xlsx";
      case MEMBER_UNRESOLVED_RATE -> scope + "-成员未修复缺陷率统计.xlsx";
      case RELEASE_LEAKAGE_RATE -> "发布缺陷遗留率统计.xlsx";
      case DEVELOPMENT_LEAKAGE_RATE -> "开发缺陷遗留率excel导出.xlsx";
    };
  }

  String sheetName(String scope) {
    return switch (this) {
      case FUNCTION_DEFECT_COUNT -> scope;
      case FUNCTION_DEFECT_DENSITY -> "功能缺陷统计";
      case QUALITY_RANKING -> "代码质量统计";
      case MEMBER_UNRESOLVED_RATE -> "成员未修复缺陷率";
      case RELEASE_LEAKAGE_RATE -> "发布缺陷遗留率统计";
      case DEVELOPMENT_LEAKAGE_RATE -> "开发缺陷遗留率excel导出";
    };
  }
}
