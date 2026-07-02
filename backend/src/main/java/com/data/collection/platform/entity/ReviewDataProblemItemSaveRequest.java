package com.data.collection.platform.entity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReviewDataProblemItemSaveRequest(
    @NotBlank String reviewerName,
    @NotNull @Min(0) Double workloadHours,
    @NotBlank String reviewCategory,
    String documentPosition,
    @NotBlank String problemCategory,
    @NotBlank String problemDescription,
    String suggestedSolution,
    String ownerName,
    String rejectionReason,
    String problemStatus) {}
