package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FactBuildOperationGuardTest {
  @Test
  void shouldRejectSameScopeWhenAlreadyRunning() {
    FactBuildOperationGuard guard = new FactBuildOperationGuard();
    AtomicBoolean nestedRejected = new AtomicBoolean(false);

    String result =
        guard.run(
            "issue",
            () -> {
              assertThatThrownBy(() -> guard.run("issue", () -> "nested"))
                  .isInstanceOf(BizException.class)
                  .hasMessageContaining("事实重建任务执行中");
              nestedRejected.set(true);
              return "outer";
            });

    assertThat(result).isEqualTo("outer");
    assertThat(nestedRejected).isTrue();
  }

  @Test
  void shouldRejectScopedBuildWhenAllBuildIsRunning() {
    FactBuildOperationGuard guard = new FactBuildOperationGuard();

    guard.run(
        "all",
        () -> {
          assertThatThrownBy(() -> guard.run("issue", () -> "issue"))
              .isInstanceOf(BizException.class)
              .hasMessageContaining("事实重建任务执行中");
          return "all";
        });
  }

  @Test
  void shouldRejectSameSourceScopedBuildWhenSourceAllBuildIsRunning() {
    FactBuildOperationGuard guard = new FactBuildOperationGuard();

    guard.run(
        "cc:all",
        () -> {
          assertThatThrownBy(() -> guard.run("cc:issue", () -> "issue"))
              .isInstanceOf(BizException.class)
              .hasMessageContaining("事实重建任务执行中");
          return "all";
        });
  }

  @Test
  void shouldRejectSameConfigScopedBuildWhenConfigAllBuildIsRunning() {
    FactBuildOperationGuard guard = new FactBuildOperationGuard();

    guard.run(
        "all:config-1",
        () -> {
          assertThatThrownBy(() -> guard.run("issue:config-1", () -> "issue"))
              .isInstanceOf(BizException.class);
          return "all";
        });
  }

  @Test
  void shouldRejectConfigScopedBuildWhenGlobalAllBuildIsRunning() {
    FactBuildOperationGuard guard = new FactBuildOperationGuard();

    guard.run(
        "all",
        () -> {
          assertThatThrownBy(() -> guard.run("issue:config-1", () -> "issue"))
              .isInstanceOf(BizException.class);
          return "all";
        });
  }

  @Test
  void shouldAllowDifferentSourcesToBuildInParallel() {
    FactBuildOperationGuard guard = new FactBuildOperationGuard();

    String result = guard.run(
        "cc:all",
        () -> guard.run("dgm:issue", () -> "dgm"));

    assertThat(result).isEqualTo("dgm");
  }

  @Test
  void shouldAllowDifferentConfigsToBuildInParallel() {
    FactBuildOperationGuard guard = new FactBuildOperationGuard();

    String result = guard.run(
        "all:config-1",
        () -> guard.run("issue:config-2", () -> "config-2"));

    assertThat(result).isEqualTo("config-2");
  }

  @Test
  void shouldRecoverExpiredRunningScope() {
    AtomicLong now = new AtomicLong(1000L);
    FactBuildOperationGuard guard = new FactBuildOperationGuard(500L, now::get);

    String result = guard.run(
        "issue",
        () -> {
          now.addAndGet(1100L);
          return guard.run("issue", () -> "recovered");
        });

    assertThat(result).isEqualTo("recovered");
  }
}
