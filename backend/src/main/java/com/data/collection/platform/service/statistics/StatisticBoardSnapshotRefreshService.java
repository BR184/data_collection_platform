package com.data.collection.platform.service.statistics;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class StatisticBoardSnapshotRefreshService {
  private final List<StatisticBoardSnapshotRefresher> refreshers;

  public StatisticBoardSnapshotRefreshService(List<StatisticBoardSnapshotRefresher> refreshers) {
    this.refreshers = refreshers == null ? List.of() : List.copyOf(refreshers);
  }

  public void refreshAfterFactBuild(String factType, boolean full) {
    if (refreshers.isEmpty()) {
      return;
    }
    StatisticBoardSnapshotRefresher.RefreshContext context =
        new StatisticBoardSnapshotRefresher.RefreshContext(factType, full);
    for (StatisticBoardSnapshotRefresher refresher : refreshers) {
      try {
        refresher.refreshSnapshots(context);
      } catch (Exception e) {
        log.warn(
            "Statistic board snapshot refresh failed, refresher={}, factType={}",
            refresher.getClass().getSimpleName(),
            factType,
            e);
      }
    }
  }
}
