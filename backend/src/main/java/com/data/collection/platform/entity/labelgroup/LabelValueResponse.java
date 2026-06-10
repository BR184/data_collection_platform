package com.data.collection.platform.entity.labelgroup;

import com.data.collection.platform.service.labelgroup.LabelValueKind;

public record LabelValueResponse(
    String value,
    String label,
    LabelValueKind valueKind,
    String source,
    long hitCount) {}
