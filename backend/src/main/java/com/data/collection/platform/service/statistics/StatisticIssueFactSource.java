package com.data.collection.platform.service.statistics;

import com.data.collection.platform.service.IssueFactRecord;
import com.data.collection.platform.service.IssueScopeContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

public record StatisticIssueFactSource(IssueFactRecord record) {
  public Long id() {
    return record.issueId();
  }

  public Integer iid() {
    return record.issueIid();
  }

  public String sourceInstance() {
    return record.sourceInstance();
  }

  public String title() {
    return record.title();
  }

  public Long projectId() {
    return record.projectId();
  }

  public String projectName() {
    return record.projectName();
  }

  public String milestoneTitle() {
    return record.milestoneTitle();
  }

  public String authorName() {
    return record.authorName();
  }

  public LocalDateTime createdAt() {
    return record.createdAt();
  }

  public LocalDateTime updatedAt() {
    return record.updatedAt();
  }

  public LocalDateTime closedAt() {
    return record.closedAt();
  }

  public String issueState() {
    return record.issueState();
  }

  public String testingPhase() {
    return record.testingPhase();
  }

  public String systemTestLabel() {
    return record.systemTestLabel();
  }

  public String severityLevel() {
    return record.severityLevel();
  }

  public String priorityLevel() {
    return record.priorityLevel();
  }

  public String bugStatus() {
    return record.bugStatus();
  }

  public String category() {
    return record.category();
  }

  public String reasonCategory() {
    return record.reasonCategory();
  }

  public String delayCause() {
    return record.delayCause();
  }

  public String delayReason() {
    return record.delayReason();
  }

  public String assigneeName() {
    return record.assigneeName();
  }

  public String fixUser() {
    return record.fixUser();
  }

  public String functionName() {
    return record.functionName();
  }

  public boolean excluded() {
    return record.excluded();
  }

  public String exclusionReason() {
    return record.exclusionReason();
  }

  public boolean fixed() {
    return record.fixed();
  }

  public boolean delayIssue() {
    return record.delayIssue();
  }

  public boolean regression() {
    return record.regression();
  }

  public boolean crash() {
    return record.crash();
  }

  public boolean level1Other() {
    return record.level1Other();
  }

  public boolean legacy() {
    return record.legacy();
  }

  public List<String> moduleNames() {
    return record.moduleNames();
  }

  public List<String> labels() {
    return record.labels();
  }

  public IssueScopeContext scopeContext() {
    return record.scopeContext();
  }

  public boolean inSystemTestScope() {
    return StringUtils.hasText(record.primaryPhaseLabel());
  }

  public String phaseFilterValue() {
    return record.phaseFilterValue();
  }

  public boolean hasReasonCategory() {
    return StringUtils.hasText(reasonCategory());
  }

  public boolean isClosed() {
    return closedAt() != null || "closed".equalsIgnoreCase(issueState());
  }

  public boolean isPriority(String priority) {
    return priority.equalsIgnoreCase(priorityLevel());
  }

  public boolean isSeverity(String severity) {
    return severity.equalsIgnoreCase(severityLevel());
  }

  public boolean isLevel1() {
    return isSeverity("LEVEL1");
  }

  public boolean isLevel1Back() {
    return isLevel1() && regression();
  }

  public boolean isLevel1Hang() {
    return isLevel1() && crash();
  }

  public boolean isLevel1Other() {
    return isLevel1() && level1Other();
  }

  public boolean isLevel2() {
    return isSeverity("LEVEL2");
  }

  public boolean isLevel3() {
    return isSeverity("LEVEL3");
  }

  public boolean isSuggestion() {
    return isSeverity("SUGGESTION");
  }

  public boolean isNewIssue() {
    return !legacy();
  }

  public boolean isSolvedLike() {
    return fixed() || isClosed();
  }

  public boolean isClosedResolved() {
    return fixed() && isClosed();
  }

  public boolean hasExtensionLabel() {
    return labels().contains("申请延期");
  }

  public boolean isRetestFailed() {
    // 老平台逻辑：bug_status LIKE '%未修复%'
    // bug_status 在新平台对应 bugStatus() 字段
    String status = bugStatus();
    return status != null && status.contains("未修复");
  }

  public boolean isOtherReason(Set<String> knownReasonCategories) {
    return hasReasonCategory() && !knownReasonCategories.contains(reasonCategory());
  }
}
