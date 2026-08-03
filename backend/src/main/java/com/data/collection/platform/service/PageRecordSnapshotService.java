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
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PageRecordSnapshotService {
  public static final String SNAPSHOT_TYPE_LIST = "LIST";
  public static final String SNAPSHOT_TYPE_FILTER_OPTIONS = "FILTER_OPTIONS";
  public static final String FACT_TYPE_ISSUE = "ISSUE";
  public static final String FACT_TYPE_MERGE_REQUEST = "MERGE_REQUEST";

  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final FactProjectionVersionService projectionVersionService;
  private final IssueProjectionScopeResolver issueScopeResolver;

  public PageRecordSnapshotService(
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      FactProjectionVersionService projectionVersionService,
      IssueProjectionScopeResolver issueScopeResolver) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.projectionVersionService = projectionVersionService;
    this.issueScopeResolver = issueScopeResolver;
  }

  public <T> T readOrRefresh(
      SnapshotRequest request,
      Class<T> responseType,
      Supplier<T> responseSupplier) {
    Optional<T> snapshot = findReadyOrInvalidate(request, responseType);
    if (snapshot.isPresent()) {
      return snapshot.get();
    }
    T response = responseSupplier.get();
    save(request, response);
    return response;
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
    String matchModeVersion =
        jdbcTemplate.queryForObject(
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
            String.class);
    return mergeRequestFactSourceVersion() + "|" + matchModeVersion;
  }

  public String reviewDataSourceVersion() {
    String reviewReadModeVersion =
        jdbcTemplate.queryForObject(
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
            String.class);
    String reviewVersion =
        jdbcTemplate.queryForObject(
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
            String.class);
    String matchModeReviewVersion =
        jdbcTemplate.queryForObject(
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
            String.class);
    return reviewReadModeVersion + "|" + reviewVersion + "|" + matchModeReviewVersion
        + "|" + issueFactSourceVersion();
  }

  private String labelGroupSourceVersion() {
    return jdbcTemplate.queryForObject(
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
