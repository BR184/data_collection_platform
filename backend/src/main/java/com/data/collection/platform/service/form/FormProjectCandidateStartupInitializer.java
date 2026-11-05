package com.data.collection.platform.service.form;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 服务启动时初始化已确认来源的既有项目候选目录。
 *
 * <p>迁移模式使用独立的 {@code MigrationApplication}，不装配本组件；初始化复用同步事件同一刷新服务，
 * 只读本地目录且幂等，刷新失败由服务记录状态，不阻断平台启动。
 */
@Component
@ConditionalOnProperty(
    name = "platform.form-project-candidates.startup-init-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FormProjectCandidateStartupInitializer implements ApplicationRunner {

  private final FormProjectCandidateService candidateService;

  public FormProjectCandidateStartupInitializer(FormProjectCandidateService candidateService) {
    this.candidateService = candidateService;
  }

  @Override
  public void run(ApplicationArguments args) {
    candidateService.initializeExistingSources();
  }
}
