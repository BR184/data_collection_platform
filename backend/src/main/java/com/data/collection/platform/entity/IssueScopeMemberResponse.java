package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record IssueScopeMemberResponse(
    Long id,
    Long catalogId,
    Long groupId,
    String sourceValue,
    String displayName,
    Integer sortOrder,
    LocalDateTime activeFrom,
    LocalDateTime activeUntil,
    Boolean enabled,
    Long sourceReferenceId,
    String remark,
    Long issueCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}

