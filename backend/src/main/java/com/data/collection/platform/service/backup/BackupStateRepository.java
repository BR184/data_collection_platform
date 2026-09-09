package com.data.collection.platform.service.backup;

import com.data.collection.platform.common.exception.BizException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * backup_state 单行运行权：单行条件 UPDATE 原子抢锁，同一时刻至多一个备份在执行。
 * 心跳续租与孤儿回收共用该租约；租约丢失视为主流程异常（单实例部署下意味着运行已被回收）。
 */
@Repository
public class BackupStateRepository {
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  public BackupStateRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  /** 尝试以指定运行 ID 占据运行权；已被占且租约未过期时返回 false（业务级拒绝，不改任何状态）。 */
  public boolean tryStartRun(long runId, Instant leaseUntil, Instant now) {
    Boolean acquired =
        transactionTemplate.execute(
            status -> {
              jdbcTemplate.update("insert into backup_state (id) values (1) on conflict (id) do nothing");
              return jdbcTemplate.update(
                  """
                  update backup_state
                     set active_run_id = ?, lease_expires_at = ?, updated_at = ?
                   where id = 1
                     and (active_run_id is null or lease_expires_at is null or lease_expires_at < ?)
                  """,
                  runId,
                  Timestamp.from(leaseUntil),
                  Timestamp.from(now),
                  Timestamp.from(now)) == 1;
            });
    return Boolean.TRUE.equals(acquired);
  }

  /** 心跳续租；运行权已不属于该运行时抛异常终止执行（防双主写入）。 */
  public void heartbeat(long runId, Instant leaseUntil, Instant now) {
    int updated =
        jdbcTemplate.update(
            "update backup_state set lease_expires_at = ?, updated_at = ? where id = 1 and active_run_id = ?",
            Timestamp.from(leaseUntil),
            Timestamp.from(now),
            runId);
    if (updated != 1) {
      throw new BizException("备份运行租约已丢失，执行终止");
    }
  }

  /** 释放运行权；仅当仍归属该运行时生效（幂等）。 */
  public void release(long runId, Instant now) {
    jdbcTemplate.update(
        "update backup_state set active_run_id = null, lease_expires_at = null, updated_at = ?"
            + " where id = 1 and active_run_id = ?",
        Timestamp.from(now),
        runId);
  }

  /** 当前运行权归属（状态展示用）。 */
  public Optional<Long> activeRunId() {
    List<Long> rows =
        jdbcTemplate.query(
            "select active_run_id from backup_state where id = 1 and active_run_id is not null",
            (rs, rowNum) -> rs.getLong(1));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** 租约已过期的运行权归属（孤儿回收依据）。 */
  public Optional<Long> expiredActiveRunId(Instant now) {
    List<Long> rows =
        jdbcTemplate.query(
            "select active_run_id from backup_state where id = 1 and active_run_id is not null"
                + " and (lease_expires_at is null or lease_expires_at < ?)",
            (rs, rowNum) -> rs.getLong(1),
            Timestamp.from(now));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }
}
