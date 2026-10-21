package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.FactPublicationContext;

public interface StatisticBoardSnapshotRefresher {
  /** 创建持有候选身份的预热读取对象，不能调用无读取身份的服务工厂执行预热。 */
  StatisticBoardSnapshotRefresher forRead(com.data.collection.platform.entity.read.FactReadSelection selection);
  void refreshSnapshots(FactPublicationContext context);
}
