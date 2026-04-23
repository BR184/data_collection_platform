package com.data.collection.platform.entity;

import java.math.BigDecimal;

public record IntegrationTestSummaryRowResponse(
    String moduleName,
    long issueCount,
    int executeCase,
    int passCase,
    int notPassCase,
    int notPassCaseNow,
    int problemCase,
    int exceptionCount,
    BigDecimal passRate,
    long illegalCount) {}
