package com.data.collection.platform.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PageRecordSnapshotRefreshService {
  private final List<PageRecordSnapshotRefresher> refreshers;

  public PageRecordSnapshotRefreshService(List<PageRecordSnapshotRefresher> refreshers) {
    this.refreshers = refreshers == null ? List.of() : List.copyOf(refreshers);
  }

  public void refreshAfterFactBuild(String factType, boolean full) {
    if (refreshers.isEmpty()) {
      return;
    }
    PageRecordSnapshotRefresher.RefreshContext context =
        new PageRecordSnapshotRefresher.RefreshContext(factType, full);
    for (PageRecordSnapshotRefresher refresher : refreshers) {
      try {
        refresher.refreshRecordSnapshots(context);
      } catch (Exception e) {
        log.warn(
            "Page record snapshot refresh failed, refresher={}, factType={}",
            refresher.getClass().getSimpleName(),
            factType,
            e);
      }
    }
  }
}
