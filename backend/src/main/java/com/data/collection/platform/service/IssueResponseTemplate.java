package com.data.collection.platform.service;

import java.time.LocalDateTime;

/** 最新问题调研响应模板中可展示的稳定字段。 */
record IssueResponseTemplate(
    LocalDateTime plannedResolutionAt,
    String plannedResolutionText,
    String plannedMergeVersionBranch) {

  static IssueResponseTemplate empty() {
    return new IssueResponseTemplate(null, "", "");
  }
}
