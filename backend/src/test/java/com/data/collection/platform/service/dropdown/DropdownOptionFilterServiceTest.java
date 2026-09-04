package com.data.collection.platform.service.dropdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionRule;
import com.data.collection.platform.entity.dropdown.DropdownOptionRulesPayload;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.ReviewDataMirrorOptionRepository;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DropdownOptionFilterServiceTest {

  @Mock private ReviewDataMirrorOptionRepository mirrorOptionRepository;
  @Mock private DropdownOptionConfigRepository configRepository;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;

  private static final String FIELD = DropdownOptionFieldRegistry.REVIEW_FORM_PROJECT_NAME_FIELD;

  private DropdownOptionFilterService service(List<String> acquiredPool) {
    when(mirrorOptionRepository.loadLabelProjectNames()).thenReturn(acquiredPool);
    DropdownOptionFieldRegistry registry = new DropdownOptionFieldRegistry(mirrorOptionRepository);
    return new DropdownOptionFilterService(registry, configRepository, labelGroupExpansionService);
  }

  private static StatisticFilterCondition literal(String operator, String value) {
    return new StatisticFilterCondition(DropdownOptionRuleSupport.OPTION_FIELD_KEY, operator, value, null);
  }

  private static StatisticFilterCondition labelGroup(long groupId) {
    return new StatisticFilterCondition(
        DropdownOptionRuleSupport.OPTION_FIELD_KEY, "intersects", null, null, "LABEL_GROUP", groupId, null, List.of());
  }

  private static DropdownOptionRule rule(String listType, StatisticFilterCondition... conditions) {
    return new DropdownOptionRule(listType, null, new StatisticFilterGroup("OR", List.of(conditions)));
  }

  private static DropdownOptionRulesPayload payload(List<DropdownOptionRule> acquired, List<DropdownOptionRule> manual) {
    return new DropdownOptionRulesPayload(acquired, manual);
  }

  private void boundConfig(DropdownOptionRulesPayload rules, List<String> manualOptions) {
    when(configRepository.findBoundConfigId(FIELD)).thenReturn(Optional.of(1L));
    when(configRepository.loadConfig(1L))
        .thenReturn(Optional.of(new DropdownOptionConfigRepository.StoredConfig(1L, rules, manualOptions, 3L)));
  }

  @Test
  void test_blacklistOnlyKeepsUnmatchedValues() {
    DropdownOptionFilterService service = service(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
    boundConfig(payload(List.of(rule("BLACKLIST", literal("eq", "1"))), List.of()), List.of());

    List<String> options = service.resolveOptions(FIELD);

    assertThat(options).containsExactly("2", "3", "4", "5", "6", "7", "8", "9", "10");
  }

  @Test
  void test_firstMatchedRuleDecidesWhenBlackAndWhiteListsCoexist() {
    DropdownOptionFilterService service = service(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
    // 黑1 / 黑2,3,4 / 白5 / 白1：1 先命中更靠上的黑名单，6-10 未命中但存在白名单默认剔除 → 只显示 5。
    boundConfig(
        payload(
            List.of(
                rule("BLACKLIST", literal("eq", "1")),
                rule("BLACKLIST", literal("eq", "2"), literal("eq", "3"), literal("eq", "4")),
                rule("WHITELIST", literal("eq", "5")),
                rule("WHITELIST", literal("eq", "1"))),
            List.of()),
        List.of());

    List<String> options = service.resolveOptions(FIELD);

    assertThat(options).containsExactly("5");
  }

  @Test
  void test_whitelistAboveBlacklistRescuesTheValue() {
    DropdownOptionFilterService service = service(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
    // 白1 拖到最上方：1 先命中白名单被保留，结果变为 1 和 5。
    boundConfig(
        payload(
            List.of(
                rule("WHITELIST", literal("eq", "1")),
                rule("BLACKLIST", literal("eq", "1")),
                rule("BLACKLIST", literal("eq", "2"), literal("eq", "3"), literal("eq", "4")),
                rule("WHITELIST", literal("eq", "5"))),
            List.of()),
        List.of());

    List<String> options = service.resolveOptions(FIELD);

    assertThat(options).containsExactly("1", "5");
  }

  @Test
  void test_manualOptionsAreJudgedByTheirOwnRuleSet() {
    DropdownOptionFilterService service = service(List.of("王三", "张三"));
    // 自动值黑名单剔除张三；手动值白名单只保留李四，刘五因存在白名单被默认剔除。
    boundConfig(
        payload(
            List.of(rule("BLACKLIST", literal("eq", "张三"))),
            List.of(rule("WHITELIST", literal("eq", "李四")))),
        List.of("李四", "刘五"));

    List<String> options = service.resolveOptions(FIELD);

    assertThat(options).containsExactly("王三", "李四");
  }

  @Test
  void test_manualValueWithoutRulesAlwaysSurvives() {
    DropdownOptionFilterService service = service(List.of("王三"));
    boundConfig(payload(List.of(), List.of()), List.of("李四", "刘五"));

    List<String> options = service.resolveOptions(FIELD);

    assertThat(options).containsExactly("王三", "李四", "刘五");
  }

  @Test
  void test_overlapValueSurvivesWhenEitherRuleSetKeepsIt() {
    DropdownOptionFilterService service = service(List.of("CC2027 R1&2027 R2"));
    // 自动规则拉黑该值，但手动添加且手动规则为空 → 手动通道保留，并集语义下仍显示。
    boundConfig(
        payload(List.of(rule("BLACKLIST", literal("eq", "CC2027 R1&2027 R2"))), List.of()),
        List.of("CC2027 R1&2027 R2"));

    List<String> options = service.resolveOptions(FIELD);

    assertThat(options).containsExactly("CC2027 R1&2027 R2");
  }

  @Test
  void test_emptyConfigPassesAcquiredPoolThrough() {
    DropdownOptionFilterService service = service(List.of("A", "B"));
    boundConfig(DropdownOptionRulesPayload.empty(), List.of());

    assertThat(service.resolveOptions(FIELD)).containsExactly("A", "B");
  }

  @Test
  void test_unboundFieldPassesAcquiredPoolThrough() {
    DropdownOptionFilterService service = service(List.of("A", "B"));
    when(configRepository.findBoundConfigId(FIELD)).thenReturn(Optional.empty());

    assertThat(service.resolveOptions(FIELD)).containsExactly("A", "B");
  }

  @Test
  void test_acquiredOrderComesFirstAndManualExtrasAppendDeduplicated() {
    DropdownOptionFilterService service = service(List.of("1", "2"));
    boundConfig(DropdownOptionRulesPayload.empty(), List.of("2", "3"));

    assertThat(service.resolveOptions(FIELD)).containsExactly("1", "2", "3");
  }

  @Test
  void test_labelGroupRuleMatchesByMembership() {
    DropdownOptionFilterService service = service(List.of("王三", "李四"));
    boundConfig(
        payload(List.of(rule("WHITELIST", labelGroup(7L))), List.of()),
        List.of());
    when(labelGroupExpansionService.expand(7L, "STRING", "optionValue", "dropdown-option-config", null))
        .thenReturn(new LabelGroupExpansionResponse(7L, "王姓专家", "STRING", List.of("王三"), List.of()));

    List<String> options = service.resolveOptions(FIELD);

    assertThat(options).containsExactly("王三");
  }

  @Test
  void test_labelGroupExpansionFailureDegradesToNoMatchInsteadOfBreakingReads() {
    DropdownOptionFilterService service = service(List.of("王三", "李四"));
    boundConfig(
        payload(List.of(rule("BLACKLIST", labelGroup(7L))), List.of()),
        List.of());
    when(labelGroupExpansionService.expand(eq(7L), anyString(), anyString(), anyString(), isNull()))
        .thenThrow(new BizException("标签组不存在"));

    List<String> options = service.resolveOptions(FIELD);

    assertThat(options).containsExactly("王三", "李四");
  }
}
