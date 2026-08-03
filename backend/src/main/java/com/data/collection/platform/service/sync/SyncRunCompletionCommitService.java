package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原子捕获页面发布水位并提交同步运行终态。 */
@Service
public class SyncRunCompletionCommitService {
  private final SyncRunPublicationFenceService publicationFenceService;
  private final SyncRunLeaseService leaseService;

  public SyncRunCompletionCommitService(
      SyncRunPublicationFenceService publicationFenceService,
      SyncRunLeaseService leaseService) {
    this.publicationFenceService = publicationFenceService;
    this.leaseService = leaseService;
  }

  /**
   * 在同一运行行锁事务中捕获全部已登记工作区栅栏并释放运行租约。
   *
   * @throws SyncRunLeaseLostException 运行已过期或被其他 worker 重新领取
   */
  @Transactional
  public void finishOwnedRun(SyncRun run) {
    publicationFenceService.captureOwnedRun(run);
    if (leaseService.finishOwnedRun(run) != 1) {
      throw new SyncRunLeaseLostException(run == null ? null : run.getId());
    }
  }
}
