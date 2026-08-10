package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.GitlabConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原子捕获页面发布水位并提交同步运行终态。 */
@Service
public class SyncRunCompletionCommitService {
  private final SyncRunPublicationFenceService publicationFenceService;
  private final SyncRunLeaseService leaseService;
  private final GitlabConfigService configService;
  private final SyncIncrementalRerunService incrementalRerunService;
  private final SyncSourceSubmissionLockService sourceSubmissionLockService;

  public SyncRunCompletionCommitService(
      SyncRunPublicationFenceService publicationFenceService,
      SyncRunLeaseService leaseService,
      GitlabConfigService configService,
      SyncIncrementalRerunService incrementalRerunService,
      SyncSourceSubmissionLockService sourceSubmissionLockService) {
    this.publicationFenceService = publicationFenceService;
    this.leaseService = leaseService;
    this.configService = configService;
    this.incrementalRerunService = incrementalRerunService;
    this.sourceSubmissionLockService = sourceSubmissionLockService;
  }

  /**
   * 在同一运行行锁事务中捕获全部已登记工作区栅栏并释放运行租约。
   *
   * @throws SyncRunLeaseLostException 运行已过期或被其他 worker 重新领取
   */
  @Transactional
  public void finishOwnedRun(SyncRun run) {
    if (run != null && run.getRunType() == SyncRunType.INCREMENTAL_SYNC) {
      sourceSubmissionLockService.lock(run.getConfigId(), run.getSourceInstance());
    }
    publicationFenceService.captureOwnedRun(run);
    if (leaseService.finishOwnedRun(run) != 1) {
      throw new SyncRunLeaseLostException(run == null ? null : run.getId());
    }
    if (run.getStatus() == SyncRunStatus.SUCCESS) {
      if (run.getRunType() == SyncRunType.FULL_SYNC) {
        configService.updateSyncTime(run.getConfigId(), true);
      } else if (run.getRunType() == SyncRunType.INCREMENTAL_SYNC) {
        configService.updateSyncTime(run.getConfigId(), false);
      }
    }
    incrementalRerunService.enqueuePendingRerun(run);
  }
}
