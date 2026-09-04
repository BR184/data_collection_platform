package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PageRecordSnapshotService {
  public static final String SNAPSHOT_TYPE_LIST = "LIST";
  public static final String SNAPSHOT_TYPE_FILTER_OPTIONS = "FILTER_OPTIONS";
  public static final String FACT_TYPE_ISSUE = "ISSUE";
  public static final String FACT_TYPE_MERGE_REQUEST = "MERGE_REQUEST";

  // 指纹查询专用短超时：指纹被数据库瞬时问题阻塞时快速失败并降级读旧快照，
  // 而不是拖住整个页面请求直到前端超时。主模板保持 30s，仅指纹路径收窄到 3s。
  private static final int FINGERPRINT_QUERY_TIMEOUT_SECONDS = 3;
  private static final long FINGERPRINT_DEGRADE_BUCKET_MS = 300_000L;
  private static final String DEGRADED_SEGMENT_PREFIX = "degraded-";

  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final FactProjectionVersionService projectionVersionService;
  private final IssueProjectionScopeResolver issueScopeResolver;
  private final JdbcTemplate fingerprintJdbcTemplate;
  private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlightRebuilds =
      new ConcurrentHashMap<>();

  public PageRecordSnapshotService(
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      FactProjectionVersionService projectionVersionService,
      IssueProjectionScopeResolver issueScopeResolver) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.projectionVersionService = projectionVersionService;
    this.issueScopeResolver = issueScopeResolver;
    this.fingerprintJdbcTemplate = buildFingerprintJdbcTemplate(jdbcTemplate);
  }

  private static JdbcTemplate buildFingerprintJdbcTemplate(JdbcTemplate mainTemplate) {
    DataSource dataSource = mainTemplate.getDataSource();
    if (dataSource == null) {
      return mainTemplate;
    }
    JdbcTemplate fingerprintTemplate = new JdbcTemplate(dataSource);
    fingerprintTemplate.setQueryTimeout(FINGERPRINT_QUERY_TIMEOUT_SECONDS);
    return fingerprintTemplate;
  }

  public <T> T readOrRefresh(
      SnapshotRequest request,
      Class<T> responseType,
      Supplier<T> responseSupplier) {
    if (isDegradedVersion(request.sourceVersion())) {
      Optional<T> degraded = findLatestSnapshot(request, responseType);
      if (degraded.isPresent()) {
        return degraded.get();
      }
    }
    Optional<T> snapshot = findReadyOrInvalidate(request, responseType);
    if (snapshot.isPresent()) {
      return snapshot.get();
    }
    return rebuildWithSingleFlight(request, responseType, responseSupplier);
  }

  /**
   * 并发未命中合并：同一快照键的并发请求共享一次重建，避免写后失效引发重建惊群。
   * 失败结果同样共享；合并窗口结束后的新请求会重新尝试重建。
   */
  @SuppressWarnings("unchecked")
  private <T> T rebuildWithSingleFlight(
      SnapshotRequest request,
      Class<T> responseType,
      Supplier<T> responseSupplier) {
    String rebuildKey = request.pageKey() + "|" + request.snapshotType() + "|" + request.scopeKey()
        + "|" + request.ruleVersion() + "|" + requestHash(request.requestPayload());
    CompletableFuture<Object> future = new CompletableFuture<>();
    CompletableFuture<Object> winner = inFlightRebuilds.computeIfAbsent(rebuildKey, key -> future);
    if (winner != future) {
      try {
        return (T) winner.join();
      } catch (CompletionException error) {
        throw asRuntimeException(error);
      }
    }
    try {
      T response = responseSupplier.get();
      save(request, response);
      future.complete(response);
      return response;
    } catch (RuntimeException error) {
      future.completeExceptionally(error);
      throw error;
    } finally {
      inFlightRebuilds.remove(rebuildKey, future);
    }
  }

  private static RuntimeException asRuntimeException(CompletionException error) {
    Throwable cause = error.getCause() == null ? error : error.getCause();
    if (cause instanceof RuntimeException runtime) {
      return runtime;
    }
    return new IllegalStateException(cause);
  }

  /** 指纹降级时读取最近一次快照（含 STALE）：宁可返回旧数据，也不让页面挂在指纹查询上。 */
  private <T> Optional<T> findLatestSnapshot(SnapshotRequest request, Class<T> responseType) {
    List<String> rows =
        jdbcTemplate.queryForList(
            """
            select response_payload::text
              from page_record_snapshots
             where page_key = ?
               and snapshot_type = ?
               and scope_key = ?
               and rule_version = ?
               and request_hash = ?
             order by refreshed_at desc, id desc
             limit 1
            """,
            String.class,
            request.pageKey(),
            request.snapshotType(),
            request.scopeKey(),
            request.ruleVersion(),
            requestHash(request.requestPayload()));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(jsonUtils.fromJson(rows.getFirst(), responseType));
    } catch (RuntimeException error) {
      log.warn("Degraded snapshot payload could not be parsed for page {}", request.pageKey(), error);
      return Optional.empty();
    }
  }

  /** 降级版本指纹：段内稳定可缓存；指纹查询恢复后自动回到精确失效语义。 */
  public static boolean isDegradedVersion(String sourceVersion) {
    return sourceVersion != null && sourceVersion.contains(DEGRADED_SEGMENT_PREFIX);
  }

  /**
   * 版本指纹分段计算器：任一段查询失败后短路余下分段并统一输出降级标记。
   * 降级标记按 5 分钟时间桶取值，同桶内请求共享同一版本串，不会因降级引发缓存重建风暴。
   */
  private class SourceVersionFingerprint {
    private boolean degraded;

    String segment(String name, Supplier<String> query) {
      if (degraded) {
        return degradedSegment();
      }
      try {
        return query.get();
      } catch (RuntimeException error) {
        degraded = true;
        log.warn("Snapshot fingerprint segment {} failed; degrade version until bucket rolls over", name, error);
        return degradedSegment();
      }
    }

    private String degradedSegment() {
      return DEGRADED_SEGMENT_PREFIX + (System.currentTimeMillis() / FINGERPRINT_DEGRADE_BUCKET_MS);
    }
  }

  private <T> Optional<T> findReadyOrInvalidate(SnapshotRequest request, Class<T> responseType) {
    try {
      return findReady(request, responseType);
    } catch (IllegalStateException e) {
      invalidateSnapshot(request);
      return Optional.empty();
    }
  }

  public <T> Optional<T> findReady(SnapshotRequest request, Class<T> responseType) {
    String requestHash = requestHash(request.requestPayload());
    List<String> rows =
        jdbcTemplate.queryForList(
            """
            select response_payload::text
              from page_record_snapshots
             where page_key = ?
               and snapshot_type = ?
               and scope_key = ?
               and rule_version = ?
               and source_version = ?
               and request_hash = ?
               and status = 'READY'
             order by refreshed_at desc, id desc
             limit 1
            """,
            String.class,
            request.pageKey(),
            request.snapshotType(),
            request.scopeKey(),
            request.ruleVersion(),
            request.sourceVersion(),
            requestHash);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(jsonUtils.fromJson(rows.getFirst(), responseType));
  }

  public void save(SnapshotRequest request, Object response) {
    jdbcTemplate.update(
        """
        insert into page_record_snapshots(
          page_key, snapshot_type, scope_key, rule_version, source_version, request_hash,
          request_payload, response_payload, status, error_message,
          generated_at, refreshed_at, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'READY', null,
                  current_timestamp, current_timestamp, current_timestamp, current_timestamp)
        on conflict (page_key, snapshot_type, scope_key, rule_version, request_hash)
        do update set
          source_version = excluded.source_version,
          request_payload = excluded.request_payload,
          response_payload = excluded.response_payload,
          status = 'READY',
          error_message = null,
          generated_at = current_timestamp,
          refreshed_at = current_timestamp,
          updated_at = current_timestamp
        """,
        request.pageKey(),
        request.snapshotType(),
        request.scopeKey(),
        request.ruleVersion(),
        request.sourceVersion(),
        requestHash(request.requestPayload()),
        payloadJson(request.requestPayload()),
        jsonUtils.toJson(response));
  }

  public void invalidatePage(String pageKey) {
    jdbcTemplate.update(
        """
        update page_record_snapshots
           set status = 'STALE',
               updated_at = current_timestamp
         where page_key = ?
           and status = 'READY'
        """,
        pageKey);
  }

  private void invalidateSnapshot(SnapshotRequest request) {
    jdbcTemplate.update(
        """
        update page_record_snapshots
           set status = 'STALE',
               error_message = 'Cached payload could not be parsed; refresh on next read',
               updated_at = current_timestamp
         where page_key = ?
           and snapshot_type = ?
           and scope_key = ?
           and rule_version = ?
           and request_hash = ?
           and status = 'READY'
        """,
        request.pageKey(),
        request.snapshotType(),
        request.scopeKey(),
        request.ruleVersion(),
        requestHash(request.requestPayload()));
  }

  public String issueFactSourceVersion() {
    return projectionVersionService.globalSourceVersion(
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE, FactType.ISSUE)
        + "|" + labelGroupSourceVersion();
  }

  /** 为项目或议题范围组生成记录快照来源版本。 */
  public String issueFactSourceVersion(
      String sourceInstance,
      long projectId,
      IssueScopeDimension dimension,
      String businessKey) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    return projectionVersionService.sourceVersion(
            normalizedSource,
            FactType.ISSUE,
            issueScopeResolver.resolve(
                normalizedSource,
                FactType.ISSUE,
                projectId,
                dimension,
                businessKey))
        + "|" + labelGroupSourceVersion();
  }

  public String mergeRequestFactSourceVersion() {
    return projectionVersionService.globalSourceVersion(
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE, FactType.MERGE_REQUEST);
  }

  public String codeReviewSourceVersion() {
    SourceVersionFingerprint fingerprint = new SourceVersionFingerprint();
    String matchModeVersion =
        fingerprint.segment(
            "match-mode",
            () ->
                fingerprintJdbcTemplate.queryForObject(
                    """
                    -- 兼容模式-MatchMode：老平台 spider_crowncad_data 没有 updated_at。
                    -- 这里使用新平台落地表的 synced_at 作为缓存失效版本，避免连接真实老平台 MySQL 时误依赖源表更新时间列。
                    select concat(
                             'match:',
                             count(*),
                             ':',
                             coalesce(to_char(max(synced_at), 'YYYY-MM-DD"T"HH24:MI:SS.US'), 'empty')
                           )
                      from code_review_match_mode_records
                    """,
                    String.class));
    String mergeRequestVersion = fingerprint.segment("merge-request-fact", this::mergeRequestFactSourceVersion);
    return mergeRequestVersion + "|" + matchModeVersion;
  }

  public String reviewDataSourceVersion() {
    SourceVersionFingerprint fingerprint = new SourceVersionFingerprint();
    String reviewReadModeVersion =
        fingerprint.segment(
            "review-read-mode",
            () ->
                fingerprintJdbcTemplate.queryForObject(
                    """
                    select concat(
                             'review-read-mode:',
                             enabled,
                             ':',
                             review_data_read_mode,
                             ':',
                             coalesce(to_char(updated_at, 'YYYY-MM-DD"T"HH24:MI:SS.US'), 'empty')
                           )
                      from code_review_match_mode_db_settings
                     where id = 1
                    """,
                    String.class));
    String reviewVersion =
        fingerprint.segment(
            "review",
            () ->
                fingerprintJdbcTemplate.queryForObject(
                    """
                    select concat(
                             'review:',
                             coalesce((select count(*) from review_records), 0),
                             ':',
                             coalesce((select count(*) from review_problem_items), 0),
                             ':',
                             coalesce(
                               to_char(
                                 greatest(
                                   coalesce((select max(updated_at) from review_records), timestamp 'epoch'),
                                   coalesce((select max(updated_at) from review_problem_items), timestamp 'epoch')
                                 ),
                                 'YYYY-MM-DD"T"HH24:MI:SS.US'
                               ),
                               'empty'
                             )
                           )
                    """,
                    String.class));
    String matchModeReviewVersion =
        fingerprint.segment(
            "match-review",
            () ->
                fingerprintJdbcTemplate.queryForObject(
                    """
                    select concat(
                             'match-review:',
                             coalesce((select count(*) from review_data_match_mode_reports), 0),
                             ':',
                             coalesce((select count(*) from review_data_match_mode_problem_details), 0),
                             ':',
                             coalesce(
                               to_char(
                                 greatest(
                                   coalesce((select max(synced_at) from review_data_match_mode_reports), timestamp 'epoch'),
                                   coalesce((select max(synced_at) from review_data_match_mode_problem_details), timestamp 'epoch')
                                 ),
                                 'YYYY-MM-DD"T"HH24:MI:SS.US'
                               ),
                               'empty'
                             )
                           )
                    """,
                    String.class));
    String issueFactVersion = fingerprint.segment("issue-fact", this::issueFactSourceVersion);
    String dropdownOptionVersion =
        fingerprint.segment(
            "dropdown",
            () ->
                fingerprintJdbcTemplate.queryForObject(
                    """
                    -- 下拉框选项配置与字段绑定任一变化都推进版本指纹，使评审候选快照失效重建。
                    select concat(
                             'dropdown:',
                             coalesce((select sum(version) from dropdown_option_configs), 0), ':',
                             coalesce(
                               to_char(
                                 (select max(updated_at) from dropdown_option_configs),
                                 'YYYY-MM-DD"T"HH24:MI:SS.US'
                               ),
                               'empty'
                             ), ':',
                             coalesce(
                               to_char(
                                 (select max(bound_at) from dropdown_option_field_bindings),
                                 'YYYY-MM-DD"T"HH24:MI:SS.US'
                               ),
                               'empty'
                             ), ':',
                             (select count(*) from dropdown_option_field_bindings)
                           )
                    """,
                    String.class));
    return reviewReadModeVersion + "|" + reviewVersion + "|" + matchModeReviewVersion
        + "|" + issueFactVersion + "|" + dropdownOptionVersion;
  }

  private String labelGroupSourceVersion() {
    return fingerprintJdbcTemplate.queryForObject(
        """
        select concat(
                 'label-group:',
                 coalesce(to_char(max(changed_at), 'YYYY-MM-DD"T"HH24:MI:SS.US'), 'empty')
               )
          from (
            select updated_at as changed_at from label_groups
            union all
            select created_at as changed_at from label_group_members
          ) s
        """,
        String.class);
  }

  public String factSourceVersion(String factType) {
    String normalizedFactType = factType == null ? "" : factType.trim().toUpperCase();
    FactType type = FACT_TYPE_MERGE_REQUEST.equals(normalizedFactType)
        ? FactType.MERGE_REQUEST
        : FactType.ISSUE;
    return projectionVersionService.globalSourceVersion(
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE, type);
  }

  public String requestHash(Object requestPayload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payloadJson(requestPayload).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private String payloadJson(Object requestPayload) {
    if (requestPayload == null) {
      return "{}";
    }
    if (requestPayload instanceof Map<?, ?> map) {
      return jsonUtils.toJson(new java.util.TreeMap<>(map));
    }
    return jsonUtils.toJson(requestPayload);
  }

  public record SnapshotRequest(
      String pageKey,
      String snapshotType,
      String scopeKey,
      String ruleVersion,
      String sourceVersion,
      Object requestPayload) {
  }
}
