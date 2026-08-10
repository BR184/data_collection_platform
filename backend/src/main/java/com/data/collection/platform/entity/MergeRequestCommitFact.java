package com.data.collection.platform.entity;

import java.time.LocalDateTime;

/** GitLab 合并请求最新 Diff 中的一条提交关系事实。 */
public record MergeRequestCommitFact(
    String sourceSystem,
    String sourceInstance,
    long projectId,
    long mergeRequestId,
    Long mergeRequestIid,
    String commitSha,
    LocalDateTime committedAtSource) {}
