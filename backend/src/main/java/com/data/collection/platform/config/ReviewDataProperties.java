package com.data.collection.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.review-data")
public class ReviewDataProperties {
  private boolean searchIndexBackfillEnabled = false;
  private int searchIndexBackfillDelayMs = 300000;
  private int searchIndexBackfillBatchSize = 200;

  public boolean isSearchIndexBackfillEnabled() {
    return searchIndexBackfillEnabled;
  }

  public void setSearchIndexBackfillEnabled(boolean searchIndexBackfillEnabled) {
    this.searchIndexBackfillEnabled = searchIndexBackfillEnabled;
  }

  public int getSearchIndexBackfillDelayMs() {
    return searchIndexBackfillDelayMs;
  }

  public void setSearchIndexBackfillDelayMs(int searchIndexBackfillDelayMs) {
    this.searchIndexBackfillDelayMs = searchIndexBackfillDelayMs;
  }

  public int getSearchIndexBackfillBatchSize() {
    return searchIndexBackfillBatchSize;
  }

  public void setSearchIndexBackfillBatchSize(int searchIndexBackfillBatchSize) {
    this.searchIndexBackfillBatchSize = searchIndexBackfillBatchSize;
  }
}
