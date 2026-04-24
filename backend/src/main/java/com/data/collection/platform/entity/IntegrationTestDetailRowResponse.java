package com.data.collection.platform.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record IntegrationTestDetailRowResponse(
    Long issueId,
    Long issueIid,
    String issuableReference,
    Long projectId,
    String projectName,
    String title,
    String moduleName,
    String functionName,
    String functionLabels,
    String executor,
    Integer executeCase,
    Integer passCase,
    Integer notPassCase,
    Integer notPassCaseNow,
    Integer problemCase,
    Integer exceptionCount,
    BigDecimal passRate,
    Boolean legal,
    String parseStatus,
    String validationReason,
    String issueState,
    String authorName,
    String assigneeName,
    LocalDateTime noteUpdatedAt,
    LocalDateTime updatedAtSource) {}
