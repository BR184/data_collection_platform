package com.data.collection.platform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SemanticTagGroupService {
  private final SemanticTagGroupRepository repository;
  private final List<SemanticTagGroupDefinition> staticIssueGroups;
  private final String staticIssueSchemaHash;

  public SemanticTagGroupService() {
    this(SemanticTagGroupRepository.empty(), staticIssueGroups());
  }

  @Autowired
  public SemanticTagGroupService(SemanticTagGroupRepository repository) {
    this(repository, staticIssueGroups());
  }

  SemanticTagGroupService(List<SemanticTagGroupDefinition> staticIssueGroups) {
    this(SemanticTagGroupRepository.empty(), staticIssueGroups);
  }

  SemanticTagGroupService(
      SemanticTagGroupRepository repository,
      List<SemanticTagGroupDefinition> staticIssueGroups) {
    this.repository = repository;
    this.staticIssueGroups = List.copyOf(staticIssueGroups);
    this.staticIssueSchemaHash = sha256(this.staticIssueGroups.toString());
  }

  public static SemanticTagGroupService withDefaults() {
    return new SemanticTagGroupService(staticIssueGroups());
  }

  public SemanticTagGroupCatalog listStaticGroups(String entityType) {
    List<SemanticTagGroupDefinition> databaseGroups =
        localizeKnownStaticGroups(repository.listEnabledGroups(entityType));
    if (!databaseGroups.isEmpty()) {
      return new SemanticTagGroupCatalog(entityType, sha256(databaseGroups.toString()), databaseGroups);
    }
    if (!"issue".equals(entityType)) {
      return new SemanticTagGroupCatalog(entityType, sha256(entityType + ":empty"), List.of());
    }
    return new SemanticTagGroupCatalog(entityType, staticIssueSchemaHash, staticIssueGroups);
  }

  private static List<SemanticTagGroupDefinition> localizeKnownStaticGroups(
      List<SemanticTagGroupDefinition> groups) {
    return groups.stream().map(SemanticTagGroupService::localizeKnownStaticGroup).toList();
  }

  private static SemanticTagGroupDefinition localizeKnownStaticGroup(
      SemanticTagGroupDefinition group) {
    String groupLabel = GROUP_LABELS.getOrDefault(group.groupKey(), group.label());
    Map<String, String> valueLabels = VALUE_LABELS.getOrDefault(group.groupKey(), Map.of());
    List<SemanticTagValueDefinition> values =
        group.values().stream()
            .map(
                value ->
                    new SemanticTagValueDefinition(
                        value.valueKey(),
                        valueLabels.getOrDefault(value.valueKey(), value.label()),
                        value.valueType(),
                        value.canonicalValue(),
                        value.enabled(),
                        value.sortOrder()))
            .toList();
    return new SemanticTagGroupDefinition(
        group.domain(),
        group.groupKey(),
        groupLabel,
        group.sourceMode(),
        group.rulePolicyKey(),
        group.selectionMode(),
        group.matchStrategyName(),
        group.enabled(),
        group.sortOrder(),
        values);
  }

  private static List<SemanticTagGroupDefinition> staticIssueGroups() {
    return List.of(
        group(
            "issue",
            "severity_level",
            "严重程度",
            "issue_severity_policy",
            List.of(
                value("LEVEL1", "一级缺陷", 10),
                value("LEVEL2", "二级缺陷", 20),
                value("LEVEL3", "三级缺陷", 30),
                value("SUGGESTION", "建议类", 40))),
        group(
            "issue",
            "urgency",
            "紧急程度",
            "customer_issue_urgency_policy",
            List.of(value("P1", "P1", 10), value("P2", "P2", 20), value("P3", "P3", 30))),
        group(
            "issue",
            "system_test_exclusion_type",
            "系统测试排除类型",
            "system_test_exclusion_policy",
            List.of(
                value("FUNCTION_BLOCKED", "功能屏蔽", 10),
                value("REJECTED", "已拒绝", 20),
                value("SUGGESTION", "建议", 30),
                value("CLOSED_REJECTION", "关闭-申请否决", 40),
                value("CLOSED_REQUIREMENT_AS_IS", "关闭-需求如此", 50))),
        group(
            "issue",
            "delay_cause",
            "延期原因",
            "system_test_delay_cause_policy",
            List.of(
                value("TECHNICAL_BLOCKER", "技术卡点", 10),
                value("SOLUTION_BLOCKER", "方案卡点", 20),
                value("RESOURCE_BLOCKER", "资源卡点", 30),
                value("DATA_ANOMALY", "数据异常", 40),
                value("ALGORITHM_ISSUE", "算法问题", 50),
                value("MECHANISM_ISSUE", "机制问题", 60),
                value("COMPUTATION_EFFICIENCY", "计算效率", 70))),
        group(
            "issue",
            "customer_issue_closure_status",
            "客户问题闭环状态",
            "customer_issue_closure_policy",
            List.of(
                value("FIXED_DONE", "已修复/完成", 10),
                value("DELAY_REQUESTED", "申请延期", 20),
                value("DATA_ANOMALY", "数据异常", 30),
                value("REQUIREMENT_AS_IS", "需求如此", 40),
                value("DESIGN_AS_IS", "设计如此", 50),
                value("NOT_REPRODUCED", "未复现", 60))),
        group(
            "issue",
            "illegal_type",
            "非法数据类型",
            "system_test_illegal_type_policy",
            List.of(
                value("MISSING_SEVERITY", "未设定严重程度", 10),
                value("MISSING_MODULE", "未设定模块", 20),
                value("MISSING_REQUIRED_REPLY", "未按模板回复", 30),
                value("NON_UNIQUE_DEFECT_REASON", "缺陷原因不唯一", 40),
                value("MISSING_DEFECT_INVESTIGATION_TEMPLATE", "未填写缺陷调研模板", 50),
                value("INVALID_PLAN_RESOLVE_TIME", "计划解决时间格式异常", 60),
                value("LEVEL1_MISSING_OWNER_SIGN", "一级缺陷缺少负责人签字", 70))),
        group(
            "issue",
            "defect_reason_standard",
            "缺陷原因标准项",
            "defect_reason_policy",
            List.of(
                value("NEW_UNDERSTANDING_DEVIATION", "新增理解偏差", 10),
                value("NEW_REQUIREMENT", "新增需求", 20),
                value("CODING_BUSINESS_LOGIC_ERROR", "编码逻辑：业务逻辑错误", 30),
                value("BUILD_PACKAGE_DEPLOYMENT_ISSUE", "构建/打包/部署问题", 40),
                value("MECHANISM_UNSUPPORTED", "机制不支持", 50))),
        singleSelectionGroup(
            "issue",
            "ratio_empty_value_policy",
            "比例空值展示策略",
            "ratio_display_policy",
            List.of(
                value("DISPLAY_SLASH", "显示为 /", 10),
                value("DISPLAY_ZERO", "显示为 0", 20))));
  }

  private static SemanticTagGroupDefinition group(
      String domain,
      String groupKey,
      String label,
      String rulePolicyKey,
      List<SemanticTagValueDefinition> values) {
    return new SemanticTagGroupDefinition(
        domain,
        groupKey,
        label,
        "STATIC",
        rulePolicyKey,
        "MULTIPLE",
        "EXACT",
        true,
        values.getFirst().sortOrder(),
        values);
  }

  private static SemanticTagGroupDefinition singleSelectionGroup(
      String domain,
      String groupKey,
      String label,
      String rulePolicyKey,
      List<SemanticTagValueDefinition> values) {
    return new SemanticTagGroupDefinition(
        domain,
        groupKey,
        label,
        "STATIC",
        rulePolicyKey,
        "SINGLE",
        "EXACT",
        true,
        values.getFirst().sortOrder(),
        values);
  }

  private static SemanticTagValueDefinition value(String valueKey, String label, int sortOrder) {
    return new SemanticTagValueDefinition(valueKey, label, "STRING", valueKey, true, sortOrder);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  private static final Map<String, String> GROUP_LABELS =
      Map.of(
          "severity_level", "严重程度",
          "urgency", "紧急程度",
          "system_test_exclusion_type", "系统测试排除类型",
          "delay_cause", "延期原因",
          "customer_issue_closure_status", "客户问题闭环状态",
          "illegal_type", "非法数据类型",
          "defect_reason_standard", "缺陷原因标准项",
          "ratio_empty_value_policy", "比例空值展示策略");

  private static final Map<String, Map<String, String>> VALUE_LABELS =
      Map.of(
          "severity_level",
          Map.of(
              "LEVEL1", "一级缺陷",
              "LEVEL2", "二级缺陷",
              "LEVEL3", "三级缺陷",
              "SUGGESTION", "建议类"),
          "urgency",
          Map.of("P1", "P1", "P2", "P2", "P3", "P3"),
          "system_test_exclusion_type",
          Map.of(
              "FUNCTION_BLOCKED", "功能屏蔽",
              "REJECTED", "已拒绝",
              "SUGGESTION", "建议",
              "CLOSED_REJECTION", "关闭-申请否决",
              "CLOSED_REQUIREMENT_AS_IS", "关闭-需求如此"),
          "delay_cause",
          Map.of(
              "TECHNICAL_BLOCKER", "技术卡点",
              "SOLUTION_BLOCKER", "方案卡点",
              "RESOURCE_BLOCKER", "资源卡点",
              "DATA_ANOMALY", "数据异常",
              "ALGORITHM_ISSUE", "算法问题",
              "MECHANISM_ISSUE", "机制问题",
              "COMPUTATION_EFFICIENCY", "计算效率"),
          "customer_issue_closure_status",
          Map.of(
              "FIXED_DONE", "已修复/完成",
              "DELAY_REQUESTED", "申请延期",
              "DATA_ANOMALY", "数据异常",
              "REQUIREMENT_AS_IS", "需求如此",
              "DESIGN_AS_IS", "设计如此",
              "NOT_REPRODUCED", "未复现"),
          "illegal_type",
          Map.of(
              "MISSING_SEVERITY", "未设定严重程度",
              "MISSING_MODULE", "未设定模块",
              "MISSING_REQUIRED_REPLY", "未按模板回复",
              "NON_UNIQUE_DEFECT_REASON", "缺陷原因不唯一",
              "MISSING_DEFECT_INVESTIGATION_TEMPLATE", "未填写缺陷调研模板",
              "INVALID_PLAN_RESOLVE_TIME", "计划解决时间格式异常",
              "LEVEL1_MISSING_OWNER_SIGN", "一级缺陷缺少负责人签字"),
          "defect_reason_standard",
          Map.of(
              "NEW_UNDERSTANDING_DEVIATION", "新增理解偏差",
              "NEW_REQUIREMENT", "新增需求",
              "CODING_BUSINESS_LOGIC_ERROR", "编码逻辑：业务逻辑错误",
              "BUILD_PACKAGE_DEPLOYMENT_ISSUE", "构建/打包/部署问题",
              "MECHANISM_UNSUPPORTED", "机制不支持"),
          "ratio_empty_value_policy",
          Map.of("DISPLAY_SLASH", "显示为 /", "DISPLAY_ZERO", "显示为 0"));
}
