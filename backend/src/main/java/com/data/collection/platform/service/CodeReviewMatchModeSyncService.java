package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.CodeReviewMatchModeSyncResponse;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CodeReviewMatchModeSyncService {
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String CODE_REVIEW_TARGET_TABLE = "code_review_match_mode_records";
  private static final String CODE_REVIEW_TEMP_TABLE = "code_review_match_mode_records_loading";
  private static final String RAW_TARGET_TABLE = "legacy_mysql_imported_rows";
  private static final String RAW_TEMP_TABLE = "legacy_mysql_imported_rows_loading";

  private final CodeReviewMatchModeConfigService configService;
  private final CodeReviewMatchModeSwitchService switchService;
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public CodeReviewMatchModeSyncService(
      CodeReviewMatchModeConfigService configService,
      CodeReviewMatchModeSwitchService switchService,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils) {
    this.configService = configService;
    this.switchService = switchService;
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  //兼容模式-MatchMode
  @Scheduled(fixedDelayString = "${platform.code-review.match-mode.sync-delay-ms:3600000}", initialDelayString = "${platform.code-review.match-mode.initial-delay-ms:15000}")
  public void syncScheduled() {
    if (!switchService.isEnabled()) {
      return;
    }
    syncNow();
  }

  //兼容模式-MatchMode
  public CodeReviewMatchModeSyncResponse syncNow() {
    if (!switchService.isEnabled()) {
      return currentState(false, "兼容模式未开启，未同步老平台数据");
    }
    CodeReviewMatchModeConfig config = configService.loadConfig();
    if (!config.syncEnabled()) {
      return currentState(false, "兼容模式自动同步已关闭，未同步老平台数据");
    }
    if (!StringUtils.hasText(config.mysqlJdbcUrl()) || !StringUtils.hasText(config.mysqlUsername())) {
      markFailed("兼容模式老平台 MySQL 配置缺失");
      return currentState(false, "兼容模式老平台 MySQL 配置缺失");
    }
    markRunning();
    try {
      ImportSummary summary = loadAndReplace(config);
      markSuccess(summary);
      return currentState(true, "兼容模式已同步老平台数据");
    } catch (Exception error) {
      log.warn("Code review match mode sync failed", error);
      markFailed(error.getMessage());
      return currentState(false, error.getMessage());
    }
  }

  private ImportSummary loadAndReplace(CodeReviewMatchModeConfig config) throws SQLException {
    List<String> selectedTableNames = config.selectedTableNames();
    boolean refreshCodeReviewRecords = selectedTableNames.contains(config.mysqlTableName());
    createTempTables(refreshCodeReviewRecords);
    ImportSummary summary = new ImportSummary();
    try (Connection connection =
        DriverManager.getConnection(config.mysqlJdbcUrl(), config.mysqlUsername(), config.mysqlPassword())) {
      connection.setReadOnly(true);
      for (String tableName : selectedTableNames) {
        TableLoadResult result = loadRawRows(connection, config, tableName);
        summary.add(tableName, result.count(), result.columnNames());
      }
      if (refreshCodeReviewRecords) {
        summary.setCodeReviewRecordCount(loadCodeReviewRows(connection, config));
      }
    } catch (RuntimeException | SQLException error) {
      dropTempTables(refreshCodeReviewRecords);
      throw error;
    }
    replaceSnapshots(selectedTableNames, refreshCodeReviewRecords, summary);
    return summary;
  }

  private int loadCodeReviewRows(Connection connection, CodeReviewMatchModeConfig config) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(selectSql(config.mysqlTableName()))) {
      statement.setFetchSize(Math.max(1, config.mysqlFetchSize()));
      try (ResultSet rs = statement.executeQuery()) {
        return insertCodeReviewRows(config, rs);
      }
    }
  }

  private String selectSql(String tableName) {
    return """
        select
          id,
          name,
          code_walkthrough_date,
          status,
          module_name,
          project_name,
          issuable_reference,
          merge_request_title,
          author,
          assignee,
          assigneed,
          merged_time,
          merged_local_date_time,
          merged_user_name,
          code_walkthrough_duration,
          added_line,
          deleted_line,
          code_specification_count,
          code_logic_specification_count,
          performance_specification_count,
          design_specification_count,
          other_specification_count,
          defect_count,
          code_walkthrough_speed_loc,
          code_walkthrough_speed_kloc,
          code_walkthrough_defect_density,
          code_walkthrough_efficiency,
          target_branch,
          sonar_qube_result,
          commit_count,
          commit_rate,
          annotation_rate,
          annotation_rate_result,
          bug_count,
          bug_count_result,
          function_name,
          ct_code_line_count
        from %s
        """.formatted(quoteMysqlIdentifier(tableName));
  }

  private String quoteMysqlIdentifier(String tableName) {
    if (!StringUtils.hasText(tableName)) {
      throw new IllegalArgumentException("兼容模式 MySQL 表名不能为空");
    }
    String normalized = tableName.trim();
    if (normalized.length() > 255 || normalized.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("兼容模式 MySQL 表名不合法");
    }
    return "`" + normalized.replace("`", "``") + "`";
  }

  private int insertCodeReviewRows(CodeReviewMatchModeConfig config, ResultSet rs) throws SQLException {
    String sql = """
        insert into %s (
          source_instance, project_id, project_name, repository_name, merge_request_id, merge_request_iid,
          title, merge_request_state, target_branch, author_name, merge_user_name, owner_name,
          reviewer_names, assignee_names, module_name, label_names, search_text, search_compact,
          search_spell, search_initials, merged_at_source, code_walkthrough_date, review_status,
          review_duration_minutes, review_exception_reason, scan_status, scan_bug_count,
          annotation_rate_result, bug_count_result, comment_rate, defect_count, added_lines,
          deleted_lines, code_specification_count, code_logic_specification_count,
          performance_specification_count, design_specification_count, other_specification_count,
          review_speed_loc_per_hour, review_speed_kloc_per_hour, review_defect_density_per_kloc,
          review_efficiency_per_hour, commit_count, commit_rate, function_name, clang_added_line_count,
          legacy_source_id, synced_at
        ) values (
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp
        )
        """.formatted(CODE_REVIEW_TEMP_TABLE);
    int count = 0;
    List<Object[]> batch = new ArrayList<>();
    while (rs.next()) {
      LegacyRow row = mapLegacyRow(rs);
      batch.add(row.toArgs());
      if (batch.size() >= 500) {
        jdbcTemplate.batchUpdate(sql, batch);
        count += batch.size();
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      jdbcTemplate.batchUpdate(sql, batch);
      count += batch.size();
    }
    return count;
  }

  private TableLoadResult loadRawRows(
      Connection connection, CodeReviewMatchModeConfig config, String tableName) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(selectAllSql(tableName))) {
      statement.setFetchSize(Math.max(1, config.mysqlFetchSize()));
      try (ResultSet rs = statement.executeQuery()) {
        return insertRawRows(tableName, rs);
      }
    }
  }

  private String selectAllSql(String tableName) {
    return "select * from " + quoteMysqlIdentifier(tableName);
  }

  private TableLoadResult insertRawRows(String tableName, ResultSet rs) throws SQLException {
    String sql = """
        insert into %s (
          table_name, row_key, raw_payload, synced_at
        ) values (
          ?, ?, ?::jsonb, current_timestamp
        )
        """.formatted(RAW_TEMP_TABLE);
    int count = 0;
    int rowIndex = 0;
    List<Object[]> batch = new ArrayList<>();
    ResultSetMetaData metadata = rs.getMetaData();
    List<String> columnNames = new ArrayList<>();
    for (int index = 1; index <= metadata.getColumnCount(); index++) {
      String label = metadata.getColumnLabel(index);
      columnNames.add(label == null || label.isBlank() ? metadata.getColumnName(index) : label);
    }
    while (rs.next()) {
      rowIndex++;
      Map<String, Object> row = currentRow(rs);
      batch.add(new Object[] {tableName, legacyId(row, rowIndex), jsonUtils.toJson(row)});
      if (batch.size() >= 500) {
        jdbcTemplate.batchUpdate(sql, batch);
        count += batch.size();
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      jdbcTemplate.batchUpdate(sql, batch);
      count += batch.size();
    }
    return new TableLoadResult(count, List.copyOf(columnNames));
  }

  private LegacyRow mapLegacyRow(ResultSet rs) throws SQLException {
    String repositoryName = text(rs, "name");
    String legacySourceId = rs.getObject("id") == null ? null : String.valueOf(rs.getObject("id"));
    Long mergeRequestId = longValue(rs.getObject("id"));
    Integer mergeRequestIid = parseIid(text(rs, "issuable_reference"));
    if (mergeRequestIid != null) {
      mergeRequestId = mergeRequestIid.longValue();
    }
    String projectName = text(rs, "project_name");
    String moduleName = text(rs, "module_name");
    String title = text(rs, "merge_request_title");
    String author = text(rs, "author");
    String mergedBy = text(rs, "merged_user_name");
    String targetBranch = text(rs, "target_branch");
    String labelNames = syntheticLabels(projectName, moduleName);
    LocalDateTime mergedAt = localDateTime(rs.getObject("merged_local_date_time"));
    if (mergedAt == null) {
      mergedAt = parseDateTime(text(rs, "merged_time"));
    }
    LocalDateTime walkthroughDate = localDateTime(rs.getObject("code_walkthrough_date"));
    Double annotationRate = doubleValue(rs.getObject("annotation_rate"));
    TextQuerySupport.SearchIndex searchIndex =
        TextQuerySupport.buildSearchIndex(String.join(" ", safe(title), safe(author), safe(projectName), safe(repositoryName), safe(moduleName), safe(targetBranch), safe(mergedBy)));
    return new LegacyRow(
        "cc",
        0L,
        projectName,
        repositoryName,
        mergeRequestId == null ? 0L : mergeRequestId,
        mergeRequestIid == null ? 0 : mergeRequestIid,
        safe(title),
        text(rs, "status"),
        targetBranch,
        author,
        mergedBy,
        author,
        text(rs, "assignee"),
        text(rs, "assigneed"),
        moduleName,
        labelNames,
        searchIndex.normalized(),
        searchIndex.compact(),
        searchIndex.spell(),
        searchIndex.initials(),
        mergedAt,
        walkthroughDate,
        text(rs, "status"),
        intValue(rs.getObject("code_walkthrough_duration")),
        reviewExceptionReason(rs),
        text(rs, "sonar_qube_result"),
        intValue(rs.getObject("bug_count")),
        text(rs, "annotation_rate_result"),
        text(rs, "bug_count_result"),
        annotationRate,
        intValue(rs.getObject("defect_count")),
        intValue(rs.getObject("added_line")),
        intValue(rs.getObject("deleted_line")),
        intValue(rs.getObject("code_specification_count")),
        intValue(rs.getObject("code_logic_specification_count")),
        intValue(rs.getObject("performance_specification_count")),
        intValue(rs.getObject("design_specification_count")),
        intValue(rs.getObject("other_specification_count")),
        intValue(rs.getObject("code_walkthrough_speed_loc")),
        decimal(rs.getObject("code_walkthrough_speed_kloc")),
        decimal(rs.getObject("code_walkthrough_defect_density")),
        decimal(rs.getObject("code_walkthrough_efficiency")),
        intValue(rs.getObject("commit_count")),
        intValue(rs.getObject("commit_rate")),
        text(rs, "function_name"),
        intValue(rs.getObject("ct_code_line_count")),
        legacySourceId);
  }

  private String reviewExceptionReason(ResultSet rs) throws SQLException {
    Integer duration = intValue(rs.getObject("code_walkthrough_duration"));
    Integer defectCount = intValue(rs.getObject("defect_count"));
    if (duration != null && duration > 0 && defectCount != null && defectCount >= 0) {
      return null;
    }
    return CodeReviewIllegalRuleRegistry.LEGACY_REVIEW_EXCEPTION_REASONS.getFirst();
  }

  private String syntheticLabels(String projectName, String moduleName) {
    List<String> labels = new ArrayList<>();
    if (StringUtils.hasText(projectName) && !CodeReviewIllegalRuleRegistry.isLegacyMissingProject(projectName)) {
      labels.add("项目：" + projectName.trim());
    }
    if (StringUtils.hasText(moduleName) && !CodeReviewIllegalRuleRegistry.isLegacyMissingModule(moduleName)) {
      labels.add("模块：" + moduleName.trim());
    }
    return String.join(",", labels);
  }

  @Transactional
  protected void createTempTables(boolean refreshCodeReviewRecords) {
    dropTempTables(refreshCodeReviewRecords);
    jdbcTemplate.execute(
        "create table "
            + RAW_TEMP_TABLE
            + " (like "
            + RAW_TARGET_TABLE
            + " including defaults including constraints)");
    if (refreshCodeReviewRecords) {
      jdbcTemplate.execute(
          "create table "
              + CODE_REVIEW_TEMP_TABLE
              + " (like "
              + CODE_REVIEW_TARGET_TABLE
              + " including defaults including constraints)");
    }
  }

  @Transactional
  protected void replaceSnapshots(
      List<String> selectedTableNames,
      boolean refreshCodeReviewRecords,
      ImportSummary summary) {
    for (String tableName : selectedTableNames) {
      jdbcTemplate.update("delete from " + RAW_TARGET_TABLE + " where table_name = ?", tableName);
    }
    jdbcTemplate.execute(
        "insert into "
            + RAW_TARGET_TABLE
            + " (table_name, row_key, raw_payload, synced_at) "
            + "select table_name, row_key, raw_payload, synced_at from "
            + RAW_TEMP_TABLE);
    for (TableSummary tableSummary : summary.tableSummaries()) {
      jdbcTemplate.update("""
          insert into legacy_mysql_imported_tables(
            table_name, record_count, last_synced_at, column_names, synced_at
          ) values (
            ?, ?, current_timestamp, ?, current_timestamp
          )
          on conflict (table_name) do update
             set record_count = excluded.record_count,
                 last_synced_at = excluded.last_synced_at,
                 column_names = excluded.column_names,
                 synced_at = current_timestamp
          """,
          tableSummary.tableName(),
          tableSummary.count(),
          jsonUtils.toJson(tableSummary.columnNames()));
    }
    if (refreshCodeReviewRecords) {
      jdbcTemplate.execute("truncate table " + CODE_REVIEW_TARGET_TABLE);
      jdbcTemplate.execute(
          "insert into "
              + CODE_REVIEW_TARGET_TABLE
              + " select * from "
              + CODE_REVIEW_TEMP_TABLE);
    }
    dropTempTables(refreshCodeReviewRecords);
  }

  protected void dropTempTables(boolean refreshCodeReviewRecords) {
    jdbcTemplate.execute("drop table if exists " + RAW_TEMP_TABLE);
    if (refreshCodeReviewRecords) {
      jdbcTemplate.execute("drop table if exists " + CODE_REVIEW_TEMP_TABLE);
    }
  }

  private void markRunning() {
    jdbcTemplate.update("""
        update code_review_match_mode_sync_state
           set status = 'RUNNING', message = '兼容模式正在同步老平台数据', started_at = current_timestamp,
               finished_at = null, updated_at = current_timestamp
         where id = 1
        """);
  }

  private void markSuccess(ImportSummary summary) {
    jdbcTemplate.update("""
        update code_review_match_mode_sync_state
           set status = 'SUCCESS', message = ?, record_count = ?, finished_at = current_timestamp,
               updated_at = current_timestamp
         where id = 1
        """, "兼容模式已同步老平台数据：" + summary.describe(), summary.totalCount());
  }

  private void markFailed(String message) {
    jdbcTemplate.update("""
        update code_review_match_mode_sync_state
           set status = 'FAILED', message = ?, finished_at = current_timestamp, updated_at = current_timestamp
         where id = 1
        """, message == null ? "兼容模式同步失败" : message);
  }

  private CodeReviewMatchModeSyncResponse currentState(boolean accepted, String fallbackMessage) {
    return jdbcTemplate.queryForObject("""
        select status, message, record_count, started_at, finished_at
          from code_review_match_mode_sync_state
         where id = 1
        """, (rs, rowNum) ->
        new CodeReviewMatchModeSyncResponse(
            accepted,
            rs.getString("status"),
            rs.getString("message") == null ? fallbackMessage : rs.getString("message"),
            rs.getLong("record_count"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime()));
  }

  private Map<String, Object> currentRow(ResultSet rs) throws SQLException {
    ResultSetMetaData metadata = rs.getMetaData();
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 1; index <= metadata.getColumnCount(); index++) {
      String label = metadata.getColumnLabel(index);
      row.put(label == null || label.isBlank() ? metadata.getColumnName(index) : label, jsonValue(rs.getObject(index)));
    }
    return row;
  }

  private Object value(Map<String, Object> row, String... candidates) {
    if (row == null || row.isEmpty() || candidates == null) {
      return null;
    }
    for (String candidate : candidates) {
      String normalizedCandidate = normalizeColumnKey(candidate);
      for (Map.Entry<String, Object> entry : row.entrySet()) {
        if (normalizeColumnKey(entry.getKey()).equals(normalizedCandidate)) {
          return entry.getValue();
        }
      }
    }
    return null;
  }

  private String textValue(Map<String, Object> row, String... candidates) {
    Object value = value(row, candidates);
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private String legacyId(Map<String, Object> row, int rowIndex) {
    String id = textValue(row, "id", "_id", "legacy_id", "legacyId");
    if (id != null) {
      return rowIndex + "-" + id;
    }
    return "row-" + rowIndex + "-" + Integer.toHexString(row.toString().hashCode());
  }

  private Object jsonValue(Object value) {
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime().toString();
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate().toString();
    }
    if (value instanceof Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).toString();
    }
    return value;
  }

  private String normalizeColumnKey(String key) {
    return key == null ? "" : key.replace("_", "").toLowerCase(java.util.Locale.ROOT);
  }

  private String text(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private Integer parseIid(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String digits = value.replaceAll("[^0-9]", "");
    if (!StringUtils.hasText(digits)) {
      return null;
    }
    return Integer.parseInt(digits);
  }

  private LocalDateTime localDateTime(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime;
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate().atStartOfDay();
    }
    if (value instanceof Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
    return parseDateTime(String.valueOf(value));
  }

  private LocalDate localDate(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate();
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime().toLocalDate();
    }
    if (value instanceof LocalDate date) {
      return date;
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime.toLocalDate();
    }
    if (value instanceof Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).toLocalDate();
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    LocalDateTime parsed = parseDateTime(text);
    return parsed == null ? null : parsed.toLocalDate();
  }

  private LocalDateTime parseDateTime(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim().replace('T', ' ');
    try {
      return LocalDateTime.parse(normalized.length() > 19 ? normalized.substring(0, 19) : normalized, DATE_TIME);
    } catch (DateTimeParseException ignored) {
      try {
        return java.time.LocalDate.parse(normalized.substring(0, Math.min(10, normalized.length()))).atStartOfDay();
      } catch (RuntimeException ignoredAgain) {
        return null;
      }
    }
  }

  private Integer intValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : Integer.parseInt(text);
  }

  private Long longValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : Long.parseLong(text);
  }

  private Double doubleValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : Double.parseDouble(text);
  }

  private BigDecimal decimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : new BigDecimal(text);
  }

  private static final class ImportSummary {
    private final Map<String, TableSummary> tables = new LinkedHashMap<>();
    private int codeReviewRecordCount;

    void add(String tableName, int count, List<String> columnNames) {
      tables.put(tableName, new TableSummary(tableName, count, columnNames));
    }

    void setCodeReviewRecordCount(int count) {
      codeReviewRecordCount = count;
    }

    List<TableSummary> tableSummaries() {
      return List.copyOf(tables.values());
    }

    long totalCount() {
      long total = 0L;
      for (TableSummary table : tables.values()) {
        total += table.count();
      }
      return total;
    }

    String describe() {
      if (tables.isEmpty()) {
        return "未选择数据表";
      }
      List<String> parts = new ArrayList<>();
      for (TableSummary table : tables.values()) {
        parts.add(table.tableName() + " " + table.count() + " 条");
      }
      if (codeReviewRecordCount > 0) {
        parts.add("代码走查兼容记录 " + codeReviewRecordCount + " 条");
      }
      return String.join("，", parts);
    }
  }

  private record TableLoadResult(int count, List<String> columnNames) {
  }

  private record TableSummary(String tableName, int count, List<String> columnNames) {
  }

  private record LegacyRow(
      String sourceInstance,
      Long projectId,
      String projectName,
      String repositoryName,
      Long mergeRequestId,
      Integer mergeRequestIid,
      String title,
      String mergeRequestState,
      String targetBranch,
      String authorName,
      String mergeUserName,
      String ownerName,
      String reviewerNames,
      String assigneeNames,
      String moduleName,
      String labelNames,
      String searchText,
      String searchCompact,
      String searchSpell,
      String searchInitials,
      LocalDateTime mergedAtSource,
      LocalDateTime codeWalkthroughDate,
      String reviewStatus,
      Integer reviewDurationMinutes,
      String reviewExceptionReason,
      String scanStatus,
      Integer scanBugCount,
      String annotationRateResult,
      String bugCountResult,
      Double commentRate,
      Integer defectCount,
      Integer addedLines,
      Integer deletedLines,
      Integer codeSpecificationCount,
      Integer codeLogicSpecificationCount,
      Integer performanceSpecificationCount,
      Integer designSpecificationCount,
      Integer otherSpecificationCount,
      Integer reviewSpeedLocPerHour,
      BigDecimal reviewSpeedKlocPerHour,
      BigDecimal reviewDefectDensityPerKloc,
      BigDecimal reviewEfficiencyPerHour,
      Integer commitCount,
      Integer commitRate,
      String functionName,
      Integer clangAddedLineCount,
      String legacySourceId) {
    Object[] toArgs() {
      return new Object[] {
          sourceInstance, projectId, projectName, repositoryName, mergeRequestId, mergeRequestIid, title,
          mergeRequestState, targetBranch, authorName, mergeUserName, ownerName, reviewerNames, assigneeNames,
          moduleName, labelNames, searchText, searchCompact, searchSpell, searchInitials, mergedAtSource,
          codeWalkthroughDate, reviewStatus, reviewDurationMinutes, reviewExceptionReason, scanStatus,
          scanBugCount, annotationRateResult, bugCountResult, commentRate, defectCount, addedLines,
          deletedLines, codeSpecificationCount, codeLogicSpecificationCount, performanceSpecificationCount,
          designSpecificationCount, otherSpecificationCount, reviewSpeedLocPerHour, reviewSpeedKlocPerHour,
          reviewDefectDensityPerKloc, reviewEfficiencyPerHour, commitCount, commitRate, functionName,
          clangAddedLineCount, legacySourceId
      };
    }
  }
}
