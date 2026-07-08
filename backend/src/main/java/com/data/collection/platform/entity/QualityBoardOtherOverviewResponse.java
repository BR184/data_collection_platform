package com.data.collection.platform.entity;

import java.util.List;

public record QualityBoardOtherOverviewResponse(
    QualityBoardRdOverviewResponse summary,
    List<QualityBoardChartRowResponse> assigneeDefectDensityRows,
    List<QualityBoardChartRowResponse> authorDefectDensityRows,
    List<QualityBoardFixUserSeverityRowResponse> fixUserSeverityRows,
    List<QualityBoardChartRowResponse> frequencyCodeSubmissionRows,
    List<QualityBoardChartRowResponse> defectRepairUserRows) {}
