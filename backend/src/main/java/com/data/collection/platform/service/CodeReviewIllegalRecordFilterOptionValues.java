package com.data.collection.platform.service;

import java.util.List;

record CodeReviewIllegalRecordFilterOptionValues(
    List<CodeReviewIllegalRecordFilterProjectOption> projects,
    List<String> repositoryNames,
    List<String> targetBranches,
    List<String> owners,
    List<String> mergedBys,
    List<String> moduleNames,
    List<String> projectNames) {
  static CodeReviewIllegalRecordFilterOptionValues empty() {
    return new CodeReviewIllegalRecordFilterOptionValues(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }
}

record CodeReviewIllegalRecordFilterProjectOption(Long projectId, String projectName) {}
