package com.data.collection.platform.service;

import java.math.BigDecimal;
import java.util.List;

record IntegrationTestModuleFunctionExportData(
    String testingPhase,
    List<IntegrationTestFunctionExportRow> functionRows,
    List<IntegrationTestModuleExportRow> moduleRows) {}

record IntegrationTestFunctionExportRow(
    String moduleName,
    String functionName,
    int executeCase,
    int passCase,
    int notPassCase,
    int notPassCaseNow,
    int problemCase,
    int exceptionCount,
    BigDecimal passRate,
    String functionLabels) {}

record IntegrationTestModuleExportRow(
    String moduleName,
    int executeCase,
    int passCase,
    BigDecimal passRate) {}

record IntegrationTestComparisonExportData(
    String basePhase,
    String targetPhase,
    List<IntegrationTestComparisonExportRow> rows) {}

record IntegrationTestComparisonExportRow(
    String moduleName,
    String functionName,
    IntegrationTestComparisonMetric base,
    IntegrationTestComparisonMetric target,
    IntegrationTestComparisonMetric diff) {}

record IntegrationTestComparisonMetric(
    Integer executeCase,
    Integer passCase,
    Integer notPassCase,
    Integer notPassCaseNow,
    Integer problemCase,
    Integer exceptionCount,
    BigDecimal passRate,
    String functionLabels) {}
