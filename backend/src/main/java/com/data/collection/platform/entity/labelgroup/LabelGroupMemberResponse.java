package com.data.collection.platform.entity.labelgroup;

public record LabelGroupMemberResponse(
    Long id,
    String value,
    String label,
    boolean currentAvailable,
    int sortOrder) {}
