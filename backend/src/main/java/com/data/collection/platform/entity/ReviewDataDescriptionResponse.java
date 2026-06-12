package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record ReviewDataDescriptionResponse(
    Long id,
    Long reviewRecordId,
    String reviewProduct,
    String reviewVersion,
    String authorName,
    Integer reviewScalePages,
    String unit,
    Integer sortOrder,
    LocalDateTime updatedAt) {}
