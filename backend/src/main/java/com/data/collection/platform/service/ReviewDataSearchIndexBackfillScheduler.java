package com.data.collection.platform.service;

import com.data.collection.platform.config.ReviewDataProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
// 搜索索引补偿调度器用于修复历史评审数据缺失影子字段的问题。
// 默认关闭，开启后按批次补齐，避免启动时一次性重算影响正常查询。
public class ReviewDataSearchIndexBackfillScheduler {
  private final ReviewDataProperties properties;
  private final ReviewDataRecordPersistenceSupport persistenceSupport;

  public ReviewDataSearchIndexBackfillScheduler(
      ReviewDataProperties properties, ReviewDataRecordPersistenceSupport persistenceSupport) {
    this.properties = properties;
    this.persistenceSupport = persistenceSupport;
  }

  @Scheduled(fixedDelayString = "${platform.review-data.search-index-backfill-delay-ms:300000}")
  public void backfillMissingSearchIndexes() {
    if (!properties.isSearchIndexBackfillEnabled()) {
      return;
    }
    persistenceSupport.refreshMissingSearchIndexes(properties.getSearchIndexBackfillBatchSize());
  }
}
