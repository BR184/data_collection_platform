package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CodeReviewMatchModeSyncResponse;
import com.data.collection.platform.entity.LegacyPlatformFormalImportRequest;
import com.data.collection.platform.entity.LegacyPlatformFormalImportResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class LegacyPlatformFormalImportService {
  public static final String CONFIRMATION_TEXT = "我确认要将数据源导入新采集平台中";

  private final CodeReviewMatchModeSyncService mysqlSyncService;
  private final CodeReviewMatchModeMongoReviewSyncService mongoReviewSyncService;
  private final CodeReviewMatchModeLegacyRefreshService legacyRefreshService;
  private final ReviewDataMatchModeRecordRepository reviewMatchModeRecordRepository;
  private final ReviewDataMatchModeMaterializeService reviewMaterializeService;
  private final CodeReviewMatchModeConfigService configService;
  private final PageRecordSnapshotService pageRecordSnapshotService;
  private final JdbcTemplate jdbcTemplate;

  public LegacyPlatformFormalImportService(
      CodeReviewMatchModeSyncService mysqlSyncService,
      CodeReviewMatchModeMongoReviewSyncService mongoReviewSyncService,
      CodeReviewMatchModeLegacyRefreshService legacyRefreshService,
      ReviewDataMatchModeRecordRepository reviewMatchModeRecordRepository,
      ReviewDataMatchModeMaterializeService reviewMaterializeService,
      CodeReviewMatchModeConfigService configService,
      PageRecordSnapshotService pageRecordSnapshotService,
      JdbcTemplate jdbcTemplate) {
    this.mysqlSyncService = mysqlSyncService;
    this.mongoReviewSyncService = mongoReviewSyncService;
    this.legacyRefreshService = legacyRefreshService;
    this.reviewMatchModeRecordRepository = reviewMatchModeRecordRepository;
    this.reviewMaterializeService = reviewMaterializeService;
    this.configService = configService;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
    this.jdbcTemplate = jdbcTemplate;
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
    legacyRefreshService.refreshOneForFormalImport(source, mergeRequestIid);
    importCodeReviewData();
    pageRecordSnapshotService.invalidatePage(CodeReviewIllegalRecordService.WORKSPACE_KEY);
  }

  //兼容模式-MatchMode
  public LegacyPlatformFormalImportResponse importToFormal(LegacyPlatformFormalImportRequest request) {
    validateRequest(request);
    ImportCounters counters = new ImportCounters();
    try {
      if (request.importReviewData()) {
        CodeReviewMatchModeSyncResponse syncResponse = mongoReviewSyncService.syncNowForFormalImport();
        if (!syncResponse.accepted()) {
          throw new BizException(syncResponse.message());
        }
        ReviewImportCounters reviewCounters = importReviewData();
        counters.reviewInsertedCount = reviewCounters.inserted();
        counters.reviewUpdatedCount = reviewCounters.updated();
        configService.markReviewDataFormalReadMode();
        pageRecordSnapshotService.invalidatePage("review-data-records");
      }
      if (request.importCodeReviewData()) {
        CodeReviewMatchModeSyncResponse syncResponse = mysqlSyncService.syncNowForFormalImport();
        if (!syncResponse.accepted()) {
          throw new BizException(syncResponse.message());
        }
        CodeReviewImportCounters codeReviewCounters = importCodeReviewData();
        counters.codeReviewInsertedCount = codeReviewCounters.inserted();
        counters.codeReviewUpdatedCount = codeReviewCounters.updated();
        configService.markCodeReviewFormalReadMode();
        pageRecordSnapshotService.invalidatePage(CodeReviewIllegalRecordService.WORKSPACE_KEY);
      }
      recordRun(request, counters, "SUCCESS", "老平台数据已转入新平台正式数据");
      return new LegacyPlatformFormalImportResponse(
          true,
          "老平台数据已转入新平台正式数据",
          counters.reviewInsertedCount,
          counters.reviewUpdatedCount,
          counters.codeReviewInsertedCount,
          counters.codeReviewUpdatedCount);
    } catch (RuntimeException error) {
      log.warn("Legacy platform formal import failed", error);
      recordRun(request, counters, "FAILED", rootMessage(error));
      throw error;
    }
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

  //兼容模式-MatchMode
  private ReviewImportCounters importReviewData() {
    long inserted = 0L;
    long updated = 0L;
    for (Long matchModeRecordId : reviewMatchModeRecordRepository.loadAllPublicRecordIds()) {
      ReviewDataMatchModeMaterializeService.MaterializeResult result =
          reviewMaterializeService.materializeRecordWithResult(matchModeRecordId);
      if (result.inserted()) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ReviewImportCounters(inserted, updated);
  }

  //兼容模式-MatchMode
  @Transactional
  protected CodeReviewImportCounters importCodeReviewData() {
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
    return counters;
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
            return new CodeReviewImportCounters(0, 0);
          }
          return new CodeReviewImportCounters(rs.getLong("inserted_count"), rs.getLong("updated_count"));
        });
  }

  private void recordRun(
      LegacyPlatformFormalImportRequest request,
      ImportCounters counters,
      String status,
      String message) {
    jdbcTemplate.update("""
        insert into legacy_platform_formal_import_runs(
          import_type,
          confirmation_text,
          review_requested,
          code_review_requested,
          review_inserted_count,
          review_updated_count,
          code_review_inserted_count,
          code_review_updated_count,
          status,
          message
        ) values (
          'MATCH_MODE_PROMOTION',
          ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """,
        request == null ? "" : TextQuerySupport.normalizeDisplay(request.confirmationText()),
        request != null && request.importReviewData(),
        request != null && request.importCodeReviewData(),
        counters.reviewInsertedCount,
        counters.reviewUpdatedCount,
        counters.codeReviewInsertedCount,
        counters.codeReviewUpdatedCount,
        status,
        message);
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
    private long codeReviewInsertedCount;
    private long codeReviewUpdatedCount;
  }

  private record ReviewImportCounters(long inserted, long updated) {}

  private record CodeReviewImportCounters(long inserted, long updated) {}
}
