package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅负责触发 BI 独占人工走查兼容同步的薄 Spring 调度壳。 */
@Component
public class BiCodeReviewCompatibilitySyncScheduler {
  private final BiCodeReviewCompatibilitySyncService syncService;

  public BiCodeReviewCompatibilitySyncScheduler(
      CodeReviewMatchModeConfigService configService,
      CodeReviewMatchModeSwitchService switchService,
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate) {
    this.syncService =
        new BiCodeReviewCompatibilitySyncService(
            configService, switchService, jdbcTemplate, transactionTemplate);
  }

  /** 按独立周期同步；服务内部再次校验平台全局兼容模式和并发状态。 */
  @Scheduled(
      fixedDelayString = "${platform.bi.code-review-compatibility.sync-delay-ms:600000}",
      initialDelayString = "${platform.bi.code-review-compatibility.initial-delay-ms:30000}")
  public void syncScheduled() {
    syncService.syncNow();
  }
}
