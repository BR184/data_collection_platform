package com.data.collection.platform.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TestingPhaseGroupSaveRequest(
    @NotNull Long projectId,
    @NotBlank String name,
    Integer sortOrder,
    Boolean enabled,
    String remark) {}
