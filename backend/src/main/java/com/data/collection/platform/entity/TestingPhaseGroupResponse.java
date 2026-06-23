package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record TestingPhaseGroupResponse(
    Long id,
    Long projectId,
    String name,
    Integer sortOrder,
    Boolean enabled,
    String remark,
    Long issueCount,
    List<TestingPhaseDefinitionResponse> children,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
