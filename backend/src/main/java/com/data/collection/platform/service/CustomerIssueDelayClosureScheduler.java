package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CustomerIssueDelayClosureScheduler {
  private final GitlabConfigService configService;
  private final FactBuildService factBuildService;

  public CustomerIssueDelayClosureScheduler(
      GitlabConfigService configService,
      FactBuildService factBuildService) {
    this.configService = configService;
    this.factBuildService = factBuildService;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.customer-issue-delay-check-delay-ms:3600000}")
  public void refreshCustomerIssueDelayFacts() {
    List<GitlabSyncConfig> configs = configService.listConfigs().stream()
        .filter(config -> config.getId() != null)
        .filter(config -> Boolean.TRUE.equals(config.getSourceEnabled() == null ? config.isEnabled() : config.getSourceEnabled()))
        .toList();
    for (GitlabSyncConfig config : configs) {
      try {
        factBuildService.refreshCustomerIssueDelayFactsForConfig(config);
      } catch (RuntimeException error) {
        log.warn(
            "Customer issue delay closure refresh failed, sourceInstance={}",
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            error);
      }
    }
  }
}
