package com.data.collection.platform.entity;

/**
 * 走查均时的持久权威分区。
 *
 * <p>{@code FORM} 按有效记录计数，{@code COMMENT} 每合并请求一行；混合范围禁止把两种口径
 * 合成一个平均值，调用方应分别展示分区结果。
 */
public record CodeReviewDurationPartitionResponse(
    String authority,
    int mergeRequestCount,
    int recordCount,
    Double averageReviewDurationMinutes) {}
