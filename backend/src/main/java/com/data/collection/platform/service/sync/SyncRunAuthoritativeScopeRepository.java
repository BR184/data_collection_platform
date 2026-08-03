package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.JsonUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 权威范围工作集的唯一持久化入口。 */
@Repository
public class SyncRunAuthoritativeScopeRepository {
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public SyncRunAuthoritativeScopeRepository(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  /**
   * 原子登记一批规范范围；同一运行、子表和范围只保留一行。
   *
   * @return 本次实际新增的范围数
   */
  public int enqueueScopes(
      long runId,
      String sourceInstance,
      Long producerTaskId,
      String childTable,
      String relationKey,
      List<Map<String, Object>> lookupScopes) {
    if (runId <= 0L
        || sourceInstance == null
        || sourceInstance.isBlank()
        || childTable == null
        || childTable.isBlank()
        || relationKey == null
        || relationKey.isBlank()
        || lookupScopes == null
        || lookupScopes.isEmpty()) {
      return 0;
    }
    List<Map<String, Object>> payload = lookupScopes.stream()
        .map(this::registrationPayload)
        .distinct()
        .toList();
    Integer inserted =
        jdbcTemplate.queryForObject(
            """
            with inserted as (
              insert into sync_run_authoritative_scopes(
                  run_id, source_instance, child_table, relation_key,
                  scope_signature, lookup_scope_json, task_id,
                  status, run_after, created_at, updated_at)
              select ?, ?, ?, ?,
                     item.scope_signature,
                     item.lookup_scope::text,
                     ?,
                     'QUEUED', current_timestamp, current_timestamp, current_timestamp
                from jsonb_to_recordset(?::jsonb)
                     as item(scope_signature text, lookup_scope jsonb)
              on conflict do nothing
              returning id
            )
            select count(*) from inserted
            """,
            Integer.class,
            runId,
            sourceInstance,
            childTable,
            relationKey,
            producerTaskId,
            jsonUtils.toJson(payload));
    return inserted == null ? 0 : inserted;
  }

  /** 返回当前运行实际选择的全部扫描来源表。 */
  public java.util.Set<String> selectedSourceTables(long runId) {
    return jdbcTemplate.query(
        """
        select distinct source_table
          from sync_run_table_tasks
         where run_id = ?
           and task_stage = 'SCAN'
         order by source_table
        """,
        resultSet -> {
          LinkedHashSet<String> tables = new LinkedHashSet<>();
          while (resultSet.next()) {
            tables.add(resultSet.getString("source_table"));
          }
          return java.util.Collections.unmodifiableSet(tables);
        },
        runId);
  }

  /** 按子表和关系定义领取一个有界批次。 */
  @Transactional
  public List<SyncRunAuthoritativeScope> claimNextBatch(
      long runId, String owner, int leaseSeconds, int batchSize) {
    if (runId <= 0L || owner == null || owner.isBlank()) {
      return List.of();
    }
    return jdbcTemplate.query(
        """
        with candidate_group as (
          select child_table, relation_key
            from sync_run_authoritative_scopes
           where run_id = ?
             and (
               status = 'QUEUED'
               or (status = 'RETRY_WAITING' and run_after <= current_timestamp)
             )
           order by run_after, child_table, relation_key, id
           for update skip locked
           limit 1
        ), selected as (
          select scope.id
            from sync_run_authoritative_scopes scope
            join candidate_group candidate
              on candidate.child_table = scope.child_table
             and candidate.relation_key = scope.relation_key
           where scope.run_id = ?
             and (
               scope.status = 'QUEUED'
               or (scope.status = 'RETRY_WAITING' and scope.run_after <= current_timestamp)
             )
           order by scope.run_after, scope.id
           for update of scope skip locked
           limit ?
        ), claimed as (
          update sync_run_authoritative_scopes scope
             set status = 'RUNNING',
                 lease_owner = ?,
                 lease_expires_at = current_timestamp + (? * interval '1 second'),
                 heartbeat_at = current_timestamp,
                 started_at = coalesce(started_at, current_timestamp),
                 error_message = null,
                 updated_at = current_timestamp
            from selected
           where scope.id = selected.id
          returning scope.*
        )
        select * from claimed order by id
        """,
        this::mapScope,
        runId,
        runId,
        Math.max(1, batchSize),
        owner,
        Math.max(3, leaseSeconds));
  }

  /** 续期当前 owner 持有的整个范围批次。 */
  public boolean renewBatchLease(
      List<Long> scopeIds, String owner, int leaseSeconds) {
    if (scopeIds == null || scopeIds.isEmpty() || owner == null || owner.isBlank()) {
      return false;
    }
    int updated =
        jdbcTemplate.update(
            """
            update sync_run_authoritative_scopes
               set lease_expires_at = current_timestamp + (? * interval '1 second'),
                   heartbeat_at = current_timestamp,
                   updated_at = current_timestamp
             where id in (
               select value::bigint from jsonb_array_elements_text(?::jsonb)
             )
               and status = 'RUNNING'
               and lease_owner = ?
               and lease_expires_at >= current_timestamp
            """,
            Math.max(3, leaseSeconds),
            jsonUtils.toJson(scopeIds),
            owner);
    return updated == scopeIds.size();
  }

  /** 在提交事务内锁定并校验整个批次仍归当前 owner 所有。 */
  public void lockOwnedBatch(List<Long> scopeIds, String owner, long runId) {
    Integer owned =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from (
                select id
                  from sync_run_authoritative_scopes
                 where id in (
                   select value::bigint from jsonb_array_elements_text(?::jsonb)
                 )
                   and status = 'RUNNING'
                   and lease_owner = ?
                   and lease_expires_at >= current_timestamp
                 for update
              ) locked
            """,
            Integer.class,
            jsonUtils.toJson(scopeIds),
            owner);
    if (owned == null || owned != scopeIds.size()) {
      throw new SyncAuthoritativeScopeLeaseLostException(runId);
    }
  }

  /** 完成当前 owner 持有的整个范围批次。 */
  public void completeOwnedBatch(List<Long> scopeIds, String owner, long runId) {
    int updated =
        jdbcTemplate.update(
            """
            update sync_run_authoritative_scopes
               set status = 'SUCCESS',
                   lease_owner = null,
                   lease_expires_at = null,
                   heartbeat_at = null,
                   error_message = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where id in (
               select value::bigint from jsonb_array_elements_text(?::jsonb)
             )
               and status = 'RUNNING'
               and lease_owner = ?
               and lease_expires_at >= current_timestamp
            """,
            jsonUtils.toJson(scopeIds),
            owner);
    if (updated != scopeIds.size()) {
      throw new SyncAuthoritativeScopeLeaseLostException(runId);
    }
  }

  /** 执行失败时保留原范围身份并进入有界退避或最终失败。 */
  public void failOwnedBatch(
      List<Long> scopeIds, String owner, long runId, String errorMessage) {
    int updated =
        jdbcTemplate.update(
            """
            update sync_run_authoritative_scopes
               set retry_count = retry_count + 1,
                   status = case
                     when retry_count + 1 >= max_retry_count then 'FAILED'
                     else 'RETRY_WAITING'
                   end,
                   run_after = case
                     when retry_count + 1 >= max_retry_count then run_after
                     else current_timestamp
                          + (least(300, 5 * power(2, retry_count)) * interval '1 second')
                   end,
                   lease_owner = null,
                   lease_expires_at = null,
                   heartbeat_at = null,
                   error_message = ?,
                   finished_at = case
                     when retry_count + 1 >= max_retry_count then current_timestamp
                     else null
                   end,
                   updated_at = current_timestamp
             where id in (
               select value::bigint from jsonb_array_elements_text(?::jsonb)
             )
               and status = 'RUNNING'
               and lease_owner = ?
            """,
            errorMessage,
            jsonUtils.toJson(scopeIds),
            owner);
    if (updated != scopeIds.size()) {
      throw new SyncAuthoritativeScopeLeaseLostException(runId);
    }
  }

  /** 汇总运行的权威范围状态。 */
  public ScopeSummary summarize(long runId) {
    return jdbcTemplate.queryForObject(
        """
        select count(*) as total,
               count(*) filter (where status = 'SUCCESS') as succeeded,
               count(*) filter (where status = 'FAILED') as failed,
               count(*) filter (where status = 'QUEUED') as queued,
               count(*) filter (where status = 'RUNNING') as running,
               count(*) filter (where status = 'RETRY_WAITING') as retry_waiting,
               min(run_after) filter (where status = 'RETRY_WAITING') as next_run_after
          from sync_run_authoritative_scopes
         where run_id = ?
        """,
        (resultSet, rowNumber) ->
            new ScopeSummary(
                resultSet.getInt("total"),
                resultSet.getInt("succeeded"),
                resultSet.getInt("failed"),
                resultSet.getInt("queued"),
                resultSet.getInt("running"),
                resultSet.getInt("retry_waiting"),
                toDateTime(resultSet.getTimestamp("next_run_after"))),
        runId);
  }

  /** 把超时租约恢复为同一范围上的重试或最终失败。 */
  public int recoverExpiredLeases() {
    return jdbcTemplate.update(
        """
        update sync_run_authoritative_scopes
           set retry_count = retry_count + 1,
               status = case
                 when retry_count + 1 >= max_retry_count then 'FAILED'
                 else 'RETRY_WAITING'
               end,
               run_after = current_timestamp,
               lease_owner = null,
               lease_expires_at = null,
               heartbeat_at = null,
               error_message = coalesce(error_message, '权威范围租约超时'),
               finished_at = case
                 when retry_count + 1 >= max_retry_count then current_timestamp
                 else null
               end,
               updated_at = current_timestamp
         where status = 'RUNNING'
           and (lease_expires_at is null or lease_expires_at < current_timestamp)
        """);
  }

  /** 父运行终态化时关闭尚未完成的范围，避免遗留活动租约。 */
  public int terminalizeRun(long runId, String errorMessage) {
    return jdbcTemplate.update(
        """
        update sync_run_authoritative_scopes
           set status = 'FAILED',
               lease_owner = null,
               lease_expires_at = null,
               heartbeat_at = null,
               error_message = coalesce(error_message, ?),
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where run_id = ?
           and status in ('QUEUED', 'RUNNING', 'RETRY_WAITING')
        """,
        errorMessage,
        runId);
  }

  private Map<String, Object> registrationPayload(Map<String, Object> lookupScope) {
    if (lookupScope == null || lookupScope.isEmpty()) {
      throw new IllegalArgumentException("权威范围不能为空");
    }
    LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
    new java.util.TreeMap<>(lookupScope)
        .forEach(
            (column, value) -> {
              if (column == null
                  || column.isBlank()
                  || value == null
                  || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("权威范围包含无效列或值");
              }
              normalized.put(column, value);
            });
    String signature = jsonUtils.toJson(normalized);
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("scope_signature", signature);
    payload.put("lookup_scope", normalized);
    return java.util.Collections.unmodifiableMap(payload);
  }

  private SyncRunAuthoritativeScope mapScope(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new SyncRunAuthoritativeScope(
        resultSet.getLong("id"),
        resultSet.getLong("run_id"),
        resultSet.getString("source_instance"),
        resultSet.getString("child_table"),
        resultSet.getString("relation_key"),
        resultSet.getString("scope_signature"),
        jsonUtils.toMap(resultSet.getString("lookup_scope_json")),
        resultSet.getObject("task_id") == null ? null : resultSet.getLong("task_id"),
        resultSet.getString("lease_owner"),
        toDateTime(resultSet.getTimestamp("lease_expires_at")),
        resultSet.getInt("retry_count"),
        resultSet.getInt("max_retry_count"));
  }

  private LocalDateTime toDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  /** 运行级权威范围汇总。 */
  public record ScopeSummary(
      int total,
      int succeeded,
      int failed,
      int queued,
      int running,
      int retryWaiting,
      LocalDateTime nextRunAfter) {
    public boolean allSucceeded() {
      return failed == 0 && queued == 0 && running == 0 && retryWaiting == 0;
    }
  }
}
