package com.data.collection.platform.service;

import com.data.collection.platform.config.ReviewDataProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
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
