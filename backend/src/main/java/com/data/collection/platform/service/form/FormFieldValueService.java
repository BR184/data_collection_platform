package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormPayloadTooLargeException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormDictionaryItem;
import com.data.collection.platform.domain.form.FormDictionarySpec;
import com.data.collection.platform.domain.form.FormFieldOption;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormHashSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.service.IssueResponsePlanFieldRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 按冻结 field_spec 校验并规范化三模板字段。
 *
 * <p>服务端是必填、条件、枚举、词典、整数边界、日期年份和人员权威姓名的唯一判定者；
 * 物理列值只由已校验的规格列名与参数化值组成。
 */
@Service
public class FormFieldValueService {

  public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

  private final ObjectMapper objectMapper;
  private final FormReviewerDirectoryService reviewerDirectory;

  public FormFieldValueService(
      ObjectMapper objectMapper, FormReviewerDirectoryService reviewerDirectory) {
    this.objectMapper = objectMapper;
    this.reviewerDirectory = reviewerDirectory;
  }

  /** 请求体大小超限固定 413；在任何字段解析前判定。 */
  public void requirePayloadSize(Map<String, JsonNode> fields) {
    if (fields == null || fields.isEmpty()) {
      return;
    }
    int size;
    try {
      size = objectMapper.writeValueAsBytes(fields).length;
    } catch (Exception error) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单内容无法解析");
    }
    if (size > MAX_PAYLOAD_BYTES) {
      throw new FormPayloadTooLargeException("表单内容超过1MB上限");
    }
  }

  /**
   * 校验当前记录的全部可编辑字段，并从权威人员目录取值用于首次写入。
   *
   * @param context 服务端结构上下文
   * @param input API 字段集合，键为 apiKey
   * @return 规范化列值、规范指纹节点与响应字段
   */
  public ValidatedFormValues validate(
      FormVersionContext context, Map<String, JsonNode> input) {
    return validate(context, input, true);
  }

  /**
   * 仅按冻结规格校验并生成规范字段，不读取当前人员目录。
   *
   * <p>用于同 requestKey 重放：稳定身份已由首次写入的规范指纹确定，当前目录姓名变化或人员消失
   * 不得使已有记录无法重放；新 key 仍须走 {@link #validate(FormVersionContext, Map)} 取权威姓名。
   *
   * @param context 服务端结构上下文
   * @param input API 字段集合，键为 apiKey
   * @return 不含人员姓名快照的规范字段
   */
  public ValidatedFormValues validateForReplay(
      FormVersionContext context, Map<String, JsonNode> input) {
    return validate(context, input, false);
  }

  private ValidatedFormValues validate(
      FormVersionContext context, Map<String, JsonNode> input, boolean resolvePersonNames) {
    Map<String, JsonNode> source = input == null ? Map.of() : input;
    Set<String> knownKeys = new HashSet<>();
    context.spec().fields().forEach(field -> knownKeys.add(field.apiKey()));
    List<String> errors = new ArrayList<>();
    for (String key : source.keySet()) {
      if (!knownKeys.contains(key)) {
        errors.add("未知字段：" + key);
      }
    }

    Map<String, NormalizedValue> normalized = new LinkedHashMap<>();
    for (FormFieldSpec field : context.spec().fields()) {
      normalized.put(
          field.name(),
          normalize(
              context.spec(), field, source.get(field.apiKey()), errors, resolvePersonNames));
    }

    Map<String, Boolean> snapshots =
        applyConditions(context, normalized, errors);

    if (!errors.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "字段校验失败：" + String.join("；", errors));
    }

    Map<String, Object> columnValues = new LinkedHashMap<>();
    Map<String, Object> responseFields = new LinkedHashMap<>();
    Map<String, String> fieldNames = new LinkedHashMap<>();
    ObjectNode canonicalFields = objectMapper.createObjectNode();
    for (FormFieldSpec field : context.spec().fields()) {
      NormalizedValue value = normalized.get(field.name());
      columnValues.put(field.storage().resolvedColumn(), value.columnValue());
      if (field.storage().nameColumn() != null) {
        columnValues.put(field.storage().nameColumn(), value.nameSnapshot());
        if (value.nameSnapshot() != null) {
          fieldNames.put(field.apiKey(), value.nameSnapshot());
        }
      }
      responseFields.put(field.apiKey(), value.responseValue());
      canonicalFields.set(field.apiKey(), value.canonical());
    }
    columnValues.putAll(snapshots);
    return new ValidatedFormValues(
        java.util.Collections.unmodifiableMap(columnValues),
        canonicalFields,
        java.util.Collections.unmodifiableMap(responseFields),
        java.util.Collections.unmodifiableMap(fieldNames),
        java.util.Collections.unmodifiableMap(snapshots));
  }

  /**
   * 首次提交指纹：范围、完整C、结构版本、会话账号与冻结顺序字段。
   *
   * @param context 服务端上下文
   * @param actor 会话 userId
   * @param canonicalFields 已验证的规范字段
   * @return 小写十六进制 SHA-256
   */
  public String fingerprint(
      FormVersionContext context, String actor, ObjectNode canonicalFields) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("scopeId", Long.toString(context.scopeId()));
    ObjectNode c = root.putObject("context");
    c.put("gitlabBaseUrl", context.canonicalGitlabBaseUrl());
    c.put("projectId", Long.toString(context.projectId()));
    c.put("resourceType", context.resourceType());
    c.put("resourceId", Long.toString(context.resourceId()));
    c.put("templateKey", context.spec().templateKey());
    root.put("storageVersionId", Long.toString(context.storageVersionId()));
    root.put("actor", actor);
    root.set("fields", canonicalFields);
    try {
      return FormHashSupport.sha256Hex(objectMapper.writeValueAsString(root));
    } catch (Exception error) {
      throw new IllegalStateException("Failed to canonicalize form payload", error);
    }
  }

  private Map<String, Boolean> applyConditions(
      FormVersionContext context,
      Map<String, NormalizedValue> values,
      List<String> errors) {
    Map<String, Boolean> snapshots = new LinkedHashMap<>();
    for (JsonNode condition : context.spec().conditions()) {
      String type = condition.path("type").asText();
      if ("mutuallyExclusive".equals(type)) {
        applyMutuallyExclusive(context, condition, values, errors);
      } else if ("conditionalRequired".equals(type)) {
        applyConditionalRequired(context, condition, values, snapshots, errors);
      }
    }
    for (FormFieldSpec field : context.spec().fields()) {
      NormalizedValue value = values.get(field.name());
      if (field.required() && value.empty()) {
        errors.add(label(field) + "不能为空");
      }
      if (field.requiredWhen() != null && !field.requiredWhen().isMissingNode()) {
        if (evaluate(field.requiredWhen(), context, values) && value.empty()) {
          errors.add(label(field) + "为当前条件下的必填项");
        }
      }
      if (field.clearWhen() != null && !field.clearWhen().isMissingNode()) {
        if (evaluate(field.clearWhen(), context, values) && value.columnValue() != null) {
          errors.add(label(field) + "在当前条件下必须为空");
        }
      }
    }
    return snapshots;
  }

  private void applyMutuallyExclusive(
      FormVersionContext context,
      JsonNode condition,
      Map<String, NormalizedValue> values,
      List<String> errors) {
    String field = condition.path("field").asText();
    String fieldLabel =
        context.spec().fieldByName(field).map(this::label).orElse(field);
    String whenContains = condition.path("whenContains").asText();
    Set<String> withAnyOf = new HashSet<>();
    condition.path("withAnyOf").forEach(node -> withAnyOf.add(node.asText()));
    NormalizedValue value = values.get(field);
    if (value == null || !(value.responseValue() instanceof List<?> selected)) {
      return;
    }
    boolean containsWhen =
        selected.stream().filter(String.class::isInstance).map(String.class::cast)
            .anyMatch(whenContains::equals);
    boolean containsOther =
        selected.stream().filter(String.class::isInstance).map(String.class::cast)
            .anyMatch(withAnyOf::contains);
    if (containsWhen && containsOther) {
      errors.add("字段" + fieldLabel + "的选项互斥");
    }
  }

  private void applyConditionalRequired(
      FormVersionContext context,
      JsonNode condition,
      Map<String, NormalizedValue> values,
      Map<String, Boolean> snapshots,
      List<String> errors) {
    String target = condition.path("target").asText();
    NormalizedValue targetValue = values.get(target);
    if (targetValue == null) {
      errors.add("字段条件引用了未知目标：" + target);
      return;
    }
    String targetLabel = context.spec().fieldByName(target).map(this::label).orElse(target);
    JsonNode requiredWhen = condition.path("requiredWhen");
    boolean required = !requiredWhen.isMissingNode() && evaluate(requiredWhen, context, values);
    String snapshotColumn = condition.path("snapshotColumn").asText(null);
    if (snapshotColumn != null && !snapshotColumn.isBlank()) {
      snapshots.put(snapshotColumn, required);
      if (required && targetValue.empty()) {
        errors.add("字段" + targetLabel + "在当前资源条件下不能为空");
      }
      if (!required && targetValue.columnValue() != null) {
        errors.add("字段" + targetLabel + "在当前资源条件下必须为空");
      }
      return;
    }
    if (required && targetValue.empty()) {
      errors.add("字段" + targetLabel + "为当前条件下的必填项");
    }
    JsonNode clearWhen = condition.path("clearWhen");
    if (!clearWhen.isMissingNode()
        && evaluate(clearWhen, context, values)
        && targetValue.columnValue() != null) {
      errors.add("字段" + targetLabel + "在当前条件下必须为空");
    }
  }

  private boolean evaluate(
      JsonNode condition, FormVersionContext context, Map<String, NormalizedValue> values) {
    if (condition.hasNonNull("resourceSeverityLevel")) {
      JsonNode expected = condition.path("resourceSeverityLevel");
      if (expected.isArray()) {
        for (JsonNode item : expected) {
          if (context.severityLevel() != null
              && context.severityLevel().equals(item.asText())) {
            return true;
          }
        }
        return false;
      }
      return context.severityLevel() != null
          && context.severityLevel().equals(expected.asText());
    }
    String field = condition.path("field").asText();
    NormalizedValue value = values.get(field);
    if (condition.hasNonNull("contains")) {
      return contains(value, condition.path("contains").asText());
    }
    if (condition.hasNonNull("notContains")) {
      return !contains(value, condition.path("notContains").asText());
    }
    if (condition.has("equals")) {
      return equalsValue(value, condition.path("equals"));
    }
    throw new IllegalStateException("Unsupported form condition: " + condition);
  }

  private boolean contains(NormalizedValue value, String expected) {
    if (value == null || !(value.responseValue() instanceof List<?> selected)) {
      return false;
    }
    return selected.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .anyMatch(expected::equals);
  }

  private boolean equalsValue(NormalizedValue value, JsonNode expected) {
    if (value == null || value.responseValue() == null) {
      return false;
    }
    if (expected.isBoolean()) {
      return value.responseValue() instanceof Boolean actual && actual == expected.asBoolean();
    }
    if (expected.isNumber()) {
      return value.responseValue() instanceof Integer actual
          && actual.longValue() == expected.asLong();
    }
    return value.responseValue() instanceof String actual && actual.equals(expected.asText());
  }

  private NormalizedValue normalize(
      FormTemplateSpec spec,
      FormFieldSpec field,
      JsonNode node,
      List<String> errors,
      boolean resolvePersonNames) {
    JsonNode value = node == null || node.isNull() ? null : node;
    if (field.storage().nameColumn() != null) {
      return normalizePerson(field, value, errors, resolvePersonNames);
    }
    return switch (field.type()) {
      case "integer" -> normalizeInteger(field, value, errors);
      case "boolean" -> normalizeBoolean(field, value, errors);
      case "date" -> normalizeDate(field, value, errors);
      case "enum" -> normalizeEnum(spec, field, value, errors);
      case "enum-array" -> normalizeEnumArray(spec, field, value, errors);
      default -> normalizeString(field, value, errors);
    };
  }

  private NormalizedValue normalizePerson(
      FormFieldSpec field, JsonNode node, List<String> errors, boolean resolvePersonNames) {
    NormalizedValue text = normalizeString(field, node, errors);
    if (text.columnValue() == null || text.empty()) {
      return empty();
    }
    String userId = (String) text.columnValue();
    if (!resolvePersonNames) {
      return new NormalizedValue(text.canonical(), userId, userId, null, false);
    }
    String realName = reviewerDirectory.requireRealName(userId);
    if (field.maxLength() != null && codePoints(realName) > field.maxLength()) {
      errors.add(label(field) + "的权威姓名过长");
    }
    return new NormalizedValue(
        text.canonical(), userId, userId, realName, false);
  }

  private NormalizedValue normalizeString(
      FormFieldSpec field, JsonNode node, List<String> errors) {
    if (node == null) {
      return empty();
    }
    if (!node.isTextual()) {
      errors.add(label(field) + "必须是文本");
      return empty();
    }
    String value = node.asText();
    if ("supplement_note".equals(field.name())) {
      value = value.replace("\r\n", "\n");
    }
    if ("version-members".equals(field.control())) {
      value = IssueResponsePlanFieldRules.normalizePlannedMergeBranchText(value);
      if (!value.isEmpty() && !IssueResponsePlanFieldRules.hasOnlyCanonicalVersionBranches(value)) {
        errors.add(label(field) + "必须是一个或多个 CCyyyyRn，多个以 & 分隔");
      }
    }
    if (field.maxLength() != null && codePoints(value) > field.maxLength()) {
      errors.add(label(field) + "不能超过" + field.maxLength() + "个字符");
    }
    return new NormalizedValue(
        objectMapper.getNodeFactory().textNode(value),
        value,
        value,
        null,
        value.trim().isEmpty());
  }

  private NormalizedValue normalizeInteger(
      FormFieldSpec field, JsonNode node, List<String> errors) {
    if (node == null) {
      return empty();
    }
    if (!node.isIntegralNumber()) {
      errors.add(label(field) + "必须是整数");
      return empty();
    }
    long value = node.asLong();
    if ((field.min() != null && value < field.min())
        || (field.max() != null && value > field.max())
        || value < Integer.MIN_VALUE
        || value > Integer.MAX_VALUE) {
      errors.add(
          label(field)
              + "必须在"
              + field.min()
              + "到"
              + field.max()
              + "之间");
      return empty();
    }
    int normalized = (int) value;
    return new NormalizedValue(
        objectMapper.getNodeFactory().numberNode(normalized),
        normalized,
        normalized,
        null,
        false);
  }

  private NormalizedValue normalizeBoolean(
      FormFieldSpec field, JsonNode node, List<String> errors) {
    if (node == null) {
      return empty();
    }
    if (!node.isBoolean()) {
      errors.add(label(field) + "必须是布尔值");
      return empty();
    }
    boolean value = node.asBoolean();
    return new NormalizedValue(
        objectMapper.getNodeFactory().booleanNode(value), value, value, null, false);
  }

  private NormalizedValue normalizeDate(
      FormFieldSpec field, JsonNode node, List<String> errors) {
    if (node == null) {
      return empty();
    }
    if (!node.isTextual()) {
      errors.add(label(field) + "必须是日期");
      return empty();
    }
    LocalDate date;
    try {
      date = LocalDate.parse(node.asText().trim(), DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (DateTimeParseException error) {
      errors.add(label(field) + "必须是 YYYY-MM-DD 格式的合法日期");
      return empty();
    }
    if ((field.minYear() != null && date.getYear() < field.minYear())
        || (field.maxYear() != null && date.getYear() > field.maxYear())) {
      errors.add(
          label(field)
              + "年份必须在"
              + field.minYear()
              + "到"
              + field.maxYear()
              + "之间");
      return empty();
    }
    String text = date.toString();
    return new NormalizedValue(
        objectMapper.getNodeFactory().textNode(text), text, date, null, false);
  }

  private NormalizedValue normalizeEnum(
      FormTemplateSpec spec, FormFieldSpec field, JsonNode node, List<String> errors) {
    if (node == null) {
      return empty();
    }
    if (!node.isTextual()) {
      errors.add(label(field) + "必须是单选值");
      return empty();
    }
    String value = node.asText();
    Set<String> allowed = optionValues(spec, field);
    if (!allowed.contains(value)) {
      errors.add(label(field) + "取值不在允许范围内");
      return empty();
    }
    return new NormalizedValue(
        objectMapper.getNodeFactory().textNode(value), value, value, null, false);
  }

  private NormalizedValue normalizeEnumArray(
      FormTemplateSpec spec, FormFieldSpec field, JsonNode node, List<String> errors) {
    if (node == null) {
      return empty();
    }
    if (!node.isArray()) {
      errors.add(label(field) + "必须是多选值");
      return empty();
    }
    Set<String> allowed = optionValues(spec, field);
    Set<String> selected = new LinkedHashSet<>();
    for (JsonNode item : node) {
      if (!item.isTextual()) {
        errors.add(label(field) + "包含非文本取值");
        continue;
      }
      String value = item.asText();
      if (!allowed.contains(value)) {
        errors.add(label(field) + "包含不允许的取值：" + value);
        continue;
      }
      selected.add(value);
    }
    if (field.maxItems() != null && selected.size() > field.maxItems()) {
      errors.add(label(field) + "最多选择" + field.maxItems() + "项");
    }
    List<String> ordered = semanticOrder(spec, field);
    List<String> result = ordered.stream().filter(selected::contains).toList();
    if (result.size() != selected.size()) {
      errors.add(label(field) + "包含无法排序的取值");
    }
    if (result.isEmpty()) {
      return empty();
    }
    ArrayNode canonical = objectMapper.createArrayNode();
    result.forEach(canonical::add);
    return new NormalizedValue(
        canonical, List.copyOf(result), List.copyOf(result), null, false);
  }

  private Set<String> optionValues(FormTemplateSpec spec, FormFieldSpec field) {
    Set<String> values = new LinkedHashSet<>();
    if (field.hasOptions()) {
      for (FormFieldOption option : field.options()) {
        values.add(option.value());
      }
    }
    if (field.dictionary() != null) {
      values.addAll(
          dictionaryEntries(spec, field).stream().map(FormDictionaryItem::key).toList());
    }
    return values;
  }

  private List<String> semanticOrder(FormTemplateSpec spec, FormFieldSpec field) {
    if (field.hasOptions()) {
      return field.options().stream().map(FormFieldOption::value).toList();
    }
    return dictionaryEntries(spec, field).stream().map(FormDictionaryItem::key).toList();
  }

  private List<FormDictionaryItem> dictionaryEntries(
      FormTemplateSpec spec, FormFieldSpec field) {
    if (field.dictionary() == null) {
      return List.of();
    }
    FormDictionarySpec dictionary = spec.dictionary(field.dictionary()).orElse(null);
    return dictionary == null || dictionary.items() == null ? List.of() : dictionary.items();
  }

  private static NormalizedValue empty() {
    return new NormalizedValue(
        com.fasterxml.jackson.databind.node.NullNode.getInstance(), null, null, null, true);
  }

  private int codePoints(String value) {
    return value.codePointCount(0, value.length());
  }

  private String label(FormFieldSpec field) {
    return field.label() == null || field.label().isBlank() ? field.name() : field.label();
  }

  private record NormalizedValue(
      JsonNode canonical, Object responseValue, Object columnValue, String nameSnapshot, boolean empty) {}
}
