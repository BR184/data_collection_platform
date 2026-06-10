package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelValuePageResponse(
    List<LabelValueResponse> items,
    long total,
    int page,
    int size) {}
