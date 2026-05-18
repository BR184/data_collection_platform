package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import org.springframework.stereotype.Service;

@Service
public class SourceConnectionTester {
  private final GitlabExternalDbService externalDbService;

  public SourceConnectionTester(GitlabExternalDbService externalDbService) {
    this.externalDbService = externalDbService;
  }

  public void testConnection(GitlabSyncConfig config) {
    externalDbService.testConnection(config);
  }
}
