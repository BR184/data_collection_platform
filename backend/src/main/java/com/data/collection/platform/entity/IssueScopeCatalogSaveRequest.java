package com.data.collection.platform.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record IssueScopeCatalogSaveRequest(
    @NotNull @Positive Long projectId,
    @NotBlank String projectName,
    @NotBlank String dimension,
    Boolean enabled,
    String remark) {}

