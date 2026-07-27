package com.data.collection.platform.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record IssueScopeMemberSaveRequest(
    @NotNull Long catalogId,
    @NotNull Long groupId,
    @NotBlank String sourceValue,
    @NotBlank String displayName,
    Integer sortOrder,
    LocalDateTime activeFrom,
    LocalDateTime activeUntil,
    Boolean enabled,
    Long sourceReferenceId,
    String remark) {}

