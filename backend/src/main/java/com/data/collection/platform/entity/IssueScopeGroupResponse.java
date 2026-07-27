package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record IssueScopeGroupResponse(
    Long id,
    Long catalogId,
    Long projectId,
    String projectName,
    String dimension,
    String businessKey,
    String displayName,
    Integer sortOrder,
    Boolean enabled,
    String remark,
    Long issueCount,
    List<IssueScopeMemberResponse> members,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}

