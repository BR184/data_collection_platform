package com.data.collection.platform.service.labelgroup;

public record LabelDimensionDefinition(
    String key,
    String name,
    String description,
    LabelValueKind valueKind,
    boolean staticSupported,
    boolean dynamicSupported) {}
