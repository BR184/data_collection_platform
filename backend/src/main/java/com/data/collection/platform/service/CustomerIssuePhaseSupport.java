package com.data.collection.platform.service;

import java.util.Locale;
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

  public static boolean matchesSelectedPhase(
      String milestoneTitle,
      String testingPhase,
      String selectedPhase,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    String selected = TextQuerySupport.trimToNull(selectedPhase);
    if (selected == null) {
      return true;
    }
    return matchesSingleCandidate(milestoneTitle, selected, phaseScopeResolver)
        || matchesSingleCandidate(testingPhase, selected, phaseScopeResolver);
  }

  public static boolean matchesSelectedPhase(
      String candidatePhase,
      String selectedPhase,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    String selected = TextQuerySupport.trimToNull(selectedPhase);
    if (selected == null) {
      return true;
    }
    return matchesSingleCandidate(candidatePhase, selected, phaseScopeResolver);
  }

  private static boolean matchesSingleCandidate(
      String candidatePhase,
      String selectedPhase,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    String candidate = TextQuerySupport.trimToNull(candidatePhase);
    if (candidate == null) {
      return false;
    }
    if (candidate.equalsIgnoreCase(selectedPhase)) {
      return true;
    }
    if (matchesNormalizedRelease(candidate, selectedPhase)) {
      return true;
    }
    return phaseScopeResolver != null
        && phaseScopeResolver.matchesLegacyCrownCadPhase(candidate, selectedPhase);
  }

  private static boolean matchesNormalizedRelease(String candidatePhase, String selectedPhase) {
    String candidateRelease = releaseKey(candidatePhase);
    String selectedRelease = releaseKey(selectedPhase);
    return candidateRelease != null && candidateRelease.equals(selectedRelease);
  }

  private static String releaseKey(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?:CC|CROWNCAD)?(20\\d{2})R(\\d+)")
        .matcher(normalized);
    if (!matcher.find()) {
      return null;
    }
    return matcher.group(1) + "R" + matcher.group(2);
  }
}
