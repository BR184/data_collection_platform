package com.data.collection.platform.service.dropdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.dropdown.DropdownOptionBindingRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionBindingTarget;
import com.data.collection.platform.entity.dropdown.DropdownOptionConfigSaveRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionFieldConfigResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionPreviewRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionRule;
import com.data.collection.platform.entity.dropdown.DropdownOptionRulesPayload;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.ReviewDataMirrorOptionRepository;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DropdownOptionFieldServiceTest {

  private static final String FIELD = DropdownOptionFieldRegistry.REVIEW_FORM_PROJECT_NAME_FIELD;
  private static final String DISPLAY_NAME = "评审数据-新增评审-项目名称";

  @Mock private ReviewDataMirrorOptionRepository mirrorOptionRepository;
  @Mock private DropdownOptionConfigRepository configRepository;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;
  @Mock private DropdownOptionFilterService filterService;

  private DropdownOptionFieldService service() {
    // 注册表只持有值池供应者引用，本测试的保存/绑定路径不触达镜像仓储，无需打桩。
    DropdownOptionFieldRegistry registry = new DropdownOptionFieldRegistry(mirrorOptionRepository);
    DropdownOptionRuleSupport ruleSupport = new DropdownOptionRuleSupport(labelGroupExpansionService);
    return new DropdownOptionFieldService(
        registry,
        configRepository,
        ruleSupport,
        filterService,
        new JsonUtils(new ObjectMapper()));
  }

  private static StatisticFilterCondition literal(String operator, String value) {
    return new StatisticFilterCondition(DropdownOptionRuleSupport.OPTION_FIELD_KEY, operator, value, null);
  }

  private static DropdownOptionConfigRepository.StoredConfig stored(long id, DropdownOptionRulesPayload rules, List<String> manual) {
    return new DropdownOptionConfigRepository.StoredConfig(id, rules, manual, 0L);
  }

  @Test
  void test_firstSaveCreatesConfigAndBindsField() {
    DropdownOptionFieldService service = service();
    when(configRepository.findBoundConfigId(FIELD))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(42L));
    when(configRepository.insertConfig(any(), any(), any())).thenReturn(42L);
    when(configRepository.loadAllBindings())
        .thenReturn(List.of(new DropdownOptionConfigRepository.FieldBinding(FIELD, 42L)));
    when(configRepository.loadConfig(42L))
        .thenReturn(Optional.of(stored(
            42L,
            new DropdownOptionRulesPayload(
                List.of(new DropdownOptionRule("BLACKLIST", null, new StatisticFilterGroup("OR", List.of(literal("eq", "旧项目"))))),
                List.of()),
            List.of("李四"))));

    DropdownOptionFieldConfigResponse response = service.saveConfig(
        FIELD,
        new DropdownOptionConfigSaveRequest(
            new DropdownOptionRulesPayload(
                List.of(new DropdownOptionRule("BLACKLIST", null, new StatisticFilterGroup("OR", List.of(literal("eq", "旧项目"))))),
                List.of()),
            List.of("李四"),
            null),
        "admin");

    verify(configRepository).bindField(FIELD, 42L, "admin");
    assertThat(response.configId()).isEqualTo(42L);
    assertThat(response.configLabel()).isEqualTo(DISPLAY_NAME);
    assertThat(response.consumerFields()).containsExactly(DISPLAY_NAME);
    assertThat(response.manualOptions()).containsExactly("李四");
    assertThat(response.version()).isZero();
  }

  @Test
  void test_subsequentSaveUpdatesBoundConfigWithOptimisticLock() {
    DropdownOptionFieldService service = service();
    when(configRepository.findBoundConfigId(FIELD))
        .thenReturn(Optional.of(42L))
        .thenReturn(Optional.of(42L));
    when(configRepository.loadAllBindings())
        .thenReturn(List.of(new DropdownOptionConfigRepository.FieldBinding(FIELD, 42L)));
    when(configRepository.loadConfig(42L))
        .thenReturn(Optional.of(stored(42L, DropdownOptionRulesPayload.empty(), List.of())));

    DropdownOptionFieldConfigResponse response = service.saveConfig(
        FIELD,
        new DropdownOptionConfigSaveRequest(DropdownOptionRulesPayload.empty(), List.of(), 3L),
        "admin");

    verify(configRepository).updateConfig(eq(42L), any(), any(), eq(3L), eq("admin"));
    assertThat(response.configId()).isEqualTo(42L);
  }

  @Test
  void test_sharedConfigDerivesLabelAndConsumerList() {
    DropdownOptionFieldService service = service();
    when(configRepository.findBoundConfigId(FIELD)).thenReturn(Optional.of(42L));
    when(configRepository.loadAllBindings())
        .thenReturn(List.of(
            new DropdownOptionConfigRepository.FieldBinding(FIELD, 42L),
            new DropdownOptionConfigRepository.FieldBinding("review-data.form.unknown", 42L)));
    when(configRepository.loadConfig(42L))
        .thenReturn(Optional.of(stored(42L, DropdownOptionRulesPayload.empty(), List.of())));

    DropdownOptionFieldConfigResponse response = service.getConfig(FIELD);

    assertThat(response.consumerFields()).containsExactly(DISPLAY_NAME, "review-data.form.unknown");
    assertThat(response.configLabel()).isEqualTo(DISPLAY_NAME + "、review-data.form.unknown（2 个字段共用）");
  }

  @Test
  void test_unboundFieldReturnsEmptyDraftForFirstSave() {
    DropdownOptionFieldService service = service();
    when(configRepository.findBoundConfigId(FIELD)).thenReturn(Optional.empty());

    DropdownOptionFieldConfigResponse response = service.getConfig(FIELD);

    assertThat(response.configId()).isNull();
    assertThat(response.configLabel()).isEqualTo(DISPLAY_NAME);
    assertThat(response.rules().acquiredRules()).isEmpty();
    assertThat(response.rules().manualRules()).isEmpty();
    assertThat(response.manualOptions()).isEmpty();
    assertThat(response.version()).isZero();
    assertThat(response.consumerFields()).containsExactly(DISPLAY_NAME);
  }

  @Test
  void test_copyBindingRequiresExistingBinding() {
    DropdownOptionFieldService service = service();
    when(configRepository.findBoundConfigId(FIELD)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.bindField(
            FIELD, new DropdownOptionBindingRequest(DropdownOptionBindingTarget.COPY, null), "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("无法复制拆分");
    verify(configRepository, never()).copyConfig(anyLong(), any());
  }

  @Test
  void test_configBindingRequiresExistingConfig() {
    DropdownOptionFieldService service = service();
    when(configRepository.configExists(99L)).thenReturn(false);

    assertThatThrownBy(() -> service.bindField(
            FIELD, new DropdownOptionBindingRequest(DropdownOptionBindingTarget.CONFIG, 99L), "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("目标配置不存在");
  }

  @Test
  void test_previewValidatesRulesBeforeEvaluating() {
    DropdownOptionFieldService service = service();
    DropdownOptionRulesPayload invalid = new DropdownOptionRulesPayload(
        List.of(new DropdownOptionRule("GRAYLIST", null, new StatisticFilterGroup("OR", List.of(literal("eq", "X"))))),
        List.of());

    assertThatThrownBy(() -> service.preview(FIELD, new DropdownOptionPreviewRequest(invalid, List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("名单类型");
    verify(filterService, never()).previewOptions(any(), any(), any());
  }
}
