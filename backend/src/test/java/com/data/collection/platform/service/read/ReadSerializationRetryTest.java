package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 读事务只对 PostgreSQL 40001 有界重建，业务错误与其它冲突不得被重试掩盖。 */
class ReadSerializationRetryTest {
  private final TransactionTemplate transactions =
      new TransactionTemplate(mock(PlatformTransactionManager.class));

  @Test
  void test_serialization_conflict_rebuilds_transaction_and_returns_late_result() {
    CannotAcquireLockException conflict =
        new CannotAcquireLockException("concurrent view renewal", new SQLException("snapshot moved", "40001"));
    AtomicInteger attempts = new AtomicInteger();
    String result = ReadSerializationRetry.execute(transactions, () -> {
      if (attempts.incrementAndGet() < 3) { throw conflict; }
      return "ok";
    });
    assertThat(result).isEqualTo("ok");
    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  void test_serialization_conflict_stops_after_bounded_attempts() {
    CannotAcquireLockException conflict =
        new CannotAcquireLockException("concurrent view renewal", new SQLException("snapshot moved", "40001"));
    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(() -> ReadSerializationRetry.execute(transactions, () -> {
      attempts.incrementAndGet();
      throw conflict;
    })).isSameAs(conflict);
    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  void test_non_serialization_failure_is_never_retried() {
    DataIntegrityViolationException integrity =
        new DataIntegrityViolationException("bad relationship", new SQLException("invalid input", "23503"));
    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(() -> ReadSerializationRetry.execute(transactions, () -> {
      attempts.incrementAndGet();
      throw integrity;
    })).isSameAs(integrity);
    assertThat(attempts.get()).isEqualTo(1);
  }
}
