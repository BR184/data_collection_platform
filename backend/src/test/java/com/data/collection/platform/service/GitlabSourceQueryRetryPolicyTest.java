package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GitlabSourceQueryRetryPolicyTest {
  @Test
  void shouldRetryTransientExternalQueryFailures() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryAttempts(3);
    properties.setExternalQueryRetryDelayMs(0);
    GitlabSourceQueryRetryPolicy policy = new GitlabSourceQueryRetryPolicy(properties);
    AtomicInteger attempts = new AtomicInteger();

    String result = policy.executeWithRetry("test query", () -> {
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
    GitlabSourceQueryRetryPolicy policy = new GitlabSourceQueryRetryPolicy(properties);

    long firstDelay = policy.computeRetryDelayMs(1);
    long secondDelay = policy.computeRetryDelayMs(2);
    long cappedDelay = policy.computeRetryDelayMs(4);

    assertThat(firstDelay).isBetween(1000L, 1500L);
    assertThat(secondDelay).isBetween(2000L, 2500L);
    assertThat(cappedDelay).isBetween(2500L, 2500L);
  }

  @Test
  void shouldNotRetrySqlErrorsFromSourceDatabase() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryAttempts(3);
    properties.setExternalQueryRetryDelayMs(0);
    GitlabSourceQueryRetryPolicy policy = new GitlabSourceQueryRetryPolicy(properties);
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() -> policy.executeWithRetry("test query", () -> {
      attempts.incrementAndGet();
      throw new BizException("ERROR: relation \"missing_table\" does not exist");
    })).isInstanceOf(BizException.class);

    assertThat(attempts).hasValue(1);
  }

  @Test
  void shouldInspectNestedFailureMessagesWhenCheckingRetryability() {
    GitlabSourceQueryRetryPolicy policy = new GitlabSourceQueryRetryPolicy(new GitlabMirrorProperties());

    boolean retryable =
        policy.isRetryableExternalFailure(new RuntimeException("outer", new IllegalStateException("Broken pipe")));

    assertThat(retryable).isTrue();
  }
}
