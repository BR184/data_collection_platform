package com.data.collection.platform.domain.form;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/** 一个模板的完整发布规格；字段顺序即页面与规范编码顺序。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FormTemplateSpec(
    @JsonProperty("spec_version") int specVersion,
    @JsonProperty("template_key") String templateKey,
    List<FormFieldSpec> fields,
    List<JsonNode> conditions,
    List<FormDictionarySpec> dictionaries) {

  public FormTemplateSpec {
    fields = fields == null ? List.of() : List.copyOf(fields);
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
    dictionaries = dictionaries == null ? List.of() : List.copyOf(dictionaries);
  }

  public Optional<FormFieldSpec> fieldByName(String name) {
    return fields.stream().filter(field -> field.name().equals(name)).findFirst();
  }

  public Optional<FormDictionarySpec> dictionary(String name) {
    return dictionaries.stream().filter(dictionary -> dictionary.name().equals(name)).findFirst();
  }

  public List<String> snapshotColumns() {
    return conditions.stream()
        .map(condition -> condition.path("snapshotColumn"))
        .filter(node -> node.isTextual() && !node.asText().isBlank())
        .map(JsonNode::asText)
        .toList();
  }
}
