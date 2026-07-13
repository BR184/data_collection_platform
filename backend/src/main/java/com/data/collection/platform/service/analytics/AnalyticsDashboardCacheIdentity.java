package com.data.collection.platform.service.analytics;

/** Complete identity for a dashboard snapshot; storage policy remains provider-independent. */
public record AnalyticsDashboardCacheIdentity(
    String dashboardKey,
    String ruleVersion,
    String sourceVersion,
    String scopeKey) {

  public String identityKey() {
    StringBuilder identity = new StringBuilder();
    appendPart(identity, dashboardKey);
    appendPart(identity, ruleVersion);
    appendPart(identity, sourceVersion);
    appendPart(identity, scopeKey);
    return identity.toString();
  }

  private static void appendPart(StringBuilder target, String value) {
    String normalized = value == null ? "" : value;
    target.append(normalized.length()).append(':').append(normalized).append('|');
  }
}
