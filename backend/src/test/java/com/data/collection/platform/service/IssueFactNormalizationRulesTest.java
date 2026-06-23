package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IssueFactNormalizationRulesTest {

  @Test
  void shouldNormalizeSeverityAndPriorityIndependently() {
    assertThat(IssueFactNormalizationRules.normalizeSeverityLevel(List.of("一级缺陷"))).isEqualTo("LEVEL1");
    assertThat(IssueFactNormalizationRules.normalizeSeverityLevel(List.of("二级严重"))).isEqualTo("LEVEL2");
    assertThat(IssueFactNormalizationRules.normalizeSeverityLevel(List.of("三级缺陷"))).isEqualTo("LEVEL3");
    assertThat(IssueFactNormalizationRules.normalizeSeverityLevel(List.of("需求如此"))).isEqualTo("SUGGESTION");
    assertThat(IssueFactNormalizationRules.normalizeSeverityAlias(List.of("一级严重"))).isEqualTo("一级严重");

    assertThat(IssueFactNormalizationRules.normalizePriorityLevel(List.of("P1"))).isEqualTo("P1");
    assertThat(IssueFactNormalizationRules.normalizePriorityLevel(List.of("P2"))).isEqualTo("P2");
    assertThat(IssueFactNormalizationRules.normalizePriorityLevel(List.of("P3"))).isEqualTo("P3");
    assertThat(IssueFactNormalizationRules.normalizePriorityLevel(List.of("一级缺陷"))).isNull();
  }

  @Test
  void shouldNormalizeExclusionAndFixedRules() {
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("功能屏蔽"), false)).isEqualTo("功能屏蔽");
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("申请否决"), true)).isEqualTo("申请否决+Closed");
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("需求如此"), true)).isEqualTo("需求如此+Closed");
    assertThat(IssueFactNormalizationRules.isFixed(List.of("待合并"), false)).isTrue();
    assertThat(IssueFactNormalizationRules.isFixed(List.of("未复现"), true)).isTrue();
    assertThat(IssueFactNormalizationRules.isFixed(List.of("已修复/完成"), false)).isTrue();
  }

  @Test
  void shouldNormalizeReasonAndDelayCategories() {
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(List.of("业务逻辑错误"), "")).isEqualTo("编码逻辑错误");
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(List.of(), "本次属于编译打包问题")).isEqualTo("环境部署问题");
    assertThat(IssueFactNormalizationRules.normalizeDelayReason(List.of("申请延期"), "当前属于算法问题")).isEqualTo("算法问题");
    assertThat(IssueFactNormalizationRules.inferDelayCause(List.of("申请延期"), "当前属于算法问题")).isEqualTo("算法问题");
  }

  @Test
  void shouldExcludeBarePhaseFunctionAndNavigationLabelsFromModules() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of(
        "草图模块",
        "质量看板模块",
        "R1集成测试",
        "新功能")))
        .isEmpty();
  }

  @Test
  void shouldNormalizeIssueModuleAndToolboxLabelsByLegacySeparators() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of(
        "模块：草图",
        "工具箱：草图",
        "模块:工程图",
        "工具箱:装配",
        "模块-曲面",
        "工具箱-钣金",
        "前端",
        "9007",
        "分支：发布",
        "CC2023R3客户")))
        .containsExactly("草图", "工程图", "装配", "曲面", "钣金");
  }

  @Test
  void normalizeModuleNamesContractShouldStayStable() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of("模块：草图")))
        .containsExactly("草图");
  }

  @Test
  void shouldNotRecognizeBareModuleLikeLabels() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of(
        "草图",
        "工程图",
        "前端",
        "9007",
        "分支：发布",
        "CC2023R3客户",
        "系统测试",
        "一级缺陷")))
        .isEmpty();
  }

  @Test
  void shouldDropInvalidIssueModuleLabels() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of(
        "module：草图",
        "module:草图",
        "模块：：草图",
        "工具箱::工程图",
        "图纸解析模块",
        "渲染同步模块",
        "联动搜索",
        "镜像同步模块",
        "装配体验模块")))
        .isEmpty();
  }

  @Test
  void shouldKeepDifferentModuleAndToolboxValuesAsSeparateModules() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of("模块：草图", "工具箱：曲线")))
        .containsExactly("草图", "曲线");
  }

  @Test
  void shouldParseLegacyLabelMapByOldPlatformPrefixes() {
    Map<String, List<String>> labels = IssueFactNormalizationRules.parseLegacyLabelMap(List.of(
        "模块：草图",
        "工具箱：曲线",
        "软件：CrownCAD",
        "项目：CC2026R1",
        "状态：待合并",
        "测试阶段：R1第一轮系统测试",
        "严重程度：一级缺陷",
        "类别：业务逻辑错误",
        "P1",
        "技术卡点",
        "无效标签"));

    assertThat(labels).containsExactly(
        Map.entry("模块", List.of("草图", "曲线")),
        Map.entry("软件", List.of("CrownCAD")),
        Map.entry("项目", List.of("CC2026R1")),
        Map.entry("状态", List.of("待合并")),
        Map.entry("测试阶段", List.of("R1第一轮系统测试")),
        Map.entry("严重程度", List.of("一级缺陷")),
        Map.entry("类别", List.of("业务逻辑错误")),
        Map.entry("紧急程度", List.of("P1")),
        Map.entry("延期原因", List.of("技术卡点")));
  }

  @Test
  void shouldParseLegacyPhaseKeywordsAndDropUnknownLabels() {
    Map<String, List<String>> labels = IssueFactNormalizationRules.parseLegacyLabelMap(List.of(
        "CC2026R1系统测试",
        "CC2026R1回归测试",
        "CC2026R1集成测试",
        "未知标签"));

    assertThat(labels).containsExactly(
        Map.entry("测试阶段", List.of("CC2026R1系统测试", "CC2026R1回归测试", "CC2026R1集成测试")));
  }

  @Test
  void shouldNormalizeMergeRequestModulesByLegacyFetcherRulesOnly() {
    assertThat(IssueFactNormalizationRules.normalizeMergeRequestModuleNames(List.of(
        "模块：草图",
        "模块-工程图",
        "工具箱：Curve Edit",
        "工具箱-曲线",
        "模块:代码走查",
        "工具箱:装配",
        "图纸解析模块",
        "渲染同步模块")))
        .containsExactly("草图", "工程图", "Curve Edit", "曲线", "代码走查", "装配");
  }

  @Test
  void shouldRecognizeSpecialLevelOneAndIllegalCases() {
    List<String> level1 = List.of("一级缺陷", "模块A");
    assertThat(IssueFactNormalizationRules.isRegression(level1, "模型回退导致显示错误")).isTrue();
    assertThat(IssueFactNormalizationRules.isCrash(level1, "启动后出现挂机问题")).isTrue();
    assertThat(IssueFactNormalizationRules.isLevel1Other(level1, "一级缺陷但不属于回退")).isFalse();
    assertThat(IssueFactNormalizationRules.isLevel1Other(level1, "一级缺陷但属于渲染错误")).isTrue();

    assertThat(IssueFactNormalizationRules.illegalReason(List.of("模块A"), false, List.of("模块A"), "", false)).isEqualTo("未设定严重程度");
    assertThat(IssueFactNormalizationRules.illegalReason(List.of("一级缺陷"), false, List.of(), "", false)).isEqualTo("未设定模块");
    assertThat(IssueFactNormalizationRules.illegalReason(List.of("一级缺陷", "模块A"), false, List.of("模块A"), "", false)).isEqualTo("流程越位");
    assertThat(IssueFactNormalizationRules.illegalReason(List.of("一级缺陷", "模块A", "待合并"), false, List.of("模块A"), "", false)).isNull();

    String validTemplate = "# 问题调研情况说明\n业务逻辑错误";
    assertThat(IssueFactNormalizationRules.hasTemplateReply(validTemplate)).isTrue();
    assertThat(IssueFactNormalizationRules.latestReasonCategoryCount(validTemplate)).isEqualTo(1);
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("一级缺陷", "模块A", "已修复/完成"),
        true,
        List.of("模块A"),
        "",
        true)).isEqualTo("未按照模板回复");
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("一级缺陷", "模块A", "已修复/完成"),
        true,
        List.of("模块A"),
        "# 问题调研情况说明\n业务逻辑错误\n新增需求问题",
        true)).isEqualTo("缺陷原因不唯一");
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("一级缺陷", "模块A", "已修复/完成"),
        true,
        List.of("模块A"),
        validTemplate,
        true)).isNull();
  }

  @Test
  void shouldHandleResponseResolveDeadlineAndLegacyRules() {
    String notes = "# 问题调研情况说明\n预计解决时间 7 天";
    assertThat(IssueFactNormalizationRules.hasResponse(notes)).isTrue();
    assertThat(IssueFactNormalizationRules.isResponseDelayed(List.of("响应已延期"), "")).isTrue();
    assertThat(IssueFactNormalizationRules.resolveSlaDays(notes)).isEqualTo(7);
    assertThat(IssueFactNormalizationRules.resolveSlaDays("预计解决时间 28 天")).isEqualTo(18);

    LocalDateTime createdAt = LocalDateTime.of(2026, 4, 1, 10, 0);
    LocalDateTime deadline = IssueFactNormalizationRules.resolveDeadline(createdAt, 7);
    assertThat(deadline).isEqualTo(LocalDateTime.of(2026, 4, 8, 10, 0));
    assertThat(IssueFactNormalizationRules.isResolveDelayed(
        List.of("一级缺陷"),
        false,
        deadline,
        LocalDateTime.of(2026, 4, 9, 10, 0))).isTrue();
    assertThat(IssueFactNormalizationRules.isResolveDelayed(
        List.of("一级缺陷", "已修复"),
        false,
        deadline,
        LocalDateTime.of(2026, 4, 9, 10, 0))).isFalse();
    assertThat(IssueFactNormalizationRules.isLegacy(
        false,
        LocalDateTime.of(2026, 3, 1, 9, 0),
        LocalDateTime.of(2026, 4, 1, 9, 0))).isTrue();
  }
}
