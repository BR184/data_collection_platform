package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.LegacyMysqlReadConfiguration;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 将老平台人工代码走查结构化数据发布到 BI 独占兼容快照。 */
public final class BiCodeReviewCompatibilitySyncService {
  private static final Logger log =
      LoggerFactory.getLogger(BiCodeReviewCompatibilitySyncService.class);
  private static final String SOURCE_TABLE = "spider_crowncad_data";
  private static final int INSERT_BATCH_SIZE = 500;
  private static final DateTimeFormatter LEGACY_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String SOURCE_QUERY = """
      select id, name, code_walkthrough_date, status, module_name, project_name,
             issuable_reference, author, assignee, merged_time, merged_local_date_time,
             code_walkthrough_duration, added_line, deleted_line,
             code_specification_count, code_logic_specification_count,
             performance_specification_count, design_specification_count,
             other_specification_count, defect_count, target_branch,
             sonar_qube_result, annotation_rate, bug_count
        from spider_crowncad_data
      """;
  private static final String LOADING_INSERT = """
      insert into bi_code_review_compatibility_records_loading(
        sync_run_id, source_instance, legacy_source_id, project_name, repository_name,
        merge_request_iid, merge_request_state, target_branch, author_name, reviewer_names,
        module_name, merged_at_source, code_walkthrough_date, review_duration_minutes,
        added_lines, deleted_lines, defect_count, code_specification_count,
        code_logic_specification_count, performance_specification_count,
        design_specification_count, other_specification_count, scan_status,
        scan_bug_count, comment_rate, comment_rate_source, loaded_at
      ) values (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        current_timestamp
      )
      """;

  private final CodeReviewMatchModeConfigService configService;
  private final CodeReviewMatchModeSwitchService switchService;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public BiCodeReviewCompatibilitySyncService(
      CodeReviewMatchModeConfigService configService,
      CodeReviewMatchModeSwitchService switchService,
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate) {
    this.configService = configService;
    this.switchService = switchService;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  /**
   * 同步并原子发布 BI 人工走查兼容快照。
   *
   * <p>平台兼容模式关闭、自动同步关闭或已有本实例任务运行时不产生外部连接。任一来源失败时
   * 只清理本运行暂存数据并保留上一版 BI 快照。
   */
  public SyncResult syncNow() {
    if (!switchService.isCodeReviewCompatibilityReadEnabled()) {
      return new SyncResult(false, "平台代码走查兼容模式未开启", 0L, currentVersion());
    }
    LegacyMysqlReadConfiguration config = configService.loadLegacyMysqlReadConfiguration();
    if (!config.syncEnabled()) {
      return new SyncResult(false, "平台兼容数据自动同步未开启", 0L, currentVersion());
    }
    if (config.sources().isEmpty() || !hasText(config.username())) {
      markFailed("BI 人工走查兼容同步缺少老平台 MySQL 连接配置");
      return new SyncResult(false, "老平台 MySQL 连接配置缺失", 0L, currentVersion());
    }
    if (!running.compareAndSet(false, true)) {
      return new SyncResult(false, "BI 人工走查兼容数据正在同步", 0L, currentVersion());
    }
    UUID runId = UUID.randomUUID();
    markRunning(runId);
    try {
      long loaded = loadSources(runId, config);
      long version = publish(runId);
      return new SyncResult(true, "BI 人工走查兼容数据已发布", loaded, version);
    } catch (Exception error) {
      cleanupRun(runId);
      markFailed(rootMessage(error));
      log.warn("BI code review compatibility sync failed", error);
      return new SyncResult(false, rootMessage(error), 0L, currentVersion());
    } finally {
      running.set(false);
    }
  }

  private long loadSources(UUID runId, LegacyMysqlReadConfiguration config) throws SQLException {
    long loaded = 0L;
    for (LegacyMysqlReadConfiguration.Source source : config.sources()) {
      try (Connection connection =
              DriverManager.getConnection(source.jdbcUrl(), config.username(), config.password());
          PreparedStatement statement = connection.prepareStatement(SOURCE_QUERY)) {
        connection.setReadOnly(true);
        statement.setFetchSize(Math.max(1, config.fetchSize()));
        try (ResultSet resultSet = statement.executeQuery()) {
          loaded = Math.addExact(loaded, insertRows(runId, source.sourceInstance(), resultSet));
        }
      }
    }
    return loaded;
  }

  private int insertRows(UUID runId, String configuredSource, ResultSet resultSet)
      throws SQLException {
    List<Object[]> batch = new ArrayList<>();
    int inserted = 0;
    while (resultSet.next()) {
      Long legacyId = number(resultSet.getObject("id"));
      if (legacyId == null || legacyId <= 0L) {
        throw new SQLException(SOURCE_TABLE + ".id 必须为正数");
      }
      String repositoryName = text(resultSet.getObject("name"));
      batch.add(
          new Object[] {
            runId,
            sourceInstance(configuredSource, repositoryName),
            legacyId,
            text(resultSet.getObject("project_name")),
            repositoryName,
            reference(resultSet.getObject("issuable_reference")),
            text(resultSet.getObject("status")),
            text(resultSet.getObject("target_branch")),
            text(resultSet.getObject("author")),
            text(resultSet.getObject("assignee")),
            text(resultSet.getObject("module_name")),
            dateTime(resultSet.getObject("merged_local_date_time"), resultSet.getObject("merged_time")),
            dateTime(resultSet.getObject("code_walkthrough_date"), null),
            decimal(resultSet.getObject("code_walkthrough_duration")),
            number(resultSet.getObject("added_line")),
            number(resultSet.getObject("deleted_line")),
            number(resultSet.getObject("defect_count")),
            number(resultSet.getObject("code_specification_count")),
            number(resultSet.getObject("code_logic_specification_count")),
            number(resultSet.getObject("performance_specification_count")),
            number(resultSet.getObject("design_specification_count")),
            number(resultSet.getObject("other_specification_count")),
            text(resultSet.getObject("sonar_qube_result")),
            number(resultSet.getObject("bug_count")),
            decimal(resultSet.getObject("annotation_rate")),
            SOURCE_TABLE + ".annotation_rate"
          });
      if (batch.size() >= INSERT_BATCH_SIZE) {
        jdbcTemplate.batchUpdate(LOADING_INSERT, batch);
        inserted += batch.size();
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      jdbcTemplate.batchUpdate(LOADING_INSERT, batch);
      inserted += batch.size();
    }
    return inserted;
  }

  private long publish(UUID runId) {
    Long version =
        transactionTemplate.execute(
            ignored -> {
              jdbcTemplate.queryForObject(
                  "select id from bi_code_review_compatibility_sync_state where id = 1 for update",
                  Long.class);
              jdbcTemplate.update(
                  """
                  delete from bi_code_review_compatibility_records target
                   where not exists (
                     select 1
                       from bi_code_review_compatibility_records_loading loading
                      where loading.sync_run_id = ?
                        and loading.source_instance = target.source_instance
                        and loading.legacy_source_id = target.legacy_source_id
                   )
                  """,
                  runId);
              jdbcTemplate.update(
                  """
                  insert into bi_code_review_compatibility_records(
                    source_instance, legacy_source_id, project_name, repository_name,
                    merge_request_iid, merge_request_state, target_branch, author_name,
                    reviewer_names, module_name, merged_at_source, code_walkthrough_date,
                    review_duration_minutes, added_lines, deleted_lines, defect_count,
                    code_specification_count, code_logic_specification_count,
                    performance_specification_count, design_specification_count,
                    other_specification_count, scan_status, scan_bug_count, comment_rate,
                    comment_rate_source, synced_at
                  )
                  select source_instance, legacy_source_id, project_name, repository_name,
                         merge_request_iid, merge_request_state, target_branch, author_name,
                         reviewer_names, module_name, merged_at_source, code_walkthrough_date,
                         review_duration_minutes, added_lines, deleted_lines, defect_count,
                         code_specification_count, code_logic_specification_count,
                         performance_specification_count, design_specification_count,
                         other_specification_count, scan_status, scan_bug_count, comment_rate,
                         comment_rate_source, current_timestamp
                    from bi_code_review_compatibility_records_loading
                   where sync_run_id = ?
                  on conflict (source_instance, legacy_source_id) do update set
                    project_name = excluded.project_name,
                    repository_name = excluded.repository_name,
                    merge_request_iid = excluded.merge_request_iid,
                    merge_request_state = excluded.merge_request_state,
                    target_branch = excluded.target_branch,
                    author_name = excluded.author_name,
                    reviewer_names = excluded.reviewer_names,
                    module_name = excluded.module_name,
                    merged_at_source = excluded.merged_at_source,
                    code_walkthrough_date = excluded.code_walkthrough_date,
                    review_duration_minutes = excluded.review_duration_minutes,
                    added_lines = excluded.added_lines,
                    deleted_lines = excluded.deleted_lines,
                    defect_count = excluded.defect_count,
                    code_specification_count = excluded.code_specification_count,
                    code_logic_specification_count = excluded.code_logic_specification_count,
                    performance_specification_count = excluded.performance_specification_count,
                    design_specification_count = excluded.design_specification_count,
                    other_specification_count = excluded.other_specification_count,
                    scan_status = excluded.scan_status,
                    scan_bug_count = excluded.scan_bug_count,
                    comment_rate = excluded.comment_rate,
                    comment_rate_source = excluded.comment_rate_source,
                    synced_at = current_timestamp
                  """,
                  runId);
              Long recordCount =
                  jdbcTemplate.queryForObject(
                      "select count(*) from bi_code_review_compatibility_records", Long.class);
              Long nextVersion =
                  jdbcTemplate.queryForObject(
                      """
                      update bi_code_review_compatibility_sync_state
                         set status = 'SUCCESS',
                             message = 'BI 人工走查兼容快照发布成功',
                             record_count = ?,
                             published_version = published_version + 1,
                             active_run_id = null,
                             finished_at = current_timestamp,
                             updated_at = current_timestamp
                       where id = 1
                      returning published_version
                      """,
                      Long.class,
                      recordCount == null ? 0L : recordCount);
              jdbcTemplate.update(
                  "delete from bi_code_review_compatibility_records_loading where sync_run_id = ?",
                  runId);
              return nextVersion;
            });
    if (version == null) {
      throw new IllegalStateException("BI 人工走查兼容快照未返回发布版本");
    }
    return version;
  }

  private void markRunning(UUID runId) {
    jdbcTemplate.update(
        """
        update bi_code_review_compatibility_sync_state
           set status = 'RUNNING', message = 'BI 人工走查兼容数据正在同步',
               active_run_id = ?, started_at = current_timestamp,
               finished_at = null, updated_at = current_timestamp
         where id = 1
        """,
        runId);
  }

  private void markFailed(String message) {
    jdbcTemplate.update(
        """
        update bi_code_review_compatibility_sync_state
           set status = 'FAILED', message = ?, active_run_id = null,
               finished_at = current_timestamp, updated_at = current_timestamp
         where id = 1
        """,
        hasText(message) ? message : "BI 人工走查兼容数据同步失败");
  }

  private void cleanupRun(UUID runId) {
    try {
      jdbcTemplate.update(
          "delete from bi_code_review_compatibility_records_loading where sync_run_id = ?",
          runId);
    } catch (RuntimeException cleanupError) {
      log.warn("Failed to clean BI code review compatibility loading rows, runId={}", runId,
          cleanupError);
    }
  }

  private long currentVersion() {
    Long version =
        jdbcTemplate.queryForObject(
            "select published_version from bi_code_review_compatibility_sync_state where id = 1",
            Long.class);
    return version == null ? 0L : version;
  }

  private String sourceInstance(String configuredSource, String repositoryName) {
    if ("DGM".equalsIgnoreCase(text(repositoryName))) {
      return "dgm";
    }
    return hasText(configuredSource) ? configuredSource.trim().toLowerCase(Locale.ROOT) : "cc";
  }

  private Long reference(Object value) {
    Long direct = number(value);
    if (direct != null) {
      return direct;
    }
    String text = text(value);
    if (!hasText(text)) {
      return null;
    }
    String digits = text.replaceAll("^.*?([0-9]+)$", "$1");
    return digits.matches("[0-9]+") ? Long.valueOf(digits) : null;
  }

  private Long number(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (!hasText(text(value))) {
      return null;
    }
    try {
      return Long.valueOf(text(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private BigDecimal decimal(Object value) {
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    if (!hasText(text(value))) {
      return null;
    }
    try {
      return new BigDecimal(text(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private LocalDateTime dateTime(Object preferred, Object fallback) {
    LocalDateTime parsed = parseLegacyDateTime(preferred);
    return parsed == null ? parseLegacyDateTime(fallback) : parsed;
  }

  static LocalDateTime parseLegacyDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof LocalDate localDate) {
      return localDate.atStartOfDay();
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate().atStartOfDay();
    }
    if (value instanceof java.util.Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
    }
    String candidate = value == null ? null : String.valueOf(value).trim();
    if (!hasText(candidate)) {
      return null;
    }
    try {
      return LocalDateTime.parse(candidate, LEGACY_DATE_TIME);
    } catch (DateTimeParseException dateTimeError) {
      try {
        return LocalDate.parse(candidate).atStartOfDay();
      } catch (DateTimeParseException dateError) {
        return null;
      }
    }
  }

  private String text(Object value) {
    return value == null ? null : String.valueOf(value).trim();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private String rootMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return hasText(current.getMessage())
        ? current.getMessage()
        : current.getClass().getSimpleName();
  }

  /** 单次同步调用的结果，不复用平台现有兼容同步状态 DTO。 */
  public record SyncResult(boolean published, String message, long recordCount, long version) {}
}
