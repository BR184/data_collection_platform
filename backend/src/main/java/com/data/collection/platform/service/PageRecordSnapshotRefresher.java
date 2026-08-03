package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactPublicationContext;

public interface PageRecordSnapshotRefresher {
  void refreshRecordSnapshots(FactPublicationContext context);
}
