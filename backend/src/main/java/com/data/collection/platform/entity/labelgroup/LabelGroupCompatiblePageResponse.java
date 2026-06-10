package com.data.collection.platform.entity.labelgroup;

public record LabelGroupCompatiblePageResponse(
    String pageKey,
    String pageName,
    String fieldKey,
    String fieldName,
    boolean mvpEnabled) {}
