package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 延期标签写回的来源级持久进度。
 *
 * <p>该仓库只做有界状态变更：所有推进、等待与结算都要求 owner、领取令牌和处理修订同时匹配，
 * 因此重启后的新处理者可以接管过期租约，旧处理者无法回写已经失效的阶段。
 */
@Service
@Slf4j
public class CustomerIssueDelayClosureProgressRepository {
  private static final String FENCE =
      " id = ? and lease_owner = ? and lease_token = ? and processed_revision = ? ";

  private final JdbcTemplate jdbcTemplate;

  public CustomerIssueDelayClosureProgressRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 提交一次新的来源请求。
   *
   * <p>已有在飞阶段时只累加请求修订（重复触发合并），不取消正在运行的前置刷新；处于
   * {@code IDLE} 时才重新进入 {@code REQUESTED}。配置身份变化会撤销旧轮次绑定。
   *
   * @param config 当前数据源配置
   * @param reason 请求原因，仅用于日志
   */
  public void requestRound(GitlabSyncConfig config, String reason) {
    if (config == null || config.getId() == null) {
      return;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    jdbcTemplate.update(
        """
        insert into customer_issue_delay_closure_requests(
            source_instance, config_id, stage, request_revision, processed_revision,
            completed_revision, next_check_at, updated_at)
        values (?, ?, 'REQUESTED', 1, 0, 0, current_timestamp, current_timestamp)
        on conflict (source_instance) do update
          set config_id = excluded.config_id,
              stage = case
                when customer_issue_delay_closure_requests.config_id is distinct from excluded.config_id
                  then 'REQUESTED'
                when customer_issue_delay_closure_requests.stage = 'IDLE' then 'REQUESTED'
                else customer_issue_delay_closure_requests.stage
              end,
              mirror_run_id = case
                when customer_issue_delay_closure_requests.config_id is distinct from excluded.config_id
                  then null
                else customer_issue_delay_closure_requests.mirror_run_id
              end,
              authorized_fact_version = case
                when customer_issue_delay_closure_requests.config_id is distinct from excluded.config_id
                  then null
                else customer_issue_delay_closure_requests.authorized_fact_version
              end,
              authorization_revision = case
                when customer_issue_delay_closure_requests.config_id is distinct from excluded.config_id
                  then null
                else customer_issue_delay_closure_requests.authorization_revision
              end,
              enqueue_cursor_issue_id = case
                when customer_issue_delay_closure_requests.config_id is distinct from excluded.config_id
                  then null
                else customer_issue_delay_closure_requests.enqueue_cursor_issue_id
              end,
              request_revision = customer_issue_delay_closure_requests.request_revision + 1,
              next_check_at = current_timestamp,
              updated_at = current_timestamp
        """,
        sourceInstance,
        config.getId());
    log.debug(
        "Requested customer issue delay closure round, configId={}, sourceInstance={}, reason={}",
        config.getId(),
        sourceInstance,
        reason);
  }

  /**
   * 数据源配置保存后的可靠授权撤销。
   *
   * <p>该写与配置保存同事务提交，不依赖内存事件或 worker 恰好观察到关闭瞬间：写回开启时进入新
   * {@code REQUESTED} 周期（必须重新前置刷新），关闭时进入 {@code IDLE} 并清除授权。
   * 处理修订同步前移，任何在飞旧处理者都无法再推进。
   *
   * @param config 已保存的数据源配置
   */
  public void onConfigSaved(GitlabSyncConfig config) {
    if (config == null || config.getId() == null) {
      return;
    }
    boolean writebackEnabled =
        Boolean.TRUE.equals(config.getDelayLabelWritebackEnabled())
            && org.springframework.util.StringUtils.hasText(config.getWebBaseUrl())
            && org.springframework.util.StringUtils.hasText(config.getApiToken());
    String stage = writebackEnabled ? CustomerIssueDelayClosureStage.REQUESTED.name()
        : CustomerIssueDelayClosureStage.IDLE.name();
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String reason = writebackEnabled
        ? "数据源配置已变更，本轮授权已撤销并重新执行前置刷新"
        : "延期标签写回已关闭，本轮授权已撤销";
    jdbcTemplate.update(
        """
        insert into customer_issue_delay_closure_requests(
            source_instance, config_id, stage, request_revision, processed_revision,
            completed_revision, next_check_at, last_error, updated_at)
        values (?, ?, ?, 1, ?, ?, current_timestamp, ?, current_timestamp)
        on conflict (source_instance) do update
          set config_id = excluded.config_id,
              stage = excluded.stage,
              request_revision = customer_issue_delay_closure_requests.request_revision + 1,
              processed_revision = customer_issue_delay_closure_requests.request_revision + 1,
              completed_revision = case
                when excluded.stage = 'IDLE'
                  then customer_issue_delay_closure_requests.request_revision + 1
                else customer_issue_delay_closure_requests.completed_revision
              end,
              mirror_run_id = null,
              authorized_fact_version = null,
              authorization_revision = null,
              enqueue_cursor_issue_id = null,
              failure_count = 0,
              next_check_at = current_timestamp,
              lease_owner = null,
              lease_token = null,
              lease_until = null,
              last_error = excluded.last_error,
              updated_at = current_timestamp
        """,
        sourceInstance,
        config.getId(),
        stage,
        writebackEnabled ? 0L : 1L,
        writebackEnabled ? 0L : 1L,
        reason);
  }

  public Optional<CustomerIssueDelayClosureProgress> findBySource(String sourceInstance) {
    List<CustomerIssueDelayClosureProgress> rows =
        jdbcTemplate.query(
            "select * from customer_issue_delay_closure_requests where source_instance = ?",
            (rs, rowNum) -> mapProgress(rs),
            GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance));
    return rows.stream().findFirst();
  }

  public Optional<CustomerIssueDelayClosureProgress> findByConfigId(Long configId) {
    if (configId == null) {
      return Optional.empty();
    }
    List<CustomerIssueDelayClosureProgress> rows =
        jdbcTemplate.query(
            "select * from customer_issue_delay_closure_requests where config_id = ?",
            (rs, rowNum) -> mapProgress(rs),
            configId);
    return rows.stream().findFirst();
  }

  /**
   * 领取最早到期的进度。到期条件同时覆盖未完成阶段和 {@code IDLE} 上尚未结算的新请求。
   */
  public Optional<CustomerIssueDelayClosureProgressClaim> claimNext(
      String owner, String token, int leaseSeconds) {
    return claim(
        null, owner, token, leaseSeconds);
  }

  public Optional<CustomerIssueDelayClosureProgressClaim> claimBySource(
      String sourceInstance, String owner, String token, int leaseSeconds) {
    return claim(
        GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance),
        owner,
        token,
        leaseSeconds);
  }

  private Optional<CustomerIssueDelayClosureProgressClaim> claim(
      String sourceInstance, String owner, String token, int leaseSeconds) {
    String sourceFilter = sourceInstance == null ? "" : " and source_instance = ? ";
    String sql =
        """
        update customer_issue_delay_closure_requests
           set lease_owner = ?,
               lease_token = ?,
               lease_until = current_timestamp + (? * interval '1 second'),
               processed_revision = request_revision,
               updated_at = current_timestamp
         where id = (
           select id
             from customer_issue_delay_closure_requests
            where (stage <> 'IDLE' or request_revision > completed_revision)
              and (next_check_at is null or next_check_at <= current_timestamp)
              and (lease_until is null or lease_until < current_timestamp)
        """
            + sourceFilter
            + """
            order by next_check_at asc nulls first, id asc
            for update skip locked
            limit 1
         )
        returning *
        """;
    Object[] args = sourceInstance == null
        ? new Object[] {owner, token, Math.max(1, leaseSeconds)}
        : new Object[] {owner, token, Math.max(1, leaseSeconds), sourceInstance};
    List<CustomerIssueDelayClosureProgress> rows =
        jdbcTemplate.query(sql, (rs, rowNum) -> mapProgress(rs), args);
    return rows.stream()
        .findFirst()
        .map(progress -> new CustomerIssueDelayClosureProgressClaim(progress, owner, token));
  }

  /**
   * 开始新一轮：先撤销上一轮授权，再提交本轮前置镜像，避免旧授权在新镜像运行期间继续发送。
   */
  public Optional<CustomerIssueDelayClosureProgress> beginNewRound(
      CustomerIssueDelayClosureProgressClaim claim) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'REQUESTED',
               mirror_run_id = null,
               authorized_fact_version = null,
               authorization_revision = null,
               enqueue_cursor_issue_id = null,
               failure_count = 0,
               last_error = null,
               updated_at = current_timestamp
         where
        """
            + FENCE
            + " returning *");
  }

  public Optional<CustomerIssueDelayClosureProgress> bindMirror(
      CustomerIssueDelayClosureProgressClaim claim, long mirrorRunId) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'WAIT_MIRROR',
               mirror_run_id = ?,
               enqueue_cursor_issue_id = null,
               authorized_fact_version = null,
               authorization_revision = null,
               failure_count = 0,
               next_check_at = current_timestamp,
               last_error = null,
               updated_at = current_timestamp
         where
        """
            + FENCE
            + " returning *",
        mirrorRunId);
  }

  public Optional<CustomerIssueDelayClosureProgress> moveToFactWait(
      CustomerIssueDelayClosureProgressClaim claim, String reason) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'WAIT_FACT',
               next_check_at = current_timestamp,
               last_error = ?,
               updated_at = current_timestamp
         where
        """
            + FENCE
            + " returning *",
        trimError(reason));
  }

  public Optional<CustomerIssueDelayClosureProgress> authorizeEnqueue(
      CustomerIssueDelayClosureProgressClaim claim, long factVersion) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'ENQUEUE',
               authorized_fact_version = ?,
               authorization_revision = processed_revision,
               enqueue_cursor_issue_id = null,
               failure_count = 0,
               next_check_at = current_timestamp,
               last_error = null,
               updated_at = current_timestamp
         where
        """
            + FENCE
            + " returning *",
        factVersion);
  }

  public Optional<CustomerIssueDelayClosureProgress> advanceCursor(
      CustomerIssueDelayClosureProgressClaim claim, long issueId) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set enqueue_cursor_issue_id = ?,
               next_check_at = current_timestamp,
               last_error = null,
               updated_at = current_timestamp
         where
        """
            + FENCE
            + " returning *",
        issueId);
  }

  public Optional<CustomerIssueDelayClosureProgress> completeRound(
      CustomerIssueDelayClosureProgressClaim claim) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = case when request_revision > processed_revision then 'REQUESTED' else 'IDLE' end,
               completed_revision = processed_revision,
               enqueue_cursor_issue_id = null,
               failure_count = 0,
               next_check_at = current_timestamp,
               last_error = null,
               updated_at = current_timestamp,
               lease_owner = null,
               lease_token = null,
               lease_until = null
         where
        """
            + FENCE
            + " returning *");
  }

  public Optional<CustomerIssueDelayClosureProgress> waitFor(
      CustomerIssueDelayClosureProgressClaim claim, String reason, LocalDateTime nextCheckAt) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set next_check_at = ?,
               last_error = ?,
               updated_at = current_timestamp,
               lease_owner = null,
               lease_token = null,
               lease_until = null
         where
        """
            + FENCE
            + " returning *",
        Timestamp.valueOf(nextCheckAt),
        trimError(reason));
  }

  /**
   * 撤销授权并重新开始新一轮前置刷新。
   */
  public Optional<CustomerIssueDelayClosureProgress> restartRound(
      CustomerIssueDelayClosureProgressClaim claim,
      String reason,
      int failureCount,
      LocalDateTime nextCheckAt) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'REQUESTED',
               mirror_run_id = null,
               authorized_fact_version = null,
               authorization_revision = null,
               enqueue_cursor_issue_id = null,
               failure_count = ?,
               next_check_at = ?,
               last_error = ?,
               updated_at = current_timestamp,
               lease_owner = null,
               lease_token = null,
               lease_until = null
         where
        """
            + FENCE
            + " returning *",
        failureCount,
        Timestamp.valueOf(nextCheckAt),
        trimError(reason));
  }

  /**
   * 撤销授权并回到事实等待（保留本轮已成功的前置镜像）。
   */
  public Optional<CustomerIssueDelayClosureProgress> revokeToFactWait(
      CustomerIssueDelayClosureProgressClaim claim,
      String reason,
      int failureCount,
      LocalDateTime nextCheckAt) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'WAIT_FACT',
               authorized_fact_version = null,
               authorization_revision = null,
               enqueue_cursor_issue_id = null,
               failure_count = ?,
               next_check_at = ?,
               last_error = ?,
               updated_at = current_timestamp,
               lease_owner = null,
               lease_token = null,
               lease_until = null
         where
        """
            + FENCE
            + " returning *",
        failureCount,
        Timestamp.valueOf(nextCheckAt),
        trimError(reason));
  }

  public Optional<CustomerIssueDelayClosureProgress> revokeToIdle(
      CustomerIssueDelayClosureProgressClaim claim, String reason) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'IDLE',
               completed_revision = processed_revision,
               mirror_run_id = null,
               authorized_fact_version = null,
               authorization_revision = null,
               enqueue_cursor_issue_id = null,
               failure_count = 0,
               next_check_at = current_timestamp,
               last_error = ?,
               updated_at = current_timestamp,
               lease_owner = null,
               lease_token = null,
               lease_until = null
         where
        """
            + FENCE
            + " returning *",
        trimError(reason));
  }

  /**
   * 写回关闭时只保留延期事实重算：不取得标签写入授权。
   */
  public Optional<CustomerIssueDelayClosureProgress> finishFactOnly(
      CustomerIssueDelayClosureProgressClaim claim, String reason) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'IDLE',
               completed_revision = processed_revision,
               authorized_fact_version = null,
               authorization_revision = null,
               enqueue_cursor_issue_id = null,
               failure_count = 0,
               next_check_at = current_timestamp,
               last_error = ?,
               updated_at = current_timestamp,
               lease_owner = null,
               lease_token = null,
               lease_until = null
         where
        """
            + FENCE
            + " returning *",
        trimError(reason));
  }

  public Optional<CustomerIssueDelayClosureProgress> recordFailure(
      CustomerIssueDelayClosureProgressClaim claim,
      String reason,
      int failureCount,
      LocalDateTime nextCheckAt) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set failure_count = ?,
               next_check_at = ?,
               last_error = ?,
               updated_at = current_timestamp,
               lease_owner = null,
               lease_token = null,
               lease_until = null
         where
        """
            + FENCE
            + " returning *",
        failureCount,
        Timestamp.valueOf(nextCheckAt),
        trimError(reason));
  }

  public Optional<CustomerIssueDelayClosureProgress> requestNewRound(
      CustomerIssueDelayClosureProgressClaim claim) {
    return update(
        claim,
        """
        update customer_issue_delay_closure_requests
           set stage = 'REQUESTED',
               next_check_at = current_timestamp,
               updated_at = current_timestamp
         where
        """
            + FENCE
            + " returning *");
  }

  /** 运行终态只唤醒检查；是否推进由权威进度与运行状态决定。 */
  public void wake(String sourceInstance) {
    jdbcTemplate.update(
        """
        update customer_issue_delay_closure_requests
           set next_check_at = current_timestamp,
               updated_at = current_timestamp
         where source_instance = ?
        """,
        GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance));
  }

  /** 释放本次领取；过期租约或已被接管时返回 {@code false}。 */
  public boolean release(CustomerIssueDelayClosureProgressClaim claim) {
    int updated =
        jdbcTemplate.update(
            """
            update customer_issue_delay_closure_requests
               set lease_owner = null,
                   lease_token = null,
                   lease_until = null,
                   updated_at = current_timestamp
             where
            """
                + FENCE,
            claim.progress().id(),
            claim.owner(),
            claim.token(),
            claim.progress().processedRevision());
    return updated > 0;
  }

  private Optional<CustomerIssueDelayClosureProgress> update(
      CustomerIssueDelayClosureProgressClaim claim, String sql, Object... args) {
    Object[] parameters =
        new Object[args.length + 4];
    System.arraycopy(args, 0, parameters, 0, args.length);
    parameters[args.length] = claim.progress().id();
    parameters[args.length + 1] = claim.owner();
    parameters[args.length + 2] = claim.token();
    parameters[args.length + 3] = claim.progress().processedRevision();
    List<CustomerIssueDelayClosureProgress> rows =
        jdbcTemplate.query(sql, (rs, rowNum) -> mapProgress(rs), parameters);
    if (rows.isEmpty()) {
      log.info(
          "Customer issue delay closure progress ownership lost, sourceInstance={}, stage={}, owner={}",
          claim.progress().sourceInstance(),
          claim.progress().stage(),
          claim.owner());
    }
    return rows.stream().findFirst();
  }

  private CustomerIssueDelayClosureProgress mapProgress(ResultSet rs) throws SQLException {
    return new CustomerIssueDelayClosureProgress(
        rs.getLong("id"),
        rs.getString("source_instance"),
        nullableLong(rs, "config_id"),
        CustomerIssueDelayClosureStage.valueOf(rs.getString("stage")),
        rs.getLong("request_revision"),
        rs.getLong("processed_revision"),
        rs.getLong("completed_revision"),
        nullableLong(rs, "mirror_run_id"),
        nullableLong(rs, "authorized_fact_version"),
        nullableLong(rs, "authorization_revision"),
        nullableLong(rs, "enqueue_cursor_issue_id"),
        rs.getInt("failure_count"),
        toLocalDateTime(rs.getTimestamp("next_check_at")),
        rs.getString("lease_owner"),
        rs.getString("lease_token"),
        toLocalDateTime(rs.getTimestamp("lease_until")),
        rs.getString("last_error"));
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private String trimError(String message) {
    if (!org.springframework.util.StringUtils.hasText(message)) {
      return null;
    }
    String trimmed = message.trim().replaceAll("\\s+", " ");
    return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000);
  }
}
