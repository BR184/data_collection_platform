package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SourceConnectionTesterTest {
  private ExecutorService executorService;

  @AfterEach
  void tearDown() {
    if (executorService != null) {
      executorService.shutdownNow();
    }
  }

  @Test
  void shouldDelegateConnectionTestToExternalDbService() {
    GitlabExternalDbService externalDbService = mock(GitlabExternalDbService.class);
    SourceConnectionTester tester = newTester(externalDbService, properties(5, 60));
    GitlabSyncConfig config = directConfig();

    tester.testConnection(config);

    verify(externalDbService).testConnection(config);
  }

  @Test
  void shouldFailFastWhenRecentFailureIsCached() {
    GitlabExternalDbService externalDbService = mock(GitlabExternalDbService.class);
    GitlabSyncConfig config = directConfig();
    doThrow(new BizException("network down")).when(externalDbService).testConnection(config);
    SourceConnectionTester tester = newTester(externalDbService, properties(5, 60));

    assertThatThrownBy(() -> tester.testConnection(config))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("network down");
    assertThatThrownBy(() -> tester.testConnection(config))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("temporarily unavailable")
        .hasMessageContaining("network down");

    verify(externalDbService).testConnection(config);
  }

  @Test
  void shouldTimeoutInteractiveConnectionTest() {
    GitlabExternalDbService externalDbService = mock(GitlabExternalDbService.class);
    GitlabSyncConfig config = directConfig();
    SourceConnectionTester tester = newTester(externalDbService, properties(1, 0));

    assertThatThrownBy(() -> tester.testConnection(config))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("timed out");

    verify(externalDbService, never()).testConnection(config);
  }

  private SourceConnectionTester newTester(
      GitlabExternalDbService externalDbService,
      GitlabMirrorProperties properties) {
    executorService = properties.getInteractiveConnectionTimeoutSeconds() == 1
        ? Executors.newSingleThreadExecutor(command -> new Thread(() -> {
          try {
            Thread.sleep(5000);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        }))
        : Executors.newSingleThreadExecutor();
    return new SourceConnectionTester(externalDbService, properties, executorService);
  }

  private GitlabMirrorProperties properties(int timeoutSeconds, int failureCacheSeconds) {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setInteractiveConnectionTimeoutSeconds(timeoutSeconds);
    properties.setSourceFailureCacheSeconds(failureCacheSeconds);
    return properties;
  }

  private GitlabSyncConfig directConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost("gitlab-db");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("secret");
    return config;
  }
}
