package com.data.collection.platform.service.form;

import com.data.collection.platform.service.sync.SyncRunCompletionEvent;
import com.data.collection.platform.service.sync.SyncRunCompletionListenerOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 镜像运行成功后刷新表单项目候选投影。
 *
 * <p>刷新只读本地 ODS 目录，不连接 GitLab；失败不反馈到同步终态，管理端按“目录尚未同步”提示重试。
 */
@Service
@Slf4j
public class FormProjectCandidateRefreshListener {

  private final FormProjectCandidateService candidateService;

  public FormProjectCandidateRefreshListener(FormProjectCandidateService candidateService) {
    this.candidateService = candidateService;
  }

  @Order(SyncRunCompletionListenerOrder.FORM_PROJECT_PROJECTION)
  @EventListener
  public void onSyncRunCompleted(SyncRunCompletionEvent event) {
    if (event == null
        || !event.mirrorRun()
        || !event.successful()
        || event.configId() == null) {
      return;
    }
    try {
      candidateService.refresh(event.configId());
    } catch (RuntimeException error) {
      log.warn(
          "表单项目候选投影刷新失败：configId={}, runId={}",
          event.configId(),
          event.runId(),
          error);
    }
  }
}
