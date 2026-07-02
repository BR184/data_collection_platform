package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CodeReviewMatchModeConnectionTestResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsSaveRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CodeReviewMatchModeConfigService {
  private static final String SETTINGS_SQL = """
      select s.enabled,
             s.sync_enabled,
             s.mysql_host,
             s.mysql_port,
             s.mysql_database,
             s.mysql_username,
             s.mysql_password,
             s.mysql_table_name,
             s.mysql_fetch_size,
             s.mongo_uri,
             s.mongo_database,
             s.mongo_annotation_collection,
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
        settings.mysqlFetchSize(),
        settings.mongoUri(),
        settings.mongoDatabase(),
        settings.mongoAnnotationCollection(),
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
               mysql_fetch_size = ?,
               mongo_uri = ?,
               mongo_database = ?,
               mongo_annotation_collection = ?,
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
        normalized.mysqlFetchSize(),
        normalized.mongoUri(),
        normalized.mongoDatabase(),
        normalized.mongoAnnotationCollection());
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
            settings.mysqlFetchSize(),
            settings.mongoUri(),
            settings.mongoDatabase(),
            settings.mongoAnnotationCollection(),
            settings.syncEnabled());
    if (!StringUtils.hasText(config.mysqlJdbcUrl()) || !StringUtils.hasText(config.mysqlUsername())) {
      return new CodeReviewMatchModeConnectionTestResponse(false, "老平台 MySQL 连接配置不完整", 0);
    }
    try (Connection connection =
            DriverManager.getConnection(config.mysqlJdbcUrl(), config.mysqlUsername(), config.mysqlPassword());
        PreparedStatement statement =
            connection.prepareStatement("select count(*) from " + safeTableName(config.mysqlTableName()));
        ResultSet rs = statement.executeQuery()) {
      long count = rs.next() ? rs.getLong(1) : 0;
      return new CodeReviewMatchModeConnectionTestResponse(true, "老平台 MySQL 连接成功", count);
    } catch (SQLException | RuntimeException error) {
      return new CodeReviewMatchModeConnectionTestResponse(false, rootMessage(error), 0);
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
              rs.getInt("mysql_fetch_size"),
              text(rs.getString("mongo_uri")),
              text(rs.getString("mongo_database")),
              text(rs.getString("mongo_annotation_collection")),
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
    safeTableName(tableName);
    int fetchSize = request.mysqlFetchSize() == null ? current.mysqlFetchSize() : request.mysqlFetchSize();
    if (fetchSize < 1 || fetchSize > 100000) {
      throw new BizException("兼容模式抓取批量大小必须在 1 到 100000 之间");
    }
    String mongoUri = resolveSecret(request.mongoUri(), current.mongoUri());
    String mongoDatabase = optionalText(request.mongoDatabase(), current.mongoDatabase());
    String mongoCollection =
        defaultText(request.mongoAnnotationCollection(), current.mongoAnnotationCollection(), "annotationRateInfo");
    return new CodeReviewMatchModeDbSettings(
        enabled,
        syncEnabled,
        host,
        port,
        database,
        username,
        password,
        tableName,
        fetchSize,
        mongoUri,
        mongoDatabase,
        mongoCollection,
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
        settings.mysqlFetchSize(),
        settings.mongoDatabase(),
        settings.mongoAnnotationCollection(),
        StringUtils.hasText(settings.mongoUri()),
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

  private String safeTableName(String tableName) {
    String normalized = tableName == null || tableName.isBlank() ? "spider_crowncad_data" : tableName.trim();
    if (!normalized.matches("[A-Za-z0-9_]+")) {
      throw new BizException("兼容模式 MySQL 表名只能包含字母、数字和下划线");
    }
    return normalized;
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
      int mysqlFetchSize,
      String mongoUri,
      String mongoDatabase,
      String mongoAnnotationCollection,
      String syncStatus,
      String syncMessage,
      long syncRecordCount,
      LocalDateTime syncStartedAt,
      LocalDateTime syncFinishedAt,
      LocalDateTime updatedAt) {
  }
}
