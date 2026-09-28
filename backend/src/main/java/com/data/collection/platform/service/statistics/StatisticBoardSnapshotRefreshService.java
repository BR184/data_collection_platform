package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.FactPublicationContext;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StatisticBoardSnapshotRefreshService {
  private final List<StatisticBoardSnapshotRefresher> refreshers;

  public StatisticBoardSnapshotRefreshService(List<StatisticBoardSnapshotRefresher> refreshers) {
    this.refreshers = refreshers == null ? List.of() : List.copyOf(refreshers);
  }

  /** 刷新与稳定发布范围匹配的统计投影；在每个刷新器边界确认并续租执行身份。 */
  public void refreshAfterFactBuild(FactPublicationContext context, Runnable requireLease) {
    if (refreshers.isEmpty()) {
      return;
    }
    for (StatisticBoardSnapshotRefresher refresher : refreshers) {
      requireLease.run();
      refresher.refreshSnapshots(context);
      requireLease.run();
    }
  }
}
