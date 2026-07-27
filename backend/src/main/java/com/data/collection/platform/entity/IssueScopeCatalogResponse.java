package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record IssueScopeCatalogResponse(
    Long id,
    Long projectId,
    String projectName,
    String dimension,
    String dimensionName,
    Boolean enabled,
    String remark,
    Long groupCount,
    Long unassignedValueCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}

