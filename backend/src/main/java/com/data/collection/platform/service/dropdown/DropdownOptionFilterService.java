package com.data.collection.platform.service.dropdown;

import com.data.collection.platform.entity.dropdown.DropdownOptionRule;
import com.data.collection.platform.entity.dropdown.DropdownOptionRulesPayload;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.service.LabelGroupFilterOperatorSupport;
import com.data.collection.platform.service.TextQuerySupport;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.data.collection.platform.service.statistics.engine.StatisticFieldDescriptor;
import com.data.collection.platform.service.statistics.engine.StatisticFilterEngine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 下拉框黑白名单判定管道：自动获取值与手动添加值各走各的规则套，幸存者取并集。
 *
 * <p>判定语义为全平台唯一实现，消费链路（如评审候选装配）只调用 {@link #resolveOptions(String)}；
 * 操作符语义复用 {@link StatisticFilterEngine}（对单选项值包装出的单元素多值行求值），
 * 与统计看板筛选行为逐字一致。
 */
@Service
@Slf4j
public class DropdownOptionFilterService {
  /**
   * 引擎字段注册表：把单个选项值包装成单元素列表，文本与集合操作符直接可用。
   */
  private static final StatisticFieldDescriptor<String> OPTION_DESCRIPTOR =
      StatisticFieldDescriptor.multiValue(
          DropdownOptionRuleSupport.OPTION_FIELD_KEY, value -> value == null ? List.of() : List.of(value));

  private static final String LIST_TYPE_WHITELIST = "WHITELIST";

  private final DropdownOptionFieldRegistry fieldRegistry;
  private final DropdownOptionConfigRepository configRepository;
  private final LabelGroupExpansionService labelGroupExpansionService;

  public DropdownOptionFilterService(
      DropdownOptionFieldRegistry fieldRegistry,
      DropdownOptionConfigRepository configRepository,
      LabelGroupExpansionService labelGroupExpansionService) {
    this.fieldRegistry = fieldRegistry;
    this.configRepository = configRepository;
    this.labelGroupExpansionService = labelGroupExpansionService;
  }

  /**
   * 消费链路唯一入口：字段绑定的配置判定结果。
   *
   * <p>字段未绑定、绑定配置不存在或配置全空（两套规则皆空且无手动选项）时，
   * 原样返回自动获取值池（直通现状）；否则自动获取值过自动规则、手动选项过手动规则，取并集。
   *
   * @param fieldKey 注册字段键
   * @return 下拉框最终显示的选项列表
   */
  public List<String> resolveOptions(String fieldKey) {
    DropdownOptionFieldRegistry.DropdownOptionFieldDefinition definition =
        fieldRegistry.requireField(fieldKey);
    List<String> acquired = definition.acquiredOptions().get();
    Optional<Long> configId = configRepository.findBoundConfigId(fieldKey);
    if (configId.isEmpty()) {
      return acquired;
    }
    DropdownOptionConfigRepository.StoredConfig config = configRepository.loadConfig(configId.get()).orElse(null);
    if (config == null || isEmptyConfig(config)) {
      return acquired;
    }
    return union(
        evaluate(acquired, config.rules().acquiredRules()),
        evaluate(config.manualOptions(), config.rules().manualRules()));
  }

  /**
   * 预览求值：调用方（字段服务）已完成规则校验与归一化，本方法不做合法性检查。
   *
   * @param fieldKey 注册字段键
   * @param normalizedRules 归一化后的双套规则
   * @param normalizedManualOptions 归一化后的手动选项
   * @return 最终显示列表
   */
  public List<String> previewOptions(
      String fieldKey, DropdownOptionRulesPayload normalizedRules, List<String> normalizedManualOptions) {
    DropdownOptionFieldRegistry.DropdownOptionFieldDefinition definition =
        fieldRegistry.requireField(fieldKey);
    return union(
        evaluate(definition.acquiredOptions().get(), normalizedRules.acquiredRules()),
        evaluate(normalizedManualOptions, normalizedRules.manualRules()));
  }

  private static boolean isEmptyConfig(DropdownOptionConfigRepository.StoredConfig config) {
    return config.rules().acquiredRules().isEmpty()
        && config.rules().manualRules().isEmpty()
        && config.manualOptions().isEmpty();
  }

  /** 去重并集：自动获取值按值池原顺序在前，手动项按录入顺序排后。 */
  private static List<String> union(List<String> acquiredKept, List<String> manualKept) {
    if (manualKept.isEmpty()) {
      return acquiredKept;
    }
    LinkedHashSet<String> merged = new LinkedHashSet<>(acquiredKept);
    merged.addAll(manualKept);
    return List.copyOf(merged);
  }

  /**
   * 单套规则判定：逐值自上而下取第一条命中规则——黑名单剔除、白名单保留；
   * 全部未命中时存在任一白名单则剔除（精确模式），否则保留。
   *
   * <p>空规则集原样放行；空白值直接丢弃（值池不应包含空白项，防御性兜底）。
   */
  private List<String> evaluate(List<String> values, List<DropdownOptionRule> rules) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    if (rules == null || rules.isEmpty()) {
      return List.copyOf(values);
    }
    boolean hasWhitelist = rules.stream().anyMatch(rule -> LIST_TYPE_WHITELIST.equals(rule.listType()));
    List<DropdownOptionRule> safeRules = List.copyOf(rules);
    List<Predicate<String>> predicates = safeRules.stream().map(this::compileRule).toList();
    List<String> kept = new ArrayList<>();
    for (String value : values) {
      if (TextQuerySupport.trimToNull(value) == null) {
        continue;
      }
      Boolean keptByRule = null;
      for (int index = 0; index < safeRules.size(); index++) {
        if (predicates.get(index).test(value)) {
          keptByRule = LIST_TYPE_WHITELIST.equals(safeRules.get(index).listType());
          break;
        }
      }
      if (keptByRule == null ? !hasWhitelist : keptByRule) {
        kept.add(value);
      }
    }
    return List.copyOf(kept);
  }

  private Predicate<String> compileRule(DropdownOptionRule rule) {
    StatisticFilterGroup group = expandLabelGroups(rule.filterGroup());
    return StatisticFilterEngine.compile(
        group, Map.of(DropdownOptionRuleSupport.OPTION_FIELD_KEY, OPTION_DESCRIPTOR));
  }

  /**
   * 标签组条件在求值时实时展开为成员值：标签组后续编辑无需重存配置即可生效。
   *
   * <p>展开失败（如标签组已被删除）时该条件按"不命中"降级并告警——判定是消费链路读取路径，
   * 不允许配置引用失效拖垮候选装配。
   */
  private StatisticFilterGroup expandLabelGroups(StatisticFilterGroup group) {
    if (group == null || group.conditions() == null || group.conditions().isEmpty()) {
      return new StatisticFilterGroup("AND", List.of());
    }
    List<StatisticFilterCondition> expanded = new ArrayList<>(group.conditions().size());
    for (StatisticFilterCondition condition : group.conditions()) {
      expanded.add(expandCondition(condition));
    }
    return new StatisticFilterGroup(group.logic(), List.copyOf(expanded));
  }

  private StatisticFilterCondition expandCondition(StatisticFilterCondition condition) {
    if (condition == null || !condition.usesLabelGroup()) {
      return condition;
    }
    if (condition.labelGroupId() == null) {
      return neverMatch(condition.fieldKey());
    }
    try {
      LabelGroupExpansionResponse expansion =
          labelGroupExpansionService.expand(
              condition.labelGroupId(),
              "STRING",
              DropdownOptionRuleSupport.OPTION_FIELD_KEY,
              DropdownOptionRuleSupport.OPTION_PAGE_KEY,
              null);
      return new StatisticFilterCondition(
          condition.fieldKey(),
          LabelGroupFilterOperatorSupport.normalize(condition.operator()),
          null,
          null,
          "LABEL_GROUP",
          condition.labelGroupId(),
          condition.labelGroupName(),
          expansion.values());
    } catch (BizException error) {
      log.warn(
          "下拉框规则标签组 {} 展开失败，该条件按不命中处理：{}",
          condition.labelGroupId(),
          error.getMessage());
      return neverMatch(condition.fieldKey());
    }
  }

  /**
   * 恒不命中的标签组条件：期望集合为空时引擎集合语义恒为 false
   * （见 StatisticFilterEngine.matchesSet 的空集守卫）。
   */
  private static StatisticFilterCondition neverMatch(String fieldKey) {
    return new StatisticFilterCondition(
        fieldKey, "intersects", null, null, "LABEL_GROUP", null, null, List.of());
  }
}
