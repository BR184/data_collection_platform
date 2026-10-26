package com.data.collection.platform.domain.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 表单规格静态校验。
 *
 * <p>种子资源加载与管理员草稿共用同一实现；任一字段、词典或物理绑定不合法时拒绝发布，
 * 避免运行期用错误结构建表。校验返回全部问题供预览展示，种子加载取首条直接拒绝启动。
 */
@Component
public class FormSpecValidator {

  private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");
  private static final Pattern API_KEY = Pattern.compile("^[a-z][A-Za-z0-9]*$");
  private static final Pattern VARCHAR = Pattern.compile("^varchar\\((\\d+)\\)$");
  private static final Set<String> SCALAR_TYPES =
      Set.of("integer", "text", "boolean", "date", "text[]");
  private static final Set<String> FIELD_TYPES =
      Set.of("string", "integer", "boolean", "date", "enum", "enum-array");
  private static final Set<String> SYSTEM_COLUMNS =
      Set.of(
          "id",
          "instance_id",
          "origin_version_id",
          "template_version",
          "created_by",
          "request_key",
          "create_payload_hash",
          "record_version",
          "deleted",
          "legacy_incomplete",
          "legacy_missing_fields",
          "created_at",
          "updated_at");
  private static final Set<String> SEVERITY_VALUES =
      Set.of("一级缺陷", "二级缺陷", "三级缺陷", "建议类", "未设定");

  private final ObjectMapper objectMapper;

  public FormSpecValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** 解析并返回规格；JSON 非法时抛出带原因的非法参数异常。 */
  public FormTemplateSpec parse(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      throw new IllegalArgumentException("表单规格不能为空");
    }
    try {
      return objectMapper.readValue(rawJson, FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalArgumentException("表单规格不是合法 JSON: " + error.getMessage(), error);
    }
  }

  /** 校验规格；不合法时抛出包含全部问题的非法参数异常。 */
  public void validate(FormTemplateSpec spec) {
    List<String> errors = validationErrors(spec);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("表单规格不合法：" + String.join("；", errors));
    }
  }

  /**
   * 返回全部规格问题；空列表表示可发布。
   *
   * @param spec 待校验规格
   * @return 按校验顺序排列的问题描述
   */
  public List<String> validationErrors(FormTemplateSpec spec) {
    List<String> errors = new ArrayList<>();
    if (spec == null) {
      errors.add("表单规格不能为空");
      return errors;
    }
    if (spec.templateKey() == null || spec.templateKey().isBlank()) {
      errors.add("template_key 不能为空");
    }
    if (spec.specVersion() != 1) {
      errors.add("不支持的 spec_version " + spec.specVersion());
    }
    if (spec.fields().isEmpty()) {
      errors.add("fields 不能为空");
    }
    Set<String> names = new HashSet<>();
    Set<String> apiKeys = new HashSet<>();
    Set<String> columns = new HashSet<>();
    for (FormFieldSpec field : spec.fields()) {
      validateField(spec, field, names, apiKeys, columns, errors);
    }
    for (String snapshotColumn : spec.snapshotColumns()) {
      if (!columns.add(snapshotColumn)) {
        errors.add("快照列与字段列冲突：" + snapshotColumn);
      }
    }
    validateConditions(spec, names, errors);
    validateDictionaries(spec, errors);
    return errors;
  }

  private void validateField(
      FormTemplateSpec spec,
      FormFieldSpec field,
      Set<String> names,
      Set<String> apiKeys,
      Set<String> columns,
      List<String> errors) {
    if (field == null) {
      errors.add("fields 不能包含空字段");
      return;
    }
    if (field.name() == null || !IDENTIFIER.matcher(field.name()).matches()) {
      errors.add("非法字段名 " + field.name());
      return;
    }
    if (field.apiKey() == null || !API_KEY.matcher(field.apiKey()).matches()) {
      errors.add("非法 apiKey " + field.apiKey());
    }
    if (!names.add(field.name())) {
      errors.add("重复字段名 " + field.name());
    }
    if (!apiKeys.add(field.apiKey())) {
      errors.add("重复 apiKey " + field.apiKey());
    }
    if (field.storage() == null) {
      errors.add("字段 " + field.name() + " 缺少 storage");
      return;
    }
    validateColumn(field.storage().resolvedColumn(), columns, errors);
    if (field.storage().nameColumn() != null) {
      validateColumn(field.storage().nameColumn(), columns, errors);
    }
    validateColumnType(field.storage().resolvedColumnType(), errors);
    if (field.type() == null || field.type().isBlank()) {
      errors.add("字段 " + field.name() + " 缺少 type");
      return;
    }
    if (!FIELD_TYPES.contains(field.type())) {
      errors.add("字段 " + field.name() + " 的类型不受支持：" + field.type());
      return;
    }
    if (field.maxLength() != null && field.maxLength() <= 0) {
      errors.add("字段 " + field.name() + " 的 maxLength 非法");
    }
    if ("date".equals(field.type())
        && (field.minYear() == null
            || field.maxYear() == null
            || field.minYear() > field.maxYear())) {
      errors.add("字段 " + field.name() + " 缺少合法年份边界");
    }
    if (("enum".equals(field.type()) || "enum-array".equals(field.type()))
        && (field.options() == null || field.options().isEmpty())
        && (field.dictionary() == null || field.dictionary().isBlank())) {
      errors.add("枚举字段 " + field.name() + " 缺少 options 或 dictionary");
    }
    if ("integer".equals(field.type())
        && (field.min() == null || field.max() == null || field.min() > field.max())) {
      errors.add("字段 " + field.name() + " 缺少合法整数边界");
    }
    if (field.options() != null) {
      Set<String> optionValues = new HashSet<>();
      for (FormFieldOption option : field.options()) {
        if (option.value() == null || option.value().isBlank()) {
          errors.add("字段 " + field.name() + " 含空选项值");
          continue;
        }
        if (!optionValues.add(option.value())) {
          errors.add("字段 " + field.name() + " 含重复选项 " + option.value());
        }
      }
    }
    if (field.dictionary() != null
        && spec.dictionaries().stream().noneMatch(d -> d.name().equals(field.dictionary()))) {
      errors.add("字段 " + field.name() + " 引用了未知词典 " + field.dictionary());
    }
  }

  private void validateColumn(String column, Set<String> columns, List<String> errors) {
    if (column == null || !IDENTIFIER.matcher(column).matches()) {
      errors.add("非法存储列 " + column);
      return;
    }
    if (SYSTEM_COLUMNS.contains(column)) {
      errors.add("存储列与系统列冲突：" + column);
    }
    if (column.length() > 63) {
      errors.add("存储列超过 63 字节：" + column);
    }
    if (!columns.add(column)) {
      errors.add("重复存储列 " + column);
    }
  }

  private void validateColumnType(String columnType, List<String> errors) {
    if (columnType == null) {
      errors.add("缺少 storage columnType");
      return;
    }
    if (SCALAR_TYPES.contains(columnType)) {
      return;
    }
    var matcher = VARCHAR.matcher(columnType);
    if (!matcher.matches()
        || Integer.parseInt(matcher.group(1)) < 1
        || Integer.parseInt(matcher.group(1)) > 10000) {
      errors.add("不支持的 storage columnType " + columnType);
    }
  }

  private void validateConditions(
      FormTemplateSpec spec, Set<String> fieldNames, List<String> errors) {
    for (JsonNode condition : spec.conditions()) {
      String type = condition.path("type").asText();
      if (!Set.of("conditionalRequired", "mutuallyExclusive").contains(type)) {
        errors.add("不支持的条件类型 " + type);
      }
      if (condition.hasNonNull("field")
          && !fieldNames.contains(condition.path("field").asText())) {
        errors.add("条件引用未知字段 " + condition.path("field").asText());
      }
      if (condition.hasNonNull("target")
          && !fieldNames.contains(condition.path("target").asText())) {
        errors.add("条件目标未知 " + condition.path("target").asText());
      }
      JsonNode requiredWhen = condition.path("requiredWhen");
      if (requiredWhen.hasNonNull("field")
          && !fieldNames.contains(requiredWhen.path("field").asText())) {
        errors.add("requiredWhen 引用未知字段 " + requiredWhen.path("field").asText());
      }
      JsonNode clearWhen = condition.path("clearWhen");
      if (clearWhen.hasNonNull("field")
          && !fieldNames.contains(clearWhen.path("field").asText())) {
        errors.add("clearWhen 引用未知字段 " + clearWhen.path("field").asText());
      }
      if ("mutuallyExclusive".equals(type)) {
        validateMutualCondition(spec, condition, errors);
      } else if ("conditionalRequired".equals(type)) {
        validateConditionalRequired(spec, condition, errors);
      }
    }
    for (FormFieldSpec field : spec.fields()) {
      if (field.requiredWhen() != null && !field.requiredWhen().isMissingNode()) {
        validateTrigger(
            spec, field.requiredWhen(), "字段「" + label(field) + "」的必填条件", errors);
      }
      if (field.clearWhen() != null && !field.clearWhen().isMissingNode()) {
        validateTrigger(spec, field.clearWhen(), "字段「" + label(field) + "」的清空条件", errors);
      }
    }
  }

  private void validateMutualCondition(
      FormTemplateSpec spec, JsonNode condition, List<String> errors) {
    FormFieldSpec field = spec.fieldByName(condition.path("field").asText()).orElse(null);
    if (field == null) {
      return;
    }
    if (!"enum-array".equals(field.type())) {
      errors.add("互斥条件仅支持多选字段：「" + label(field) + "」");
      return;
    }
    String whenContains = condition.path("whenContains").asText("");
    if (whenContains.isBlank()) {
      errors.add("互斥条件「" + label(field) + "」缺少触发选项");
    } else {
      requireAllowedValue(spec, field, whenContains, "互斥触发", errors);
    }
    JsonNode withAnyOf = condition.path("withAnyOf");
    if (!withAnyOf.isArray() || withAnyOf.size() == 0) {
      errors.add("互斥条件「" + label(field) + "」必须至少选择一个互斥选项");
      return;
    }
    Set<String> conflicts = new HashSet<>();
    withAnyOf.forEach(
        node -> {
          String value = node.asText("");
          if (value.isBlank() || !conflicts.add(value)) {
            errors.add("互斥条件「" + label(field) + "」含空或重复的互斥选项");
            return;
          }
          requireAllowedValue(spec, field, value, "互斥选项", errors);
        });
  }

  private void validateConditionalRequired(
      FormTemplateSpec spec, JsonNode condition, List<String> errors) {
    FormFieldSpec target = spec.fieldByName(condition.path("target").asText()).orElse(null);
    if (target == null) {
      return;
    }
    JsonNode requiredWhen = condition.path("requiredWhen");
    String snapshotColumn = condition.path("snapshotColumn").asText(null);
    if (snapshotColumn != null) {
      if (!requiredWhen.isObject() || !requiredWhen.hasNonNull("resourceSeverityLevel")) {
        errors.add(
            "条件快照列「" + snapshotColumn + "」只支持议题严重级别触发：" + label(target));
      } else {
        validateTrigger(spec, requiredWhen, "字段「" + label(target) + "」的资源必填条件", errors);
      }
      return;
    }
    if (!requiredWhen.isObject()) {
      errors.add("字段「" + label(target) + "」的条件必填缺少有效触发条件");
    } else {
      validateTrigger(spec, requiredWhen, "字段「" + label(target) + "」的必填条件", errors);
    }
    JsonNode clearWhen = condition.path("clearWhen");
    if (!clearWhen.isMissingNode()) {
      validateTrigger(spec, clearWhen, "字段「" + label(target) + "」的清空条件", errors);
    }
  }

  private void validateTrigger(
      FormTemplateSpec spec, JsonNode trigger, String context, List<String> errors) {
    if (!trigger.isObject()) {
      errors.add(context + "格式不合法");
      return;
    }
    if (trigger.hasNonNull("resourceSeverityLevel")) {
      validateSeverityTrigger(trigger, context, errors);
      return;
    }
    FormFieldSpec field = spec.fieldByName(trigger.path("field").asText("")).orElse(null);
    if (field == null) {
      return;
    }
    boolean contains = trigger.hasNonNull("contains");
    boolean notContains = trigger.hasNonNull("notContains");
    boolean equals = trigger.has("equals");
    if ((contains ? 1 : 0) + (notContains ? 1 : 0) + (equals ? 1 : 0) != 1) {
      errors.add(context + "必须且只能指定一种比较方式");
      return;
    }
    if (contains || notContains) {
      if (!"enum-array".equals(field.type())) {
        errors.add("字段「" + label(field) + "」不支持包含/不包含条件");
        return;
      }
      String value = trigger.path(contains ? "contains" : "notContains").asText("");
      if (value.isBlank()) {
        errors.add(context + "缺少比较值");
      } else {
        requireAllowedValue(spec, field, value, context + "取值", errors);
      }
      return;
    }
    if ("enum-array".equals(field.type())) {
      errors.add("多选字段「" + label(field) + "」请使用包含/不包含条件");
      return;
    }
    JsonNode value = trigger.path("equals");
    switch (field.type()) {
      case "boolean" -> {
        if (!value.isBoolean()) {
          errors.add("字段「" + label(field) + "」的条件比较值必须是是/否");
        }
      }
      case "integer" -> {
        if (!value.isIntegralNumber()
            && !(value.isTextual() && value.asText().matches("-?\\d+"))) {
          errors.add("数字字段「" + label(field) + "」的条件比较值必须是整数");
        }
      }
      case "date" -> {
        if (!value.isTextual()) {
          errors.add("日期字段「" + label(field) + "」的条件比较值必须是日期");
        } else {
          try {
            LocalDate.parse(value.asText());
          } catch (DateTimeParseException error) {
            errors.add("日期字段「" + label(field) + "」的条件比较值不是合法日期");
          }
        }
      }
      case "enum" -> {
        if (!value.isTextual() || value.asText().isBlank()) {
          errors.add("枚举字段「" + label(field) + "」的条件比较值不能为空");
        } else {
          requireAllowedValue(spec, field, value.asText(), context + "取值", errors);
        }
      }
      default -> {
        if (!value.isTextual() || value.asText().isBlank()) {
          errors.add("字段「" + label(field) + "」的条件比较值不能为空");
        }
      }
    }
  }

  private void validateSeverityTrigger(JsonNode trigger, String context, List<String> errors) {
    JsonNode expected = trigger.path("resourceSeverityLevel");
    List<String> values = new ArrayList<>();
    if (expected.isArray()) {
      expected.forEach(node -> values.add(node.asText("")));
    } else {
      values.add(expected.asText(""));
    }
    if (values.isEmpty()) {
      errors.add(context + "缺少议题严重级别");
      return;
    }
    for (String value : values) {
      if (!SEVERITY_VALUES.contains(value)) {
        errors.add(context + "的议题严重级别「" + value + "」不受支持");
      }
    }
  }

  private void requireAllowedValue(
      FormTemplateSpec spec,
      FormFieldSpec field,
      String value,
      String context,
      List<String> errors) {
    Set<String> allowed = new HashSet<>();
    if (field.options() != null) {
      field.options().forEach(option -> allowed.add(option.value()));
    }
    if (field.dictionary() != null && !field.dictionary().isBlank()) {
      spec.dictionaries().stream()
          .filter(dictionary -> field.dictionary().equals(dictionary.name()))
          .forEach(
              dictionary ->
                  dictionary.items().forEach(item -> allowed.add(item.key())));
    }
    if (!allowed.isEmpty() && !allowed.contains(value)) {
      errors.add(context + "「" + value + "」不在字段「" + label(field) + "」的选项或词典中");
    }
  }

  private String label(FormFieldSpec field) {
    return field.label() == null || field.label().isBlank() ? field.name() : field.label();
  }

  private void validateDictionaries(FormTemplateSpec spec, List<String> errors) {
    Set<String> dictionaryNames = new HashSet<>();
    for (FormDictionarySpec dictionary : spec.dictionaries()) {
      if (dictionary.name() == null || !dictionaryNames.add(dictionary.name())) {
        errors.add("词典名重复或为空 " + dictionary.name());
        continue;
      }
      if (dictionary.items() == null || dictionary.items().isEmpty()) {
        errors.add("词典 " + dictionary.name() + " 没有条目");
        continue;
      }
      Set<String> keys = new HashSet<>();
      for (FormDictionaryItem item : dictionary.items()) {
        if (item.key() == null || item.key().isBlank() || !keys.add(item.key())) {
          errors.add("词典 " + dictionary.name() + " 含空或重复键 " + item.key());
        }
      }
      String actual = FormHashSupport.canonicalDictionaryHash(objectMapper, dictionary);
      if (!actual.equalsIgnoreCase(dictionary.canonicalSha256())) {
        errors.add(
            "词典 "
                + dictionary.name()
                + " 哈希不一致：期望 "
                + dictionary.canonicalSha256()
                + "，实际 "
                + actual);
      }
    }
  }
}
