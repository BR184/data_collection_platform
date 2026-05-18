package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncWorkerLeaseServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncWorkerLeaseService service;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    service = new SyncWorkerLeaseService(jdbcTemplate, "host-a", "12345@host-a");
  }

  @Test
  void shouldUpsertRunExecutorWorkerLease() {
    when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

    int updated = service.heartbeatRunExecutor(4, 2, 1, 30);

    assertThat(updated).isEqualTo(1);
    verify(jdbcTemplate)
        .update(
            org.mockito.ArgumentMatchers.contains("on conflict (worker_id) do update"),
            eq("run-executor:host-a:12345@host-a"),
            eq(SyncWorkerLeaseService.RUN_EXECUTOR_WORKER_TYPE),
            eq("host-a"),
            eq(BigDecimal.valueOf(4)),
            eq(4),
            eq(2),
            eq(1),
            eq(30));
  }

  @Test
  void shouldNormalizeLeaseMetrics() {
    when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

    service.heartbeatRunExecutor(0, -1, -2, 0);

    verify(jdbcTemplate)
        .update(
            any(String.class),
            eq("run-executor:host-a:12345@host-a"),
            eq(SyncWorkerLeaseService.RUN_EXECUTOR_WORKER_TYPE),
            eq("host-a"),
            eq(BigDecimal.ONE),
            eq(1),
            eq(0),
            eq(0),
            eq(1));
  }
}
