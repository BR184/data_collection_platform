package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRulePreviewResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleAggregationRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleConditionGroupRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleConditionRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleConfigRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleFieldRefRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleRelationRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleSortRequest;
import com.data.collection.platform.service.TextQuerySupport;
import com.data.collection.platform.service.labelgroup.LabelGroupDynamicRuleCatalogService.DynamicRuleFieldDefinition;
import com.data.collection.platform.service.labelgroup.LabelGroupDynamicRuleCatalogService.DynamicRuleRelationDefinition;
import com.data.collection.platform.service.labelgroup.LabelGroupDynamicRuleCatalogService.DynamicRuleSourceDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupDynamicRuleEvaluationService {
  private static final int MAX_OUTPUT_VALUES = LabelGroupService.MAX_MEMBER_COUNT;
  private static final int DEFAULT_PREVIEW_LIMIT = 50;
  private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
  private static final Set<String> AGGREGATE_FUNCTIONS =
      Set.of("count", "countDistinct", "sum", "avg", "min", "max");

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final LabelGroupDynamicRuleCatalogService catalogService;

  public LabelGroupDynamicRuleEvaluationService(
      NamedParameterJdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      LabelGroupDynamicRuleCatalogService catalogService) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.catalogService = catalogService;
  }

  public LabelGroupDynamicRulePreviewResponse preview(LabelGroupRuleConfigRequest ruleConfig) {
    EvaluationResult result = evaluate(ruleConfig, true);
    return new LabelGroupDynamicRulePreviewResponse(
        result.outputValueType(),
        "SUCCESS",
        result.values().isEmpty() ? "当前规则未计算出成员" : "已计算出 " + result.values().size() + " 个成员",
        result.values().stream()
            .map(value -> new LabelGroupMemberResponse(null, value, value, true, 0))
            .toList());
  }

  public List<LabelGroupMemberRecord> materializeMembers(LabelGroupRuleConfigRequest ruleConfig) {
    List<String> values = evaluate(ruleConfig, false).values();
    List<LabelGroupMemberRecord> members = new ArrayList<>();
    int sortOrder = 0;
    for (String value : values) {
      members.add(new LabelGroupMemberRecord(null, null, value, value, sortOrder++));
    }
    return members;
  }

  public String outputValueType(LabelGroupRuleConfigRequest ruleConfig) {
    RulePlan plan = buildPlan(ruleConfig, false);
    return plan.outputField().valueType();
  }

  public String serializeRuleConfig(LabelGroupRuleConfigRequest ruleConfig) {
    try {
      return objectMapper.writeValueAsString(ruleConfig);
    } catch (Exception error) {
      throw new BizException("动态规则配置序列化失败");
    }
  }

  public LabelGroupRuleConfigRequest parseRuleConfig(String ruleConfigJson) {
    String json = TextQuerySupport.trimToNull(ruleConfigJson);
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, LabelGroupRuleConfigRequest.class);
    } catch (Exception error) {
      throw new BizException("动态规则配置格式不正确");
    }
  }

  private EvaluationResult evaluate(LabelGroupRuleConfigRequest ruleConfig, boolean preview) {
    RulePlan plan = buildPlan(ruleConfig, preview);
    List<String> values =
        jdbcTemplate.query(plan.sql(), plan.params(), (rs, rowNum) -> TextQuerySupport.trimToNull(rs.getString("member_value")));
    LinkedHashSet<String> deduped = new LinkedHashSet<>();
    for (String value : values) {
      if (value != null) {
        deduped.add(value);
      }
      if (deduped.size() > MAX_OUTPUT_VALUES) {
        throw new BizException("动态规则输出超过 200 个成员，请缩小规则范围");
      }
    }
    return new EvaluationResult(plan.outputField().valueType(), List.copyOf(deduped));
  }

  private RulePlan buildPlan(LabelGroupRuleConfigRequest ruleConfig, boolean preview) {
    if (ruleConfig == null) {
      throw new BizException("动态规则不能为空");
    }
    DynamicRuleSourceDefinition outputSource = catalogService.requireSource(ruleConfig.outputSourceKey());
    DynamicRuleFieldDefinition outputField =
        catalogService.requireField(outputSource.key(), ruleConfig.outputFieldKey());
    if (!outputField.outputSupported()) {
      throw new BizException("字段不允许作为动态标签组输出：" + outputField.name());
    }

    LinkedHashMap<String, DynamicRuleSourceDefinition> sources = collectSources(ruleConfig, outputSource);
    Map<String, String> aliases = buildAliases(sources);
    List<DynamicRuleRelationDefinition> relations = normalizeRelations(ruleConfig.relations());
    validateRelations(sources.keySet(), relations);

    MapSqlParameterSource params = new MapSqlParameterSource();
    SqlParts sqlParts = buildFromAndJoins(outputSource.key(), sources, aliases, relations);
    List<String> where = new ArrayList<>();
    for (String sourceKey : sources.keySet()) {
      where.add(aliases.get(sourceKey) + ".deleted = false");
    }
    appendConditionGroup(where, params, aliases, effectiveConditionGroup(ruleConfig.filterGroup(), ruleConfig.filters()), false, Map.of(), "f");

    Map<String, String> aggregateExpressions = buildAggregateExpressions(ruleConfig.aggregations(), aliases);
    boolean grouped = !aggregateExpressions.isEmpty() || !safeList(ruleConfig.groupBy()).isEmpty();
    String outputExpression = fieldExpression(aliases.get(outputSource.key()), outputField);
    List<String> groupBy = new ArrayList<>();
    if (grouped) {
      groupBy.add(outputExpression);
      for (LabelGroupRuleFieldRefRequest ref : safeList(ruleConfig.groupBy())) {
        DynamicRuleFieldDefinition field = requireGroupField(ref);
        String expression = fieldExpression(aliases.get(ref.sourceKey()), field);
        if (!groupBy.contains(expression)) {
          groupBy.add(expression);
        }
      }
    }

    List<String> having = new ArrayList<>();
    appendConditionGroup(having, params, aliases, effectiveConditionGroup(ruleConfig.havingGroup(), ruleConfig.having()), true, aggregateExpressions, "h");

    StringBuilder sql = new StringBuilder();
    sql.append("select ");
    if (!grouped && Boolean.TRUE.equals(ruleConfig.distinct())) {
      sql.append("distinct ");
    }
    sql.append(outputExpression).append(" as member_value");
    sql.append(sqlParts.fromSql());
    if (!where.isEmpty()) {
      sql.append(" where ").append(String.join(" and ", where));
    }
    if (grouped) {
      sql.append(" group by ").append(String.join(", ", groupBy));
    }
    if (!having.isEmpty()) {
      sql.append(" having ").append(String.join(" and ", having));
    }
    appendSort(sql, aliases, ruleConfig.sort(), aggregateExpressions, grouped, outputExpression);
    sql.append(" limit :_limit");
    params.addValue("_limit", preview ? Math.min(normalizeLimit(ruleConfig.limit(), DEFAULT_PREVIEW_LIMIT), DEFAULT_PREVIEW_LIMIT)
        : normalizeLimit(ruleConfig.limit(), MAX_OUTPUT_VALUES + 1));

    return new RulePlan(sql.toString(), params, outputField);
  }

  private LinkedHashMap<String, DynamicRuleSourceDefinition> collectSources(
      LabelGroupRuleConfigRequest ruleConfig,
      DynamicRuleSourceDefinition outputSource) {
    LinkedHashMap<String, DynamicRuleSourceDefinition> sources = new LinkedHashMap<>();
    sources.put(outputSource.key(), outputSource);
    collectConditionGroupSources(sources, effectiveConditionGroup(ruleConfig.filterGroup(), ruleConfig.filters()), false);
    for (LabelGroupRuleRelationRequest relation : safeList(ruleConfig.relations())) {
      sources.put(relation.leftSourceKey(), catalogService.requireSource(relation.leftSourceKey()));
      sources.put(relation.rightSourceKey(), catalogService.requireSource(relation.rightSourceKey()));
    }
    for (LabelGroupRuleFieldRefRequest ref : safeList(ruleConfig.groupBy())) {
      sources.put(ref.sourceKey(), catalogService.requireSource(ref.sourceKey()));
    }
    for (LabelGroupRuleAggregationRequest aggregation : safeList(ruleConfig.aggregations())) {
      sources.put(aggregation.sourceKey(), catalogService.requireSource(aggregation.sourceKey()));
    }
    collectConditionGroupSources(sources, effectiveConditionGroup(ruleConfig.havingGroup(), ruleConfig.having()), true);
    for (LabelGroupRuleSortRequest sort : safeList(ruleConfig.sort())) {
      if (TextQuerySupport.trimToNull(sort.sourceKey()) != null) {
        sources.put(sort.sourceKey(), catalogService.requireSource(sort.sourceKey()));
      }
    }
    return sources;
  }

  private void collectConditionGroupSources(
      LinkedHashMap<String, DynamicRuleSourceDefinition> sources,
      LabelGroupRuleConditionGroupRequest group,
      boolean aggregateCondition) {
    if (group == null) {
      return;
    }
    if (!aggregateCondition) {
      for (LabelGroupRuleConditionRequest condition : safeList(group.conditions())) {
        if (TextQuerySupport.trimToNull(condition.sourceKey()) != null) {
          sources.put(condition.sourceKey(), catalogService.requireSource(condition.sourceKey()));
        }
      }
    }
    for (LabelGroupRuleConditionGroupRequest child : safeList(group.groups())) {
      collectConditionGroupSources(sources, child, aggregateCondition);
    }
  }

  private Map<String, String> buildAliases(LinkedHashMap<String, DynamicRuleSourceDefinition> sources) {
    Map<String, String> aliases = new LinkedHashMap<>();
    int index = 0;
    for (String sourceKey : sources.keySet()) {
      aliases.put(sourceKey, "s" + index++);
    }
    return aliases;
  }

  private List<DynamicRuleRelationDefinition> normalizeRelations(List<LabelGroupRuleRelationRequest> requests) {
    return safeList(requests).stream().map(catalogService::normalizeRelation).toList();
  }

  private void validateRelations(Set<String> sourceKeys, List<DynamicRuleRelationDefinition> relations) {
    if (sourceKeys.size() <= 1) {
      return;
    }
    if (relations.isEmpty()) {
      throw new BizException("跨数据源动态规则必须配置显式逻辑关联");
    }
    Set<String> visited = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    String first = sourceKeys.iterator().next();
    visited.add(first);
    queue.add(first);
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      for (DynamicRuleRelationDefinition relation : relations) {
        String next = null;
        if (relation.leftSourceKey().equals(current)) {
          next = relation.rightSourceKey();
        } else if (relation.rightSourceKey().equals(current)) {
          next = relation.leftSourceKey();
        }
        if (next != null && sourceKeys.contains(next) && visited.add(next)) {
          queue.add(next);
        }
      }
    }
    if (!visited.containsAll(sourceKeys)) {
      throw new BizException("跨数据源动态规则存在无法解释的关联路径");
    }
  }

  private SqlParts buildFromAndJoins(
      String outputSourceKey,
      LinkedHashMap<String, DynamicRuleSourceDefinition> sources,
      Map<String, String> aliases,
      List<DynamicRuleRelationDefinition> relations) {
    StringBuilder sql = new StringBuilder();
    DynamicRuleSourceDefinition outputSource = sources.get(outputSourceKey);
    sql.append(" from ").append(outputSource.tableName()).append(" ").append(aliases.get(outputSourceKey));
    Set<String> joined = new LinkedHashSet<>();
    joined.add(outputSourceKey);
    while (joined.size() < sources.size()) {
      boolean changed = false;
      for (DynamicRuleRelationDefinition relation : relations) {
        String joinedSide = null;
        String newSide = null;
        boolean reversed = false;
        if (joined.contains(relation.leftSourceKey()) && !joined.contains(relation.rightSourceKey())) {
          joinedSide = relation.leftSourceKey();
          newSide = relation.rightSourceKey();
        } else if (joined.contains(relation.rightSourceKey()) && !joined.contains(relation.leftSourceKey())) {
          joinedSide = relation.rightSourceKey();
          newSide = relation.leftSourceKey();
          reversed = true;
        }
        if (newSide == null || !sources.containsKey(newSide)) {
          continue;
        }
        DynamicRuleSourceDefinition source = sources.get(newSide);
        sql.append(" join ").append(source.tableName()).append(" ").append(aliases.get(newSide)).append(" on ");
        sql.append(joinCondition(relation, aliases, reversed));
        joined.add(newSide);
        changed = true;
      }
      if (!changed) {
        throw new BizException("跨数据源动态规则存在无法生成的逻辑关联");
      }
    }
    return new SqlParts(sql.toString());
  }

  private String joinCondition(
      DynamicRuleRelationDefinition relation,
      Map<String, String> aliases,
      boolean reversed) {
    String leftSource = reversed ? relation.rightSourceKey() : relation.leftSourceKey();
    String leftField = reversed ? relation.rightFieldKey() : relation.leftFieldKey();
    String rightSource = reversed ? relation.leftSourceKey() : relation.rightSourceKey();
    String rightField = reversed ? relation.leftFieldKey() : relation.rightFieldKey();
    String leftExpression = normalizedExpression(
        fieldExpression(aliases.get(leftSource), catalogService.requireField(leftSource, leftField)),
        relation.normalizer());
    String rightExpression = normalizedExpression(
        fieldExpression(aliases.get(rightSource), catalogService.requireField(rightSource, rightField)),
        relation.normalizer());
    return leftExpression + " = " + rightExpression;
  }

  private String normalizedExpression(String expression, String normalizer) {
    return switch (normalizer) {
      case "TRIM" -> "trim(" + expression + "::text)";
      case "LOWER" -> "lower(" + expression + "::text)";
      case "TRIM_LOWER" -> "lower(trim(" + expression + "::text))";
      default -> expression;
    };
  }

  private Map<String, String> buildAggregateExpressions(
      List<LabelGroupRuleAggregationRequest> aggregations,
      Map<String, String> aliases) {
    Map<String, String> expressions = new LinkedHashMap<>();
    for (LabelGroupRuleAggregationRequest aggregation : safeList(aggregations)) {
      String key = requireSafeKey(aggregation.key(), "聚合标识不能为空");
      if (expressions.containsKey(key)) {
        throw new BizException("聚合标识重复：" + key);
      }
      String function = TextQuerySupport.trimToNull(aggregation.function());
      if (function == null || !AGGREGATE_FUNCTIONS.contains(function)) {
        throw new BizException("聚合函数不支持：" + aggregation.function());
      }
      DynamicRuleFieldDefinition field = catalogService.requireField(aggregation.sourceKey(), aggregation.fieldKey());
      if (!field.aggregateSupported()) {
        throw new BizException("字段不允许聚合：" + field.name());
      }
      String fieldExpression = fieldExpression(aliases.get(aggregation.sourceKey()), field);
      String expression = switch (function) {
        case "count" -> "count(" + fieldExpression + ")";
        case "countDistinct" -> "count(distinct " + fieldExpression + ")";
        case "sum" -> "sum(" + fieldExpression + ")";
        case "avg" -> "avg(" + fieldExpression + ")";
        case "min" -> "min(" + fieldExpression + ")";
        case "max" -> "max(" + fieldExpression + ")";
        default -> throw new BizException("聚合函数不支持：" + function);
      };
      expressions.put(key, expression);
    }
    return expressions;
  }

  private void appendConditions(
      List<String> target,
      MapSqlParameterSource params,
      Map<String, String> aliases,
      List<LabelGroupRuleConditionRequest> conditions,
      boolean aggregateCondition,
      Map<String, String> aggregateExpressions,
      String prefix) {
    int index = 0;
    for (LabelGroupRuleConditionRequest condition : safeList(conditions)) {
      String expression;
      String valueType;
      if (aggregateCondition) {
        String aggregateKey = requireSafeKey(condition.aggregateKey(), "聚合过滤必须选择聚合项");
        expression = aggregateExpressions.get(aggregateKey);
        if (expression == null) {
          throw new BizException("聚合过滤项不存在：" + aggregateKey);
        }
        valueType = LabelGroupDynamicRuleCatalogService.VALUE_NUMBER;
        if (!catalogService.operatorsFor(valueType).contains(TextQuerySupport.trimToNull(condition.operator()))) {
          throw new BizException("聚合过滤不支持该关系：" + condition.operator());
        }
      } else {
        DynamicRuleFieldDefinition field = catalogService.requireField(condition.sourceKey(), condition.fieldKey());
        if (!field.filterSupported()) {
          throw new BizException("字段不允许过滤：" + field.name());
        }
        catalogService.validateOperator(field, condition.operator());
        expression = fieldExpression(aliases.get(condition.sourceKey()), field);
        valueType = field.valueType();
      }
      target.add(conditionSql(expression, valueType, condition, params, prefix + index++));
    }
  }

  private void appendConditionGroup(
      List<String> target,
      MapSqlParameterSource params,
      Map<String, String> aliases,
      LabelGroupRuleConditionGroupRequest group,
      boolean aggregateCondition,
      Map<String, String> aggregateExpressions,
      String prefix) {
    if (group == null) {
      return;
    }
    List<String> clauses = new ArrayList<>();
    int index = 0;
    for (LabelGroupRuleConditionRequest condition : safeList(group.conditions())) {
      appendConditions(clauses, params, aliases, List.of(condition), aggregateCondition, aggregateExpressions, prefix + "c" + index++ + "_");
    }
    for (LabelGroupRuleConditionGroupRequest child : safeList(group.groups())) {
      appendConditionGroup(clauses, params, aliases, child, aggregateCondition, aggregateExpressions, prefix + "g" + index++ + "_");
    }
    if (clauses.isEmpty()) {
      return;
    }
    String logic = "OR".equalsIgnoreCase(group.logic()) ? " or " : " and ";
    target.add("(" + String.join(logic, clauses) + ")");
  }

  private LabelGroupRuleConditionGroupRequest effectiveConditionGroup(
      LabelGroupRuleConditionGroupRequest group,
      List<LabelGroupRuleConditionRequest> legacyConditions) {
    if (group != null) {
      return group;
    }
    if (safeList(legacyConditions).isEmpty()) {
      return null;
    }
    return new LabelGroupRuleConditionGroupRequest("AND", legacyConditions, List.of());
  }

  private String conditionSql(
      String expression,
      String valueType,
      LabelGroupRuleConditionRequest condition,
      MapSqlParameterSource params,
      String paramPrefix) {
    String operator = TextQuerySupport.trimToNull(condition.operator());
    if (operator == null) {
      throw new BizException("过滤关系不能为空");
    }
    return switch (operator) {
      case "eq" -> singleValue(expression + " = :" + paramPrefix, params, paramPrefix, condition.value(), valueType);
      case "ne" -> singleValue(expression + " <> :" + paramPrefix, params, paramPrefix, condition.value(), valueType);
      case "gt" -> singleValue(expression + " > :" + paramPrefix, params, paramPrefix, condition.value(), valueType);
      case "gte" -> singleValue(expression + " >= :" + paramPrefix, params, paramPrefix, condition.value(), valueType);
      case "lt" -> singleValue(expression + " < :" + paramPrefix, params, paramPrefix, condition.value(), valueType);
      case "lte" -> singleValue(expression + " <= :" + paramPrefix, params, paramPrefix, condition.value(), valueType);
      case "contains" -> {
        params.addValue(paramPrefix, "%" + requireValue(condition.value()) + "%");
        yield expression + " ilike :" + paramPrefix;
      }
      case "notContains" -> {
        params.addValue(paramPrefix, "%" + requireValue(condition.value()) + "%");
        yield "(" + expression + " is null or " + expression + " not ilike :" + paramPrefix + ")";
      }
      case "startsWith" -> {
        params.addValue(paramPrefix, requireValue(condition.value()) + "%");
        yield expression + " ilike :" + paramPrefix;
      }
      case "endsWith" -> {
        params.addValue(paramPrefix, "%" + requireValue(condition.value()));
        yield expression + " ilike :" + paramPrefix;
      }
      case "between" -> {
        params.addValue(paramPrefix + "Start", typedValue(condition.value(), valueType));
        params.addValue(paramPrefix + "End", typedValue(condition.secondValue(), valueType));
        yield expression + " between :" + paramPrefix + "Start and :" + paramPrefix + "End";
      }
      case "lastDays" -> {
        int days = parsePositiveInt(condition.value(), "最近天数必须是正整数");
        params.addValue(paramPrefix, days);
        yield expression + " >= (now() - (:" + paramPrefix + " || ' days')::interval)";
      }
      case "isEmpty" -> "(" + expression + " is null or trim(" + expression + "::text) = '')";
      case "isNotEmpty" -> "(" + expression + " is not null and trim(" + expression + "::text) <> '')";
      case "in" -> {
        List<String> values = safeList(condition.values()).stream()
            .map(TextQuerySupport::trimToNull)
            .filter(value -> value != null)
            .toList();
        if (values.isEmpty()) {
          throw new BizException("IN 过滤至少需要一个值");
        }
        params.addValue(paramPrefix, values.stream().map(value -> typedValue(value, valueType)).toList());
        yield expression + " in (:" + paramPrefix + ")";
      }
      default -> throw new BizException("过滤关系不支持：" + operator);
    };
  }

  private String singleValue(
      String sql,
      MapSqlParameterSource params,
      String paramName,
      String value,
      String valueType) {
    params.addValue(paramName, typedValue(value, valueType));
    return sql;
  }

  private Object typedValue(String value, String valueType) {
    String text = requireValue(value);
    try {
      return switch (valueType) {
        case LabelGroupDynamicRuleCatalogService.VALUE_NUMBER -> new java.math.BigDecimal(text);
        case LabelGroupDynamicRuleCatalogService.VALUE_BOOLEAN -> parseBooleanValue(text);
        case LabelGroupDynamicRuleCatalogService.VALUE_DATE -> parseDateValue(text);
        default -> text;
      };
    } catch (NumberFormatException | DateTimeParseException error) {
      throw new BizException("过滤值类型不匹配：" + text);
    }
  }

  private Object parseDateValue(String text) {
    if (text.length() == 10) {
      return LocalDate.parse(text);
    }
    return OffsetDateTime.parse(text);
  }

  private Boolean parseBooleanValue(String text) {
    if ("true".equalsIgnoreCase(text)) {
      return true;
    }
    if ("false".equalsIgnoreCase(text)) {
      return false;
    }
    throw new BizException("布尔过滤值只能是 true 或 false：" + text);
  }

  private void appendSort(
      StringBuilder sql,
      Map<String, String> aliases,
      List<LabelGroupRuleSortRequest> sorts,
      Map<String, String> aggregateExpressions,
      boolean grouped,
      String outputExpression) {
    List<String> clauses = new ArrayList<>();
    for (LabelGroupRuleSortRequest sort : safeList(sorts)) {
      String expression;
      if (TextQuerySupport.trimToNull(sort.aggregateKey()) != null) {
        expression = aggregateExpressions.get(sort.aggregateKey());
        if (expression == null) {
          throw new BizException("排序聚合项不存在：" + sort.aggregateKey());
        }
      } else {
        DynamicRuleFieldDefinition field = catalogService.requireField(sort.sourceKey(), sort.fieldKey());
        expression = fieldExpression(aliases.get(sort.sourceKey()), field);
        if (grouped && !expression.equals(outputExpression)) {
          throw new BizException("聚合规则只能按输出字段或聚合项排序");
        }
      }
      String direction = "desc".equalsIgnoreCase(sort.direction()) ? "desc" : "asc";
      clauses.add(expression + " " + direction + " nulls last");
    }
    if (clauses.isEmpty()) {
      clauses.add(outputExpression + " asc nulls last");
    }
    sql.append(" order by ").append(String.join(", ", clauses));
  }

  private DynamicRuleFieldDefinition requireGroupField(LabelGroupRuleFieldRefRequest ref) {
    DynamicRuleFieldDefinition field = catalogService.requireField(ref.sourceKey(), ref.fieldKey());
    if (!field.groupSupported()) {
      throw new BizException("字段不允许分组：" + field.name());
    }
    return field;
  }

  private String fieldExpression(String alias, DynamicRuleFieldDefinition field) {
    if (alias == null) {
      throw new BizException("动态规则字段所属数据源未加入查询：" + field.name());
    }
    return alias + "." + field.columnName();
  }

  private int normalizeLimit(Integer limit, int defaultValue) {
    if (limit == null) {
      return defaultValue;
    }
    return Math.max(1, Math.min(limit, MAX_OUTPUT_VALUES + 1));
  }

  private String requireSafeKey(String value, String message) {
    String key = TextQuerySupport.trimToNull(value);
    if (key == null) {
      throw new BizException(message);
    }
    if (!SAFE_KEY.matcher(key).matches()) {
      throw new BizException("动态规则标识只允许字母、数字和下划线：" + key);
    }
    return key;
  }

  private String requireValue(String value) {
    String text = TextQuerySupport.trimToNull(value);
    if (text == null) {
      throw new BizException("过滤值不能为空");
    }
    return text;
  }

  private int parsePositiveInt(String value, String message) {
    try {
      int parsed = Integer.parseInt(requireValue(value));
      if (parsed <= 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException error) {
      throw new BizException(message);
    }
  }

  private <T> List<T> safeList(List<T> values) {
    return values == null ? List.of() : values;
  }

  private record SqlParts(String fromSql) {}

  private record RulePlan(
      String sql,
      MapSqlParameterSource params,
      DynamicRuleFieldDefinition outputField) {}

  private record EvaluationResult(
      String outputValueType,
      List<String> values) {}
}
