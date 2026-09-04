package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupCompatiblePageResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LabelDimensionCatalogService {
  private static final Map<String, LabelDimensionDefinition> DIMENSIONS = createDimensions();
  private static final Map<String, List<LabelGroupCompatiblePageResponse>> COMPATIBLE_PAGES =
      createCompatiblePages();

  public List<LabelDimensionDefinition> listDimensions() {
    return List.copyOf(DIMENSIONS.values());
  }

  public LabelDimensionDefinition getDimension(String dimensionKey) {
    LabelDimensionDefinition definition = DIMENSIONS.get(dimensionKey);
    if (definition == null) {
      throw new BizException("标签维度不存在：" + dimensionKey);
    }
    return definition;
  }

  public List<LabelGroupCompatiblePageResponse> listCompatiblePages(String dimensionKey) {
    getDimension(dimensionKey);
    return COMPATIBLE_PAGES.getOrDefault(dimensionKey, List.of());
  }

  public boolean pageSupportsDimension(String dimensionKey, String pageKey) {
    if (pageKey == null || pageKey.isBlank()) {
      return true;
    }
    return listCompatiblePages(dimensionKey).stream().anyMatch(page -> page.pageKey().equals(pageKey));
  }

  private static Map<String, LabelDimensionDefinition> createDimensions() {
    Map<String, LabelDimensionDefinition> dimensions = new LinkedHashMap<>();
    put(dimensions, "module", "模块", "按老平台规则识别并归一化后的模块", LabelValueKind.STRING_LITERAL);
    put(dimensions, "project", "项目", "镜像 labels 表项目标签解析", LabelValueKind.STRING_LITERAL);
    put(dimensions, "person", "人员", "镜像库 users 表全量人名", LabelValueKind.STRING_LITERAL);
    put(dimensions, "target_branch", "目标分支", "合并请求目标分支", LabelValueKind.BRANCH_NAME, false);
    put(dimensions, "milestone", "里程碑", "镜像库里程碑标题全量", LabelValueKind.STRING_LITERAL);
    put(dimensions, "round", "轮次", "系统测试阶段定义中的轮次", LabelValueKind.STRING_LITERAL, false);
    put(dimensions, "test_stage", "测试阶段", "老平台规则识别出的测试阶段", LabelValueKind.STRING_LITERAL);
    put(dimensions, "severity_level", "严重程度", "一级缺陷、二级缺陷、三级缺陷", LabelValueKind.ENUM_KEY);
    put(dimensions, "priority_level", "紧急程度", "P1、P2、P3", LabelValueKind.ENUM_KEY);
    put(dimensions, "defect_reason", "缺陷原因", "规则层缺陷原因枚举", LabelValueKind.ENUM_KEY, false);
    put(dimensions, "delay_reason", "延期原因", "规则层延期原因枚举", LabelValueKind.ENUM_KEY, false);
    put(dimensions, "closure_status", "客户问题闭环状态", "客户问题闭环状态规范值", LabelValueKind.ENUM_KEY);
    return java.util.Collections.unmodifiableMap(dimensions);
  }

  private static void put(
      Map<String, LabelDimensionDefinition> dimensions,
      String key,
      String name,
      String description,
      LabelValueKind valueKind) {
    put(dimensions, key, name, description, valueKind, true);
  }

  private static void put(
      Map<String, LabelDimensionDefinition> dimensions,
      String key,
      String name,
      String description,
      LabelValueKind valueKind,
      boolean staticSupported) {
    dimensions.put(
        key,
        new LabelDimensionDefinition(key, name, description, valueKind, staticSupported, false));
  }

  private static Map<String, List<LabelGroupCompatiblePageResponse>> createCompatiblePages() {
    Map<String, List<LabelGroupCompatiblePageResponse>> pages = new LinkedHashMap<>();
    add(pages, "project", "review-data-home", "评审数据管理", "projectName", "项目", true);
    add(pages, "module", "review-data-home", "评审数据管理", "moduleName", "模块", true);
    add(pages, "person", "review-data-home", "评审数据管理", "reviewOwner", "负责人", true);
    add(pages, "person", "question-metrics-issue-search", "系统测试议题查询", "assigneeName", "处理人", true);
    add(pages, "person", "customer-issues-cc-product-issues", "客户问题列表", "assigneeName", "处理人", true);
    add(pages, "project", "question-metrics-issue-search", "系统测试议题查询", "projectName", "项目", true);
    add(pages, "module", "question-metrics-issue-search", "系统测试议题查询", "moduleName", "模块", true);
    add(pages, "test_stage", "question-metrics-issue-search", "系统测试议题查询", "testingPhase", "测试阶段", true);
    add(pages, "severity_level", "question-metrics-issue-search", "系统测试议题查询", "severityLevel", "严重程度", true);
    add(pages, "priority_level", "question-metrics-issue-search", "系统测试议题查询", "priorityLevel", "紧急程度", true);
    add(pages, "milestone", "question-metrics-issue-search", "系统测试议题查询", "milestoneTitle", "里程碑", true);
    add(pages, "module", "customer-issues-cc-product-issues", "客户问题列表", "moduleName", "模块", true);
    add(pages, "priority_level", "customer-issues-cc-product-issues", "客户问题列表", "priorityLevel", "紧急程度", true);
    add(pages, "milestone", "customer-issues-cc-product-issues", "客户问题列表", "milestoneTitle", "里程碑", true);
    add(pages, "closure_status", "customer-issues-cc-product-issues", "客户问题列表", "bugStatus", "闭环状态", true);
    return java.util.Collections.unmodifiableMap(pages);
  }

  private static void add(
      Map<String, List<LabelGroupCompatiblePageResponse>> pages,
      String dimensionKey,
      String pageKey,
      String pageName,
      String fieldKey,
      String fieldName,
      boolean mvpEnabled) {
    List<LabelGroupCompatiblePageResponse> current = pages.getOrDefault(dimensionKey, List.of());
    java.util.ArrayList<LabelGroupCompatiblePageResponse> next = new java.util.ArrayList<>(current);
    next.add(new LabelGroupCompatiblePageResponse(pageKey, pageName, fieldKey, fieldName, mvpEnabled));
    pages.put(dimensionKey, List.copyOf(next));
  }
}
