package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record TestingPhaseDefinitionResponse(
    Long id,
    Long projectId,
    String projectName,
    String testingPhase,
    LocalDateTime phaseStartAt,
    LocalDateTime phaseEndAt,
    Boolean enabled,
    String remark,
    Long issueCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
