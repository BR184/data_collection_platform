package com.data.collection.platform.entity;

import java.util.List;

public record QualityBoardRdDashboardResponse(
    QualityBoardRdOverviewResponse summary,
    String codeReviewSource,
    List<OptionItemResponse> codeReviewSourceOptions,
    List<QualityBoardChartRowResponse> assigneeDefectDensityRows,
    List<QualityBoardChartRowResponse> authorDefectDensityRows,
    List<QualityBoardFixUserSeverityRowResponse> fixUserSeverityRows,
    List<QualityBoardChartRowResponse> frequencyCodeSubmissionRows,
    List<QualityBoardChartRowResponse> defectRepairUserRows) {}
