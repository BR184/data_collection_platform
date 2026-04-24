package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.IntegrationTestFact;
import com.data.collection.platform.mapper.IntegrationTestFactMapper;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class IntegrationTestFactBuildService {
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final String DEFAULT_SOURCE_INSTANCE = "default";
  private static final String MIRROR_INGEST_CHANNEL = "MIRROR";

  private static final String INTEGRATION_TEST_SOURCE_SQL = """
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
           and l.title is not null
           and l.title <> ''
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
  private final IntegrationTestFactMapper integrationTestFactMapper;
  private final ModuleDictionaryService moduleDictionaryService;

  public IntegrationTestFactBuildService(
      JdbcTemplate jdbcTemplate,
      IntegrationTestFactMapper integrationTestFactMapper,
      ModuleDictionaryService moduleDictionaryService) {
    this.jdbcTemplate = jdbcTemplate;
    this.integrationTestFactMapper = integrationTestFactMapper;
    this.moduleDictionaryService = moduleDictionaryService;
  }

  public FactBuildResponse rebuildFacts(boolean full) {
    LocalDateTime changedSince = full ? null : getChangedSince();
    String sql =
        INTEGRATION_TEST_SOURCE_SQL
            + (changedSince == null
                ? ""
                : " and greatest(coalesce(i.updated_at, i.created_at), coalesce(notes.note_updated_at, i.updated_at, i.created_at)) > ?");
    try {
      ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar = loadPhaseCalendar();
      List<IntegrationTestFact> facts =
          changedSince == null
              ? jdbcTemplate.query(sql, (rs, rowNum) -> mapFact(rs, moduleDictionary, calendar))
              : jdbcTemplate.query(
                  sql,
                  (rs, rowNum) -> mapFact(rs, moduleDictionary, calendar),
                  Timestamp.valueOf(changedSince));
      if (full) {
        jdbcTemplate.update(
            """
            delete from integration_test_fact
             where source_system = ?
               and source_instance = ?
            """,
            DEFAULT_SOURCE_SYSTEM,
            DEFAULT_SOURCE_INSTANCE);
      }
      for (IntegrationTestFact fact : facts) {
        integrationTestFactMapper.upsert(fact);
      }
      return new FactBuildResponse(
          "integration-test",
          full,
          facts.size(),
          changedSince == null ? "集成测试事实已全量构建" : "集成测试事实已按增量构建");
    } catch (DataAccessException ex) {
      log.warn("Failed to rebuild integration test facts", ex);
      throw ex;
    }
  }

  private LocalDateTime getChangedSince() {
    return jdbcTemplate.queryForObject(
        """
        select max(ods_updated_at)
          from integration_test_fact
         where source_system = ?
           and source_instance = ?
        """,
        LocalDateTime.class,
        DEFAULT_SOURCE_SYSTEM,
        DEFAULT_SOURCE_INSTANCE);
  }

  private IntegrationTestFact mapFact(
      ResultSet rs,
      ModuleDictionary moduleDictionary,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar)
      throws SQLException {
    List<String> labels = readTextArray(rs.getArray("label_titles"));
    String noteText = defaultText(rs.getString("note_text"), "");
    ParsedIntegrationNote parsed = parseIntegrationNote(noteText);
    Long projectId = rs.getLong("project_id");
    LocalDateTime createdAt = toLocalDateTime(rs.getTimestamp("created_at"));
    LocalDateTime updatedAt = toLocalDateTime(rs.getTimestamp("updated_at"));
    String testingPhase = resolveTestingPhase(projectId, labels, updatedAt, createdAt, calendar);
    List<String> moduleNames =
        moduleDictionary.normalizeIssueModules(
            projectId, IssueFactNormalizationRules.normalizeModuleNames(labels));
    List<String> functionLabels = IntegrationTestFactRules.extractFunctionLabels(labels);
    String moduleName = moduleNames.isEmpty() ? "未识别模块" : moduleNames.get(0);

    IntegrationTestFact fact = new IntegrationTestFact();
    fact.setSourceSystem(DEFAULT_SOURCE_SYSTEM);
    fact.setSourceInstance(DEFAULT_SOURCE_INSTANCE);
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
    fact.setModuleName(moduleName);
    fact.setFunctionName(parsed.functionName());
    fact.setExecutor(parsed.executor());
    fact.setTestingPhase(testingPhase);
    fact.setExecuteCase(parsed.executeCase());
    fact.setPassCase(parsed.passCase());
    fact.setNotPassCase(parsed.notPassCase());
    fact.setNotPassCaseNow(parsed.notPassCaseNow());
    fact.setProblemCase(parsed.problemCase());
    fact.setExceptionCount(parsed.exceptionCount());
    fact.setPassRate(calculatePassRate(parsed.executeCase(), parsed.passCase()));
    IntegrationTestFactRules.ValidationResult validation =
        IntegrationTestFactRules.validateRecord(
            parsed.executeCase(), parsed.passCase(), parsed.notPassCaseNow());
    fact.setLegal(validation.legal());
    fact.setParseStatus(validation.parseStatus());
    fact.setValidationReason(validation.validationReason());
    fact.setLabelNames(String.join(", ", labels));
    fact.setFunctionLabels(String.join(", ", functionLabels));
    fact.setDeleted(false);
    return fact;
  }

  private Map<PhaseCalendarKey, PhaseCalendarEntry> loadPhaseCalendar() {
    List<PhaseCalendarEntry> entries =
        jdbcTemplate.query(
            """
            select project_id, testing_phase, phase_start_at, phase_end_at, enabled
              from testing_phase_calendar
             where enabled = true
            """,
            (rs, rowNum) ->
                new PhaseCalendarEntry(
                    rs.getLong("project_id"),
                    defaultText(rs.getString("testing_phase"), null),
                    toLocalDateTime(rs.getTimestamp("phase_start_at")),
                    toLocalDateTime(rs.getTimestamp("phase_end_at")),
                    rs.getBoolean("enabled")));
    Map<PhaseCalendarKey, PhaseCalendarEntry> result = new LinkedHashMap<>();
    for (PhaseCalendarEntry entry : entries) {
      result.putIfAbsent(
          new PhaseCalendarKey(entry.projectId(), normalizeKey(entry.testingPhase())), entry);
    }
    return result;
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
      if (!projectId.equals(entry.projectId())) {
        continue;
      }
      if (!StringUtils.hasText(entry.testingPhase())
          || !entry.testingPhase().contains("集成测试")) {
        continue;
      }
      if (entry.matches(referenceTime)) {
        return entry.testingPhase();
      }
    }
    return null;
  }

  private ParsedIntegrationNote parseIntegrationNote(String noteText) {
    if (!StringUtils.hasText(noteText)) {
      return ParsedIntegrationNote.empty();
    }
    List<String> lines = List.of(noteText.replace("\r\n", "\n").split("\n"));
    boolean started = false;
    String functionName = null;
    String executor = null;
    Integer executeCase = null;
    Integer passCase = null;
    Integer notPassCase = null;
    Integer notPassCaseNow = null;
    Integer problemCase = null;
    Integer exceptionCount = null;
    for (String rawLine : lines) {
      String line = TextQuerySupport.trimToNull(stripMarkdownPrefix(rawLine));
      if (line == null) {
        continue;
      }
      if (!started) {
        if (line.contains("集成测试数据")) {
          started = true;
        }
        continue;
      }
      if (line.startsWith("## ") && !line.contains("集成测试数据")) {
        break;
      }
      KeyValue keyValue = splitKeyValue(line);
      if (keyValue == null) {
        continue;
      }
      String key = keyValue.key();
      String value = keyValue.value();
      if (IntegrationTestFactRules.matchesKey(key, "功能标签")) {
        continue;
      }
      if (IntegrationTestFactRules.matchesKey(key, "功能")) {
        functionName = value;
      } else if (IntegrationTestFactRules.matchesKey(key, "执行人")) {
        executor = value;
      } else if (IntegrationTestFactRules.matchesKey(key, "执行用例总数", "执行用例数")) {
        executeCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "初始未通过用例数", "初始未通过")) {
        notPassCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "本次未通过用例数", "本次未通过")) {
        notPassCaseNow = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "本次问题用例数", "本次问题用例")) {
        problemCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "本次通过用例数", "通过用例数", "通过用例")) {
        passCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "未通过用例数", "未通过用例")) {
        notPassCaseNow = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "问题用例数", "问题用例")) {
        problemCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "用例外问题数", "例外问题数")) {
        exceptionCount = IntegrationTestFactRules.parseNumericValue(value);
      }
    }
    return new ParsedIntegrationNote(
        functionName,
        executor,
        executeCase,
        passCase,
        notPassCase,
        notPassCaseNow,
        problemCase,
        exceptionCount);
  }

  private String stripMarkdownPrefix(String value) {
    String result = value == null ? "" : value.trim();
    while (result.startsWith("#") || result.startsWith("-") || result.startsWith("*")) {
      result = result.substring(1).trim();
    }
    return result;
  }

  private KeyValue splitKeyValue(String line) {
    int colonIndex = line.indexOf('：');
    if (colonIndex < 0) {
      colonIndex = line.indexOf(':');
    }
    if (colonIndex < 0) {
      return null;
    }
    String key = TextQuerySupport.trimToNull(line.substring(0, colonIndex));
    String value = TextQuerySupport.trimToNull(line.substring(colonIndex + 1));
    return key == null || value == null ? null : new KeyValue(key, value);
  }

  private BigDecimal calculatePassRate(Integer executeCase, Integer passCase) {
    if (executeCase == null || executeCase <= 0 || passCase == null || passCase <= 0) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(passCase)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(executeCase), 2, RoundingMode.HALF_UP);
  }

  private boolean isClosed(ResultSet rs) throws SQLException {
    Timestamp closedAt = rs.getTimestamp("closed_at");
    Integer stateId = (Integer) rs.getObject("state_id");
    return closedAt != null || (stateId != null && stateId != 1);
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private List<String> readTextArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (!(raw instanceof Object[] values)) {
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

  private String normalizeKey(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
  }

  private String defaultText(String value) {
    return defaultText(value, "");
  }

  private String defaultText(String value, String fallback) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private record KeyValue(String key, String value) {}

  private record ParsedIntegrationNote(
      String functionName,
      String executor,
      Integer executeCase,
      Integer passCase,
      Integer notPassCase,
      Integer notPassCaseNow,
      Integer problemCase,
      Integer exceptionCount) {
    private static ParsedIntegrationNote empty() {
      return new ParsedIntegrationNote(null, null, null, null, null, null, null, null);
    }
  }

  private record PhaseCalendarKey(Long projectId, String testingPhase) {}

  private record PhaseCalendarEntry(
      Long projectId,
      String testingPhase,
      LocalDateTime phaseStartAt,
      LocalDateTime phaseEndAt,
      boolean enabled) {
    private boolean matches(LocalDateTime target) {
      if (!enabled || target == null || phaseStartAt == null) {
        return false;
      }
      return !target.isBefore(phaseStartAt)
          && (phaseEndAt == null || !target.isAfter(phaseEndAt));
    }
  }
}
