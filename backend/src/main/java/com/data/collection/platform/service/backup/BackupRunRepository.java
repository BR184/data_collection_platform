package com.data.collection.platform.service.backup;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** backup_runs 运行历史的读写：运行登记、阶段推进、终态落库、历史分页与判期依据查询。 */
@Repository
public class BackupRunRepository {
  private static final String SELECT_COLUMNS =
      """
      id, trigger_type, status, storage_mode, stage, target_path, file_name, file_bytes,
      sha256, pg_server_version, flyway_version, started_at, finished_at, duration_ms, error_message
      """;
  private static final RowMapper<BackupRun> MAPPER =
      (rs, rowNum) ->
          new BackupRun(
              rs.getLong("id"),
              rs.getString("trigger_type"),
              rs.getString("status"),
              rs.getString("storage_mode"),
              rs.getString("stage"),
              rs.getString("target_path"),
              rs.getString("file_name"),
              rs.getObject("file_bytes") == null ? null : rs.getLong("file_bytes"),
              rs.getString("sha256"),
              rs.getString("pg_server_version"),
              rs.getString("flyway_version"),
              rs.getTimestamp("started_at").toInstant(),
              rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
              rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
              rs.getString("error_message"));

  private final JdbcTemplate jdbcTemplate;

  public BackupRunRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 预分配运行 ID（nextval），使抢运行权可以先于运行行落库完成互斥判定。 */
  public long nextRunId() {
    Long id = jdbcTemplate.queryForObject("select nextval('backup_runs_id_seq')", Long.class);
    if (id == null) {
      throw new IllegalStateException("backup_runs 序列取号失败");
    }
    return id;
  }

  /** 以预分配 ID 登记一条 RUNNING 运行。 */
  public void insertRunning(long runId, String triggerType, String storageMode, Instant startedAt) {
    jdbcTemplate.update(
        """
        insert into backup_runs (id, trigger_type, status, storage_mode, stage, started_at)
        values (?, ?, 'RUNNING', ?, 'PRECHECK', ?)
        """,
        runId,
        triggerType,
        storageMode,
        Timestamp.from(startedAt));
  }

  /** 推进运行阶段（PRECHECK/DUMP/VERIFY/STORE/RETENTION/DONE）。 */
  public void updateStage(long runId, String stage) {
    jdbcTemplate.update("update backup_runs set stage = ? where id = ?", stage, runId);
  }

  /** 成功终态：写入产物统计与耗时。 */
  public void finishSuccess(
      long runId,
      String targetPath,
      String fileName,
      long fileBytes,
      String sha256,
      String pgServerVersion,
      String flywayVersion,
      Instant startedAt,
      Instant finishedAt) {
    jdbcTemplate.update(
        """
        update backup_runs
           set status = 'SUCCESS', stage = 'DONE', target_path = ?, file_name = ?, file_bytes = ?,
               sha256 = ?, pg_server_version = ?, flyway_version = ?, finished_at = ?, duration_ms = ?
         where id = ?
        """,
        targetPath,
        fileName,
        fileBytes,
        sha256,
        pgServerVersion,
        flywayVersion,
        Timestamp.from(finishedAt),
        finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
        runId);
  }

  /** 失败终态：保留最后阶段与错误信息；duration 未知（如孤儿回收）时传 null。 */
  public void finishFailure(long runId, String errorMessage, Instant startedAt, Instant finishedAt) {
    jdbcTemplate.update(
        """
        update backup_runs
           set status = 'FAILED', finished_at = ?, duration_ms = ?, error_message = ?
         where id = ?
        """,
        Timestamp.from(finishedAt),
        finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
        errorMessage,
        runId);
  }

  /** 读取单条运行（状态展示用）。 */
  public Optional<BackupRun> get(long runId) {
    List<BackupRun> rows =
        jdbcTemplate.query(
            "select " + SELECT_COLUMNS + " from backup_runs where id = ?", MAPPER, runId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** 最近一条已结束的运行（成功或失败），供"最近一次结果"展示。 */
  public Optional<BackupRun> lastFinished() {
    List<BackupRun> rows =
        jdbcTemplate.query(
            "select "
                + SELECT_COLUMNS
                + " from backup_runs where finished_at is not null"
                + " order by started_at desc, id desc limit 1",
            MAPPER);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** 历史分页（开始时间倒序）。 */
  public List<BackupRun> page(int page, int size) {
    return jdbcTemplate.query(
        "select "
            + SELECT_COLUMNS
            + " from backup_runs order by started_at desc, id desc limit ? offset ?",
        MAPPER,
        size,
        (long) (page - 1) * size);
  }

  /** 历史总数。 */
  public long count() {
    Long total = jdbcTemplate.queryForObject("select count(*) from backup_runs", Long.class);
    return total == null ? 0 : total;
  }

  /** 历史最大成功产物字节数（磁盘预检公式的基准）。 */
  public OptionalLong maxSuccessfulBytes() {
    Long max = jdbcTemplate.queryForObject(
        "select max(file_bytes) from backup_runs where status = 'SUCCESS' and file_bytes is not null",
        Long.class);
    return max == null ? OptionalLong.empty() : OptionalLong.of(max);
  }

  /** 当日（按平台时区）是否已有定时触发尝试；有则当日不再补跑，失败等次日。 */
  public boolean existsScheduleTriggeredOn(LocalDate day, ZoneId zone) {
    Instant dayStart = day.atStartOfDay(zone).toInstant();
    Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from backup_runs where trigger_type = 'SCHEDULE'"
                + " and started_at >= ? and started_at < ?",
            Integer.class,
            Timestamp.from(dayStart),
            Timestamp.from(dayEnd));
    return count != null && count > 0;
  }

  /** 平台库当前 Flyway 版本（运行终态留痕）。 */
  public String latestFlywayVersion() {
    List<String> versions =
        jdbcTemplate.query(
            "select version from flyway_schema_history order by installed_rank desc limit 1",
            (rs, rowNum) -> rs.getString(1));
    return versions.isEmpty() ? null : versions.get(0);
  }

  /** 平台库 PG 服务端版本（current_setting('server_version')，形如 16.4）。 */
  public String databaseServerVersion() {
    return jdbcTemplate.queryForObject("select current_setting('server_version')", String.class);
  }
}
