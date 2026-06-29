package com.data.collection.platform.service.statistics;

public interface StatisticBoardSnapshotRefresher {
  void refreshSnapshots(RefreshContext context);

  record RefreshContext(String factType, boolean full) {
    boolean affectsIssues() {
      return "ISSUE".equalsIgnoreCase(factType);
    }

    boolean affectsMergeRequests() {
      return "MERGE_REQUEST".equalsIgnoreCase(factType);
    }
  }
}
