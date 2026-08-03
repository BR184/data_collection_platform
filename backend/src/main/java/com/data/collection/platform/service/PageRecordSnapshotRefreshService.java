package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactPublicationContext;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PageRecordSnapshotRefreshService {
  private final List<PageRecordSnapshotRefresher> refreshers;

  public PageRecordSnapshotRefreshService(List<PageRecordSnapshotRefresher> refreshers) {
    this.refreshers = refreshers == null ? List.of() : List.copyOf(refreshers);
  }

  /** 刷新与稳定发布范围匹配的全部记录投影；任一失败由持久任务统一重试。 */
  public void refreshAfterFactBuild(FactPublicationContext context) {
    if (refreshers.isEmpty()) {
      return;
    }
    for (PageRecordSnapshotRefresher refresher : refreshers) {
      refresher.refreshRecordSnapshots(context);
    }
  }
}
