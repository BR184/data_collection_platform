package com.data.collection.platform.service;

public interface PageRecordSnapshotRefresher {
  void refreshRecordSnapshots(RefreshContext context);

  record RefreshContext(String factType, boolean full) {
    public boolean affectsIssues() {
      return "ISSUE".equalsIgnoreCase(factType) || "ALL".equalsIgnoreCase(factType);
    }

    public boolean affectsMergeRequests() {
      return "MERGE_REQUEST".equalsIgnoreCase(factType) || "ALL".equalsIgnoreCase(factType);
    }
  }
}
