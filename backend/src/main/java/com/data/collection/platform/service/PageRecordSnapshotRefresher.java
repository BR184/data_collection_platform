package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactPublicationContext;

public interface PageRecordSnapshotRefresher {
  /** 创建持有候选身份的独立预热读取对象。 */
  PageRecordSnapshotRefresher forRead(com.data.collection.platform.entity.read.FactReadSelection selection);
  void refreshRecordSnapshots(FactPublicationContext context);
}
