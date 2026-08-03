package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.FactPublicationContext;

public interface StatisticBoardSnapshotRefresher {
  void refreshSnapshots(FactPublicationContext context);
}
