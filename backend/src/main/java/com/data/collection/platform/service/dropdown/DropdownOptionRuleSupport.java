package com.data.collection.platform.service.dropdown;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.dropdown.DropdownOptionRule;
import com.data.collection.platform.entity.dropdown.DropdownOptionRulesPayload;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.LabelGroupFilterOperatorSupport;
import com.data.collection.platform.service.TextQuerySupport;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 下拉框黑白名单规则的唯一校验与归一化入口：保存与预览共用同一套校验，
 * 非法配置在此处被拒绝，存储与判定管道只接收归一化后的线格式。
 *
 * <p>判定语义的实现在 {@link DropdownOptionFilterService}，本类只负责"能不能存"。
 */
@Component
public class DropdownOptionRuleSupport {
  /** 下拉规则条件专用伪字段：每个选项值作为单元素多值行参与求值。 */
  public static final String OPTION_FIELD_KEY = "optionValue";
  /** 每套规则的条数上限，防止规则列表无限膨胀拖慢判定。 */
  public static final int MAX_RULES_PER_SET = 20;
  /** 手动添加选项个数上限（与标签组成员上限同量级）。 */
  public static final int MAX_MANUAL_OPTIONS = 200;
  /** 标签组条件校验/求值时使用的页面键：下拉规则无看板页面语义，仅需稳定取值。 */
  public static final String OPTION_PAGE_KEY = "dropdown-option-config";

  private static final String LIST_TYPE_BLACKLIST = "BLACKLIST";
  private static final String LIST_TYPE_WHITELIST = "WHITELIST";
  private static final int MAX_RULE_NAME_LENGTH = 50;
  private static final Set<String> LITERAL_OPERATORS =
      Set.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty");

  private final LabelGroupExpansionService labelGroupExpansionService;

  public DropdownOptionRuleSupport(LabelGroupExpansionService labelGroupExpansionService) {
    this.labelGroupExpansionService = labelGroupExpansionService;
  }

  /**
   * 校验并归一化双套规则：名单类型、条件字段、操作符词表、判定值完整性、标签组引用合法性、条数上限。
   *
   * @param payload 待校验的双套规则（可为 null，等价于空）
   * @return 归一化后可直接序列化存储的规则载荷
   * @throws BizException 任一规则非法
   */
  public DropdownOptionRulesPayload normalizeAndValidate(DropdownOptionRulesPayload payload) {
    DropdownOptionRulesPayload safe =
        payload == null ? DropdownOptionRulesPayload.empty() : payload.withNullsAsEmpty();
    return new DropdownOptionRulesPayload(
        normalizeRuleSet("自动获取值规则", safe.acquiredRules()),
        normalizeRuleSet("手动添加值规则", safe.manualRules()));
  }

  /**
   * 归一化手动选项：去首尾空白、去重、保序、限量。
   *
   * @param manualOptions 手动选项原始输入
   * @return 归一化后的选项列表
   * @throws BizException 超出数量上限
   */
  public List<String> normalizeManualOptions(List<String> manualOptions) {
    if (manualOptions == null || manualOptions.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (String option : manualOptions) {
      String normalized = TextQuerySupport.trimToNull(option);
      if (normalized != null) {
        values.add(normalized);
      }
    }
    if (values.size() > MAX_MANUAL_OPTIONS) {
      throw new BizException("手动添加选项不能超过 " + MAX_MANUAL_OPTIONS + " 个");
    }
    return List.copyOf(values);
  }

  private List<DropdownOptionRule> normalizeRuleSet(String setLabel, List<DropdownOptionRule> rules) {
    if (rules == null || rules.isEmpty()) {
      return List.of();
    }
    if (rules.size() > MAX_RULES_PER_SET) {
      throw new BizException(setLabel + "不能超过 " + MAX_RULES_PER_SET + " 条");
    }
    List<DropdownOptionRule> normalized = new ArrayList<>(rules.size());
    for (DropdownOptionRule rule : rules) {
      if (rule != null) {
        normalized.add(normalizeRule(setLabel, rule));
      }
    }
    return List.copyOf(normalized);
  }

  private DropdownOptionRule normalizeRule(String setLabel, DropdownOptionRule rule) {
    String listType = TextQuerySupport.trimToNull(rule.listType());
    if (listType == null) {
      throw new BizException(setLabel + "缺少名单类型");
    }
    String type = listType.toUpperCase(Locale.ROOT);
    if (!LIST_TYPE_BLACKLIST.equals(type) && !LIST_TYPE_WHITELIST.equals(type)) {
      throw new BizException(setLabel + "名单类型只能是黑名单或白名单：" + rule.listType());
    }
    String name = TextQuerySupport.trimToNull(rule.name());
    if (name != null && name.length() > MAX_RULE_NAME_LENGTH) {
      throw new BizException(setLabel + "规则名不能超过 " + MAX_RULE_NAME_LENGTH + " 字");
    }
    StatisticFilterGroup group = normalizeGroup(rule.filterGroup());
    if (group.conditions().isEmpty()) {
      String ruleLabel = name == null ? setLabel : setLabel + "「" + name + "」";
      throw new BizException(ruleLabel + "至少需要一条有效条件");
    }
    return new DropdownOptionRule(type, name, group);
  }

  private StatisticFilterGroup normalizeGroup(StatisticFilterGroup group) {
    if (group == null || group.conditions() == null || group.conditions().isEmpty()) {
      return new StatisticFilterGroup("AND", List.of());
    }
    String logic = "OR".equalsIgnoreCase(group.logic()) ? "OR" : "AND";
    List<StatisticFilterCondition> conditions = new ArrayList<>();
    for (StatisticFilterCondition condition : group.conditions()) {
      if (condition != null) {
        conditions.add(normalizeCondition(condition));
      }
    }
    return new StatisticFilterGroup(logic, List.copyOf(conditions));
  }

  private StatisticFilterCondition normalizeCondition(StatisticFilterCondition condition) {
    String fieldKey = TextQuerySupport.trimToNull(condition.fieldKey());
    if (!OPTION_FIELD_KEY.equals(fieldKey)) {
      throw new BizException("下拉规则条件字段只能是选项值：" + condition.fieldKey());
    }
    String operator = TextQuerySupport.trimToNull(condition.operator());
    if (operator == null) {
      throw new BizException("下拉规则条件缺少操作符");
    }
    if (condition.usesLabelGroup()) {
      if (!LabelGroupFilterOperatorSupport.isSetOperator(operator)) {
        throw new BizException("标签组条件只支持集合关系操作符：" + operator);
      }
      Long groupId = condition.labelGroupId();
      if (groupId == null) {
        throw new BizException("标签组条件缺少标签组 ID");
      }
      // 保存即校验标签组可用性（存在、适用域、成员上限）；展开结果不落存储，判定时按成员实时展开。
      labelGroupExpansionService.expand(groupId, "STRING", OPTION_FIELD_KEY, OPTION_PAGE_KEY, null);
      return new StatisticFilterCondition(
          fieldKey,
          LabelGroupFilterOperatorSupport.normalize(operator),
          null,
          null,
          "LABEL_GROUP",
          groupId,
          TextQuerySupport.trimToNull(condition.labelGroupName()),
          List.of());
    }
    if (!LITERAL_OPERATORS.contains(operator)) {
      throw new BizException("下拉规则不支持该操作符：" + operator);
    }
    String value = TextQuerySupport.trimToNull(condition.value());
    if (value == null && !"isEmpty".equals(operator) && !"isNotEmpty".equals(operator)) {
      throw new BizException("下拉规则条件缺少判定值");
    }
    return new StatisticFilterCondition(fieldKey, operator, value, null);
  }
}
