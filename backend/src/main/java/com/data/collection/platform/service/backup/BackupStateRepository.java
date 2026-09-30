package com.data.collection.platform.service.backup;

import com.data.collection.platform.common.exception.BizException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** backup_state 单行运行权；每次触发使用独立 token，过期回收先撤销 token 再确认停止。 */
@Repository
public class BackupStateRepository {
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  public BackupStateRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  /** 尝试占据空闲运行权；失租运行须先经过回收巡检，不在新触发事务内直接抢占。 */
  public boolean tryStartRun(
      long runId, String executionToken, Instant leaseUntil, Instant now) {
    if (runId <= 0L
        || executionToken == null
        || !executionToken.matches("[A-Fa-f0-9-]{36}")) {
      throw new IllegalArgumentException("备份运行权必须携带唯一执行 token");
    }
    Boolean acquired =
        transactionTemplate.execute(
            status -> {
              jdbcTemplate.update("insert into backup_state (id) values (1) on conflict (id) do nothing");
              return jdbcTemplate.update(
                      """
                      update backup_state
                         set active_run_id = ?, execution_token = ?, execution_revoked = false,
                             process_id = null, process_started_at = null,
                             lease_expires_at = ?, updated_at = ?
                       where id = 1 and active_run_id is null
                      """,
                      runId,
                      executionToken,
                      Timestamp.from(leaseUntil),
                      Timestamp.from(now))
                  == 1;
            });
    return Boolean.TRUE.equals(acquired);
  }

  /** 心跳只能续当前、未撤销且尚未过期的执行身份。 */
  public void heartbeat(long runId, String executionToken, Instant leaseUntil, Instant now) {
    int updated =
        jdbcTemplate.update(
            """
            update backup_state
               set lease_expires_at = ?, updated_at = ?
             where id = 1 and active_run_id = ? and execution_token = ?
               and execution_revoked = false and lease_expires_at >= ?
            """,
            Timestamp.from(leaseUntil),
            Timestamp.from(now),
            runId,
            executionToken,
            Timestamp.from(now));
    if (updated != 1) {
      throw new BizException("备份运行租约已丢失或过期，执行已撤销");
    }
  }

  /** 只有未撤销的当前 token 才能继续写入备份阶段或提交产物。 */
  public boolean isOwnedActive(long runId, String executionToken, Instant now) {
    Integer owned =
        jdbcTemplate.queryForObject(
            """
            select count(*) from backup_state
             where id = 1 and active_run_id = ? and execution_token = ?
               and execution_revoked = false and lease_expires_at >= ?
            """,
            Integer.class,
            runId,
            executionToken,
            Timestamp.from(now));
    return owned != null && owned == 1;
  }

  /** 把已启动子进程的 PID 与启动时刻登记到仍有效的执行身份上。 */
  public boolean recordProcessIdentity(
      long runId, String executionToken, long processId, Instant processStartedAt, Instant now) {
    return jdbcTemplate.update(
            """
            update backup_state
               set process_id = ?, process_started_at = ?, updated_at = ?
             where id = 1 and active_run_id = ? and execution_token = ?
               and execution_revoked = false and lease_expires_at >= ?
            """,
            processId,
            processStartedAt == null ? null : Timestamp.from(processStartedAt),
            Timestamp.from(now),
            runId,
            executionToken,
            Timestamp.from(now))
        == 1;
  }

  /** 清除本次已退出子进程身份；撤销后也允许当前恢复器清理对应 PID 记录。 */
  public void clearProcessIdentity(long runId, String executionToken, long processId, Instant now) {
    jdbcTemplate.update(
        """
        update backup_state
           set process_id = null, process_started_at = null, updated_at = ?
         where id = 1 and active_run_id = ? and execution_token = ? and process_id = ?
        """,
        Timestamp.from(now),
        runId,
        executionToken,
        processId);
  }

  /**
   * 唯一结算入口：在同一个事务里确定本次运行的历史终态与运行权去向。
   *
   * <p>只作用于 (runId, executionToken) 自己持有的那一行，因此正常结束与恢复器回收走同一协议，
   * 谁先到达谁完成结算；已被其他运行接管或已结算时返回 false 且不修改任何状态。租约是否过期、
   * 是否已被撤销都不再是结算前提——两者都只说明"本次执行不再拥有提交资格"，而不是"这个运行
   * 无法收敛"。真正的保护是 token 所有权：迟到的旧执行无法碰新运行或恢复器仍需要的状态。
   *
   * <p>失败无法落库（例如数据库不可用）时整个事务回滚，历史终态与运行权都保持原状，
   * 调用方据此保留恢复所需的上下文。
   *
   * @param failureMessage 未提交成功时的失败原因；null 表示成功已登记，只收敛运行权
   * @return true 表示本次调用完成了结算；false 表示该运行已不属于本次执行
   */
  public boolean settleOwnedRun(
      long runId, String executionToken, String failureMessage, Instant now) {
    Boolean settled =
        transactionTemplate.execute(
            status -> {
              List<Long> ownerRows =
                  jdbcTemplate.query(
                      "select active_run_id from backup_state where id = 1 "
                          + "and active_run_id = ? and execution_token = ? for update",
                      (rs, rowNum) -> rs.getLong(1),
                      runId,
                      executionToken);
              if (ownerRows.isEmpty()) {
                return false;
              }
              if (failureMessage != null) {
                jdbcTemplate.update(
                    """
                    update backup_runs
                       set status = 'FAILED', finished_at = ?,
                           duration_ms = (extract(epoch from (? - started_at)) * 1000)::bigint,
                           error_message = ?
                     where id = ? and status = 'RUNNING'
                    """,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    failureMessage,
                    runId);
              }
              int cleared =
                  jdbcTemplate.update(
                      """
                      update backup_state
                         set active_run_id = null, execution_token = null, execution_revoked = false,
                             lease_expires_at = null, process_id = null, process_started_at = null,
                             updated_at = ?
                       where id = 1 and active_run_id = ? and execution_token = ?
                      """,
                      Timestamp.from(now),
                      runId,
                      executionToken);
              if (cleared != 1) {
                throw new IllegalStateException("备份运行结算发生竞争");
              }
              return true;
            });
    return Boolean.TRUE.equals(settled);
  }

  /** 当前运行权归属（状态展示用）。 */
  public Optional<Long> activeRunId() {
    List<Long> rows =
        jdbcTemplate.query(
            "select active_run_id from backup_state where id = 1 and active_run_id is not null",
            (rs, rowNum) -> rs.getLong(1));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  /**
   * 撤销已过期执行身份但暂不释放运行权，供调度器协调停止后完成回收。
   *
   * @return 待恢复的运行与可验证子进程身份；无过期运行时为空
   */
  public Optional<ExpiredExecution> revokeExpiredExecution(Instant now) {
    Optional<ExpiredExecution> expired =
        transactionTemplate.execute(
            status -> {
              List<ExpiredExecution> rows =
                  jdbcTemplate.query(
                      """
                      select active_run_id, execution_token, process_id, process_started_at
                        from backup_state
                       where id = 1 and active_run_id is not null
                         and (lease_expires_at is null or lease_expires_at < ?)
                       for update
                      """,
                      (rs, rowNum) ->
                          new ExpiredExecution(
                              rs.getLong("active_run_id"),
                              rs.getString("execution_token"),
                              rs.getObject("process_id") == null ? null : rs.getLong("process_id"),
                              rs.getTimestamp("process_started_at") == null
                                  ? null
                                  : rs.getTimestamp("process_started_at").toInstant()),
                      Timestamp.from(now));
              if (rows.isEmpty()) {
                return Optional.empty();
              }
              ExpiredExecution execution = rows.getFirst();
              jdbcTemplate.update(
                  "update backup_state set execution_revoked = true, updated_at = ? "
                      + "where id = 1 and active_run_id = ? and execution_token = ?",
                  Timestamp.from(now),
                  execution.runId(),
                  execution.executionToken());
              return Optional.of(execution);
            });
    return expired == null ? Optional.empty() : expired;
  }

  /** 保留待处理身份，明确记录停止未确认；不会释放运行权或删除暂存文件。 */
  public void recordRecoveryPending(ExpiredExecution execution, String message, Instant now) {
    jdbcTemplate.update(
        """
        update backup_runs
           set error_message = ?
         where id = ? and status = 'RUNNING'
           and exists (
             select 1 from backup_state
              where id = 1 and active_run_id = ? and execution_token = ? and execution_revoked = true
           )
        """,
        message,
        execution.runId(),
        execution.runId(),
        execution.executionToken());
  }

  /** 已撤销执行的身份与可复核子进程信息。 */
  public record ExpiredExecution(
      long runId, String executionToken, Long processId, Instant processStartedAt) {}
}
