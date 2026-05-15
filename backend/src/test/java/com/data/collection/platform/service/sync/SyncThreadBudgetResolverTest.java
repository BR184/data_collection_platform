package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncThreadBudgetResolverTest {
  private GitlabMirrorProperties properties;
  private SyncThreadBudgetResolver resolver;

  @BeforeEach
  void setUp() {
    properties = new GitlabMirrorProperties();
    properties.setMaxSyncThreads(16);
    resolver = new SyncThreadBudgetResolver(properties);
  }

  @Test
  void shouldResolveFixedThreadMode() {
    GitlabSyncConfig config = config(SyncThreadBudgetResolver.MODE_FIXED, BigDecimal.valueOf(4), null);

    assertThat(resolver.resolve(config, 16)).isEqualTo(4);
  }

  @Test
  void shouldResolveCpuRatioThreadMode() {
    GitlabSyncConfig config = config(SyncThreadBudgetResolver.MODE_CPU_RATIO, new BigDecimal("0.8"), null);

    assertThat(resolver.resolve(config, 16)).isEqualTo(12);
  }

  @Test
  void shouldClampBelowMinimumToOne() {
    GitlabSyncConfig config = config(SyncThreadBudgetResolver.MODE_FIXED, BigDecimal.ZERO, null);

    assertThat(resolver.resolve(config, 16)).isEqualTo(1);
  }

  @Test
  void shouldClampAboveConfiguredMax() {
    GitlabSyncConfig config = config(SyncThreadBudgetResolver.MODE_FIXED, BigDecimal.valueOf(32), 6);

    assertThat(resolver.resolve(config, 16)).isEqualTo(6);
  }

  private GitlabSyncConfig config(String mode, BigDecimal value, Integer maxThreads) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSyncThreadMode(mode);
    config.setSyncThreadValue(value);
    config.setMaxSyncThreads(maxThreads);
    return config;
  }
}
