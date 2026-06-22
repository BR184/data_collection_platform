package com.data.collection.platform.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record TestingPhaseDefinitionSaveRequest(
    @NotNull Long projectId,
    Long legacySourceId,
    String legacyPhaseName,
    Integer legacySortOrder,
    @NotBlank String testingPhase,
    LocalDateTime phaseStartAt,
    LocalDateTime phaseEndAt,
    Boolean enabled,
    String remark) {}
