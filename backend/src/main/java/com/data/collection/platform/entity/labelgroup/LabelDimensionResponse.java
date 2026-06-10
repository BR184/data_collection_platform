package com.data.collection.platform.entity.labelgroup;

import com.data.collection.platform.service.labelgroup.LabelDimensionDefinition;
import com.data.collection.platform.service.labelgroup.LabelValueKind;

public record LabelDimensionResponse(
    String key,
    String name,
    String description,
    LabelValueKind valueKind,
    boolean staticSupported,
    boolean dynamicSupported) {
  public static LabelDimensionResponse from(LabelDimensionDefinition definition) {
    return new LabelDimensionResponse(
        definition.key(),
        definition.name(),
        definition.description(),
        definition.valueKind(),
        definition.staticSupported(),
        definition.dynamicSupported());
  }
}
