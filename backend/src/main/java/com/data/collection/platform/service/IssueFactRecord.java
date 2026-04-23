package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.util.StringUtils;

public record IssueFactRecord(
    Long projectId,
    String projectName,
    Long issueId,
    Integer issueIid,
    String title,
    String issueState,
    String testingPhase,
    String systemTestLabel,
    String severityLevel,
    String priorityLevel,
    String bugStatus,
    String category,
    String reasonCategory,
    String milestoneTitle,
    String authorName,
    String assigneeName,
    List<String> moduleNames,
    List<String> labels,
    boolean delayIssue,
    String delayReason,
    String delayCause,
    boolean responseDelayed,
    boolean resolveDelayed,
    boolean illegal,
    String illegalReason,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime closedAt) {

  private static final List<String> SYSTEM_TEST_TOKENS = List.of("系统测试", "回归测试");

  public IssueFactRecord {
    moduleNames = moduleNames == null ? List.of() : List.copyOf(moduleNames);
    labels = labels == null ? List.of() : List.copyOf(labels);
  }

  public IssueScopeContext scopeContext() {
    return new IssueScopeContext(
        projectId, projectName, milestoneTitle, testingPhase, systemTestLabel, createdAt, labels);
  }

  public boolean delayRelated() {
    return delayIssue || responseDelayed || resolveDelayed;
  }

  public String primaryPhaseLabel() {
    if (hasSystemTestScope(testingPhase)) {
      return testingPhase;
    }
    if (hasSystemTestScope(systemTestLabel)) {
      return systemTestLabel;
    }
    return labels.stream().filter(this::hasSystemTestScope).findFirst().orElse("");
  }

  public String phaseFilterValue() {
    String phase = primaryPhaseLabel();
    if (!StringUtils.hasText(phase)) {
      return "";
    }
    int systemTestIndex = phase.indexOf("系统测试");
    int regressionIndex = phase.indexOf("回归测试");
    int cutIndex = systemTestIndex >= 0 ? systemTestIndex : regressionIndex;
    if (cutIndex > 0) {
      return phase.substring(0, cutIndex).trim();
    }
    return phase.trim();
  }

  private boolean hasSystemTestScope(String value) {
    return StringUtils.hasText(value) && IssueRuleSupport.containsToken(value, SYSTEM_TEST_TOKENS);
  }
}
