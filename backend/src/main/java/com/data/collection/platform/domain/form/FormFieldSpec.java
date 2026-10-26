package com.data.collection.platform.domain.form;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 一个稳定字段的完整发布规格。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FormFieldSpec(
    String name,
    String apiKey,
    String label,
    String type,
    String control,
    boolean required,
    Integer maxLength,
    Long min,
    Long max,
    List<FormFieldOption> options,
    String dictionary,
    Integer maxItems,
    Integer minYear,
    Integer maxYear,
    String format,
    String optionsSource,
    JsonNode requiredWhen,
    JsonNode clearWhen,
    FormStorageSpec storage) {

  public boolean hasOptions() {
    return options != null && !options.isEmpty();
  }
}
