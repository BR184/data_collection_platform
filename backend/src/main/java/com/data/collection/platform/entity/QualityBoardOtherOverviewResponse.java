package com.data.collection.platform.entity;

import java.util.List;

public record QualityBoardOtherOverviewResponse(
    String projectName,
    List<QualityBoardChartRowResponse> functionDefectCountRows,
    List<QualityBoardChartRowResponse> functionDefectDensityRows,
    List<QualityBoardChartRowResponse> qualityRankingRows,
    List<QualityBoardChartRowResponse> memberUnresolvedRateRows,
    List<QualityBoardChartRowResponse> releaseLeakageRateRows,
    List<QualityBoardChartRowResponse> developmentLeakageRateRows) {}
