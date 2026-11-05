package com.data.collection.platform.service.form;

import com.data.collection.platform.service.sync.SyncRunCompletionEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 复用镜像运行终态与平台执行器，采集完成后登记自定义表单资源。 */
@Component
public class FormCustomAuthorityListener {
  private final FormCustomAuthorityService service;

  public FormCustomAuthorityListener(FormCustomAuthorityService service) {
    this.service = service;
  }

  /** 成功镜像才登记，失败不凭未提交资源生成填写权威。 */
  @Async("platformAsyncExecutor")
  @EventListener
  public void onSyncCompleted(SyncRunCompletionEvent event) {
    if (event != null && event.configId() != null && event.mirrorRun() && event.factRefreshEligible()) {
      service.registerConfig(event.configId());
    }
  }
}
