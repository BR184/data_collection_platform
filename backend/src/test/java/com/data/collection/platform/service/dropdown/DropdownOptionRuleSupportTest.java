package com.data.collection.platform.service.dropdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.dropdown.DropdownOptionRule;
import com.data.collection.platform.entity.dropdown.DropdownOptionRulesPayload;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DropdownOptionRuleSupportTest {

  @Mock private LabelGroupExpansionService labelGroupExpansionService;

  private DropdownOptionRuleSupport support;

  @BeforeEach
  void setUp() {
    support = new DropdownOptionRuleSupport(labelGroupExpansionService);
  }

  private static StatisticFilterCondition literal(String operator, String value) {
    return new StatisticFilterCondition(DropdownOptionRuleSupport.OPTION_FIELD_KEY, operator, value, null);
  }

  private static StatisticFilterCondition condition(StatisticFilterCondition condition) {
    return condition;
  }

  @Test
  void test_normalizesListTypeLogicAndValues() {
    DropdownOptionRulesPayload normalized = support.normalizeAndValidate(new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule(
            "blacklist", "  剔除测试  ", new StatisticFilterGroup("or", List.of(literal("eq", " X "))))),
        null));

    assertThat(normalized.acquiredRules()).hasSize(1);
    assertThat(normalized.acquiredRules().get(0).listType()).isEqualTo("BLACKLIST");
    assertThat(normalized.acquiredRules().get(0).name()).isEqualTo("剔除测试");
    assertThat(normalized.acquiredRules().get(0).filterGroup().logic()).isEqualTo("OR");
    assertThat(normalized.acquiredRules().get(0).filterGroup().conditions().get(0).value()).isEqualTo("X");
    assertThat(normalized.manualRules()).isEmpty();
  }

  @Test
  void test_rejectsUnknownFieldKey() {
    assertThatThrownBy(() -> support.normalizeAndValidate(new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule("BLACKLIST", null, new StatisticFilterGroup("AND",
            List.of(new StatisticFilterCondition("projectName", "eq", "X", null))))), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("选项值");
  }

  @Test
  void test_rejectsUnsupportedLiteralOperator() {
    assertThatThrownBy(() -> support.normalizeAndValidate(new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule("BLACKLIST", null, new StatisticFilterGroup("AND",
            List.of(literal("between", "1"))))), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("操作符");
  }

  @Test
  void test_rejectsConditionWithoutRequiredValue() {
    assertThatThrownBy(() -> support.normalizeAndValidate(new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule("BLACKLIST", null, new StatisticFilterGroup("AND",
            List.of(literal("contains", "  "))))), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("缺少判定值");
  }

  @Test
  void test_rejectsRuleWithoutEffectiveConditions() {
    assertThatThrownBy(() -> support.normalizeAndValidate(new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule("BLACKLIST", "空规则", new StatisticFilterGroup("AND", List.of()))), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("至少需要一条有效条件");
  }

  @Test
  void test_rejectsUnknownListType() {
    assertThatThrownBy(() -> support.normalizeAndValidate(new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule("GRAYLIST", null, new StatisticFilterGroup("AND", List.of(literal("eq", "1"))))), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("名单类型");
  }

  @Test
  void test_normalizesLabelGroupOperatorAndValidatesGroupAtSaveTime() {
    when(labelGroupExpansionService.expand(
            eq(7L), eq("STRING"), eq(DropdownOptionRuleSupport.OPTION_FIELD_KEY),
            eq(DropdownOptionRuleSupport.OPTION_PAGE_KEY), isNull()))
        .thenReturn(new LabelGroupExpansionResponse(7L, "G", "STRING", List.of("A"), List.of()));

    DropdownOptionRulesPayload normalized = support.normalizeAndValidate(new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule("WHITELIST", null, new StatisticFilterGroup("AND",
            List.of(condition(new StatisticFilterCondition(
                DropdownOptionRuleSupport.OPTION_FIELD_KEY, "eq", null, null, "LABEL_GROUP", 7L, "G", List.of())))))),
        List.of()));

    StatisticFilterCondition condition = normalized.acquiredRules().get(0).filterGroup().conditions().get(0);
    assertThat(condition.operator()).isEqualTo("intersects");
    assertThat(condition.values()).isEmpty();
    verify(labelGroupExpansionService).expand(
        eq(7L), eq("STRING"), eq(DropdownOptionRuleSupport.OPTION_FIELD_KEY),
        eq(DropdownOptionRuleSupport.OPTION_PAGE_KEY), isNull());
  }

  @Test
  void test_rejectsLabelGroupConditionWithoutGroupId() {
    assertThatThrownBy(() -> support.normalizeAndValidate(new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule("BLACKLIST", null, new StatisticFilterGroup("AND",
            List.of(condition(new StatisticFilterCondition(
                DropdownOptionRuleSupport.OPTION_FIELD_KEY, "intersects", null, null, "LABEL_GROUP", null, null, List.of())))))),
        List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("标签组 ID");
  }

  @Test
  void test_rejectsExcessiveRulesPerSet() {
    List<DropdownOptionRule> tooMany = IntStream.rangeClosed(1, DropdownOptionRuleSupport.MAX_RULES_PER_SET + 1)
        .mapToObj(index -> new DropdownOptionRule("BLACKLIST", null, new StatisticFilterGroup("AND", List.of(literal("eq", String.valueOf(index))))))
        .toList();

    assertThatThrownBy(() -> support.normalizeAndValidate(new DropdownOptionRulesPayload(tooMany, List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不能超过");
  }

  @Test
  void test_normalizesManualOptionsWithTrimAndDeduplication() {
    List<String> normalized = support.normalizeManualOptions(List.of(" 李四 ", "李四", "", "  ", "刘五"));

    assertThat(normalized).containsExactly("李四", "刘五");
  }

  @Test
  void test_rejectsExcessiveManualOptions() {
    List<String> tooMany = IntStream.rangeClosed(1, DropdownOptionRuleSupport.MAX_MANUAL_OPTIONS + 1)
        .mapToObj(index -> "选项" + index)
        .toList();

    assertThatThrownBy(() -> support.normalizeManualOptions(tooMany))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("手动添加选项");
  }
}
