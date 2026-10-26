package com.data.collection.platform.domain.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormConditionContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final FormSpecValidator validator = new FormSpecValidator(objectMapper);
  private final FormVersionSchemaService schemaService =
      new FormVersionSchemaService(objectMapper);

  private static final String SPEC =
      """
      {
        "spec_version": 1,
        "template_key": "issue_reply",
        "fields": [
          {"name":"status","apiKey":"status","label":"状态","type":"enum","control":"radio",
           "required":true,"options":[{"value":"A","label":"甲"},{"value":"B","label":"乙"}],
           "storage":{"column":"status","columnType":"varchar(32)"}},
          {"name":"tags","apiKey":"tags","label":"多选","type":"enum-array","control":"checkbox",
           "required":false,"options":[{"value":"A","label":"甲"},{"value":"B","label":"乙"},{"value":"C","label":"丙"}],
           "storage":{"column":"tags","columnType":"text[]"}},
          {"name":"note","apiKey":"note","label":"说明","type":"string","control":"textarea",
           "required":false,"maxLength":100,"storage":{"column":"note","columnType":"text"}},
          {"name":"count","apiKey":"count","label":"数量","type":"integer","control":"number",
           "required":false,"min":0,"max":100,"storage":{"column":"count","columnType":"integer"}},
          {"name":"flag","apiKey":"flag","label":"开关","type":"boolean","control":"switch",
           "required":false,"storage":{"column":"flag","columnType":"boolean"}},
          {"name":"due","apiKey":"due","label":"日期","type":"date","control":"date",
           "required":false,"minYear":2020,"maxYear":2030,"storage":{"column":"due","columnType":"date"}},
          {"name":"owner","apiKey":"owner","label":"人员","type":"string","control":"person-select",
           "required":false,"storage":{"idColumn":"owner_id","nameColumn":"owner_name","columnType":"varchar(128)"}}
        ],
        "conditions": [
          {"type":"mutuallyExclusive","field":"tags","whenContains":"A","withAnyOf":["B"]},
          {"type":"conditionalRequired","target":"note","requiredWhen":{"field":"status","equals":"A"}},
          {"type":"conditionalRequired","target":"count","requiredWhen":{"field":"flag","equals":true}},
          {"type":"conditionalRequired","target":"flag","requiredWhen":{"field":"status","equals":"A"}},
          {"type":"conditionalRequired","target":"due","requiredWhen":{"field":"status","equals":"A"}},
          {"type":"conditionalRequired","target":"owner","requiredWhen":{"field":"status","equals":"A"}},
          {"type":"conditionalRequired","target":"tags","requiredWhen":{"field":"flag","equals":true}},
          {"type":"conditionalRequired","target":"status","requiredWhen":{"field":"flag","equals":true}}
        ],
        "dictionaries": []
      }
      """;

  @Test
  void generatesTypeAwareConstraintsAndFrozenMutualSet() {
    FormTemplateSpec spec = validator.parse(SPEC);
    validator.validate(spec);

    FormVersionSchema schema = schemaService.generate(1, 2, spec, false);
    String ddl = schema.statements().get(1);

    assertThat(ddl).contains("not ('A' = any(tags) and ('B' = any(tags)))");
    assertThat(ddl).contains("btrim(note) <> ''");
    assertThat(ddl).contains("count is not null").doesNotContain("btrim(count)");
    assertThat(ddl).contains("flag is not null").doesNotContain("btrim(flag)");
    assertThat(ddl).contains("due is not null").doesNotContain("btrim(due)");
    assertThat(ddl).contains("btrim(owner_id) <> ''");
    assertThat(ddl).contains("cardinality(tags) > 0");
    assertThat(ddl).contains("btrim(status) <> ''");
  }

  @Test
  void rejectsUnsupportedConditionCombinationsBeforePublish() {
    ObjectNode mutualOnEnum = specNode();
    ((ObjectNode) mutualOnEnum.get("conditions").get(0)).put("field", "status");
    assertThat(validationErrors(mutualOnEnum)).anyMatch(error -> error.contains("互斥条件仅支持多选"));

    ObjectNode containsOnText = specNode();
    ObjectNode noteCondition = (ObjectNode) containsOnText.get("conditions").get(1);
    noteCondition.remove("requiredWhen");
    noteCondition.putObject("requiredWhen").put("field", "note").put("contains", "x");
    assertThat(validationErrors(containsOnText))
        .anyMatch(error -> error.contains("不支持包含/不包含"));

    ObjectNode equalsOnArray = specNode();
    ObjectNode tagsCondition = (ObjectNode) equalsOnArray.get("conditions").get(2);
    tagsCondition.remove("requiredWhen");
    tagsCondition.putObject("requiredWhen").put("field", "tags").put("equals", "A");
    assertThat(validationErrors(equalsOnArray))
        .anyMatch(error -> error.contains("请使用包含/不包含"));

    ObjectNode badInteger = specNode();
    ObjectNode countCondition = (ObjectNode) badInteger.get("conditions").get(2);
    countCondition.remove("requiredWhen");
    countCondition.putObject("requiredWhen").put("field", "status").put("equals", "A");
    ((ObjectNode) countCondition.get("requiredWhen")).put("field", "count");
    ((ObjectNode) countCondition.get("requiredWhen")).put("equals", "abc");
    assertThat(validationErrors(badInteger))
        .anyMatch(error -> error.contains("条件比较值必须是整数"));

    ObjectNode invalidOption = specNode();
    ObjectNode invalid = (ObjectNode) invalidOption.get("conditions").get(7);
    invalid.remove("requiredWhen");
    invalid.putObject("requiredWhen").put("field", "status").put("equals", "Z");
    assertThat(validationErrors(invalidOption))
        .anyMatch(error -> error.contains("不在字段") && error.contains("选项或词典中"));
  }

  private ObjectNode specNode() {
    try {
      return (ObjectNode) objectMapper.readTree(SPEC);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private List<String> validationErrors(ObjectNode node) {
    return validator.validationErrors(validator.parse(node.toString()));
  }
}
