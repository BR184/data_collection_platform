package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IntegrationTestFact;
import com.data.collection.platform.mapper.IntegrationTestFactMapper;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarEntry;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
// 正式集成测试事实只由 GitLab ODS 议题、标签和评论构建；不得读取兼容模式-MatchMode 表。
public class IntegrationTestFactBuildService {
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final String DEFAULT_SOURCE_INSTANCE = "default";
  private static final String MIRROR_INGEST_CHANNEL = "MIRROR";

  private static final String SOURCE_SQL =
      """
      with distinct_issue_labels as (
        select distinct
               ll.target_id as issue_id,
               nullif(btrim(l.title), '') as title
          from ods_gitlab_label_links ll
          join ods_gitlab_labels l
            on l.id = ll.label_id
           and coalesce(l.mirror_deleted, false) = false
         where coalesce(ll.mirror_deleted, false) = false
           and ll.target_type = 'Issue'
           and nullif(btrim(l.title), '') is not null
      ),
      issue_labels as (
        select issue_id,
               array_agg(title order by lower(title)) as label_titles
          from distinct_issue_labels
         group by issue_id
      ),
      integration_notes as (
        select n.noteable_id as issue_id,
               n.id as note_id,
               coalesce(n.note, '') as note_text,
               n.created_at as note_created_at,
               coalesce(n.updated_at, n.created_at) as note_updated_at,
               row_number() over (
                 partition by n.noteable_id
                 order by coalesce(n.updated_at, n.created_at) desc nulls last, n.id desc
               ) as rn
          from ods_gitlab_notes n
         where coalesce(n.mirror_deleted, false) = false
           and n.noteable_type = 'Issue'
           and n.note is not null
           and n.note like '%集成测试数据%'
      )
      select
        i.id as issue_id,
        i.iid as issue_iid,
        i.project_id,
        p.name as project_name,
        i.title,
        coalesce(author.name, '') as author_name,
        i.created_at,
        i.updated_at,
        greatest(
          coalesce(i.updated_at, i.created_at),
          coalesce(notes.note_updated_at, i.updated_at, i.created_at)
        ) as ods_updated_at,
        i.closed_at,
        i.state_id,
        labels.label_titles,
        notes.note_id,
        notes.note_text,
        notes.note_created_at,
        notes.note_updated_at
      from ods_gitlab_issues i
      join integration_notes notes
        on notes.issue_id = i.id
       and notes.rn = 1
      left join ods_gitlab_projects p
        on p.id = i.project_id
       and coalesce(p.mirror_deleted, false) = false
      left join ods_gitlab_users author
        on author.id = i.author_id
       and coalesce(author.mirror_deleted, false) = false
      left join issue_labels labels
        on labels.issue_id = i.id
      where coalesce(i.mirror_deleted, false) = false
      """;

  private final JdbcTemplate jdbcTemplate;
  private final IntegrationTestFactMapper factMapper;
  private final ModuleDictionaryService moduleDictionaryService;
  private final GitlabSourceSchemaGuard sourceSchemaGuard;
  private final SqlQueryMonitor sqlQueryMonitor;
  private final GitlabConfigService configService;
  private final IssuePhaseCalendarLoader phaseCalendarLoader;

  public IntegrationTestFactBuildService(
      JdbcTemplate jdbcTemplate,
      IntegrationTestFactMapper factMapper,
      ModuleDictionaryService moduleDictionaryService,
      GitlabSourceSchemaGuard sourceSchemaGuard,
      SqlQueryMonitor sqlQueryMonitor,
      GitlabConfigService configService,
      IssuePhaseCalendarLoader phaseCalendarLoader) {
    this.jdbcTemplate = jdbcTemplate;
    this.factMapper = factMapper;
    this.moduleDictionaryService = moduleDictionaryService;
    this.sourceSchemaGuard = sourceSchemaGuard;
    this.sqlQueryMonitor = sqlQueryMonitor;
    this.configService = configService;
    this.phaseCalendarLoader = phaseCalendarLoader;
  }

  @Transactional
  public FactBuildResponse rebuildFacts(boolean full) {
    return rebuildFactsForSource(DEFAULT_SOURCE_INSTANCE, full);
  }

  @Transactional
  public FactBuildResponse rebuildFacts(boolean full, Long configId) {
    GitlabSyncConfig config =
        configId == null ? configService.getConfig() : configService.getConfigById(configId);
    return rebuildFactsForConfig(config, full);
  }

  @Transactional
  public FactBuildResponse rebuildFactsForConfig(GitlabSyncConfig config, boolean full) {
    return rebuildFactsForSource(GitlabSourceInstanceSupport.sourceInstanceOf(config), full);
  }

  @Transactional
  public FactBuildResponse rebuildFactsForSource(String sourceInstance, boolean full) {
    String normalizedSource =
        GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    sourceSchemaGuard.verifyIntegrationTestSource(normalizedSource);
    try {
      List<IntegrationTestFact> facts = loadFacts(normalizedSource, List.of());
      jdbcTemplate.update(
          """
          delete from integration_test_fact
           where source_system = ?
             and source_instance = ?
          """,
          DEFAULT_SOURCE_SYSTEM,
          normalizedSource);
      facts.forEach(factMapper::upsert);
      return new FactBuildResponse(
          factScope(normalizedSource),
          full,
          facts.size(),
          facts.isEmpty() ? "集成测试事实已完成一致性重建，当前源无有效数据" : "集成测试事实已完成一致性重建");
    } catch (DataAccessException error) {
      log.warn("Failed to rebuild integration test facts", error);
      throw error;
    }
  }

  @Transactional
  public FactBuildResponse rebuildFactsByRootIds(
      String sourceInstance, List<Long> rootIds) {
    String normalizedSource =
        GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    List<Long> safeRootIds = sanitizeRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      return new FactBuildResponse(
          factScope(normalizedSource), false, 0, "没有需要刷新的集成测试事实");
    }
    sourceSchemaGuard.verifyIntegrationTestSource(normalizedSource);
    List<IntegrationTestFact> facts = loadFacts(normalizedSource, safeRootIds);
    deleteRootFacts(normalizedSource, safeRootIds);
    facts.forEach(factMapper::upsert);
    return new FactBuildResponse(
        factScope(normalizedSource), false, facts.size(), "集成测试事实已按受影响议题刷新");
  }

  private List<IntegrationTestFact> loadFacts(
      String sourceInstance, List<Long> rootIds) {
    List<Object> args = new ArrayList<>(rootIds);
    // 正式 GitLab 镜像只有一套 ods_gitlab_* 表；source_instance 只标识事实来源，
    // 不再参与镜像表名改写，避免重新引入已废弃的多镜像表结构。
    String sql = SOURCE_SQL + rootPredicate("i.id", rootIds);
    ModuleDictionary dictionary = moduleDictionaryService.loadDictionary();
    Map<PhaseCalendarKey, PhaseCalendarEntry> calendar =
        phaseCalendarLoader.loadIntegrationTestCalendar();
    long startedAt = sqlQueryMonitor.start();
    try {
      return jdbcTemplate.query(
          sql,
          (rs, rowNumber) -> mapFact(rs, sourceInstance, dictionary, calendar),
          args.toArray());
    } finally {
      sqlQueryMonitor.logIfSlow("integration-test-fact-source-query", sql, args, startedAt);
    }
  }

  private IntegrationTestFact mapFact(
      ResultSet rs,
      String sourceInstance,
      ModuleDictionary dictionary,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar)
      throws SQLException {
    List<String> labels = readTextArray(rs.getArray("label_titles"));
    String noteText = defaultText(rs.getString("note_text"), "");
    IntegrationTestNoteParser.ParsedIntegrationNote parsed =
        IntegrationTestNoteParser.parse(noteText);
    Long projectId = rs.getLong("project_id");
    LocalDateTime createdAt = toLocalDateTime(rs.getTimestamp("created_at"));
    LocalDateTime updatedAt = toLocalDateTime(rs.getTimestamp("updated_at"));
    List<String> moduleNames =
        dictionary.normalizeIssueModules(
            projectId, IssueFactNormalizationRules.normalizeModuleNames(labels));
    List<String> functionLabels = IntegrationTestFactRules.extractFunctionLabels(labels);
    IntegrationTestFactRules.ValidationResult validation =
        IntegrationTestFactRules.validateRecord(
            parsed.executeCase(),
            parsed.passCase(),
            parsed.notPassCaseNow(),
            parsed.notPassCase(),
            parsed.problemCase(),
            parsed.exceptionCount());

    IntegrationTestFact fact = new IntegrationTestFact();
    fact.setSourceSystem(DEFAULT_SOURCE_SYSTEM);
    fact.setSourceInstance(sourceInstance);
    fact.setIngestChannel(MIRROR_INGEST_CHANNEL);
    fact.setSourceSummary("GitLab 集成测试备注解析");
    fact.setRawPayload(noteText);
    fact.setProjectId(projectId);
    fact.setProjectName(defaultText(rs.getString("project_name")));
    fact.setIssueId(rs.getLong("issue_id"));
    fact.setIssueIid(rs.getLong("issue_iid"));
    fact.setIssuableReference("#" + rs.getLong("issue_iid"));
    fact.setTitle(defaultText(rs.getString("title")));
    fact.setIssueState(isClosed(rs) ? "closed" : "opened");
    fact.setAuthorName(defaultText(rs.getString("author_name")));
    fact.setAssigneeName(null);
    fact.setCreatedAtSource(createdAt);
    fact.setUpdatedAtSource(updatedAt);
    fact.setOdsUpdatedAt(toLocalDateTime(rs.getTimestamp("ods_updated_at")));
    fact.setNoteId(rs.getLong("note_id"));
    fact.setNoteCreatedAtSource(toLocalDateTime(rs.getTimestamp("note_created_at")));
    fact.setNoteUpdatedAtSource(toLocalDateTime(rs.getTimestamp("note_updated_at")));
    fact.setModuleName(moduleNames.isEmpty() ? null : moduleNames.getFirst());
    fact.setFunctionName(parsed.functionName());
    fact.setExecutor(parsed.executor());
    fact.setTestingPhase(resolveTestingPhase(projectId, labels, updatedAt, createdAt, calendar));
    fact.setExecuteCase(parsed.executeCase());
    fact.setPassCase(parsed.passCase());
    fact.setNotPassCase(parsed.notPassCase());
    fact.setNotPassCaseNow(parsed.notPassCaseNow());
    fact.setProblemCase(parsed.problemCase());
    fact.setExceptionCount(parsed.exceptionCount());
    fact.setPassRate(calculatePassRate(parsed.executeCase(), parsed.passCase()));
    fact.setLegal(validation.legal());
    fact.setParseStatus(validation.parseStatus());
    fact.setValidationReason(validation.validationReason());
    fact.setLabelNames(String.join(", ", labels));
    fact.setFunctionLabels(String.join(", ", functionLabels));
    fact.setDeleted(false);
    return fact;
  }

  private void deleteRootFacts(String sourceInstance, List<Long> rootIds) {
    List<Object> args = new ArrayList<>();
    args.add(DEFAULT_SOURCE_SYSTEM);
    args.add(sourceInstance);
    args.addAll(rootIds);
    String predicate = rootPredicate("issue_id", rootIds);
    jdbcTemplate.update(
        "delete from integration_test_fact where source_system = ? and source_instance = ?"
            + predicate,
        args.toArray());
  }

  private String rootPredicate(String rootColumn, List<Long> rootIds) {
    if (rootIds == null || rootIds.isEmpty()) {
      return "";
    }
    return " and " + rootColumn + " in ("
        + String.join(", ", java.util.Collections.nCopies(rootIds.size(), "?")) + ")";
  }

  private List<Long> sanitizeRootIds(List<Long> rootIds) {
    if (rootIds == null || rootIds.isEmpty()) {
      return List.of();
    }
    return rootIds.stream()
        .filter(rootId -> rootId != null && rootId > 0L)
        .distinct()
        .sorted()
        .toList();
  }

  private String resolveTestingPhase(
      Long projectId,
      List<String> labels,
      LocalDateTime updatedAt,
      LocalDateTime createdAt,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar) {
    for (String label : labels) {
      String normalized = TextQuerySupport.trimToNull(label);
      if (normalized != null && normalized.contains("集成测试")) {
        return normalized;
      }
    }
    LocalDateTime referenceTime = updatedAt != null ? updatedAt : createdAt;
    if (referenceTime == null) {
      return null;
    }
    for (PhaseCalendarEntry entry : calendar.values()) {
      if (projectId.equals(entry.projectId())
          && StringUtils.hasText(entry.testingPhase())
          && entry.testingPhase().contains("集成测试")
          && entry.matches(referenceTime)) {
        return entry.testingPhase();
      }
    }
    return null;
  }

  private BigDecimal calculatePassRate(Integer executeCase, Integer passCase) {
    if (executeCase == null || executeCase <= 0 || passCase == null || passCase <= 0) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(passCase)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(executeCase), 2, RoundingMode.HALF_UP);
  }

  private List<String> readTextArray(Array array) throws SQLException {
    if (array == null || !(array.getArray() instanceof Object[] values)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object value : values) {
      String normalized = defaultText(value == null ? null : String.valueOf(value), null);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return result;
  }

  private boolean isClosed(ResultSet rs) throws SQLException {
    Timestamp closedAt = rs.getTimestamp("closed_at");
    Integer stateId = (Integer) rs.getObject("state_id");
    return closedAt != null || (stateId != null && stateId != 1);
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private String defaultText(String value) {
    return defaultText(value, "");
  }

  private String defaultText(String value, String fallback) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private String factScope(String sourceInstance) {
    return DEFAULT_SOURCE_INSTANCE.equals(sourceInstance)
        ? "integration-test"
        : sourceInstance + ":integration-test";
  }

}
