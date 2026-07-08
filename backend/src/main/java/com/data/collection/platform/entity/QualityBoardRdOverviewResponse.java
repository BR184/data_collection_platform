package com.data.collection.platform.entity;

import java.util.List;

public record QualityBoardRdOverviewResponse(
    String projectName,
    Double demandReviewReportDensity,
    Double designReviewReportDensity,
    Double codeWalkThroughDefectDensityCc,
    Double codeWalkThroughDefectDensityDgm,
    Double integrationPassRate,
    Double defectLeakageRate,
    Double defectEliminationRate,
    Double newIssueFixRate,
    List<QualityBoardMetricResponse> metrics) {}
