package com.data.collection.platform.service.sync;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SyncWorkerLeaseService {
  static final String RUN_EXECUTOR_WORKER_TYPE = "RUN_EXECUTOR";

  private final JdbcTemplate jdbcTemplate;
  private final String hostname;
  private final String processName;

  @Autowired
  public SyncWorkerLeaseService(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, resolveHostname(), ManagementFactory.getRuntimeMXBean().getName());
  }

  SyncWorkerLeaseService(JdbcTemplate jdbcTemplate, String hostname, String processName) {
    this.jdbcTemplate = jdbcTemplate;
    this.hostname = hostname == null || hostname.isBlank() ? "unknown-host" : hostname;
    this.processName = processName == null || processName.isBlank() ? "unknown-process" : processName;
  }

  public int heartbeatRunExecutor(int maxThreads, int activeThreads, int queueDepth, int leaseSeconds) {
    int safeMaxThreads = Math.max(1, maxThreads);
    int safeActiveThreads = Math.max(0, activeThreads);
    int safeQueueDepth = Math.max(0, queueDepth);
    int safeLeaseSeconds = Math.max(1, leaseSeconds);
    return jdbcTemplate.update(
        """
        insert into sync_worker_leases (
            worker_id,
            worker_type,
            hostname,
            thread_mode,
            thread_value,
            max_threads,
            active_threads,
            queue_depth,
            lease_until,
            heartbeat_at,
            created_at,
            updated_at
        )
        values (?, ?, ?, 'FIXED', ?, ?, ?, ?, current_timestamp + (? * interval '1 second'), current_timestamp, current_timestamp, current_timestamp)
        on conflict (worker_id) do update
           set worker_type = excluded.worker_type,
               hostname = excluded.hostname,
               thread_mode = excluded.thread_mode,
               thread_value = excluded.thread_value,
               max_threads = excluded.max_threads,
               active_threads = excluded.active_threads,
               queue_depth = excluded.queue_depth,
               lease_until = excluded.lease_until,
               heartbeat_at = excluded.heartbeat_at,
               updated_at = current_timestamp
        """,
        runExecutorWorkerId(),
        RUN_EXECUTOR_WORKER_TYPE,
        hostname,
        BigDecimal.valueOf(safeMaxThreads),
        safeMaxThreads,
        safeActiveThreads,
        safeQueueDepth,
        safeLeaseSeconds);
  }

  private String runExecutorWorkerId() {
    return truncate("run-executor:" + hostname + ":" + processName, 128);
  }

  private String truncate(String value, int maxLength) {
    if (value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private static String resolveHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception ignored) {
      return "unknown-host";
    }
  }
}
