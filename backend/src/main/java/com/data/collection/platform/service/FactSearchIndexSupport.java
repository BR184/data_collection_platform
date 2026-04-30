package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestFact;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;

final class FactSearchIndexSupport {
  private static final List<String> SYSTEM_TEST_TOKENS = List.of("系统测试", "回归测试");

  private FactSearchIndexSupport() {}

  static IssueSearchIndexes buildIssueIndexes(IssueFact fact) {
    String primaryPhaseLabel =
        primaryPhaseLabel(fact.getTestingPhase(), fact.getSystemTestLabel(), fact.getLabelNames());
    String phaseFilterValue = phaseFilterValue(primaryPhaseLabel);
    TextQuerySupport.SearchIndex keywordIndex =
        TextQuerySupport.buildSearchIndex(
            join(
                Objects.toString(fact.getIssueIid(), ""),
                fact.getTitle(),
                fact.getProjectName(),
                fact.getModuleNames(),
                primaryPhaseLabel,
                phaseFilterValue,
                fact.getReasonCategory(),
                fact.getIllegalReason(),
                SystemTestIllegalReasonSupport.normalize(fact.getIllegalReason()),
                fact.getAuthorName(),
                fact.getAssigneeName(),
                fact.getBugStatus(),
                fact.getCategory(),
                fact.getMilestoneTitle()));
    return new IssueSearchIndexes(
        keywordIndex,
        TextQuerySupport.buildSearchIndex(fact.getTitle()),
        TextQuerySupport.buildSearchIndex(fact.getModuleNames()),
        TextQuerySupport.buildSearchIndex(fact.getMilestoneTitle()),
        TextQuerySupport.buildSearchIndex(fact.getAuthorName()),
        TextQuerySupport.buildSearchIndex(fact.getAssigneeName()),
        primaryPhaseLabel,
        phaseFilterValue,
        TextQuerySupport.buildSearchIndex(phaseFilterValue));
  }

  static MergeRequestSearchIndexes buildMergeRequestIndexes(MergeRequestFact fact) {
    TextQuerySupport.SearchIndex keywordIndex =
        TextQuerySupport.buildSearchIndex(
            join(
                fact.getTitle(),
                fact.getOwnerName(),
                fact.getProjectName(),
                fact.getRepositoryName(),
                fact.getModuleName(),
                fact.getTargetBranch(),
                fact.getMergeUserName()));
    return new MergeRequestSearchIndexes(
        keywordIndex, TextQuerySupport.buildSearchIndex(fact.getOwnerName()));
  }

  static List<String> keywordCandidates(String keyword) {
    String normalizedKeyword = TextQuerySupport.normalizeForMatch(keyword);
    if (normalizedKeyword == null) {
      return List.of();
    }
    TextQuerySupport.SearchIndex index = TextQuerySupport.buildSearchIndex(keyword);
    Set<String> candidates = new LinkedHashSet<>();
    candidates.add(normalizedKeyword);
    candidates.add(index.compact());
    candidates.add(index.spell());
    return candidates.stream().filter(StringUtils::hasText).toList();
  }

  private static String primaryPhaseLabel(String testingPhase, String systemTestLabel, String labelNames) {
    if (hasSystemTestScope(testingPhase)) {
      return TextQuerySupport.normalizeDisplay(testingPhase);
    }
    if (hasSystemTestScope(systemTestLabel)) {
      return TextQuerySupport.normalizeDisplay(systemTestLabel);
    }
    return splitLabels(labelNames).stream()
        .filter(FactSearchIndexSupport::hasSystemTestScope)
        .findFirst()
        .orElse("");
  }

  private static String phaseFilterValue(String primaryPhaseLabel) {
    String phase = TextQuerySupport.trimToNull(primaryPhaseLabel);
    if (phase == null) {
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

  private static boolean hasSystemTestScope(String value) {
    return StringUtils.hasText(value) && IssueRuleSupport.containsToken(value, SYSTEM_TEST_TOKENS);
  }

  private static List<String> splitLabels(String labelNames) {
    String normalized = TextQuerySupport.trimToNull(labelNames);
    if (normalized == null) {
      return List.of();
    }
    return Stream.of(normalized.split(","))
        .map(TextQuerySupport::trimToNull)
        .filter(Objects::nonNull)
        .toList();
  }

  private static String join(String... values) {
    return Stream.of(values).filter(Objects::nonNull).reduce("", (left, right) -> left + " " + right);
  }

  record IssueSearchIndexes(
      TextQuerySupport.SearchIndex keyword,
      TextQuerySupport.SearchIndex title,
      TextQuerySupport.SearchIndex module,
      TextQuerySupport.SearchIndex milestone,
      TextQuerySupport.SearchIndex author,
      TextQuerySupport.SearchIndex assignee,
      String primaryPhaseLabel,
      String phaseFilterValue,
      TextQuerySupport.SearchIndex phase) {}

  record MergeRequestSearchIndexes(
      TextQuerySupport.SearchIndex keyword, TextQuerySupport.SearchIndex owner) {}
}
