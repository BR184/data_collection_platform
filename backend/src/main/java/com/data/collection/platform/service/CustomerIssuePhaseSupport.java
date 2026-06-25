package com.data.collection.platform.service;

import org.springframework.util.StringUtils;

public final class CustomerIssuePhaseSupport {
  private CustomerIssuePhaseSupport() {}

  public static String displayPhase(IssueFactRecord record) {
    if (record == null) {
      return "";
    }
    return displayPhase(record.milestoneTitle(), record.primaryPhaseLabel());
  }

  public static String displayPhase(String milestoneTitle, String fallbackPhase) {
    if (StringUtils.hasText(milestoneTitle)) {
      return milestoneTitle.trim();
    }
    return StringUtils.hasText(fallbackPhase) ? fallbackPhase.trim() : "";
  }
}
