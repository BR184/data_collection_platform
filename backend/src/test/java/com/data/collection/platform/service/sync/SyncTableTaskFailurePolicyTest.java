package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.GitlabSourceAccessException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;

class SyncTableTaskFailurePolicyTest {
  private final SyncTableTaskFailurePolicy policy = new SyncTableTaskFailurePolicy();

  @Test
  void test_transient_database_failure_receives_bounded_retry_time() {
    LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);

    SyncTableTaskFailurePolicy.Decision decision =
        policy.evaluate(new TransientDataAccessResourceException("连接中断"), 0, 3, now);

    assertThat(decision.retryable()).isTrue();
    assertThat(decision.runAfter()).isEqualTo(now.plusSeconds(5));
  }

  @Test
  void test_lock_timeout_sql_state_is_retryable() {
    SQLException sqlException = new SQLException("lock timeout", "55P03");

    SyncTableTaskFailurePolicy.Decision decision =
        policy.evaluate(sqlException, 1, 3, LocalDateTime.of(2026, 8, 10, 12, 0));

    assertThat(decision.retryable()).isTrue();
    assertThat(decision.runAfter())
        .isEqualTo(LocalDateTime.of(2026, 8, 10, 12, 0, 10));
  }

  @Test
  void test_undefined_column_is_not_retried() {
    BadSqlGrammarException error =
        new BadSqlGrammarException(
            "resolve", "select missing", new SQLException("undefined column", "42703"));

    SyncTableTaskFailurePolicy.Decision decision =
        policy.evaluate(error, 0, 3, LocalDateTime.of(2026, 8, 10, 12, 0));

    assertThat(decision.retryable()).isFalse();
    assertThat(decision.runAfter()).isNull();
  }

  @Test
  void test_retry_limit_prevents_another_retry() {
    SyncTableTaskFailurePolicy.Decision decision =
        policy.evaluate(
            new TransientDataAccessResourceException("连接中断"),
            3,
            3,
            LocalDateTime.of(2026, 8, 10, 12, 0));

    assertThat(decision.retryable()).isFalse();
  }

  @Test
  void test_exhausted_gitlab_source_timeout_preserves_retryable_semantics() {
    SyncTableTaskFailurePolicy.Decision decision =
        policy.evaluate(
            GitlabSourceAccessException.of("GitLab 来源查询超时", null, true),
            0,
            3,
            LocalDateTime.of(2026, 8, 10, 12, 0));

    assertThat(decision.retryable()).isTrue();
  }
}
