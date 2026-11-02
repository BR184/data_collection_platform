package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.service.form.FormCommonFieldService.RecordValue;
import com.data.collection.platform.service.form.FormCommonFieldService.VersionInput;
import com.data.collection.platform.service.form.FormComparableRegistrationService.FieldDeclaration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormCommonFieldServiceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final FormTemplateSpecCatalog CATALOG = new FormTemplateSpecCatalog(MAPPER);
  private static final String SPEC_STABLE_ID = "code_review.specification_count";
  private static final String SPEC_SEMANTIC_KEY = "code_review.specification_count";
  private static final String DURATION_STABLE_ID = "code_review.review_duration_minutes";
  private static final String DURATION_SEMANTIC_KEY = "code_review.review_duration_minutes";

  private final FormCommonFieldService service =
      new FormCommonFieldService(null, null, null, null, MAPPER);

  @Test
  void twoVersionsSumSameStableFieldAndVoidedRecordIsExcluded() {
    FormTemplateSpec spec = spec(FormTemplateSpecCatalog.CODE_REVIEW);
    VersionInput v1 = version(11L, spec, List.of(record(101L, 11L, false, spec, 2)));
    VersionInput v2 = version(12L, spec, List.of(record(102L, 12L, false, spec, 3)));

    FormCommonFieldService.Selection selection = service.select(List.of(v1, v2));
    assertThat(selection.status()).isEqualTo("OK");
    assertThat(selection.common())
        .extracting(FormCommonFieldService.FieldContract::semanticKey)
        .contains(SPEC_SEMANTIC_KEY);

    FormCommonFieldService.MetricResult metric =
        service.aggregate(selection, List.of(v1, v2), SPEC_SEMANTIC_KEY);
    assertThat(metric.status()).isEqualTo("OK");
    assertThat(metric.total()).isEqualTo(5L);
    assertThat(metric.contributingVersions()).containsExactly(11L, 12L);
    assertThat(metric.contributions()).extracting(FormCommonFieldService.Contribution::total)
        .containsExactly(2L, 3L);

    VersionInput voidedV2 =
        version(
            12L,
            spec,
            List.of(record(102L, 12L, true, spec, 3)));
    FormCommonFieldService.MetricResult afterVoid =
        service.aggregate(service.select(List.of(v1, voidedV2)), List.of(v1, voidedV2),
            SPEC_SEMANTIC_KEY);
    assertThat(afterVoid.total()).isEqualTo(2L);
    assertThat(afterVoid.deleted()).isEqualTo(1);
    assertThat(afterVoid.contributingVersions()).containsExactly(11L);
  }

  @Test
  void displayLabelChangeKeepsCommonIdentity() {
    FormTemplateSpec v1Spec = spec(FormTemplateSpecCatalog.CODE_REVIEW);
    FormTemplateSpec v2Spec = relabel(spec(FormTemplateSpecCatalog.CODE_REVIEW),
        "specification_count", "规范问题数（改名）");
    VersionInput v1 = version(11L, v1Spec, List.of(record(101L, 11L, false, v1Spec, 2)));
    VersionInput v2 = version(12L, v2Spec, List.of(record(102L, 12L, false, v2Spec, 3)));

    FormCommonFieldService.MetricResult metric =
        service.aggregate(service.select(List.of(v1, v2)), List.of(v1, v2), SPEC_SEMANTIC_KEY);

    assertThat(metric.total()).isEqualTo(5L);
  }

  @Test
  void newStableIdIsOnlyInSomeAndNotAggregated() {
    FormTemplateSpec v1Spec = spec(FormTemplateSpecCatalog.CODE_REVIEW);
    FormTemplateSpec v2Spec = spec(FormTemplateSpecCatalog.CODE_REVIEW);
    VersionInput v1 =
        version(
            11L,
            v1Spec,
            Map.of(
                "specification_count",
                declaration("specification_count", SPEC_STABLE_ID, SPEC_SEMANTIC_KEY,
                    "integer", "count"),
                "review_duration_minutes",
                declaration("review_duration_minutes", DURATION_STABLE_ID,
                    DURATION_SEMANTIC_KEY, "integer", "minute")),
            List.of(record(101L, 11L, false, v1Spec, 2, 10)));
    VersionInput v2 =
        version(
            12L,
            v2Spec,
            Map.of(
                "specification_count",
                declaration("specification_count", "code_review.specification_count.v2",
                    SPEC_SEMANTIC_KEY, "integer", "count"),
                "review_duration_minutes",
                declaration("review_duration_minutes", DURATION_STABLE_ID,
                    DURATION_SEMANTIC_KEY, "integer", "minute")),
            List.of(record(102L, 12L, false, v2Spec, 3, 20)));

    FormCommonFieldService.Selection selection = service.select(List.of(v1, v2));
    assertThat(selection.common())
        .extracting(FormCommonFieldService.FieldContract::semanticKey)
        .containsExactly(DURATION_SEMANTIC_KEY);
    assertThat(selection.onlyInSome())
        .extracting(FormCommonFieldService.OnlyInSomeField::stableId)
        .contains("code_review.specification_count.v2");

    FormCommonFieldService.MetricResult metric =
        service.aggregate(selection, List.of(v1, v2), SPEC_SEMANTIC_KEY);
    assertThat(metric.status()).isEqualTo("NO_COMMON_FIELD");
    assertThat(metric.total()).isNull();
  }

  @Test
  void semanticKeyChangeIsUnmergedAndNotAggregated() {
    FormTemplateSpec v1Spec = spec(FormTemplateSpecCatalog.CODE_REVIEW);
    FormTemplateSpec v2Spec = spec(FormTemplateSpecCatalog.CODE_REVIEW);
    VersionInput v1 =
        version(
            11L,
            v1Spec,
            Map.of("specification_count",
                declaration("specification_count", SPEC_STABLE_ID, SPEC_SEMANTIC_KEY,
                    "integer", "count")),
            List.of(record(101L, 11L, false, v1Spec, 2)));
    VersionInput v2 =
        version(
            12L,
            v2Spec,
            Map.of("specification_count",
                declaration("specification_count", SPEC_STABLE_ID,
                    SPEC_SEMANTIC_KEY + ".changed", "integer", "count")),
            List.of(record(102L, 12L, false, v2Spec, 3)));

    FormCommonFieldService.Selection selection = service.select(List.of(v1, v2));
    assertThat(selection.status()).isEqualTo("NO_COMMON_FIELDS");
    assertThat(selection.unmerged())
        .singleElement()
        .satisfies(field -> {
          assertThat(field.reason()).isEqualTo("identity_or_semantic_changed");
          assertThat(field.variants()).hasSize(2);
        });
    assertThat(service.aggregate(selection, List.of(v1, v2), SPEC_SEMANTIC_KEY).total())
        .isNull();
  }

  @Test
  void enumOptionChangeIsUnmergedAndNotAggregated() {
    FormTemplateSpec v1Spec = spec(FormTemplateSpecCatalog.ISSUE_REPLY);
    FormTemplateSpec v2Spec = changeEnumOptions(spec(FormTemplateSpecCatalog.ISSUE_REPLY),
        "affected_kinds", "KNOWN_AFFECTED");
    VersionInput v1 =
        version(
            21L,
            v1Spec,
            Map.of("affected_kinds",
                declaration("affected_kinds", "issue_reply.affected_kinds",
                    "issue_reply.affected_kinds", "enum-array", null)),
            List.of(recordWithField(201L, 21L, v1Spec, "affected_kinds", List.of("KNOWN"))));
    VersionInput v2 =
        version(
            22L,
            v2Spec,
            Map.of("affected_kinds",
                declaration("affected_kinds", "issue_reply.affected_kinds",
                    "issue_reply.affected_kinds", "enum-array", null)),
            List.of(recordWithField(202L, 22L, v2Spec, "affected_kinds", List.of("KNOWN_AFFECTED"))));

    FormCommonFieldService.Selection selection = service.select(List.of(v1, v2));

    assertThat(selection.unmerged())
        .singleElement()
        .extracting(FormCommonFieldService.UnmergedField::reason)
        .isEqualTo("identity_or_semantic_changed");
    assertThat(service.aggregate(selection, List.of(v1, v2), "issue_reply.affected_kinds").total())
        .isNull();
  }

  @Test
  void noCommonFieldsReturnsExplicitStatus() {
    FormTemplateSpec review = spec(FormTemplateSpecCatalog.CODE_REVIEW);
    FormTemplateSpec reply = spec(FormTemplateSpecCatalog.ISSUE_REPLY);
    VersionInput v1 =
        version(
            11L,
            review,
            Map.of("specification_count",
                declaration("specification_count", SPEC_STABLE_ID, SPEC_SEMANTIC_KEY,
                    "integer", "count")),
            List.of(record(101L, 11L, false, review, 2)));
    VersionInput v2 =
        version(
            21L,
            reply,
            Map.of("affected_kinds",
                declaration("affected_kinds", "issue_reply.affected_kinds",
                    "issue_reply.affected_kinds", "enum-array", null)),
            List.of(recordWithField(201L, 21L, reply, "affected_kinds", List.of("KNOWN"))));

    FormCommonFieldService.Selection selection = service.select(List.of(v1, v2));

    assertThat(selection.status()).isEqualTo("NO_COMMON_FIELDS");
    assertThat(selection.common()).isEmpty();
    FormCommonFieldService.MetricResult metric =
        service.aggregate(selection, List.of(v1, v2), SPEC_SEMANTIC_KEY);
    assertThat(metric.status()).isEqualTo("NO_COMMON_FIELDS");
    assertThat(metric.total()).isNull();
  }

  @Test
  void declaredTypeConflictIsExplicitInvalidContract() {
    FormTemplateSpec v1Spec = spec(FormTemplateSpecCatalog.CODE_REVIEW);
    FormTemplateSpec v2Spec = changeType(spec(FormTemplateSpecCatalog.CODE_REVIEW),
        "specification_count", "string");
    VersionInput v1 =
        version(
            11L,
            v1Spec,
            Map.of("specification_count",
                declaration("specification_count", SPEC_STABLE_ID, SPEC_SEMANTIC_KEY,
                    "integer", "count")),
            List.of(record(101L, 11L, false, v1Spec, 2)));
    VersionInput v2 =
        version(
            12L,
            v2Spec,
            Map.of("specification_count",
                declaration("specification_count", SPEC_STABLE_ID, SPEC_SEMANTIC_KEY,
                    "integer", "count")),
            List.of(record(102L, 12L, false, v2Spec, 3)));

    FormCommonFieldService.Selection selection = service.select(List.of(v1, v2));

    assertThat(selection.invalidContracts())
        .anySatisfy(invalid -> {
          assertThat(invalid.invalidReason()).isEqualTo("TYPE_DECLARATION_CONFLICT");
          assertThat(invalid.declaredType()).isEqualTo("integer");
          assertThat(invalid.actualType()).isEqualTo("string");
        });
    assertThat(selection.common()).isEmpty();
    FormCommonFieldService.MetricResult metric =
        service.aggregate(selection, List.of(v1, v2), SPEC_SEMANTIC_KEY);
    assertThat(metric.status()).isEqualTo("NO_COMMON_FIELDS");
    assertThat(metric.total()).isNull();
  }

  @Test
  void nonIntegerMetricIsUnsupportedAggregation() {
    FormTemplateSpec spec = spec(FormTemplateSpecCatalog.ISSUE_RESPONSE);
    VersionInput version =
        version(
            31L,
            spec,
            Map.of("modification_plan",
                declaration("modification_plan", "issue.modification_plan",
                    "issue.modification_plan", "string", "text")),
            List.of(recordWithField(301L, 31L, spec, "modification_plan", "方案")));

    FormCommonFieldService.Selection selection = service.select(List.of(version));
    FormCommonFieldService.MetricResult metric =
        service.aggregate(selection, List.of(version), "issue.modification_plan");

    assertThat(metric.status()).isEqualTo("UNSUPPORTED_AGGREGATION");
    assertThat(metric.total()).isNull();
  }

  private VersionInput version(long versionId, FormTemplateSpec spec, List<RecordValue> records) {
    return version(versionId, spec, defaultDeclarations(), records);
  }

  private VersionInput version(
      long versionId,
      FormTemplateSpec spec,
      Map<String, FieldDeclaration> declarations,
      List<RecordValue> records) {
    return new VersionInput(
        versionId, 1L, spec.templateKey(), spec, declarations, records);
  }

  private Map<String, FieldDeclaration> defaultDeclarations() {
    return Map.of("specification_count",
        declaration("specification_count", SPEC_STABLE_ID, SPEC_SEMANTIC_KEY,
            "integer", "count"));
  }

  private FieldDeclaration declaration(
      String fieldName, String stableId, String semanticKey, String type, String unit) {
    return new FieldDeclaration(fieldName, stableId, semanticKey, type, unit, semanticKey);
  }

  private RecordValue record(
      long recordId, long versionId, boolean deleted, FormTemplateSpec spec, int specification) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(column(spec, "specification_count"), specification);
    return new RecordValue(recordId, versionId, deleted, fields);
  }

  private RecordValue record(
      long recordId,
      long versionId,
      boolean deleted,
      FormTemplateSpec spec,
      int specification,
      int duration) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(column(spec, "specification_count"), specification);
    fields.put(column(spec, "review_duration_minutes"), duration);
    return new RecordValue(recordId, versionId, deleted, fields);
  }

  private RecordValue recordWithField(
      long recordId, long versionId, FormTemplateSpec spec, String fieldName, Object value) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(column(spec, fieldName), value);
    return new RecordValue(recordId, versionId, false, fields);
  }

  private String column(FormTemplateSpec spec, String fieldName) {
    FormFieldSpec field = spec.fieldByName(fieldName).orElseThrow();
    return field.storage().resolvedColumn();
  }

  private FormTemplateSpec spec(String templateKey) {
    try {
      return MAPPER.readValue(CATALOG.rawJson(templateKey), FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private FormTemplateSpec relabel(FormTemplateSpec spec, String fieldName, String label) {
    ObjectNode root = MAPPER.valueToTree(spec);
    for (var node : root.withArray("fields")) {
      if (fieldName.equals(node.path("name").asText())) {
        ((ObjectNode) node).put("label", label);
      }
    }
    return MAPPER.convertValue(root, FormTemplateSpec.class);
  }

  private FormTemplateSpec changeType(FormTemplateSpec spec, String fieldName, String type) {
    ObjectNode root = MAPPER.valueToTree(spec);
    for (var node : root.withArray("fields")) {
      if (fieldName.equals(node.path("name").asText())) {
        ((ObjectNode) node).put("type", type);
      }
    }
    return MAPPER.convertValue(root, FormTemplateSpec.class);
  }

  private FormTemplateSpec changeEnumOptions(
      FormTemplateSpec spec, String fieldName, String firstValue) {
    ObjectNode root = MAPPER.valueToTree(spec);
    for (var node : root.withArray("fields")) {
      if (fieldName.equals(node.path("name").asText())) {
        ArrayNode options = ((ObjectNode) node).putArray("options");
        ObjectNode option = options.addObject();
        option.put("value", firstValue);
        option.put("label", firstValue);
        option = options.addObject();
        option.put("value", "NEW");
        option.put("label", "NEW");
      }
    }
    return MAPPER.convertValue(root, FormTemplateSpec.class);
  }
}
