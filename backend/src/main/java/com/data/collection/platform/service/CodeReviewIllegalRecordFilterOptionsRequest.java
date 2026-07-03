package com.data.collection.platform.service;

public record CodeReviewIllegalRecordFilterOptionsRequest(
    Long projectId, String repositoryName, String projectName, String source) {}
