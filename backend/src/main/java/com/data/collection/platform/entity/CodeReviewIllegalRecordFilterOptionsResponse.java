package com.data.collection.platform.entity;

import java.util.List;

public record CodeReviewIllegalRecordFilterOptionsResponse(
    List<OptionItemResponse> requestTypes,
    List<OptionItemResponse> projects,
    List<OptionItemResponse> repositoryNames,
    List<OptionItemResponse> illegalTypes,
    List<OptionItemResponse> targetBranches,
    List<OptionItemResponse> owners,
    List<OptionItemResponse> mergedBys,
    List<OptionItemResponse> moduleNames,
    List<OptionItemResponse> projectNames) {
}
