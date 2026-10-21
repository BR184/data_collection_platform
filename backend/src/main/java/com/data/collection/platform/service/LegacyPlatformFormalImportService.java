package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.CodeReviewMatchModeSyncResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsResponse;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.LegacyPlatformFormalImportDomainResponse;
import com.data.collection.platform.entity.LegacyPlatformFormalImportRequest;
import com.data.collection.platform.entity.LegacyPlatformFormalImportResponse;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublicationDomain;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.service.read.BypassPublicationRequest;
import com.data.collection.platform.service.read.InitialVersionVerification;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
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
  private static final String SOURCE_SYSTEM_LEGACY_PLATFORM = "LEGACY_PLATFORM";
  private static final String FORMAL_IMPORT_PRODUCER = "legacy-platform-formal-import";

  private final CodeReviewMatchModeSyncService mysqlSyncService;
  private final CodeReviewMatchModeLegacyRefreshService legacyRefreshService;
  private final CodeReviewMatchModeConfigService configService;
  private final PageRecordSnapshotService pageRecordSnapshotService;
  private final FactTargetPublicationService factTargetPublicationService;
  private final ReadPublicationService readPublicationService;
  private final ReadPublicationRepository readPublicationRepository;
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final TransactionTemplate transactionTemplate;

  public LegacyPlatformFormalImportService(
      CodeReviewMatchModeSyncService mysqlSyncService,
      CodeReviewMatchModeLegacyRefreshService legacyRefreshService,
      CodeReviewMatchModeConfigService configService,
      PageRecordSnapshotService pageRecordSnapshotService,
      FactTargetPublicationService factTargetPublicationService,
      ReadPublicationService readPublicationService,
      ReadPublicationRepository readPublicationRepository,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      PlatformTransactionManager transactionManager) {
    this.mysqlSyncService = mysqlSyncService;
    this.legacyRefreshService = legacyRefreshService;
    this.configService = configService;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
    this.factTargetPublicationService = factTargetPublicationService;
    this.readPublicationService = readPublicationService;
    this.readPublicationRepository = readPublicationRepository;
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
             and (created_by_publication_id is null or visible_from is not null)
             and visible_to is null
        )
        """,
        Boolean.class,
        normalizedSource);
    return Boolean.TRUE.equals(exists);
  }

  //兼容模式-MatchMode
  public void refreshAndPromoteCodeReviewRecord(String source, Long mergeRequestIid) {
    String normalizedSource =
        GitlabSourceInstanceSupport.normalizeSourceInstance(source == null ? "cc" : source);
    withExclusiveHandoverLock(() -> {
      legacyRefreshService.refreshOneForFormalImport(source, mergeRequestIid);
      transactionTemplate.executeWithoutResult(
          status -> importCodeReviewData(normalizedSource, mergeRequestIid));
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
    validateSourceScope(config);
    return withExclusiveHandoverLock(
        () -> executeHandover(request, operatorUsername, settings, config));
  }

  private LegacyPlatformFormalImportResponse executeHandover(
      LegacyPlatformFormalImportRequest request,
      String operatorUsername,
      CodeReviewMatchModeDbSettingsResponse settings,
      CodeReviewMatchModeConfig config) {
    long runId = startRun(request, operatorUsername, settings.updatedAt(), config);
    try {
      CodeReviewMatchModeSyncResponse syncResponse = mysqlSyncService.syncNowForFormalImport();
      if (!syncResponse.accepted()) {
        throw new BizException(syncResponse.message());
      }
      LegacyPlatformFormalImportResponse response = transactionTemplate.execute(status -> {
        CodeReviewImportCounters codeReviewCounters = importCodeReviewData(null, null);
        configService.markCodeReviewFormalReadMode();
        finishRun(runId, codeReviewCounters, "SUCCESS", "老平台代码走查数据已转入正式数据");
        return successResponse(runId, codeReviewCounters);
      });
      if (response == null) {
        throw new IllegalStateException("老平台数据交接事务未返回结果");
      }
      invalidateAffectedSnapshots();
      return response;
    } catch (RuntimeException error) {
      log.warn("Legacy platform formal import failed", error);
      String message = rootMessage(error);
      failRun(runId, message);
      return failureResponse(runId, message);
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

  private void validateSourceScope(CodeReviewMatchModeConfig config) {
    if (!org.springframework.util.StringUtils.hasText(config.mysqlJdbcUrl())
        || !org.springframework.util.StringUtils.hasText(config.mysqlUsername())) {
      throw new BizException("老平台 MySQL 配置不完整");
    }
    if (!config.selectedTableNames().contains(config.mysqlTableName())) {
      throw new BizException("代码走查交接范围必须包含表: " + config.mysqlTableName());
    }
  }

  //兼容模式-MatchMode
  /**
   * 把兼容模式（MatchMode）老平台记录提升为正式评审事实修订。
   *
   * <p>按来源实例在 {@code COMPAT_REVIEW} 域内组织发布：先对成熟数据做合格初始接管建立完整基线，随后在同
   * 一代际开启候选，把本次范围内的来源行写成候选修订，来源已消失的根按完整业务身份登记删除证明，最后由
   * 候选发布原子关闭旧修订。已发布修订一律不原地改写，因此旧版本继续可读、删除只在新版本出现。
   *
   * @param normalizedSourceInstanceFilter 仅提升该来源实例（已规范化）；为空表示提升来源与既有事实涉及的全部实例
   * @param mergeRequestIidFilter 仅提升该来源项目内 MR 编号；为空表示该实例全部记录
   * @return 本次提升的新增/更新/删除计数
   */
  private CodeReviewImportCounters importCodeReviewData(
      String normalizedSourceInstanceFilter, Long mergeRequestIidFilter) {
    CodeReviewImportCounters total = new CodeReviewImportCounters(0L, 0L, 0L);
    for (String sourceInstance : promotedSourceInstances(normalizedSourceInstanceFilter)) {
      total = total.plus(promoteSourceInstance(sourceInstance, mergeRequestIidFilter));
    }
    return total;
  }

  private CodeReviewImportCounters promoteSourceInstance(
      String sourceInstance, Long mergeRequestIid) {
    // 域键会对来源实例做规范化；若库里存的是非规范写法，接管与删除证明会按不同的身份匹配，
    // 产生"同一根两份可见行"。这种情况必须显式失败，不能静默继续。
    if (!sourceInstance.equals(GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance))) {
      throw new BizException(
          "兼容评审来源实例标识未规范化：" + sourceInstance + "，请先修正来源数据后再执行正式导入");
    }
    ReadPublicationDomainKey domainKey = compatReviewDomain(sourceInstance);
    String sourceSeal = compatReviewSourceSeal(sourceInstance, null);
    boolean fullDataset = !ensureCompatReviewBaseline(domainKey, sourceSeal);
    Long capturedIid = fullDataset ? null : mergeRequestIid;
    CodeReviewImportCounters counters =
        countCodeReviewImportRows(sourceInstance, capturedIid);
    List<Long> removedRootIds = removedCompatReviewRootIds(sourceInstance, capturedIid);
    factTargetPublicationService.publishWithinCandidate(
        new BypassPublicationRequest(
            domainKey,
            SOURCE_SYSTEM_LEGACY_PLATFORM,
            sourceSeal,
            0L,
            FORMAL_IMPORT_PRODUCER,
            null,
            Instant.now(), fullDataset),
        target -> {
          int written = upsertCompatReviewRevisions(target, sourceInstance, capturedIid);
          // 删除证明必须在候选发布前登记：发布只关闭清单内触及根的旧修订，漏登记会发布成“旧根仍可见的成功”。
          if (!removedRootIds.isEmpty()) {
            readPublicationRepository.recordDeletionsForRoots(
                PublicationFactTable.MERGE_REQUEST,
                target.publicationId(),
                SOURCE_SYSTEM_LEGACY_PLATFORM,
                sourceInstance,
                removedRootIds,
                target.coveredChangeVersion());
          }
          return new FactBuildResponse(
              "legacy-platform-formal-import", false, written, "兼容评审事实已生成新修订");
        });
    return new CodeReviewImportCounters(
        counters.inserted(), counters.updated(), removedRootIds.size());
  }

  private List<String> promotedSourceInstances(String normalizedSourceFilter) {
    if (normalizedSourceFilter == null) {
      return jdbcTemplate.queryForList(
          """
          select distinct source_instance from (
            select source_instance from code_review_match_mode_records
            union
            select source_instance from merge_request_fact where source_system = 'LEGACY_PLATFORM'
          ) sources
           order by source_instance
          """,
          String.class);
    }
    return jdbcTemplate.queryForList(
        """
        select distinct source_instance from (
          select source_instance
            from code_review_match_mode_records
           where lower(source_instance) = ?
          union
          select source_instance
            from merge_request_fact
           where source_system = 'LEGACY_PLATFORM'
             and lower(source_instance) = ?
        ) sources
         order by source_instance
        """,
        String.class,
        normalizedSourceFilter,
        normalizedSourceFilter);
  }

  /**
   * 成熟兼容评审数据的初始接管：无当前发布指针时，把该来源实例的既有完整事实登记为初始版本。
   *
   * <p>接管前核验可采纳性（同一业务根不得存在多条未版本化行），采纳只置代际与发布身份，不改业务列。
   * 输入栅栏取不到时直接失败并提示重试，不允许退化成“用局部集合冒充首个完整版本”。
   */
  private boolean ensureCompatReviewBaseline(
      ReadPublicationDomainKey domainKey, String sourceSeal) {
    if (compatReviewBaselineExists(domainKey)) {
      return true;
    }
    Long existing = jdbcTemplate.queryForObject("select count(*) from merge_request_fact"
        + " where source_system = 'LEGACY_PLATFORM' and source_instance = ?", Long.class,
        domainKey.sourceInstance());
    if (existing != null && existing == 0) {
      Boolean completeInput = jdbcTemplate.queryForObject("select exists(select 1 from code_review_match_mode_sync_state"
          + " where id = 1 and status = 'SUCCESS' and finished_at is not null)", Boolean.class);
      if (!Boolean.TRUE.equals(completeInput)) {
        throw new BizException("兼容评审尚无完整来源基线，请先完成一次完整兼容同步");
      }
      // 首次不发布空基线：定向请求也先从自有完整来源数据集构建首个候选，投影完成才激活。
      return false;
    }
    readPublicationService.bootstrapInitialVersion(
        domainKey,
        SOURCE_SYSTEM_LEGACY_PLATFORM,
        Instant.now(),
        sourceSeal,
        0L,
        key -> compatBaselineVerification(key, domainKey.sourceInstance()));
    if (compatReviewBaselineExists(domainKey)) {
      return true;
    }
    throw new BizException("兼容评审数据初始接管未取得稳定输入，请稍后重试正式导入");
  }

  private boolean compatReviewBaselineExists(ReadPublicationDomainKey domainKey) {
    return readPublicationRepository
        .findDomain(domainKey)
        .map(ReadPublicationDomain::currentPublicationId)
        .isPresent();
  }

  private InitialVersionVerification.VerificationResult compatBaselineVerification(
      ReadPublicationDomainKey domainKey, String sourceInstance) {
    Long duplicated = jdbcTemplate.queryForObject(
        """
        select count(*)
          from (
            select project_id, merge_request_id
              from merge_request_fact
             where source_system = 'LEGACY_PLATFORM'
               and source_instance = ?
               and publication_epoch_id is null
             group by project_id, merge_request_id
            having count(*) > 1
          ) duplicated
        """,
        Long.class,
        sourceInstance);
    if (duplicated != null && duplicated > 0L) {
      return InitialVersionVerification.VerificationResult.refused(
          "兼容评审来源实例 " + sourceInstance + " 存在 " + duplicated + " 组重复业务根，需先人工核对");
    }
    Long foreignOwned = jdbcTemplate.queryForObject(
        """
        select count(*)
          from merge_request_fact
         where source_system = 'LEGACY_PLATFORM'
           and source_instance = ?
           and publication_epoch_id is not null
           and created_by_publication_id is null
        """,
        Long.class,
        sourceInstance);
    if (foreignOwned != null && foreignOwned > 0L) {
      return InitialVersionVerification.VerificationResult.refused(
          "兼容评审来源实例 " + sourceInstance + " 存在代际已置但发布身份缺失的行，接管输入不完整");
    }
    Boolean completeImport = jdbcTemplate.queryForObject("select exists(select 1 from legacy_platform_formal_import_runs"
        + " where status = 'SUCCESS' and code_review_requested = true)", Boolean.class);
    return Boolean.TRUE.equals(completeImport)
        ? InitialVersionVerification.VerificationResult.accepted()
        : InitialVersionVerification.VerificationResult.refused("既有兼容评审事实缺少完整正式导入成功证据");
  }

  private String compatReviewSourceSeal(String sourceInstance, Long mergeRequestIid) {
    Map<String, Object> evidence =
        jdbcTemplate.queryForMap(
            mergeRequestIid == null
                ? """
                select count(*) as source_rows,
                       coalesce(max(record.synced_at)::text, '') as max_synced_at,
                       coalesce(max(record.id), 0) as max_record_id,
                       coalesce((select settings.updated_at::text
                                   from code_review_match_mode_db_settings settings
                                  where settings.id = 1), '') as db_settings_updated_at,
                       coalesce((select state.status::text
                                   from code_review_match_mode_sync_state state
                                  where state.id = 1), '') as sync_state_status,
                       coalesce((select state.updated_at::text
                                   from code_review_match_mode_sync_state state
                                  where state.id = 1), '') as sync_state_updated_at
                  from code_review_match_mode_records record
                 where record.source_instance = ?
                """
                : """
                select count(*) as source_rows,
                       coalesce(max(record.synced_at)::text, '') as max_synced_at,
                       coalesce(max(record.id), 0) as max_record_id,
                       coalesce((select settings.updated_at::text
                                   from code_review_match_mode_db_settings settings
                                  where settings.id = 1), '') as db_settings_updated_at,
                       coalesce((select state.status::text
                                   from code_review_match_mode_sync_state state
                                  where state.id = 1), '') as sync_state_status,
                       coalesce((select state.updated_at::text
                                   from code_review_match_mode_sync_state state
                                  where state.id = 1), '') as sync_state_updated_at
                  from code_review_match_mode_records record
                 where record.source_instance = ?
                   and record.merge_request_iid = ?
                """,
            mergeRequestIid == null
                ? new Object[] {sourceInstance}
                : new Object[] {sourceInstance, mergeRequestIid});
    Map<String, Object> seal = new LinkedHashMap<>();
    seal.put("producer", FORMAL_IMPORT_PRODUCER);
    seal.put("sourceSystem", SOURCE_SYSTEM_LEGACY_PLATFORM);
    seal.put("sourceInstance", sourceInstance);
    seal.put("sourceTable", "code_review_match_mode_records");
    seal.putAll(evidence);
    return jsonUtils.toJson(seal);
  }

  private ReadPublicationDomainKey compatReviewDomain(String sourceInstance) {
    return new ReadPublicationDomainKey(
        ReadPublicationProducerKind.COMPAT_REVIEW,
        sourceInstance,
        FactType.MERGE_REQUEST.name(),
        "");
  }

  /** 登记删除证明的候选：来源已不再提供、但当前发布版本仍可见的兼容评审根。 */
  private List<Long> removedCompatReviewRootIds(String sourceInstance, Long mergeRequestIid) {
    String scopedRoot =
        mergeRequestIid == null
            ? "fact.source_instance = ?"
            : "fact.source_instance = ? and fact.merge_request_iid = ?";
    String scopedSource =
        mergeRequestIid == null
            ? "source.source_instance = fact.source_instance"
            : "source.source_instance = fact.source_instance"
                + " and source.merge_request_iid = fact.merge_request_iid";
    String sql =
        """
        select fact.merge_request_id
          from merge_request_fact fact
         where fact.source_system = 'LEGACY_PLATFORM'
           and %s
           and fact.deleted = false
           and (fact.created_by_publication_id is null
                or (fact.visible_from is not null and fact.visible_to is null))
           and not exists (
             select 1
               from code_review_match_mode_records source
              where %s
                and coalesce(source.project_id, 0) = fact.project_id
                and case
                      when coalesce(source.legacy_source_id, '') ~ '^[0-9]+$'
                        then source.legacy_source_id::bigint
                      else source.id
                    end = fact.merge_request_id
           )
         order by fact.merge_request_id
        """.formatted(scopedRoot, scopedSource);
    return mergeRequestIid == null
        ? jdbcTemplate.queryForList(sql, Long.class, sourceInstance)
        : jdbcTemplate.queryForList(sql, Long.class, sourceInstance, mergeRequestIid);
  }

  //兼容模式-MatchMode
  private int upsertCompatReviewRevisions(
      FactRevisionTarget target, String sourceInstance, Long mergeRequestIid) {
    String scopedSource =
        mergeRequestIid == null
            ? "where source_instance = ?"
            : "where source_instance = ? and merge_request_iid = ?";
    String sql =
        """
        insert into merge_request_fact(
          source_system,
          publication_epoch_id,
          created_by_publication_id,
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
          ?,
          ?,
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
        """
            + scopedSource + "\n"
            + """
        on conflict (created_by_publication_id, source_system, source_instance, project_id,
                     merge_request_id)
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
        """;
    // 冲突目标只可能是同一候选内的重复提升（幂等重放）；已发布修订永不命中，因此不存在原地改写。
    return mergeRequestIid == null
        ? jdbcTemplate.update(sql, target.epochId(), target.publicationId(), sourceInstance)
        : jdbcTemplate.update(
            sql, target.epochId(), target.publicationId(), sourceInstance, mergeRequestIid);
  }

  private CodeReviewImportCounters countCodeReviewImportRows(
      String sourceInstance, Long mergeRequestIid) {
    String scopedSource =
        mergeRequestIid == null
            ? "where source_instance = ?"
            : "where source_instance = ? and merge_request_iid = ?";
    String scopedFact =
        mergeRequestIid == null
            ? "and fact.source_instance = src.source_instance"
            : "and fact.source_instance = src.source_instance"
                + " and fact.merge_request_iid = src.merge_request_iid";
    String sql =
        """
        with source_rows as (
          select
            source_instance,
            merge_request_iid,
            coalesce(project_id, 0) as project_id,
            case
              when coalesce(legacy_source_id, '') ~ '^[0-9]+$' then legacy_source_id::bigint
              else id
            end as merge_request_id
          from code_review_match_mode_records
          """
            + scopedSource + "\n"
            + """
        )
        select
          count(*) filter (where fact.id is null) as inserted_count,
          count(*) filter (where fact.id is not null) as updated_count
        from source_rows src
        left join merge_request_fact fact
          on fact.source_system = 'LEGACY_PLATFORM'
         """
            + scopedFact
            + """
         and fact.project_id = src.project_id
         and fact.merge_request_id = src.merge_request_id
         and (fact.created_by_publication_id is null
              or (fact.visible_from is not null and fact.visible_to is null))
        """;
    return jdbcTemplate.query(
        sql,
        rs -> {
          if (!rs.next()) {
            return new CodeReviewImportCounters(0, 0, 0);
          }
          return new CodeReviewImportCounters(
              rs.getLong("inserted_count"), rs.getLong("updated_count"), 0);
        },
        mergeRequestIid == null
            ? new Object[] {sourceInstance}
            : new Object[] {sourceInstance, mergeRequestIid});
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
          code_review_requested,
          operator_username,
          settings_updated_at,
          code_review_status,
          status,
          message,
          source_summary
        ) values (
          'CODE_REVIEW_PROMOTION', ?, true, ?, ?, 'PENDING', 'RUNNING', '正在交接老平台代码走查数据', ?::jsonb
        ) returning id
        """,
        Long.class,
        TextQuerySupport.normalizeDisplay(request.confirmationText()),
        TextQuerySupport.normalizeDisplay(operatorUsername),
        settingsUpdatedAt,
        sourceSummary(config));
    if (runId == null) {
      throw new IllegalStateException("创建老平台数据交接任务失败");
    }
    return runId;
  }

  private void finishRun(
      long runId,
      CodeReviewImportCounters counters,
      String status,
      String message) {
    jdbcTemplate.update("""
        update legacy_platform_formal_import_runs
           set code_review_inserted_count = ?,
               code_review_updated_count = ?,
               code_review_deleted_count = ?,
               code_review_status = ?,
               status = ?,
               message = ?,
               finished_at = current_timestamp
         where id = ?
        """,
        counters.inserted(),
        counters.updated(),
        counters.deleted(),
        "SUCCESS",
        status,
        message,
        runId);
  }

  private void failRun(long runId, String message) {
    jdbcTemplate.update("""
        update legacy_platform_formal_import_runs
           set code_review_status = ?,
               status = 'FAILED',
               message = ?,
               finished_at = current_timestamp
         where id = ?
        """,
        "FAILED",
        message,
        runId);
  }

  private String sourceSummary(CodeReviewMatchModeConfig config) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("mysqlTable", config.mysqlTableName());
    summary.put("mysqlTables", config.selectedTableNames());
    return jsonUtils.toJson(summary);
  }

  private LegacyPlatformFormalImportResponse successResponse(
      long runId,
      CodeReviewImportCounters counters) {
    return new LegacyPlatformFormalImportResponse(
        runId,
        true,
        "SUCCESS",
        "老平台代码走查数据已转入新平台正式数据",
        domainResponse(
            "SUCCESS",
            counters.inserted(),
            counters.updated(),
            0,
            counters.deleted(),
            "代码走查数据交接完成"));
  }

  private LegacyPlatformFormalImportResponse failureResponse(
      long runId,
      String message) {
    return new LegacyPlatformFormalImportResponse(
        runId,
        false,
        "FAILED",
        message,
        domainResponse("FAILED", 0, 0, 0, 0, message));
  }

  private LegacyPlatformFormalImportDomainResponse domainResponse(
      String requestedStatus,
      long inserted,
      long updated,
      long skipped,
      long deleted,
      String message) {
    return new LegacyPlatformFormalImportDomainResponse(
        requestedStatus,
        inserted,
        updated,
        skipped,
        deleted,
        message);
  }

  private void invalidateAffectedSnapshots() {
    pageRecordSnapshotService.invalidatePage(CodeReviewIllegalRecordService.WORKSPACE_KEY);
  }

  private String rootMessage(Throwable error) {
    Throwable cursor = error;
    while (cursor.getCause() != null) {
      cursor = cursor.getCause();
    }
    String message = cursor.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  private record CodeReviewImportCounters(long inserted, long updated, long deleted) {
    CodeReviewImportCounters plus(CodeReviewImportCounters other) {
      return new CodeReviewImportCounters(
          inserted + other.inserted(),
          updated + other.updated(),
          deleted + other.deleted());
    }
  }
}
