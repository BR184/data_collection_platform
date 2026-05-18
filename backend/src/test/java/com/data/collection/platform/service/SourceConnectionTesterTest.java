package com.data.collection.platform.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.entity.GitlabSyncConfig;
import org.junit.jupiter.api.Test;

class SourceConnectionTesterTest {
  @Test
  void shouldDelegateConnectionTestToExternalDbService() {
    GitlabExternalDbService externalDbService = mock(GitlabExternalDbService.class);
    SourceConnectionTester tester = new SourceConnectionTester(externalDbService);
    GitlabSyncConfig config = new GitlabSyncConfig();

    tester.testConnection(config);

    verify(externalDbService).testConnection(config);
  }
}
