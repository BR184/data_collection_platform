package com.data.collection.platform.entity;

/**
 * 当前导出范围内单个MR或总体的走查速率；行数去重、工时按走查记录累计。
 * 行数单位为LOC、工时单位为分钟；两种速率保留计算精度，Excel显示时再舍入。
 * complete为false时行数、工时和速率为空，status仅用于说明原因。
 */
public record QualityBoardCodeReviewRateSummaryRow(
    String mergeRequestKey,
    String source,
    String projectName,
    Long mergeRequestIid,
    Integer reviewCount,
    Long addedLines,
    Long reviewDurationMinutes,
    Double reviewSpeedLocPerHour,
    Double reviewSpeedKlocPerHour,
    boolean complete,
    String status) {}
