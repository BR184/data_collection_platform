package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactManualDisposition;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 运行维度故障定位查询：四类异常/待处理项、相关事件与人工待处理列表。
 *
 * <p>所有限量与计数都在数据库内按运行分区完成，Java 只做分页切片的补充字段计算；日志列表因此不会先拉取
 * 全部任务再截断，也不会按运行逐条查询（N+1）。四类定位项的字段按各自表结构显式构造，不用通用字段袋承载
 * 类型专属语义。
 */
@Service
public class SyncRunFailureDiagnosticsService {
  public static final String KIND_TABLE_TASK = "TABLE_TASK";
  public static final String KIND_AUTHORITATIVE_SCOPE = "AUTHORITATIVE_SCOPE";
  public static final String KIND_FACT_BUILD = "FACT_BUILD";
  public static final String KIND_PROJECTION = "PROJECTION";

  /** 运行摘要每类最多展示的定位项数量。 */
  public static final int SUMMARY_ITEM_LIMIT = 5;
  /** 运行摘要展示的相关事件数量。 */
  public static final int SUMMARY_EVENT_LIMIT = 5;
  public static final int DEFAULT_PAGE_LIMIT = 20;
  public static final int MAX_PAGE_LIMIT = 100;

  private static final String PROGRESS_EVENT_TYPE = "FACT_BUILD_PROGRESS";
  private static final String FACT_HANDOVER_SNAPSHOT_EVENT = "FACT_TASK_RESUMED_SNAPSHOT";
  private static final String PROJECTION_HANDOVER_SNAPSHOT_EVENT =
      "FACT_PROJECTION_TASK_TAKEOVER_SNAPSHOT";

  /**
   * 四类定位项的公共列；类型专属字段由各分支用 {@code details} 显式构造。
   *
   * <p>运行归属统一用文本键比较：事实任务的 {@code run_id} 允许 UUID 手工路径，不能强转数字。
   */
  private static final String DIAGNOSTIC_UNION =
      """
      select 'TABLE_TASK' as kind, 1 as kind_rank, task.id as task_id, task.run_id::text as run_key,
             task.status as status, null::varchar as manual_disposition,
             task.retry_count as retry_count, task.max_retry_count as max_retry_count,
             task.last_error as raw_error, null::text as disposition_reason,
             task.started_at as started_at, task.finished_at as finished_at,
             task.heartbeat_at as heartbeat_at, task.lease_until as lease_until,
             task.updated_at as record_updated_at,
             jsonb_build_object(
                 'sourceTable', task.source_table,
                 'taskStage', task.task_stage,
                 'rowsScanned', task.rows_scanned,
                 'rowsApplied', task.rows_applied)::text as details
        from sync_run_table_tasks task
       where task.run_id::text = any(string_to_array(?, ','))
         and (task.status in ('FAILED', 'TIMEOUT')
              or (task.status = 'RETRYING' and task.last_error is not null))
      union all
      select 'AUTHORITATIVE_SCOPE', 2, scope.id, scope.run_id::text,
             scope.status, null::varchar,
             scope.retry_count, scope.max_retry_count,
             scope.error_message, null::text,
             scope.started_at, scope.finished_at,
             scope.heartbeat_at, scope.lease_expires_at,
             scope.updated_at,
             jsonb_build_object(
                 'childTable', scope.child_table,
                 'relationKey', scope.relation_key,
                 'scopeSignature', scope.scope_signature,
                 'lookupScope', scope.lookup_scope_json)::text as details
        from sync_run_authoritative_scopes scope
       where scope.run_id::text = any(string_to_array(?, ','))
         and scope.status in ('FAILED', 'RETRY_WAITING')
      union all
      select 'FACT_BUILD', 3, fact.id, fact.run_id,
             fact.status, fact.manual_disposition,
             fact.retry_count, fact.max_retry_count,
             fact.error_message, nullif(btrim(coalesce(fact.message, '')), ''),
             fact.started_at, fact.finished_at,
             fact.heartbeat_at, fact.lease_until,
             fact.updated_at,
             jsonb_build_object(
                 'sourceInstance', fact.source_instance,
                 'factType', fact.fact_type,
                 'fullBuild', fact.full_build,
                 'scope', fact.scope,
                 'affectedRows', fact.affected_rows,
                 'rootCount', (select count(*) from fact_build_task_roots roots where roots.task_id = fact.id))::text as details
        from fact_build_tasks fact
       where fact.run_id = any(string_to_array(?, ','))
         and fact.fact_type <> 'ALL'
         and (fact.status in ('FAILED', 'PAUSED')
              or (fact.status = 'RETRY_WAITING' and fact.error_message is not null)
              or fact.manual_disposition in ('REQUIRES_DECISION', 'RESUMED', 'CANCELLED'))
      union all
      select 'PROJECTION', 4, proj.id, proj.fact_run_id::text,
             proj.status, proj.manual_disposition,
             proj.retry_count, proj.max_retry_count,
             proj.error_message, null::text,
             proj.started_at, proj.finished_at,
             proj.heartbeat_at, proj.lease_until,
             proj.updated_at,
             jsonb_build_object(
                 'factBuildTaskId', proj.fact_build_task_id,
                 'sourceInstance', proj.source_instance,
                 'factType', proj.fact_type,
                 'scopeType', proj.scope_type,
                 'scopeKey', proj.scope_key,
                 'targetGeneration', proj.target_generation)::text as details
        from fact_projection_refresh_tasks proj
       where proj.fact_run_id::text = any(string_to_array(?, ','))
         and (proj.status = 'FAILED'
              or (proj.status = 'RETRY_WAITING' and proj.error_message is not null)
              or proj.manual_disposition = 'REQUIRES_DECISION')
      """;

  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public SyncRunFailureDiagnosticsService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  /**
   * 一次取回一批运行的四类摘要，供日志列表使用。
   *
   * @param runIds 运行数据库主键；为空时返回空映射
   * @return 运行主键到摘要的映射，无定位项的运行同样有摘要（计数为 0）
   */
  public Map<Long, RunDiagnostics> summarizeRuns(List<Long> runIds) {
    if (runIds == null || runIds.isEmpty()) {
      return Map.of();
    }
    String runKeys = joinRunKeys(runIds);
    Map<Long, List<Map<String, Object>>> itemsByRun = new LinkedHashMap<>();
    Map<Long, Map<String, Integer>> countsByRun = new LinkedHashMap<>();
    Map<Long, List<Map<String, Object>>> eventsByRun = new LinkedHashMap<>();
    Map<Long, Integer> eventCounts = new LinkedHashMap<>();
    for (Map<String, Object> row : queryDiagnostics(
        runKeys, SUMMARY_ITEM_LIMIT, 0, true)) {
      Long runId = asLong(row.get("runKey"));
      if (runId == null) {
        continue;
      }
      itemsByRun.computeIfAbsent(runId, key -> new ArrayList<>()).add(row);
    }
    for (Map<String, Object> row : countDiagnostics(runKeys)) {
      Long runId = asLong(row.get("run_key"));
      if (runId == null) {
        continue;
      }
      Map<String, Integer> counts =
          countsByRun.computeIfAbsent(runId, key -> new LinkedHashMap<>());
      counts.put((String) row.get("kind"), asInt(row.get("kind_total")));
      counts.merge("TOTAL", asInt(row.get("kind_total")), Integer::sum);
      counts.merge("FAILURE", asInt(row.get("kind_failures")), Integer::sum);
      counts.merge("MANUAL", asInt(row.get("kind_manual")), Integer::sum);
    }
    for (Map<String, Object> row : queryEvents(runKeys, SUMMARY_EVENT_LIMIT, 0)) {
      Long runId = asLong(row.get("run_key"));
      if (runId == null) {
        continue;
      }
      eventsByRun.computeIfAbsent(runId, key -> new ArrayList<>()).add(eventItem(row));
    }
    for (Map<String, Object> row : countEvents(runKeys)) {
      Long runId = asLong(row.get("run_key"));
      if (runId != null) {
        eventCounts.put(runId, asInt(row.get("event_count")));
      }
    }
    Map<Long, Map<String, Object>> progressByRun = latestProgress(runKeys);

    Map<Long, RunDiagnostics> result = new LinkedHashMap<>();
    for (Long runId : runIds) {
      List<Map<String, Object>> items = itemsByRun.getOrDefault(runId, List.of());
      Map<String, Integer> counts = countsByRun.getOrDefault(runId, Map.of());
      Map<String, Object> progress = progressByRun.getOrDefault(runId, Map.of());
      result.put(
          runId,
          new RunDiagnostics(
              counts.getOrDefault("FAILURE", 0),
              counts.getOrDefault("MANUAL", 0),
              counts.getOrDefault("TOTAL", 0),
              items,
              eventCounts.getOrDefault(runId, 0),
              eventsByRun.getOrDefault(runId, List.of()),
              (String) progress.get("message"),
              asDateTime(progress.get("created_at"))));
    }
    return result;
  }

  /** 按稳定顺序（类型、任务编号）分页读取一个运行的全部定位项，含人工移交时保存的原诊断快照。 */
  public List<Map<String, Object>> diagnostics(long runId, int offset, int limit) {
    String runKeys = String.valueOf(runId);
    List<Map<String, Object>> rows = queryDiagnostics(runKeys, limit, offset, false);
    rows = new ArrayList<>(rows);
    rows.addAll(handoverSnapshots(runId));
    rows.sort(
        Comparator.comparingInt((Map<String, Object> row) -> asInt(row.get("kindRank")))
            .thenComparing(row -> String.valueOf(row.get("taskId"))));
    return rows;
  }

  /** 定位项总数：四类记录数之和，人工移交快照按 {@code kind + taskId} 去重后计入。 */
  public long diagnosticCount(long runId) {
    long total = 0L;
    for (Map<String, Object> row : countDiagnostics(String.valueOf(runId))) {
      total += asInt(row.get("kind_total"));
    }
    return total + handoverSnapshots(runId).size();
  }

  public List<Map<String, Object>> events(long runId, int offset, int limit) {
    return queryEvents(String.valueOf(runId), limit, offset).stream()
        .map(this::eventItem)
        .toList();
  }

  public long eventCount(long runId) {
    List<Map<String, Object>> rows = countEvents(String.valueOf(runId));
    return rows.isEmpty() ? 0L : asInt(rows.getFirst().get("event_count"));
  }

  /** 按配置与来源分页读取人工待处理项；包含没有父运行行的事实任务，并给出继续/取消所需的原运行编号。 */
  public List<Map<String, Object>> pendingTasks(
      Long configId, String sourceInstance, int offset, int limit) {
    if (configId == null || sourceInstance == null || sourceInstance.isBlank()) {
      return List.of();
    }
    return jdbcTemplate.query(
        """
        select kind, kind_rank, task_id, expected_run_id, fact_type, scope, scope_type, scope_key,
               full_build, status, manual_disposition, raw_error, disposition_reason, retry_count,
               max_retry_count, started_at, finished_at, created_at
          from (
                select 'FACT_BUILD' as kind, 1 as kind_rank, fact.id as task_id,
                       fact.run_id as expected_run_id, fact.fact_type, fact.scope,
                       null::varchar as scope_type, null::varchar as scope_key,
                       fact.full_build, fact.status, fact.manual_disposition,
                       fact.error_message as raw_error,
                       nullif(btrim(coalesce(fact.message, '')), '') as disposition_reason,
                       fact.retry_count, fact.max_retry_count,
                       fact.started_at, fact.finished_at, fact.created_at
                  from fact_build_tasks fact
                 where fact.manual_disposition = 'REQUIRES_DECISION'
                   and fact.config_id = ?
                   and fact.source_instance = ?
                union all
                select 'PROJECTION', 2, proj.id, proj.fact_run_id::text, proj.fact_type, null,
                       proj.scope_type, proj.scope_key, null,
                       proj.status, proj.manual_disposition, proj.error_message, null,
                       proj.retry_count, proj.max_retry_count,
                       proj.started_at, proj.finished_at, proj.created_at
                  from fact_projection_refresh_tasks proj
                  join sync_runs parent_run on parent_run.id = proj.fact_run_id
                 where proj.manual_disposition = 'REQUIRES_DECISION'
                   and parent_run.config_id = ?
                   and proj.source_instance = ?
               ) pending
         order by kind_rank, task_id
         limit ? offset ?
        """,
        (rs, rowNum) -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("kind", rs.getString("kind"));
          item.put("kindRank", rs.getInt("kind_rank"));
          item.put("taskId", rs.getLong("task_id"));
          item.put("expectedRunId", rs.getString("expected_run_id"));
          item.put("factType", rs.getString("fact_type"));
          item.put("scope", rs.getString("scope"));
          item.put("scopeType", rs.getString("scope_type"));
          item.put("scopeKey", rs.getString("scope_key"));
          item.put("fullBuild", rs.getObject("full_build", Boolean.class));
          item.put("status", rs.getString("status"));
          item.put("manualDisposition", rs.getString("manual_disposition"));
          item.put("rawError", rs.getString("raw_error"));
          item.put("dispositionReason", rs.getString("disposition_reason"));
          item.put("retryCount", rs.getInt("retry_count"));
          item.put("maxRetryCount", rs.getInt("max_retry_count"));
          item.put("startedAt", timestampValue(rs.getTimestamp("started_at")));
          item.put("finishedAt", timestampValue(rs.getTimestamp("finished_at")));
          item.put("createdAt", timestampValue(rs.getTimestamp("created_at")));
          return item;
        },
        configId,
        sourceInstance,
        configId,
        sourceInstance,
        Math.max(1, Math.min(MAX_PAGE_LIMIT, limit)),
        Math.max(0, offset));
  }

  public long pendingTaskCount(Long configId, String sourceInstance) {
    if (configId == null || sourceInstance == null || sourceInstance.isBlank()) {
      return 0L;
    }
    Long count =
        jdbcTemplate.queryForObject(
            """
            select count(*) from (
              select fact.id
                from fact_build_tasks fact
               where fact.manual_disposition = 'REQUIRES_DECISION'
                 and fact.config_id = ?
                 and fact.source_instance = ?
              union all
              select proj.id
                from fact_projection_refresh_tasks proj
                join sync_runs parent_run on parent_run.id = proj.fact_run_id
               where proj.manual_disposition = 'REQUIRES_DECISION'
                 and parent_run.config_id = ?
                 and proj.source_instance = ?
            ) pending
            """,
            Long.class,
            configId,
            sourceInstance,
            configId,
            sourceInstance);
    return count == null ? 0L : count;
  }

  private List<Map<String, Object>> queryDiagnostics(
      String runKeys, int limit, int offset, boolean ranked) {
    String sql =
        ranked
            ? "select * from (select diagnostics.*, row_number() over "
                + "(partition by run_key order by kind_rank, task_id) as row_order from ("
                + DIAGNOSTIC_UNION
                + ") diagnostics) ranked where row_order <= ?"
            : "select * from (" + DIAGNOSTIC_UNION + ") diagnostics "
                + "order by kind_rank, task_id limit ? offset ?";
    List<Object> args = new ArrayList<>(List.of(runKeys, runKeys, runKeys, runKeys));
    args.add(Math.max(1, Math.min(MAX_PAGE_LIMIT, limit)));
    if (!ranked) {
      args.add(Math.max(0, offset));
    }
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
    return rows.stream().map(this::diagnosticItem).toList();
  }

  private List<Map<String, Object>> countDiagnostics(String runKeys) {
    return jdbcTemplate.queryForList(
        """
        select kind,
               count(*) as kind_total,
               count(*) filter (where status in ('FAILED', 'TIMEOUT')
                                  or (status in ('RETRYING', 'RETRY_WAITING') and raw_error is not null)) as kind_failures,
               count(*) filter (where manual_disposition is not null
                                  and manual_disposition <> 'NONE') as kind_manual,
               run_key
          from (%s) diagnostics
         group by run_key, kind, kind_rank
         order by run_key, kind_rank
        """
            .formatted(DIAGNOSTIC_UNION),
        runKeys,
        runKeys,
        runKeys,
        runKeys);
  }

  /**
   * 运行内的人工移交诊断快照。
   *
   * <p>投影继续会把原行移交给新运行，原失败行因此不再归属本运行；移交时写入的结构化事件是唯一的历史证据，
   * 必须与实时行一起展示，不能因移交把原错误抹掉。
   */
  private List<Map<String, Object>> handoverSnapshots(long runId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select id as event_id, event_type, message, payload_json, created_at
              from sync_run_events
             where event_type in (?, ?)
               and run_id = ?
             order by created_at desc, id desc
            """,
            FACT_HANDOVER_SNAPSHOT_EVENT,
            PROJECTION_HANDOVER_SNAPSHOT_EVENT,
            runId);
    List<Map<String, Object>> snapshots = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> payload = payloadOf((String) row.get("payload_json"));
      if (payload.isEmpty()) {
        continue;
      }
      boolean projection = PROJECTION_HANDOVER_SNAPSHOT_EVENT.equals(row.get("event_type"));
      Map<String, Object> item = new LinkedHashMap<>();
      String kind = projection ? KIND_PROJECTION : KIND_FACT_BUILD;
      item.put("kind", kind);
      item.put("kindRank", projection ? 4 : 3);
      item.put("taskId", asLong(payload.get("taskId")));
      item.put("runKey", payload.get("originalRunId"));
      item.put("originalRunId", payload.get("originalRunId"));
      item.put("currentRunId", payload.get("newRunId"));
      item.put("newRunId", payload.get("newRunId"));
      item.put("status", payload.get("originalStatus"));
      item.put("manualDisposition", payload.get("originalDisposition"));
      item.put("retryCount", asInt(payload.get("retryCount")));
      item.put("maxRetryCount", asInt(payload.get("maxRetryCount")));
      item.put("rawError", payload.get("rawError"));
      item.put("dispositionReason", row.get("message"));
      item.put("handoverSnapshot", true);
      item.put("recordUpdatedAt", timestampValue(row.get("created_at")));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put(
          projection ? "factBuildTaskId" : "factType",
          projection ? payload.get("factBuildTaskId") : payload.get("factType"));
      if (projection) {
        details.put("scopeType", payload.get("scopeType"));
        details.put("scopeKey", payload.get("scopeKey"));
        details.put("targetGeneration", payload.get("targetGeneration"));
      } else {
        details.put("scope", payload.get("scope"));
        details.put("fullBuild", payload.get("full"));
      }
      item.put("details", normalizeDetails(details));
      snapshots.add(item);
    }
    // 同一 run 内按 kind + taskId 去重，保留最近一次移交快照。
    Set<String> seen = new LinkedHashSet<>();
    return snapshots.stream()
        .filter(item -> seen.add(item.get("kind") + ":" + item.get("taskId")))
        .toList();
  }

  private List<Map<String, Object>> queryEvents(String runKeys, int limit, int offset) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select * from (
              select event.id as event_id, event.run_id::text as run_key, event.event_type,
                     event.message, event.payload_json, event.created_at,
                     row_number() over (partition by event.run_id order by event.created_at desc, event.id desc)
                       as row_order
                from sync_run_events event
               where event.event_type <> ?
                 and event.run_id::text = any(string_to_array(?, ','))
            ) events
             where row_order > ? and row_order <= ?
             order by run_key, row_order desc
            """,
            PROGRESS_EVENT_TYPE,
            runKeys,
            Math.max(0, offset),
            Math.max(0, offset) + Math.max(1, Math.min(MAX_PAGE_LIMIT, limit)));
    return rows;
  }

  private List<Map<String, Object>> countEvents(String runKeys) {
    return jdbcTemplate.queryForList(
        """
        select event.run_id::text as run_key, count(*) as event_count
          from sync_run_events event
         where event.event_type <> ?
           and event.run_id::text = any(string_to_array(?, ','))
         group by event.run_id
        """,
        PROGRESS_EVENT_TYPE,
        runKeys);
  }

  private Map<Long, Map<String, Object>> latestProgress(String runKeys) {
    Map<Long, Map<String, Object>> progress = new LinkedHashMap<>();
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select * from (
              select event.run_id as run_id, event.message, event.created_at,
                     row_number() over (partition by event.run_id order by event.created_at desc, event.id desc)
                       as row_order
                from sync_run_events event
               where event.event_type = ?
                 and event.run_id::text = any(string_to_array(?, ','))
            ) progress
             where row_order = 1
            """,
            PROGRESS_EVENT_TYPE,
            runKeys);
    for (Map<String, Object> row : rows) {
      Long runId = asLong(row.get("run_id"));
      if (runId != null) {
        progress.put(runId, row);
      }
    }
    return progress;
  }

  private Map<String, Object> diagnosticItem(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", row.get("kind"));
    item.put("kindRank", asInt(row.get("kind_rank")));
    item.put("taskId", asLong(row.get("task_id")));
    item.put("runKey", row.get("run_key"));
    item.put("originalRunId", row.get("run_key"));
    item.put("currentRunId", row.get("run_key"));
    item.put("status", row.get("status"));
    item.put("manualDisposition", row.get("manual_disposition"));
    item.put("retryCount", asInt(row.get("retry_count")));
    item.put("maxRetryCount", asInt(row.get("max_retry_count")));
    item.put("rawError", row.get("raw_error"));
    item.put("dispositionReason", dispositionReason(row));
    item.put("startedAt", timestampValue(row.get("started_at")));
    item.put("finishedAt", timestampValue(row.get("finished_at")));
    item.put("errorObservedAt", timestampValue(row.get("finished_at")));
    item.put("heartbeatAt", timestampValue(row.get("heartbeat_at")));
    item.put("leaseUntil", timestampValue(row.get("lease_until")));
    item.put("recordUpdatedAt", timestampValue(row.get("record_updated_at")));
    item.put("elapsedMs", elapsedMillis(row.get("started_at"), row.get("finished_at")));
    item.put("handoverSnapshot", false);
    Object details = row.get("details");
    item.put("details", details instanceof String text ? normalizeDetails(payloadOf(text)) : Map.of());
    return item;
  }

  /**
   * 处置原因文案：事实任务优先用任务自身 message，投影任务由持久处置状态推导。
   *
   * <p>界面文案不充当判定依据，这里只把已持久化的稳定状态码翻译成可读原因。
   */
  private String dispositionReason(Map<String, Object> row) {
    Object message = row.get("disposition_reason");
    if (message instanceof String text && !text.isBlank()) {
      return text;
    }
    String disposition =
        row.get("manual_disposition") == null ? null : String.valueOf(row.get("manual_disposition"));
    if (disposition == null || FactManualDisposition.NONE.name().equals(disposition)) {
      return null;
    }
    return switch (disposition) {
      case "REQUIRES_DECISION" -> "已停止自动派发，等待维护人员决定继续或取消";
      case "RESUMED" -> "已由维护人员移交继续执行";
      case "CANCELLED" -> "维护人员已取消本次执行意图";
      default -> disposition;
    };
  }

  private Map<String, Object> eventItem(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("eventId", asLong(row.get("event_id")));
    item.put("eventType", row.get("event_type"));
    item.put("message", row.get("message"));
    item.put("createdAt", timestampValue(row.get("created_at")));
    return item;
  }

  private Map<String, Object> payloadOf(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> payload = jsonUtils.toMap(payloadJson);
      return payload == null ? Map.of() : payload;
    } catch (IllegalStateException ignored) {
      return Map.of();
    }
  }

  /**
   * 统一 {@code details} 中整数的 Java 类型。
   *
   * <p>库内 jsonb 与移交快照的 payload 都经 JSON 解析，小整数会落成 {@code Integer}；两处合并展示时同一字段必须
   * 是同一种类型，因此在构造点统一成 {@code Long}，布尔与文本保持不变。
   */
  private static Map<String, Object> normalizeDetails(Map<String, Object> details) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : details.entrySet()) {
      normalized.put(entry.getKey(), normalizeIntegralNumber(entry.getValue()));
    }
    return normalized;
  }

  private static Object normalizeIntegralNumber(Object value) {
    if (value instanceof Integer || value instanceof Long) {
      return ((Number) value).longValue();
    }
    if (value instanceof java.math.BigInteger bigInteger) {
      return bigInteger.longValue();
    }
    return value;
  }

  private Long elapsedMillis(Object startedAt, Object finishedAt) {
    LocalDateTime start = asDateTime(startedAt);
    LocalDateTime end = asDateTime(finishedAt);
    if (start == null || end == null) {
      return null;
    }
    long millis = java.time.Duration.between(start, end).toMillis();
    return millis < 0 ? null : millis;
  }

  private String joinRunKeys(List<Long> runIds) {
    StringBuilder builder = new StringBuilder();
    for (Long runId : runIds) {
      if (runId == null) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(runId);
    }
    return builder.toString();
  }

  private static Integer asInt(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static Long asLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static LocalDateTime asDateTime(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime;
    }
    return null;
  }

  private static Object timestampValue(Object value) {
    return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : value;
  }

  /**
   * 单个运行的诊断摘要。
   *
   * @param failureCount 四类中的真实失败项数量（FAILED/TIMEOUT/失败重试）
   * @param manualAttentionCount 等待维护人员决定的项数量；与 failureCount 可重叠，不能相加当作任务总数
   * @param diagnosticCount 去重后的可查明细总数
   * @param diagnostics 摘要展示的定位项（每类最多 {@link #SUMMARY_ITEM_LIMIT} 条）
   * @param eventCount 相关事件总数（不含高频进度事件）
   * @param eventTrail 最近的相关事件，正序展示
   * @param latestProgressMessage 最新进度事件文案；无进度记录时为空
   * @param latestProgressAt 最新进度事件时间
   */
  public record RunDiagnostics(
      int failureCount,
      int manualAttentionCount,
      int diagnosticCount,
      List<Map<String, Object>> diagnostics,
      int eventCount,
      List<Map<String, Object>> eventTrail,
      String latestProgressMessage,
      LocalDateTime latestProgressAt) {

    public static RunDiagnostics empty() {
      return new RunDiagnostics(0, 0, 0, List.of(), 0, List.of(), null, null);
    }

    /** 是否比"无异常"多出需要维护人员关注的内容。 */
    public boolean hasAttention() {
      return failureCount > 0 || manualAttentionCount > 0;
    }
  }

  /**
   * 规范化分页参数。
   *
   * @param limit 请求值；为空时取 {@link #DEFAULT_PAGE_LIMIT}，超过 {@link #MAX_PAGE_LIMIT} 时截断
   * @param offset 请求值；为空或负数时按 0 处理
   */
  public static int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_PAGE_LIMIT;
    }
    if (limit < 1) {
      throw new IllegalArgumentException("分页大小必须为正整数");
    }
    return Math.min(MAX_PAGE_LIMIT, limit);
  }

  public static int normalizeOffset(Integer offset) {
    if (offset == null || offset < 0) {
      if (offset != null && offset < 0) {
        throw new IllegalArgumentException("分页偏移必须为非负整数");
      }
      return 0;
    }
    return offset;
  }

  /** 详情区段标识。 */
  public static String normalizeSection(String section) {
    if (section == null || section.isBlank()) {
      return "DIAGNOSTICS";
    }
    String normalized = section.trim().toUpperCase(Locale.ROOT);
    if (!"DIAGNOSTICS".equals(normalized) && !"EVENTS".equals(normalized)) {
      throw new IllegalArgumentException("不支持的日志详情区段：" + section);
    }
    return normalized;
  }
}
