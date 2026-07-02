package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CodeReviewMatchModeConnectionTestResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsSaveRequest;
import com.data.collection.platform.entity.CodeReviewMatchModeTableOptionResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CodeReviewMatchModeConfigService {
  private static final List<String> DEFAULT_SELECTED_TABLES = List.of("spider_crowncad_data");

  private static final String SETTINGS_SQL = """
      select s.enabled,
             s.sync_enabled,
             s.mysql_host,
             s.mysql_port,
             s.mysql_database,
             s.mysql_username,
             s.mysql_password,
             s.mysql_table_name,
             s.selected_table_names,
             s.mysql_fetch_size,
             s.updated_at,
             st.status as sync_status,
             st.message as sync_message,
             st.record_count as sync_record_count,
             st.started_at as sync_started_at,
             st.finished_at as sync_finished_at
        from code_review_match_mode_db_settings s
        left join code_review_match_mode_sync_state st on st.id = 1
       where s.id = 1
      """;

  private final JdbcTemplate jdbcTemplate;

  public CodeReviewMatchModeConfigService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  //兼容模式-MatchMode
  public CodeReviewMatchModeConfig loadConfig() {
    CodeReviewMatchModeDbSettings settings = loadSettings();
    return new CodeReviewMatchModeConfig(
        buildMysqlJdbcUrl(settings),
        settings.mysqlUsername(),
        settings.mysqlPassword(),
        settings.mysqlTableName(),
        settings.selectedTableNames(),
        settings.mysqlFetchSize(),
        settings.syncEnabled());
  }

  //兼容模式-MatchMode
  public boolean isMatchModeEnabled() {
    return loadSettings().enabled();
  }

  //兼容模式-MatchMode
  public CodeReviewMatchModeDbSettingsResponse getResponse() {
    return toResponse(loadSettings());
  }

  //兼容模式-MatchMode
  public CodeReviewMatchModeDbSettingsResponse save(CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeDbSettings current = loadSettings();
    CodeReviewMatchModeDbSettings normalized = normalize(request, current);
    jdbcTemplate.update("""
        update code_review_match_mode_db_settings
           set enabled = ?,
               sync_enabled = ?,
               mysql_host = ?,
               mysql_port = ?,
               mysql_database = ?,
               mysql_username = ?,
               mysql_password = ?,
               mysql_table_name = ?,
               selected_table_names = ?,
               mysql_fetch_size = ?,
               updated_at = current_timestamp
         where id = 1
        """,
        normalized.enabled(),
        normalized.syncEnabled(),
        normalized.mysqlHost(),
        normalized.mysqlPort(),
        normalized.mysqlDatabase(),
        normalized.mysqlUsername(),
        normalized.mysqlPassword(),
        normalized.mysqlTableName(),
        storeTableNames(normalized.selectedTableNames()),
        normalized.mysqlFetchSize());
    return getResponse();
  }

  //兼容模式-MatchMode
  public CodeReviewMatchModeConnectionTestResponse testConnection(
      CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeDbSettings settings = normalize(request, loadSettings());
    CodeReviewMatchModeConfig config =
        new CodeReviewMatchModeConfig(
            buildMysqlJdbcUrl(settings),
            settings.mysqlUsername(),
            settings.mysqlPassword(),
            settings.mysqlTableName(),
            settings.selectedTableNames(),
            settings.mysqlFetchSize(),
            settings.syncEnabled());
    if (!StringUtils.hasText(config.mysqlJdbcUrl()) || !StringUtils.hasText(config.mysqlUsername())) {
      return new CodeReviewMatchModeConnectionTestResponse(false, "老平台 MySQL 连接配置不完整", 0);
    }
    try (Connection connection =
        DriverManager.getConnection(config.mysqlJdbcUrl(), config.mysqlUsername(), config.mysqlPassword())) {
      connection.setReadOnly(true);
      long total = 0L;
      List<String> countSummaries = new ArrayList<>();
      for (String tableName : config.selectedTableNames()) {
        long count = countRows(connection, tableName);
        total += count;
        countSummaries.add(tableName + " " + count + " 条");
      }
      String message = "老平台 MySQL 连接成功";
      if (!countSummaries.isEmpty()) {
        message += "：" + String.join("，", countSummaries);
      }
      return new CodeReviewMatchModeConnectionTestResponse(true, message, total);
    } catch (SQLException | RuntimeException error) {
      return new CodeReviewMatchModeConnectionTestResponse(false, rootMessage(error), 0);
    }
  }

  //兼容模式-MatchMode
  public List<CodeReviewMatchModeTableOptionResponse> discoverTableOptions(
      CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeDbSettings settings = normalize(request, loadSettings());
    String jdbcUrl = buildMysqlJdbcUrl(settings);
    if (!StringUtils.hasText(jdbcUrl) || !StringUtils.hasText(settings.mysqlUsername())) {
      throw new BizException("老平台 MySQL 连接配置不完整");
    }
    try (Connection connection =
        DriverManager.getConnection(jdbcUrl, settings.mysqlUsername(), settings.mysqlPassword())) {
      connection.setReadOnly(true);
      Set<String> selected = new LinkedHashSet<>(settings.selectedTableNames());
      return discoverTables(connection).stream()
          .map(tableName -> new CodeReviewMatchModeTableOptionResponse(tableName, tableName, selected.contains(tableName)))
          .toList();
    } catch (SQLException | RuntimeException error) {
      throw new BizException(rootMessage(error));
    }
  }

  private CodeReviewMatchModeDbSettings loadSettings() {
    try {
      return jdbcTemplate.queryForObject(SETTINGS_SQL, (rs, rowNum) ->
          new CodeReviewMatchModeDbSettings(
              rs.getBoolean("enabled"),
              rs.getBoolean("sync_enabled"),
              text(rs.getString("mysql_host")),
              rs.getInt("mysql_port"),
              text(rs.getString("mysql_database")),
              text(rs.getString("mysql_username")),
              rs.getString("mysql_password") == null ? "" : rs.getString("mysql_password"),
              text(rs.getString("mysql_table_name")),
              parseStoredTableNames(rs.getString("selected_table_names")),
              rs.getInt("mysql_fetch_size"),
              text(rs.getString("sync_status")),
              text(rs.getString("sync_message")),
              rs.getLong("sync_record_count"),
              rs.getTimestamp("sync_started_at") == null ? null : rs.getTimestamp("sync_started_at").toLocalDateTime(),
              rs.getTimestamp("sync_finished_at") == null ? null : rs.getTimestamp("sync_finished_at").toLocalDateTime(),
              rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime()));
    } catch (EmptyResultDataAccessException error) {
      throw new BizException("兼容模式数据库设置不存在，请先执行数据库迁移");
    }
  }

  private CodeReviewMatchModeDbSettings normalize(
      CodeReviewMatchModeDbSettingsSaveRequest request,
      CodeReviewMatchModeDbSettings current) {
    if (request == null) {
      return current;
    }
    boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
    boolean syncEnabled = request.syncEnabled() == null ? current.syncEnabled() : request.syncEnabled();
    String host = defaultText(request.mysqlHost(), current.mysqlHost(), "172.22.10.72");
    int port = request.mysqlPort() == null ? current.mysqlPort() : request.mysqlPort();
    if (port < 1 || port > 65535) {
      throw new BizException("老平台 MySQL 端口必须在 1 到 65535 之间");
    }
    String database = optionalText(request.mysqlDatabase(), current.mysqlDatabase());
    String username = optionalText(request.mysqlUsername(), current.mysqlUsername());
    String password = resolveSecret(request.mysqlPassword(), current.mysqlPassword());
    String tableName = defaultText(request.mysqlTableName(), current.mysqlTableName(), "spider_crowncad_data");
    quoteMysqlIdentifier(tableName);
    List<String> selectedTableNames =
        normalizeSelectedTableNames(request.selectedTableNames(), current.selectedTableNames());
    int fetchSize = request.mysqlFetchSize() == null ? current.mysqlFetchSize() : request.mysqlFetchSize();
    if (fetchSize < 1 || fetchSize > 100000) {
      throw new BizException("兼容模式抓取批量大小必须在 1 到 100000 之间");
    }
    return new CodeReviewMatchModeDbSettings(
        enabled,
        syncEnabled,
        host,
        port,
        database,
        username,
        password,
        tableName,
        selectedTableNames,
        fetchSize,
        current.syncStatus(),
        current.syncMessage(),
        current.syncRecordCount(),
        current.syncStartedAt(),
        current.syncFinishedAt(),
        current.updatedAt());
  }

  private CodeReviewMatchModeDbSettingsResponse toResponse(CodeReviewMatchModeDbSettings settings) {
    return new CodeReviewMatchModeDbSettingsResponse(
        settings.enabled(),
        settings.syncEnabled(),
        settings.mysqlHost(),
        settings.mysqlPort(),
        settings.mysqlDatabase(),
        settings.mysqlUsername(),
        StringUtils.hasText(settings.mysqlPassword()),
        settings.mysqlTableName(),
        settings.selectedTableNames(),
        settings.mysqlFetchSize(),
        settings.syncStatus() == null ? "IDLE" : settings.syncStatus(),
        settings.syncMessage(),
        settings.syncRecordCount(),
        settings.syncStartedAt(),
        settings.syncFinishedAt(),
        settings.updatedAt());
  }

  private String buildMysqlJdbcUrl(CodeReviewMatchModeDbSettings settings) {
    if (!StringUtils.hasText(settings.mysqlHost()) || !StringUtils.hasText(settings.mysqlDatabase())) {
      return null;
    }
    return "jdbc:mysql://"
        + settings.mysqlHost().trim()
        + ":"
        + settings.mysqlPort()
        + "/"
        + settings.mysqlDatabase().trim()
        + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
  }

  private long countRows(Connection connection, String tableName) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("select count(*) from " + quoteMysqlIdentifier(tableName));
        ResultSet rs = statement.executeQuery()) {
      return rs.next() ? rs.getLong(1) : 0L;
    }
  }

  private List<String> discoverTables(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select table_name
          from information_schema.tables
         where table_schema = database()
           and table_type = 'BASE TABLE'
         order by table_name
        """);
        ResultSet rs = statement.executeQuery()) {
      List<String> tableNames = new ArrayList<>();
      while (rs.next()) {
        tableNames.add(rs.getString("table_name"));
      }
      return tableNames;
    }
  }

  private List<String> parseStoredTableNames(String storedValue) {
    if (storedValue == null || storedValue.isBlank()) {
      return DEFAULT_SELECTED_TABLES;
    }
    String[] parts = storedValue.split(",");
    List<String> values = new ArrayList<>();
    for (String part : parts) {
      if (part != null && !part.isBlank()) {
        values.add(part.trim());
      }
    }
    return normalizeSelectedTableNames(values, DEFAULT_SELECTED_TABLES);
  }

  private List<String> normalizeSelectedTableNames(
      List<String> requestedTableNames,
      List<String> currentTableNames) {
    if (requestedTableNames == null) {
      return currentTableNames == null || currentTableNames.isEmpty()
          ? DEFAULT_SELECTED_TABLES
          : List.copyOf(currentTableNames);
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String tableName : requestedTableNames) {
      String normalizedTableName = TextQuerySupport.trimToNull(tableName);
      if (normalizedTableName != null) {
        quoteMysqlIdentifier(normalizedTableName);
        normalized.add(normalizedTableName);
      }
    }
    if (normalized.isEmpty()) {
      throw new BizException("至少选择一张老平台 MySQL 表");
    }
    return List.copyOf(normalized);
  }

  private String storeTableNames(List<String> tableNames) {
    return String.join(",", normalizeSelectedTableNames(tableNames, DEFAULT_SELECTED_TABLES));
  }

  private String quoteMysqlIdentifier(String tableName) {
    String normalized = TextQuerySupport.trimToNull(tableName);
    if (normalized == null) {
      throw new BizException("兼容模式 MySQL 表名不能为空");
    }
    if (normalized.length() > 255 || normalized.indexOf('\0') >= 0) {
      throw new BizException("兼容模式 MySQL 表名不合法");
    }
    return "`" + normalized.replace("`", "``") + "`";
  }

  private String defaultText(String nextValue, String currentValue, String fallback) {
    String normalized = TextQuerySupport.trimToNull(nextValue);
    if (normalized != null) {
      return normalized;
    }
    normalized = TextQuerySupport.trimToNull(currentValue);
    return normalized == null ? fallback : normalized;
  }

  private String optionalText(String nextValue, String currentValue) {
    String normalized = TextQuerySupport.trimToNull(nextValue);
    if (normalized != null) {
      return normalized;
    }
    normalized = TextQuerySupport.trimToNull(currentValue);
    return normalized == null ? "" : normalized;
  }

  private String resolveSecret(String nextValue, String currentValue) {
    String normalized = TextQuerySupport.trimToNull(nextValue);
    if (normalized != null) {
      return normalized;
    }
    return currentValue == null ? "" : currentValue;
  }

  private String text(String value) {
    return TextQuerySupport.trimToNull(value);
  }

  private String rootMessage(Throwable error) {
    Throwable cursor = error;
    while (cursor.getCause() != null) {
      cursor = cursor.getCause();
    }
    String message = cursor.getMessage();
    return message == null || message.isBlank()
        ? "老平台 MySQL 连接失败"
        : message.trim().replace('\n', ' ').replace('\r', ' ');
  }

  private record CodeReviewMatchModeDbSettings(
      boolean enabled,
      boolean syncEnabled,
      String mysqlHost,
      int mysqlPort,
      String mysqlDatabase,
      String mysqlUsername,
      String mysqlPassword,
      String mysqlTableName,
      List<String> selectedTableNames,
      int mysqlFetchSize,
      String syncStatus,
      String syncMessage,
      long syncRecordCount,
      LocalDateTime syncStartedAt,
      LocalDateTime syncFinishedAt,
      LocalDateTime updatedAt) {
  }
}
