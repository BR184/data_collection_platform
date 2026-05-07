package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import java.util.concurrent.atomic.AtomicBoolean;
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
}
