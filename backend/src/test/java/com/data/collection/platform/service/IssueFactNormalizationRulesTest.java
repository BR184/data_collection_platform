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
    assertThat(IssueFactNormalizationRules.normalizeSeverityAlias(List.of("严重程度：一级严重")))
        .isEqualTo("一级严重");

    assertThat(IssueFactNormalizationRules.normalizePriorityLevel(List.of("P1"))).isEqualTo("P1");
    assertThat(IssueFactNormalizationRules.normalizePriorityLevel(List.of("P2"))).isEqualTo("P2");
    assertThat(IssueFactNormalizationRules.normalizePriorityLevel(List.of("P3"))).isEqualTo("P3");
    assertThat(IssueFactNormalizationRules.normalizePriorityLevel(List.of("一级缺陷"))).isNull();
  }

  @Test
  void shouldNormalizeCategoryFromLegacyIssueCategoryLabels() {
    assertThat(IssueFactNormalizationRules.normalizeCategory(List.of("类别：建议"))).isEqualTo("建议");
    assertThat(IssueFactNormalizationRules.normalizeCategory(List.of("类别：需求", "类别：建议"))).isEqualTo("需求 & 建议");
    assertThat(IssueFactNormalizationRules.normalizeCategory(List.of("一级缺陷"))).isEqualTo("未设定类别");
  }

  @Test
  void test_prefixed_test_statuses_preserve_old_platform_members_result() {
    assertThat(IssueFactNormalizationRules.normalizeBugStatus(
        List.of(
            "状态：已修复/完成",
            "状态：已测试通过",
            "申请延期")))
        .isEqualTo("已修复/完成 & 已测试通过");
  }

  @Test
  void test_missing_test_status_returns_missing_placeholder() {
    assertThat(IssueFactNormalizationRules.normalizeBugStatus(
        List.of("P1", "响应已延期", "模块：草图")))
        .isEqualTo("未设定议题状态");
    assertThat(IssueFactNormalizationRules.normalizeBugStatus(List.of()))
        .isEqualTo("未设定议题状态");
  }

  @Test
  void test_non_legacy_status_labels_do_not_become_test_status_result() {
    assertThat(IssueFactNormalizationRules.normalizeBugStatus(
        List.of("已修复/完成", "申请延期", "状态:进行中", "状态-未复现")))
        .isEqualTo("未设定议题状态");
  }

  @Test
  void shouldNormalizeExclusionAndFixedRules() {
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("功能屏蔽"), false, 9L)).isEqualTo("功能屏蔽");
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("申请否决"), true, 9L)).isEqualTo("申请否决+Closed");
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("需求如此"), true, 9L)).isEqualTo("需求如此+Closed");
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("设计如此"), true, 9L)).isNull();
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("数据异常"), true, 9L)).isNull();
    assertThat(IssueFactNormalizationRules.isFixed(List.of("待合并"), false)).isTrue();
    assertThat(IssueFactNormalizationRules.isFixed(List.of("未复现"), true)).isTrue();
    assertThat(IssueFactNormalizationRules.isFixed(List.of("已修复/完成"), false)).isTrue();
  }

  @Test
  void shouldKeepCustomerIssueProjectFromBroadDefaultExclusion() {
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("功能屏蔽"), false, 325L)).isNull();
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("已拒绝"), false, 325L)).isNull();
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("建议"), false, 325L)).isNull();
    assertThat(IssueFactNormalizationRules.exclusionReason(List.of("申请否决"), true, 325L))
        .isEqualTo("申请否决+Closed");
  }

  @Test
  void test_invalid_fix_template_does_not_infer_reason_from_free_text() {
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(""))
        .isNull();
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory("本次属于编译打包问题"))
        .isNull();
  }

  @Test
  void test_fix_template_without_required_header_does_not_generate_reason_catalog() {
    String notes =
        """
        * [ ] 已解决
        * [ ] 部分解决
        * [x] 申请延期
        * [ ] 无法复现

        ### 2、缺陷原因分析
        * 需求阶段
          * [ ] 需求理解有误
          * [ ] 需求遗漏
          * [x] 新增需求
          * [ ] 需求变更未同步
        * 设计问题
          * [ ] 功能设计遗漏
          * [ ] 设计方案不合理
        * 编码问题
          * [ ] 编码规范错误
          * [ ] 编码逻辑：业务逻辑错误
        ### 3、请描述具体原因：
        延期处理。
        """;

    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(notes)).isNull();
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("严重程度：一级缺陷", "模块：模块A", "状态：已修复/完成"),
        true,
        List.of("模块A"),
        notes,
        true)).isEqualTo("未按照模板回复");
  }

  @Test
  void test_modified_fix_template_header_does_not_generate_reason() {
    String emphasizedHeader =
        "### **1、修复状态**\n[x] 新增需求\n### 3、请描述具体原因：\n";
    String prefixedHeader =
        "问题16\n### 1、修复状态\n[x] 新增需求\n### 3、请描述具体原因：\n";

    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(emphasizedHeader)).isNull();
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(prefixedHeader)).isNull();
  }

  @Test
  void test_valid_fixed_template_uses_checked_reason_instead_of_other_notes() {
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(
        """
        日常讨论：编译/打包/部署问题
        ---
        ### 1、修复状态
        [x] 需求理解有误
        ### 3、请描述具体原因：
        """))
        .isEqualTo("需求理解有误 具体原因, 请描述：");
  }

  @Test
  void test_only_valid_fixed_template_generates_reason() {
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(""))
        .isNull();
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(
        "# 问题调研情况说明\n## 问题原因：新增需求，目前机制不支持"))
        .isNull();
    assertThat(IssueFactNormalizationRules.normalizeReasonCategory(
        "### 1、修复状态\n[x] 需求理解有误\n### 3、请描述具体原因：\n"))
        .isEqualTo("需求理解有误 具体原因, 请描述：");
  }

  @Test
  void shouldNormalizeDelayCategories() {
    assertThat(IssueFactNormalizationRules.normalizeDelayReason(
        List.of("方案卡点", "申请延期", "技术卡点", "方案卡点"), "当前属于算法问题"))
        .isEqualTo("方案卡点&技术卡点");
    assertThat(IssueFactNormalizationRules.inferDelayCause(
        List.of("方案卡点", "申请延期", "技术卡点", "方案卡点"), "当前属于算法问题"))
        .isEqualTo("方案卡点&技术卡点");
    assertThat(IssueFactNormalizationRules.inferCustomerIssueDelayCause(List.of(), ""))
        .isEqualTo("未设定类别");
  }

  @Test
  void shouldKeepApplyDelayAsStatusWithoutUsingItAsDelayCause() {
    List<String> labels = List.of("申请延期");

    assertThat(IssueFactNormalizationRules.hasDelayFlag(labels, "")).isTrue();
    assertThat(IssueFactNormalizationRules.normalizeDelayReason(labels, "")).isNull();
    assertThat(IssueFactNormalizationRules.inferDelayCause(labels, "")).isNull();
    assertThat(IssueFactNormalizationRules.inferCustomerIssueDelayCause(labels, ""))
        .isEqualTo("未设定类别");
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
  void shouldNormalizeIssueModuleAndToolboxLabelsByOldPlatformChineseColonOnly() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of(
        "模块：草图",
        "工具箱：曲线",
        "模块:工程图",
        "工具箱:装配",
        "模块-曲面",
        "工具箱-钣金",
        "前端",
        "9007",
        "分支：发布",
        "CC2023R3客户")))
        .containsExactly("草图", "曲线");
  }

  @Test
  void normalizeModuleNamesContractShouldStayStable() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of("模块：草图")))
        .containsExactly("草图");
  }

  @Test
  void shouldSplitLegacyCombinedIssueModules() {
    assertThat(IssueFactNormalizationRules.normalizeModuleNames(List.of("模块：BOM & DWG")))
        .containsExactly("BOM", "DWG");
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
        "模块|装配",
        "工具箱|评估",
        "模块:代码走查",
        "工具箱:装配",
        "图纸解析模块",
        "渲染同步模块")))
        .containsExactly("草图", "工程图", "Curve Edit", "曲线", "装配", "评估");
  }

  @Test
  void shouldNormalizeMergeRequestProjectNameFromColonLabelOnly() {
    assertThat(IssueFactNormalizationRules.normalizeMergeRequestProjectName(List.of(
        "项目：CC2026R3",
        "项目：CC2026R4")))
        .isEqualTo("CC2026R3");
    assertThat(IssueFactNormalizationRules.normalizeMergeRequestProjectName(List.of(
        "项目: CC2026R4")))
        .isEqualTo("CC2026R4");
    assertThat(IssueFactNormalizationRules.normalizeMergeRequestProjectName(List.of(
        "项目-CC2026R3",
        "项目：：CC2026R4",
        "项目:",
        "模块：草图")))
        .isEqualTo("未标注项目名");
  }

  @Test
  void shouldRecognizeSpecialLevelOneAndIllegalCases() {
    List<String> level1 = List.of("一级缺陷", "模块A");
    assertThat(IssueFactNormalizationRules.isRegression(level1, "模型回退导致显示错误")).isTrue();
    assertThat(IssueFactNormalizationRules.isCrash(level1, "启动后出现挂机问题")).isTrue();
    assertThat(IssueFactNormalizationRules.isLevel1Other(level1, "一级缺陷但不属于回退")).isFalse();
    assertThat(IssueFactNormalizationRules.isLevel1Other(level1, "退出草图后等待时间较长")).isFalse();
    assertThat(IssueFactNormalizationRules.isLevel1Other(level1, "一级缺陷但属于渲染错误")).isTrue();

    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("模块：模块A"), false, List.of("模块A"), "", false))
        .isEqualTo("未设定严重程度");
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("严重程度：一级缺陷"), false, List.of(), "", false))
        .isEqualTo("未设定模块");
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("严重程度：一级缺陷", "模块：模块A"), false, List.of("模块A"), "", false))
        .isNull();
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("严重程度：一级缺陷", "模块：模块A", "状态：待合并"),
        false,
        List.of("模块A"),
        "",
        false))
        .isNull();

    String validTemplate = "### 1、修复状态\n[x] 编码逻辑：业务逻辑错误\n### 3、请描述具体原因：\n";
    assertThat(IssueFactNormalizationRules.hasFixTemplateReply(validTemplate)).isTrue();
    assertThat(IssueFactNormalizationRules.latestFixReasonCategoryCount(validTemplate)).isEqualTo(1);
    assertThat(IssueFactNormalizationRules.hasResearchTemplateReply(validTemplate)).isFalse();
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("严重程度：一级缺陷", "模块：模块A", "状态：已修复/完成"),
        true,
        List.of("模块A"),
        "",
        true)).isEqualTo("未按照模板回复");
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("严重程度：一级缺陷", "模块：模块A", "状态：已修复/完成"),
        true,
        List.of("模块A"),
        "### 1、修复状态\n[x] 编码逻辑：业务逻辑错误\n[x] 新增需求问题\n### 3、请描述具体原因：\n",
        true)).isNull();
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("严重程度：一级缺陷", "模块：模块A", "状态：已修复/完成"),
        true,
        List.of("模块A"),
        validTemplate,
        true)).isNull();
  }

  @Test
  void shouldKeepSystemTestIllegalCauseValidationOnFixTemplateOnly() {
    String researchTemplate = "# 问题调研情况说明\n编码逻辑：业务逻辑错误";

    assertThat(IssueFactNormalizationRules.hasResearchTemplateReply(researchTemplate)).isTrue();
    assertThat(IssueFactNormalizationRules.hasFixTemplateReply(researchTemplate)).isFalse();
    assertThat(IssueFactNormalizationRules.illegalReason(
        List.of("严重程度：一级缺陷", "模块：模块A", "状态：已修复/完成"),
        true,
        List.of("模块A"),
        researchTemplate,
        true)).isEqualTo("未按照模板回复");
  }

  @Test
  void shouldAppendCustomerResearchTemplateValidationAfterLegacyFixCauseRules() {
    String invalidResearchTemplate =
        """
        ### 1、修复状态
        [x] 编码逻辑：业务逻辑错误
        ### 3、请描述具体原因：
        ---
        # 问题调研情况说明
        ## 问题原因：已定位
        ## 修改方案：已修改
        ## 计划解决时间：2026.04.01
        ## 计划合并的版本分支：
        """;

    assertThat(IssueFactNormalizationRules.customerIssueIllegalReasons(
        List.of("二级缺陷", "模块A", "已修复/完成"),
        List.of("模块A"),
        invalidResearchTemplate,
        true))
        .containsExactly("未按照要求填写缺陷调研模板");
  }

  @Test
  void shouldHandleResponseResolveDeadlineAndLegacyRules() {
    String notes = "# 问题调研情况说明\n预计解决时间 7 天";
    assertThat(IssueFactNormalizationRules.hasResponse(notes)).isTrue();
    assertThat(IssueFactNormalizationRules.isResponseDelayed(List.of("响应已延期"), "")).isTrue();
    assertThat(IssueFactNormalizationRules.isResponseDelayed(List.of("响应已延期"), notes)).isFalse();
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
        List.of(),
        false,
        LocalDateTime.of(2026, 3, 1, 9, 0),
        LocalDateTime.of(2026, 4, 1, 9, 0))).isTrue();
  }

  @Test
  void shouldCalculateCustomerIssueResponseDelayByPriorityAndTemplateReply() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 4, 1, 10, 0);
    assertThat(IssueFactNormalizationRules.isResponseDelayed(
        List.of("P1"),
        "",
        createdAt,
        "P1",
        LocalDateTime.of(2026, 4, 2, 11, 0))).isTrue();
    assertThat(IssueFactNormalizationRules.isResponseDelayed(
        List.of("P1"),
        "",
        createdAt,
        "P1",
        LocalDateTime.of(2026, 4, 2, 9, 0))).isFalse();
    assertThat(IssueFactNormalizationRules.isResponseDelayed(
        List.of("P2"),
        "",
        createdAt,
        "P2",
        LocalDateTime.of(2026, 4, 3, 11, 0))).isTrue();
    assertThat(IssueFactNormalizationRules.isResponseDelayed(
        List.of(),
        "",
        createdAt,
        null,
        LocalDateTime.of(2026, 4, 4, 11, 0))).isTrue();
    assertThat(IssueFactNormalizationRules.isResponseDelayed(
        List.of("响应已延期", "P1"),
        "# 问题调研情况说明\n## 问题原因\n已完成调研",
        createdAt,
        "P1",
        LocalDateTime.of(2026, 4, 2, 11, 0))).isFalse();
  }

  @Test
  void shouldUsePlanSolutionDateBeforeEighteenDayResolutionDeadline() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 4, 1, 10, 0);
    String earlierPlan = "# 问题调研情况说明\n## 计划解决时间：2026.04.08";
    assertThat(IssueFactNormalizationRules.resolveDeadline(createdAt, earlierPlan))
        .isEqualTo(LocalDateTime.of(2026, 4, 8, 0, 0));

    String laterPlan = "# 问题调研情况说明\n## 计划解决时间：2026年5月1日";
    assertThat(IssueFactNormalizationRules.resolveDeadline(createdAt, laterPlan))
        .isEqualTo(LocalDateTime.of(2026, 4, 19, 10, 0));

    LocalDateTime earlierDeadline = IssueFactNormalizationRules.resolveDeadline(createdAt, earlierPlan);
    assertThat(IssueFactNormalizationRules.isResolveDelayed(
        List.of("一级缺陷"),
        false,
        false,
        earlierDeadline,
        LocalDateTime.of(2026, 4, 9, 10, 0))).isTrue();
    assertThat(IssueFactNormalizationRules.isResolveDelayed(
        List.of("一级缺陷", "已修复/完成"),
        true,
        true,
        earlierDeadline,
        LocalDateTime.of(2026, 4, 9, 10, 0))).isFalse();
  }
}
