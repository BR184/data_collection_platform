package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabExternalDbServiceTest {

  private GitlabExternalDbService service;

  @BeforeEach
  void setUp() {
    service = new GitlabExternalDbService(new GitlabMirrorProperties(), new ObjectMapper());
  }

  @Test
  void shouldPreferUpdatedAtVariantsOverCreatedAtVariants() {
    String resolved = service.resolveUpdatedAtColumn(List.of("id", "CreatedAt", "UpdatedAt"));

    assertThat(resolved).isEqualTo("UpdatedAt");
  }

  @Test
  void shouldFallbackToCreatedAtVariantWhenUpdatedAtIsMissing() {
    String resolved = service.resolveUpdatedAtColumn(List.of("id", "createdAt", "name"));

    assertThat(resolved).isEqualTo("createdAt");
  }

  @Test
  void shouldRecognizeCommonSnakeCaseAndCamelCaseVariants() {
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "updated_at"))).isEqualTo("updated_at");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "updatedAt"))).isEqualTo("updatedAt");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "gmt_modified"))).isEqualTo("gmt_modified");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "create_time"))).isEqualTo("create_time");
  }

  @Test
  void shouldReturnNullWhenNoCandidateExists() {
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "name", "description"))).isNull();
  }

  @Test
  void shouldBuildJdbcUrlWithTimeoutsAndTcpKeepAliveForUnstableNetwork() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryTimeoutSeconds(45);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setDbHost("10.0.0.8");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");

    String url = service.buildJdbcUrl(config);

    assertThat(url)
        .isEqualTo("jdbc:postgresql://10.0.0.8:5432/gitlabhq_production?connectTimeout=45&socketTimeout=45&tcpKeepAlive=true");
  }

  @Test
  void shouldRetryTransientExternalQueryFailures() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryAttempts(3);
    properties.setExternalQueryRetryDelayMs(0);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    AtomicInteger attempts = new AtomicInteger();

    String result = service.executeExternalQueryWithRetry("test query", () -> {
      if (attempts.incrementAndGet() < 3) {
        throw new BizException("Connection reset by peer");
      }
      return "ok";
    });

    assertThat(result).isEqualTo("ok");
    assertThat(attempts).hasValue(3);
  }

  @Test
  void shouldUseExponentialBackoffWithJitterForExternalQueryRetries() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryDelayMs(1000);
    properties.setExternalQueryRetryMaxDelayMs(2500);
    service = new GitlabExternalDbService(properties, new ObjectMapper());

    long firstDelay = service.computeExternalQueryRetryDelayMs(1);
    long secondDelay = service.computeExternalQueryRetryDelayMs(2);
    long cappedDelay = service.computeExternalQueryRetryDelayMs(4);

    assertThat(firstDelay).isBetween(1000L, 1500L);
    assertThat(secondDelay).isBetween(2000L, 2500L);
    assertThat(cappedDelay).isBetween(2500L, 2500L);
  }

  @Test
  void shouldNotRetrySqlErrorsFromSourceDatabase() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryAttempts(3);
    properties.setExternalQueryRetryDelayMs(0);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() -> service.executeExternalQueryWithRetry("test query", () -> {
      attempts.incrementAndGet();
      throw new BizException("ERROR: relation \"missing_table\" does not exist");
    })).isInstanceOf(BizException.class);

    assertThat(attempts).hasValue(1);
  }
}
