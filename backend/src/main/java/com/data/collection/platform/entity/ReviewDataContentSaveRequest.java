package com.data.collection.platform.entity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ReviewDataContentSaveRequest(
    @NotBlank String reviewerName,
    String assignmentContent,
    @Min(0) Double independentWorkloadHours,
    @Min(0) Integer independentProblemCount,
    @Min(0) Double meetingWorkloadHours,
    @Min(0) Integer meetingProblemCount,
    Integer sortOrder) {}
