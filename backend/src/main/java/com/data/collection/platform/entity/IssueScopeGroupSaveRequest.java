package com.data.collection.platform.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record IssueScopeGroupSaveRequest(
    @NotNull Long catalogId,
    @NotBlank String businessKey,
    @NotBlank String displayName,
    Integer sortOrder,
    Boolean enabled,
    String remark) {}

