package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.util.Arrays;

public enum CodeReviewMultiBoardTopic {
  MODULE_DEFECT_DENSITY(
      "module-defect-density",
      "模块千行缺陷率",
      "模块",
      "缺陷密度(K/LOC)",
      "行级正数缺陷密度之和 ÷ 当前模块全部合法走查记录数",
      "按数据源、项目和 MERGED 合并请求统计；排除未标注模块及非法走查记录。",
      "用于观察模块维度的平均代码走查缺陷密度。",
      "模块千行缺陷率统计.xlsx",
      "模块千行缺陷率",
      Dimension.MODULE,
      Aggregation.AVERAGE_ROW_DENSITY,
      true,
      false),
  REVIEWER_DEFECT_DENSITY(
      "reviewer-defect-density",
      "走查人千行缺陷率",
      "走查人",
      "缺陷密度(K/LOC)",
      "行级正数缺陷密度之和 ÷ 当前走查人全部匹配记录数",
      "按数据源、项目和 MERGED 合并请求统计，走查人取 reviewer_names。",
      "用于观察实际走查人员发现代码缺陷的密度。",
      "走查人千行缺陷率统计.xlsx",
      "统计结果",
      Dimension.REVIEWER,
      Aggregation.AVERAGE_ROW_DENSITY,
      true,
      false),
  REVIEWER_FIXED_DEFECTS(
      "reviewer-fixed-defects",
      "走查人修复缺陷数量",
      "走查人",
      "修复缺陷数",
      "按走查人汇总缺陷数：Σ max(defect_count, 0)",
      "按数据源、项目和 MERGED 合并请求统计，走查人取 reviewer_names。",
      "用于观察走查人发现并计入的代码走查缺陷总量。",
      "走查人修复缺陷数量统计.xlsx",
      "走查人修复缺陷数量",
      Dimension.REVIEWER,
      Aggregation.DEFECT_SUM,
      true,
      false),
  ASSIGNEE_FIXED_DEFECTS(
      "assignee-fixed-defects",
      "指派人修复缺陷数量",
      "指派人",
      "修复缺陷数",
      "按被指派人汇总缺陷数：Σ max(defect_count, 0)",
      "按数据源、项目和 MERGED 合并请求统计，指派人取 assignee_names。",
      "用于观察各指派人的代码走查缺陷承担分布。",
      "指派人修复缺陷数量统计.xlsx",
      "指派人修复缺陷数量统计",
      Dimension.ASSIGNEE,
      Aggregation.DEFECT_SUM,
      true,
      false),
  AUTHOR_DEFECT_DENSITY(
      "author-defect-density",
      "被走查人千行缺陷率",
      "被走查人",
      "缺陷密度(K/LOC)",
      "行级正数缺陷密度之和 ÷ 当前被走查人全部合法走查记录数",
      "按数据源、项目和 MERGED 合并请求统计，被走查人取 author_name。",
      "用于观察代码提交人在走查中暴露的缺陷密度。",
      "被走查人千行缺陷率统计.xlsx",
      "统计结果",
      Dimension.AUTHOR,
      Aggregation.AVERAGE_ROW_DENSITY,
      true,
      false),
  MERGE_REQUEST_COUNT(
      "merge-request-count",
      "人员合并代码次数",
      "合并人",
      "合并请求数",
      "按合并人统计去重后的 MERGED 合并请求数量",
      "按数据源、项目、目标分支 dev 和 MERGED 状态统计，合并人取 merge_user_name。",
      "同一合并请求的多条走查记录只计一次。",
      "合并人合并代码次数统计.xlsx",
      "人员合并代码次数",
      Dimension.MERGE_USER,
      Aggregation.DISTINCT_MERGE_REQUEST_COUNT,
      false,
      true),
  CODE_SUBMISSION_FREQUENCY(
      "code-submission-frequency",
      "代码提交频次",
      "提交人",
      "代码提交次数",
      "按提交人统计全量匹配代码走查记录数",
      "按数据源、项目和 MERGED 合并请求统计，不附加老平台默认时间窗口。",
      "统计 author_name 的代码提交频次，不使用缺陷密度口径。",
      "代码提交频次统计.xlsx",
      "代码提交频次统计",
      Dimension.AUTHOR,
      Aggregation.ROW_COUNT,
      false,
      false),
  CODE_SUBMISSION_DEFECT_DENSITY(
      "code-submission-defect-density",
      "代码提交缺陷密度统计",
      "模块",
      "密度(K/LOC)",
      "Σ max(defect_count, 0) × 1000 ÷ Σ max(added_lines, 0)",
      "按数据源、项目和 MERGED 合并请求统计；只排除空模块和无需标注模块。",
      "使用缺陷数与新增代码行数重新计算，不复用行级平均密度。",
      "代码提交缺陷密度统计.xlsx",
      "代码提交缺陷密度",
      Dimension.MODULE,
      Aggregation.DEFECT_PER_KLOC,
      false,
      false);

  private final String key;
  private final String title;
  private final String dimensionLabel;
  private final String valueLabel;
  private final String formula;
  private final String scope;
  private final String description;
  private final String filename;
  private final String sheetName;
  private final Dimension dimension;
  private final Aggregation aggregation;
  private final boolean legalReviewOnly;
  private final boolean devBranchOnly;

  CodeReviewMultiBoardTopic(
      String key,
      String title,
      String dimensionLabel,
      String valueLabel,
      String formula,
      String scope,
      String description,
      String filename,
      String sheetName,
      Dimension dimension,
      Aggregation aggregation,
      boolean legalReviewOnly,
      boolean devBranchOnly) {
    this.key = key;
    this.title = title;
    this.dimensionLabel = dimensionLabel;
    this.valueLabel = valueLabel;
    this.formula = formula;
    this.scope = scope;
    this.description = description;
    this.filename = filename;
    this.sheetName = sheetName;
    this.dimension = dimension;
    this.aggregation = aggregation;
    this.legalReviewOnly = legalReviewOnly;
    this.devBranchOnly = devBranchOnly;
  }

  public static CodeReviewMultiBoardTopic fromKey(String key) {
    return Arrays.stream(values())
        .filter(topic -> topic.key.equals(key))
        .findFirst()
        .orElseThrow(() -> new BizException("未知的代码走查看板专题: " + key));
  }

  public String key() {
    return key;
  }

  public String title() {
    return title;
  }

  public String dimensionLabel() {
    return dimensionLabel;
  }

  public String valueLabel() {
    return valueLabel;
  }

  public String formula() {
    return formula;
  }

  public String scope() {
    return scope;
  }

  public String description() {
    return description;
  }

  public String filename(String projectName) {
    String scope = projectName == null ? "" : projectName.trim();
    return scope + filename;
  }

  public String sheetName() {
    return sheetName;
  }

  Dimension dimension() {
    return dimension;
  }

  Aggregation aggregation() {
    return aggregation;
  }

  boolean legalReviewOnly() {
    return legalReviewOnly;
  }

  boolean devBranchOnly() {
    return devBranchOnly;
  }

  enum Dimension {
    MODULE("module_name"),
    REVIEWER("reviewer_names"),
    ASSIGNEE("assignee_names"),
    AUTHOR("author_name"),
    MERGE_USER("merge_user_name");

    private final String column;

    Dimension(String column) {
      this.column = column;
    }

    String column() {
      return column;
    }
  }

  enum Aggregation {
    AVERAGE_ROW_DENSITY,
    DEFECT_SUM,
    DISTINCT_MERGE_REQUEST_COUNT,
    ROW_COUNT,
    DEFECT_PER_KLOC
  }
}
