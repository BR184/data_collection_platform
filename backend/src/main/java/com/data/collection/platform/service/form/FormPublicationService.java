package com.data.collection.platform.service.form;

import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.form.admin.FormPublicationCreateRequest;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 发布任务编排：领取、空结构构建、短切换与失败定位。
 *
 * <p>本类不持有事务；每个持久步骤由 {@link FormPublicationTxService} 在独立短事务中完成。
 * 任务执行提交到应用异步执行器，不占用调度线程。
 */
@Service
@Slf4j
public class FormPublicationService {

  private final FormPublicationTxService txService;
  private final TaskExecutor executor;
  private final String owner =
      "form-publication-" + UUID.randomUUID().toString().substring(0, 8);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong lastScheduledAt = new AtomicLong(0);

  public FormPublicationService(
      FormPublicationTxService txService,
      @Qualifier("platformAsyncExecutor") TaskExecutor executor) {
    this.txService = txService;
    this.executor = executor;
  }

  /** 创建 VALIDATING 任务；请求同 key 同指纹返回原任务。 */
  public FormAdminViews.PublicationView createPublication(
      long scopeId,
      FormPublicationCreateRequest request,
      String requestKey,
      FormActorContext actor) {
    return txService.createPublication(scopeId, request, requestKey, actor);
  }

  public FormAdminViews.PublicationView publicationView(long publicationId) {
    return txService.publicationView(publicationId);
  }

  public FormAdminViews.PublicationView recover(long publicationId, FormActorContext actor) {
    return txService.recover(publicationId, actor);
  }

  /**
   * 同步驱动指定任务直到 PUBLISHED 或 FAILED；供测试与运维诊断使用。
   *
   * @param publicationId 任务 ID
   * @return 是否由本次调用完成了构建或切换
   */
  public boolean executeOnce(long publicationId) {
    FormPublicationTxService.Claim claim;
    try {
      claim = txService.claim(publicationId, owner);
    } catch (RuntimeException error) {
      log.warn("Form publication {} claim failed: {}", publicationId, error.getMessage());
      return false;
    }
    if (claim == null) {
      return false;
    }
    try {
      if (!"SWITCHING".equals(claim.stage())) {
        txService.build(claim);
        txService.enterSwitch(claim);
      }
      txService.applySwitch(claim);
      return true;
    } catch (RuntimeException error) {
      log.warn("Form publication {} failed: {}", publicationId, error.getMessage());
      try {
        if (!txService.fail(claim, error.getMessage())) {
          log.warn(
              "Form publication {} failure diagnostic skipped: lease expired or taken over",
              publicationId);
        }
      } catch (RuntimeException failError) {
        log.warn(
            "Form publication {} failure recording skipped: {}",
            publicationId,
            failError.getMessage());
      }
      return false;
    }
  }

  /** 最多执行 {@code limit} 个可领取任务；过期租约由领取路径接管。 */
  public int tick(int limit) {
    int executed = 0;
    for (int index = 0; index < limit; index++) {
      Long publicationId = txService.claimablePublicationId();
      if (publicationId == null) {
        break;
      }
      if (!executeOnce(publicationId)) {
        break;
      }
      executed++;
    }
    return executed;
  }

  /** 调度触发：同一时刻只允许一个发布运行排空队列。 */
  public void scheduleTick() {
    long now = System.currentTimeMillis();
    long previous = lastScheduledAt.get();
    if (now - previous < 500) {
      return;
    }
    if (!lastScheduledAt.compareAndSet(previous, now)) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              tick(5);
            } catch (RuntimeException error) {
              log.warn("Form publication tick failed: {}", error.getMessage());
            } finally {
              running.set(false);
            }
          });
    } catch (RuntimeException error) {
      running.set(false);
      throw error;
    }
  }

  @PreDestroy
  void shutdown() {
    running.set(false);
  }
}
