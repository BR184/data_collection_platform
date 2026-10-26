package com.data.collection.platform.service.form;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 发布任务轮询；测试通过 {@code platform.form-publication.worker-enabled=false} 关闭后同步驱动。 */
@Component
@ConditionalOnProperty(
    name = "platform.form-publication.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FormPublicationWorker {

  private final FormPublicationService publicationService;

  public FormPublicationWorker(FormPublicationService publicationService) {
    this.publicationService = publicationService;
  }

  @Scheduled(fixedDelayString = "${platform.form-publication.worker-delay-ms:1000}")
  public void poll() {
    publicationService.scheduleTick();
  }
}
