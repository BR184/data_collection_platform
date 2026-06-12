package com.data.collection.platform.entity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReviewDataDescriptionSaveRequest(
    @NotBlank String reviewProduct,
    @NotBlank String reviewVersion,
    @NotBlank String authorName,
    @NotNull @Min(0) Integer reviewScalePages,
    String unit,
    Integer sortOrder) {}
