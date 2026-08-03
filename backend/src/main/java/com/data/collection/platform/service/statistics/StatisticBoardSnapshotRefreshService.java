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

  /** 刷新与稳定发布范围匹配的全部统计投影；任一失败由持久任务统一重试。 */
  public void refreshAfterFactBuild(FactPublicationContext context) {
    if (refreshers.isEmpty()) {
      return;
    }
    for (StatisticBoardSnapshotRefresher refresher : refreshers) {
      refresher.refreshSnapshots(context);
    }
  }
}
