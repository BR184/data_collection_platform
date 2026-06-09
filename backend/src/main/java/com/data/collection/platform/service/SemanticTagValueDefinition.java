package com.data.collection.platform.service;

public record SemanticTagValueDefinition(
    String valueKey,
    String label,
    String valueType,
    String canonicalValue,
    boolean enabled,
    int sortOrder) {}
