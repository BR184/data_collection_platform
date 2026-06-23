package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record TestingPhaseDefinitionResponse(
    Long id,
    Long projectId,
    String projectName,
    Long legacySourceId,
    String legacyPhaseName,
    Integer legacySortOrder,
    Long phaseGroupId,
    Integer childSortOrder,
    String testingPhase,
    LocalDateTime phaseStartAt,
    LocalDateTime phaseEndAt,
    Boolean enabled,
    String remark,
    Long issueCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
