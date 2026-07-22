package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.CodeReviewMatchModeSyncResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsResponse;
import com.data.collection.platform.entity.LegacyPlatformFormalImportDomainResponse;
import com.data.collection.platform.entity.LegacyPlatformFormalImportRequest;
import com.data.collection.platform.entity.LegacyPlatformFormalImportResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class LegacyPlatformFormalImportService {
  public static final String CONFIRMATION_TEXT = "我确认要将数据源导入新采集平台中";
  private static final long HANDOVER_LOCK_ID = 7_026_072_103L;

  private final CodeReviewMatchModeSyncService mysqlSyncService;
  private final CodeReviewMatchModeMongoReviewSyncService mongoReviewSyncService;
  private final CodeReviewMatchModeLegacyRefreshService legacyRefreshService;
  private final ReviewDataMatchModeRecordRepository reviewMatchModeRecordRepository;
  private final ReviewDataMatchModeMaterializeService reviewMaterializeService;
  private final CodeReviewMatchModeConfigService configService;
  private final PageRecordSnapshotService pageRecordSnapshotService;
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final TransactionTemplate transactionTemplate;

  public LegacyPlatformFormalImportService(
      CodeReviewMatchModeSyncService mysqlSyncService,
      CodeReviewMatchModeMongoReviewSyncService mongoReviewSyncService,
      CodeReviewMatchModeLegacyRefreshService legacyRefreshService,
      ReviewDataMatchModeRecordRepository reviewMatchModeRecordRepository,
      ReviewDataMatchModeMaterializeService reviewMaterializeService,
      CodeReviewMatchModeConfigService configService,
      PageRecordSnapshotService pageRecordSnapshotService,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      PlatformTransactionManager transactionManager) {
    this.mysqlSyncService = mysqlSyncService;
    this.mongoReviewSyncService = mongoReviewSyncService;
    this.legacyRefreshService = legacyRefreshService;
    this.reviewMatchModeRecordRepository = reviewMatchModeRecordRepository;
    this.reviewMaterializeService = reviewMaterializeService;
    this.configService = configService;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  //兼容模式-MatchMode
  public boolean hasPromotedCodeReviewData(String source) {
    String normalizedSource =
        GitlabSourceInstanceSupport.normalizeSourceInstance(source == null ? "cc" : source);
    Boolean exists = jdbcTemplate.queryForObject("""
        select exists(
          select 1
            from merge_request_fact
           where source_system = 'LEGACY_PLATFORM'
             and deleted = false
             and lower(coalesce(source_instance, 'default')) = ?
        )
        """,
        Boolean.class,
        normalizedSource);
    return Boolean.TRUE.equals(exists);
  }

  //兼容模式-MatchMode
  public void refreshAndPromoteCodeReviewRecord(String source, Long mergeRequestIid) {
    withExclusiveHandoverLock(() -> {
      legacyRefreshService.refreshOneForFormalImport(source, mergeRequestIid);
      transactionTemplate.executeWithoutResult(status -> importCodeReviewData());
      return Boolean.TRUE;
    });
    pageRecordSnapshotService.invalidatePage(CodeReviewIllegalRecordService.WORKSPACE_KEY);
  }

  //兼容模式-MatchMode
  public LegacyPlatformFormalImportResponse importToFormal(
      LegacyPlatformFormalImportRequest request,
      String operatorUsername) {
    validateRequest(request);
    CodeReviewMatchModeConfig config = configService.loadConfig();
    CodeReviewMatchModeDbSettingsResponse settings = configService.getResponse();
    validateSettingsVersion(request, settings.updatedAt());
    validateSourceScope(request, config);
    return withExclusiveHandoverLock(
        () -> executeHandover(request, operatorUsername, settings, config));
  }

  private LegacyPlatformFormalImportResponse executeHandover(
      LegacyPlatformFormalImportRequest request,
      String operatorUsername,
      CodeReviewMatchModeDbSettingsResponse settings,
      CodeReviewMatchModeConfig config) {
    long runId = startRun(request, operatorUsername, settings.updatedAt(), config);
    ImportCounters counters = new ImportCounters();
    try {
      if (request.importReviewData()) {
        CodeReviewMatchModeSyncResponse syncResponse = mongoReviewSyncService.syncNowForFormalImport();
        if (!syncResponse.accepted()) {
          throw new BizException(syncResponse.message());
        }
      }
      if (request.importCodeReviewData()) {
        CodeReviewMatchModeSyncResponse syncResponse = mysqlSyncService.syncNowForFormalImport();
        if (!syncResponse.accepted()) {
          throw new BizException(syncResponse.message());
        }
      }
      LegacyPlatformFormalImportResponse response = transactionTemplate.execute(status -> {
        if (request.importReviewData()) {
          ReviewImportCounters reviewCounters = importReviewData();
          counters.reviewInsertedCount = reviewCounters.inserted();
          counters.reviewUpdatedCount = reviewCounters.updated();
          counters.reviewSkippedCount = reviewCounters.skipped();
          counters.reviewDeletedCount = reviewCounters.deleted();
        }
        if (request.importCodeReviewData()) {
          CodeReviewImportCounters codeReviewCounters = importCodeReviewData();
          counters.codeReviewInsertedCount = codeReviewCounters.inserted();
          counters.codeReviewUpdatedCount = codeReviewCounters.updated();
          counters.codeReviewDeletedCount = codeReviewCounters.deleted();
          configService.markCodeReviewFormalReadMode();
        }
        finishRun(runId, request, counters, "SUCCESS", "老平台数据已转入新平台正式数据");
        return successResponse(runId, request, counters);
      });
      if (response == null) {
        throw new IllegalStateException("老平台数据交接事务未返回结果");
      }
      invalidateAffectedSnapshots(request);
      return response;
    } catch (RuntimeException error) {
      log.warn("Legacy platform formal import failed", error);
      String message = rootMessage(error);
      failRun(runId, request, message);
      return failureResponse(runId, request, message);
    }
  }

  private <T> T withExclusiveHandoverLock(Supplier<T> action) {
    T result = jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "select pg_try_advisory_lock(?)")) {
        statement.setLong(1, HANDOVER_LOCK_ID);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next() || !resultSet.getBoolean(1)) {
            throw new BizException("已有老平台数据交接任务正在执行，请等待其完成");
          }
        }
      }
      try {
        return action.get();
      } finally {
        try (PreparedStatement statement = connection.prepareStatement(
            "select pg_advisory_unlock(?)")) {
          statement.setLong(1, HANDOVER_LOCK_ID);
          statement.executeQuery().close();
        }
      }
    });
    if (result == null) {
      throw new IllegalStateException("老平台数据交接锁未返回执行结果");
    }
    return result;
  }

  private void validateRequest(LegacyPlatformFormalImportRequest request) {
    if (request == null) {
      throw new BizException("缺少转正式导入请求");
    }
    if (!request.importReviewData() && !request.importCodeReviewData()) {
      throw new BizException("请至少选择一种需要导入的老平台数据");
    }
    if (!CONFIRMATION_TEXT.equals(TextQuerySupport.normalizeDisplay(request.confirmationText()))) {
      throw new BizException("确认文本不正确");
    }
  }

  private void validateSettingsVersion(
      LegacyPlatformFormalImportRequest request,
      LocalDateTime currentUpdatedAt) {
    if (request.expectedSettingsUpdatedAt() == null || currentUpdatedAt == null
        || !request.expectedSettingsUpdatedAt().equals(currentUpdatedAt)) {
      throw new BizException("兼容模式设置已变化，请重新加载并确认导入范围");
    }
  }

  private void validateSourceScope(
      LegacyPlatformFormalImportRequest request,
      CodeReviewMatchModeConfig config) {
    if (request.importReviewData()) {
      if (!org.springframework.util.StringUtils.hasText(config.mongoUri())) {
        throw new BizException("老平台 MongoDB 配置不完整");
      }
      for (String required : java.util.List.of(
          config.reviewReportCollectionName(),
          config.reviewProblemCollectionName(),
          "description")) {
        if (!config.selectedMongoCollectionNames().contains(required)) {
          throw new BizException("评审交接缺少必需集合: " + required);
        }
      }
    }
    if (request.importCodeReviewData()) {
      if (!org.springframework.util.StringUtils.hasText(config.mysqlJdbcUrl())
          || !org.springframework.util.StringUtils.hasText(config.mysqlUsername())) {
        throw new BizException("老平台 MySQL 配置不完整");
      }
      if (!config.selectedTableNames().contains(config.mysqlTableName())) {
        throw new BizException("代码走查交接范围必须包含表: " + config.mysqlTableName());
      }
    }
  }

  //兼容模式-MatchMode
  private ReviewImportCounters importReviewData() {
    long inserted = 0L;
    long updated = 0L;
    long skipped = 0L;
    for (ReviewDataMatchModeRecordRepository.MatchModeRecordSource source
        : reviewMatchModeRecordRepository.loadAllRecordSources()) {
      ReviewDataMatchModeMaterializeService.MaterializeResult result =
          reviewMaterializeService.materializeForHandover(source);
      switch (result.outcome()) {
        case INSERTED -> inserted++;
        case UPDATED -> updated++;
        case SKIPPED_PLATFORM_OWNED -> skipped++;
      }
    }
    long deleted = reconcileRemovedReviewData();
    return new ReviewImportCounters(inserted, updated, skipped, deleted);
  }

  //兼容模式-MatchMode
  private CodeReviewImportCounters importCodeReviewData() {
    CodeReviewImportCounters counters = countCodeReviewImportRows();
    jdbcTemplate.update("""
        insert into merge_request_fact(
          source_system,
          source_instance,
          ingest_channel,
          source_summary,
          raw_payload,
          project_id,
          project_name,
          repository_name,
          merge_request_id,
          merge_request_iid,
          title,
          merge_request_state,
          target_branch,
          source_branch,
          author_name,
          merge_user_name,
          owner_name,
          reviewer_names,
          assignee_names,
          module_name,
          label_names,
          search_text,
          search_compact,
          search_spell,
          search_initials,
          owner_search_text,
          owner_search_compact,
          owner_search_spell,
          owner_search_initials,
          created_at_source,
          updated_at_source,
          ods_updated_at,
          merged_at_source,
          review_status,
          review_duration_minutes,
          review_exception_reason,
          code_walkthrough_date,
          comment_rate,
          comment_rate_source,
          defect_count,
          defect_count_source,
          scan_status,
          scan_bug_count,
          annotation_rate_result,
          bug_count_result,
          added_lines,
          deleted_lines,
          code_specification_count,
          code_logic_specification_count,
          performance_specification_count,
          design_specification_count,
          other_specification_count,
          review_speed_loc_per_hour,
          review_speed_kloc_per_hour,
          review_defect_density_per_kloc,
          review_efficiency_per_hour,
          commit_count,
          commit_rate,
          function_name,
          clang_added_line_count,
          deleted,
          fact_refreshed_at,
          created_at,
          updated_at
        )
        select
          'LEGACY_PLATFORM',
          source_instance,
          'MATCH_MODE_PROMOTION',
          concat('legacy spider_crowncad_data:', coalesce(legacy_source_id, id::text)),
          jsonb_build_object(
            'matchModeRecordId', id,
            'legacySourceId', legacy_source_id,
            'repositoryName', repository_name,
            'mergeRequestIid', merge_request_iid
          )::text,
          coalesce(project_id, 0),
          project_name,
          repository_name,
          case
            when coalesce(legacy_source_id, '') ~ '^[0-9]+$' then legacy_source_id::bigint
            else id
          end,
          merge_request_iid,
          coalesce(title, ''),
          merge_request_state,
          target_branch,
          null,
          author_name,
          merge_user_name,
          owner_name,
          reviewer_names,
          assignee_names,
          module_name,
          label_names,
          search_text,
          search_compact,
          search_spell,
          search_initials,
          owner_name,
          regexp_replace(coalesce(owner_name, ''), '\\s+', '', 'g'),
          null,
          null,
          merged_at_source,
          synced_at,
          synced_at,
          merged_at_source,
          review_status,
          review_duration_minutes,
          review_exception_reason,
          code_walkthrough_date,
          comment_rate,
          'LEGACY_PLATFORM',
          defect_count,
          'LEGACY_PLATFORM',
          scan_status,
          scan_bug_count,
          annotation_rate_result,
          bug_count_result,
          added_lines,
          deleted_lines,
          code_specification_count,
          code_logic_specification_count,
          performance_specification_count,
          design_specification_count,
          other_specification_count,
          review_speed_loc_per_hour,
          review_speed_kloc_per_hour,
          review_defect_density_per_kloc,
          review_efficiency_per_hour,
          commit_count,
          commit_rate,
          function_name,
          clang_added_line_count,
          false,
          current_timestamp,
          current_timestamp,
          current_timestamp
        from code_review_match_mode_records
        on conflict (source_system, source_instance, project_id, merge_request_id)
        do update set
          ingest_channel = excluded.ingest_channel,
          source_summary = excluded.source_summary,
          raw_payload = excluded.raw_payload,
          project_name = excluded.project_name,
          repository_name = excluded.repository_name,
          merge_request_iid = excluded.merge_request_iid,
          title = excluded.title,
          merge_request_state = excluded.merge_request_state,
          target_branch = excluded.target_branch,
          source_branch = excluded.source_branch,
          author_name = excluded.author_name,
          merge_user_name = excluded.merge_user_name,
          owner_name = excluded.owner_name,
          reviewer_names = excluded.reviewer_names,
          assignee_names = excluded.assignee_names,
          module_name = excluded.module_name,
          label_names = excluded.label_names,
          search_text = excluded.search_text,
          search_compact = excluded.search_compact,
          search_spell = excluded.search_spell,
          search_initials = excluded.search_initials,
          owner_search_text = excluded.owner_search_text,
          owner_search_compact = excluded.owner_search_compact,
          owner_search_spell = excluded.owner_search_spell,
          owner_search_initials = excluded.owner_search_initials,
          created_at_source = excluded.created_at_source,
          updated_at_source = excluded.updated_at_source,
          ods_updated_at = excluded.ods_updated_at,
          merged_at_source = excluded.merged_at_source,
          review_status = excluded.review_status,
          review_duration_minutes = excluded.review_duration_minutes,
          review_exception_reason = excluded.review_exception_reason,
          code_walkthrough_date = excluded.code_walkthrough_date,
          comment_rate = excluded.comment_rate,
          comment_rate_source = excluded.comment_rate_source,
          defect_count = excluded.defect_count,
          defect_count_source = excluded.defect_count_source,
          scan_status = excluded.scan_status,
          scan_bug_count = excluded.scan_bug_count,
          annotation_rate_result = excluded.annotation_rate_result,
          bug_count_result = excluded.bug_count_result,
          added_lines = excluded.added_lines,
          deleted_lines = excluded.deleted_lines,
          code_specification_count = excluded.code_specification_count,
          code_logic_specification_count = excluded.code_logic_specification_count,
          performance_specification_count = excluded.performance_specification_count,
          design_specification_count = excluded.design_specification_count,
          other_specification_count = excluded.other_specification_count,
          review_speed_loc_per_hour = excluded.review_speed_loc_per_hour,
          review_speed_kloc_per_hour = excluded.review_speed_kloc_per_hour,
          review_defect_density_per_kloc = excluded.review_defect_density_per_kloc,
          review_efficiency_per_hour = excluded.review_efficiency_per_hour,
          commit_count = excluded.commit_count,
          commit_rate = excluded.commit_rate,
          function_name = excluded.function_name,
          clang_added_line_count = excluded.clang_added_line_count,
          deleted = false,
          fact_refreshed_at = current_timestamp,
          updated_at = current_timestamp
        """);
    long deleted = reconcileRemovedCodeReviewData();
    return new CodeReviewImportCounters(counters.inserted(), counters.updated(), deleted);
  }

  private CodeReviewImportCounters countCodeReviewImportRows() {
    return jdbcTemplate.query("""
        with source_rows as (
          select
            source_instance,
            coalesce(project_id, 0) as project_id,
            case
              when coalesce(legacy_source_id, '') ~ '^[0-9]+$' then legacy_source_id::bigint
              else id
            end as merge_request_id
          from code_review_match_mode_records
        )
        select
          count(*) filter (where fact.id is null) as inserted_count,
          count(*) filter (where fact.id is not null) as updated_count
        from source_rows src
        left join merge_request_fact fact
          on fact.source_system = 'LEGACY_PLATFORM'
         and fact.source_instance = src.source_instance
         and fact.project_id = src.project_id
         and fact.merge_request_id = src.merge_request_id
        """,
        rs -> {
          if (!rs.next()) {
            return new CodeReviewImportCounters(0, 0, 0);
          }
          return new CodeReviewImportCounters(
              rs.getLong("inserted_count"), rs.getLong("updated_count"), 0);
        });
  }

  private long reconcileRemovedReviewData() {
    return jdbcTemplate.update("""
        update review_records record
           set deleted = true, updated_at = current_timestamp
          from review_data_match_mode_edit_links link
         where link.review_record_id = record.id
           and link.authority = 'LEGACY_MANAGED'
           and record.deleted = false
           and not exists (
             select 1
               from review_data_match_mode_reports report
              where report.legacy_id = link.match_mode_report_legacy_id
           )
        """);
  }

  private long reconcileRemovedCodeReviewData() {
    return jdbcTemplate.update("""
        update merge_request_fact fact
           set deleted = true,
               fact_refreshed_at = current_timestamp,
               updated_at = current_timestamp
         where fact.source_system = 'LEGACY_PLATFORM'
           and fact.deleted = false
           and not exists (
             select 1
               from code_review_match_mode_records source
              where source.source_instance = fact.source_instance
                and coalesce(source.project_id, 0) = fact.project_id
                and case
                      when coalesce(source.legacy_source_id, '') ~ '^[0-9]+$'
                        then source.legacy_source_id::bigint
                      else source.id
                    end = fact.merge_request_id
           )
        """);
  }

  private long startRun(
      LegacyPlatformFormalImportRequest request,
      String operatorUsername,
      LocalDateTime settingsUpdatedAt,
      CodeReviewMatchModeConfig config) {
    Long runId = jdbcTemplate.queryForObject("""
        insert into legacy_platform_formal_import_runs(
          import_type,
          confirmation_text,
          review_requested,
          code_review_requested,
          operator_username,
          settings_updated_at,
          review_status,
          code_review_status,
          status,
          message,
          source_summary
        ) values (
          'MATCH_MODE_PROMOTION', ?, ?, ?, ?, ?, ?, ?, 'RUNNING', '正在交接老平台数据', ?::jsonb
        ) returning id
        """,
        Long.class,
        TextQuerySupport.normalizeDisplay(request.confirmationText()),
        request.importReviewData(),
        request.importCodeReviewData(),
        TextQuerySupport.normalizeDisplay(operatorUsername),
        settingsUpdatedAt,
        request.importReviewData() ? "PENDING" : "NOT_REQUESTED",
        request.importCodeReviewData() ? "PENDING" : "NOT_REQUESTED",
        sourceSummary(config));
    if (runId == null) {
      throw new IllegalStateException("创建老平台数据交接任务失败");
    }
    return runId;
  }

  private void finishRun(
      long runId,
      LegacyPlatformFormalImportRequest request,
      ImportCounters counters,
      String status,
      String message) {
    jdbcTemplate.update("""
        update legacy_platform_formal_import_runs
           set review_inserted_count = ?,
               review_updated_count = ?,
               review_skipped_count = ?,
               review_deleted_count = ?,
               code_review_inserted_count = ?,
               code_review_updated_count = ?,
               code_review_deleted_count = ?,
               review_status = ?,
               code_review_status = ?,
               status = ?,
               message = ?,
               finished_at = current_timestamp
         where id = ?
        """,
        counters.reviewInsertedCount,
        counters.reviewUpdatedCount,
        counters.reviewSkippedCount,
        counters.reviewDeletedCount,
        counters.codeReviewInsertedCount,
        counters.codeReviewUpdatedCount,
        counters.codeReviewDeletedCount,
        request.importReviewData() ? "SUCCESS" : "NOT_REQUESTED",
        request.importCodeReviewData() ? "SUCCESS" : "NOT_REQUESTED",
        status,
        message,
        runId);
  }

  private void failRun(long runId, LegacyPlatformFormalImportRequest request, String message) {
    jdbcTemplate.update("""
        update legacy_platform_formal_import_runs
           set review_status = ?,
               code_review_status = ?,
               status = 'FAILED',
               message = ?,
               finished_at = current_timestamp
         where id = ?
        """,
        request.importReviewData() ? "FAILED" : "NOT_REQUESTED",
        request.importCodeReviewData() ? "FAILED" : "NOT_REQUESTED",
        message,
        runId);
  }

  private String sourceSummary(CodeReviewMatchModeConfig config) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("mysqlTable", config.mysqlTableName());
    summary.put("mysqlTables", config.selectedTableNames());
    summary.put("mongoDatabase", config.mongoDatabase());
    summary.put("mongoCollections", config.selectedMongoCollectionNames());
    return jsonUtils.toJson(summary);
  }

  private LegacyPlatformFormalImportResponse successResponse(
      long runId,
      LegacyPlatformFormalImportRequest request,
      ImportCounters counters) {
    return new LegacyPlatformFormalImportResponse(
        runId,
        true,
        "SUCCESS",
        "老平台数据已转入新平台正式数据",
        domainResponse(
            request.importReviewData(),
            "SUCCESS",
            counters.reviewInsertedCount,
            counters.reviewUpdatedCount,
            counters.reviewSkippedCount,
            counters.reviewDeletedCount,
            "评审数据交接完成"),
        domainResponse(
            request.importCodeReviewData(),
            "SUCCESS",
            counters.codeReviewInsertedCount,
            counters.codeReviewUpdatedCount,
            0,
            counters.codeReviewDeletedCount,
            "代码走查数据交接完成"));
  }

  private LegacyPlatformFormalImportResponse failureResponse(
      long runId,
      LegacyPlatformFormalImportRequest request,
      String message) {
    return new LegacyPlatformFormalImportResponse(
        runId,
        false,
        "FAILED",
        message,
        domainResponse(request.importReviewData(), "FAILED", 0, 0, 0, 0, message),
        domainResponse(request.importCodeReviewData(), "FAILED", 0, 0, 0, 0, message));
  }

  private LegacyPlatformFormalImportDomainResponse domainResponse(
      boolean requested,
      String requestedStatus,
      long inserted,
      long updated,
      long skipped,
      long deleted,
      String message) {
    return new LegacyPlatformFormalImportDomainResponse(
        requested ? requestedStatus : "NOT_REQUESTED",
        inserted,
        updated,
        skipped,
        deleted,
        requested ? message : "未选择此数据域");
  }

  private void invalidateAffectedSnapshots(LegacyPlatformFormalImportRequest request) {
    if (request.importReviewData()) {
      pageRecordSnapshotService.invalidatePage("review-data-records");
    }
    if (request.importCodeReviewData()) {
      pageRecordSnapshotService.invalidatePage(CodeReviewIllegalRecordService.WORKSPACE_KEY);
    }
  }

  private String rootMessage(Throwable error) {
    Throwable cursor = error;
    while (cursor.getCause() != null) {
      cursor = cursor.getCause();
    }
    String message = cursor.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  private static final class ImportCounters {
    private long reviewInsertedCount;
    private long reviewUpdatedCount;
    private long reviewSkippedCount;
    private long reviewDeletedCount;
    private long codeReviewInsertedCount;
    private long codeReviewUpdatedCount;
    private long codeReviewDeletedCount;
  }

  private record ReviewImportCounters(long inserted, long updated, long skipped, long deleted) {}

  private record CodeReviewImportCounters(long inserted, long updated, long deleted) {}
}
