package com.data.collection.platform.entity;

public record CodeReviewDgmGitlabProjectSourceSaveRequest(
    Boolean enabled,
    String gitlabBaseUrl,
    String accessToken,
    String groupPath,
    Boolean includeSubgroups,
    Boolean includeArchived,
    Integer syncIntervalMinutes) {
}
