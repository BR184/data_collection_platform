package com.data.collection.platform.entity.labelgroup;

public record LabelGroupRuleSortRequest(
    String sourceKey,
    String fieldKey,
    String aggregateKey,
    String direction) {}
