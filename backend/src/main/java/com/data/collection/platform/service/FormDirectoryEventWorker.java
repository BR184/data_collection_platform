package com.data.collection.platform.service;

import com.data.collection.platform.service.form.FormDirectoryEventCursorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 目录事件自动消费：有界领取待处理事件、唤醒 FORM 资源并转入既有事实任务。
 *
 * <p>游标持久在 {@code form_directory_event_cursors}，重启后从已消费位置继续；事件、表单 head
 * 与事实 head 的唤醒在同一事务提交，任一步失败整体回滚并可由调度重试。事实任务提交在执行器之外
 * 进行，提交失败不回滚事件，待发布变化仍由 {@code fact_change_heads} 保留并由既有 FACT_REFRESH
 * 重试。
 */
@Component
@Slf4j
@ConditionalOnProperty(
    prefix = "platform.form-directory-event",
    name = "worker-enabled",
    matchIfMissing = true)
public class FormDirectoryEventWorker {
  private static final int BATCH_SIZE = 50;

  private final FormDirectoryEventCursorService cursorService;
  private final FormDirectoryEventBridge bridge;
  private final TransactionTemplate transactionTemplate;

  public FormDirectoryEventWorker(
      FormDirectoryEventCursorService cursorService,
      FormDirectoryEventBridge bridge,
      TransactionTemplate transactionTemplate) {
    this.cursorService = cursorService;
    this.bridge = bridge;
    this.transactionTemplate = transactionTemplate;
  }

  @Scheduled(fixedDelayString = "${platform.form-directory-event.worker-delay-ms:1000}")
  public void consume() {
    consumeOnce(BATCH_SIZE);
  }

  /**
   * 执行一次有界消费，供调度与回归共用。
   *
   * @param limit 单批事件上限
   * @return 本次消费的事件数
   */
  public int consumeOnce(int limit) {
    FormDirectoryEventCursorService.Consumption consumption =
        transactionTemplate.execute(
            status -> {
              FormDirectoryEventCursorService.Consumption result =
                  cursorService.consumePending(limit);
              if (!result.woken().isEmpty()) {
                bridge.register(result.woken());
              }
              return result;
            });
    if (consumption == null || consumption.woken().isEmpty()) {
      return consumption == null ? 0 : consumption.consumed();
    }
    try {
      bridge.submit(consumption.woken());
    } catch (RuntimeException error) {
      log.warn(
          "Directory events and heads committed but fact refresh submission failed, consumed={},"
              + " woken={}",
          consumption.consumed(),
          consumption.woken().size(),
          error);
    }
    return consumption.consumed();
  }
}
