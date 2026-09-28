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
import org.springframework.transaction.annotation.Transactional;

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

  /** 以预分配 ID 与当前执行 token 登记一条 RUNNING 运行。 */
  @Transactional
  public void insertRunning(
      long runId, String executionToken, String triggerType, String storageMode, Instant startedAt) {
    lockActiveOwner(runId, executionToken, startedAt);
    int inserted = jdbcTemplate.update(
        """
        insert into backup_runs (id, trigger_type, status, storage_mode, stage, started_at)
        select ?, ?, 'RUNNING', ?, 'PRECHECK', ?
         where exists (
           select 1 from backup_state
            where id = 1 and active_run_id = ? and execution_token = ?
              and execution_revoked = false and lease_expires_at >= ?
         )
        """,
        runId,
        triggerType,
        storageMode,
        Timestamp.from(startedAt),
        runId,
        executionToken,
        Timestamp.from(startedAt));
    requireOwnedUpdate(inserted, runId);
  }

  /** 推进当前有效运行的阶段（PRECHECK/DUMP/VERIFY/STORE/RETENTION）。 */
  @Transactional
  public void updateStage(long runId, String executionToken, String stage, Instant now) {
    lockActiveOwner(runId, executionToken, now);
    int updated =
        jdbcTemplate.update(
            """
            update backup_runs set stage = ?
             where id = ? and status = 'RUNNING'
               and exists (
                 select 1 from backup_state
                  where id = 1 and active_run_id = ? and execution_token = ?
                    and execution_revoked = false and lease_expires_at >= ?
               )
            """,
            stage,
            runId,
            runId,
            executionToken,
            Timestamp.from(now));
    requireOwnedUpdate(updated, runId);
  }

  /** 成功终态：写入产物统计与耗时。 */
  @Transactional
  public void finishSuccess(
      long runId,
      String executionToken,
      String targetPath,
      String fileName,
      long fileBytes,
      String sha256,
      String pgServerVersion,
      String flywayVersion,
      Instant startedAt,
      Instant finishedAt) {
    lockActiveOwner(runId, executionToken, finishedAt);
    int updated = jdbcTemplate.update(
        """
        update backup_runs
           set status = 'SUCCESS', stage = 'RETENTION', target_path = ?, file_name = ?, file_bytes = ?,
               sha256 = ?, pg_server_version = ?, flyway_version = ?, finished_at = ?, duration_ms = ?
         where id = ? and status = 'RUNNING'
           and exists (
             select 1 from backup_state
              where id = 1 and active_run_id = ? and execution_token = ?
                and execution_revoked = false and lease_expires_at >= ?
           )
        """,
        targetPath,
        fileName,
        fileBytes,
        sha256,
        pgServerVersion,
        flywayVersion,
        Timestamp.from(finishedAt),
        finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
        runId,
        runId,
        executionToken,
        Timestamp.from(finishedAt));
    requireOwnedUpdate(updated, runId);
  }

  /** 在写入或上传前登记本次产物候选；RUNNING/FAILED 候选不能进入成功集合。 */
  @Transactional
  public void recordCandidateArtifact(
      long runId, String executionToken, String targetPath, String fileName, Instant now) {
    lockActiveOwner(runId, executionToken, now);
    int updated =
        jdbcTemplate.update(
            """
            update backup_runs set target_path = ?, file_name = ?
             where id = ? and status = 'RUNNING'
            """,
            targetPath,
            fileName,
            runId);
    requireOwnedUpdate(updated, runId);
  }

  /** 失败终态只接受仍持有未过期运行权的执行者。 */
  @Transactional
  public void finishFailure(
      long runId,
      String executionToken,
      String errorMessage,
      Instant startedAt,
      Instant finishedAt) {
    lockActiveOwner(runId, executionToken, finishedAt);
    int updated = jdbcTemplate.update(
        """
        update backup_runs
           set status = 'FAILED', finished_at = ?, duration_ms = ?, error_message = ?
         where id = ? and status = 'RUNNING'
           and exists (
             select 1 from backup_state
              where id = 1 and active_run_id = ? and execution_token = ?
                and execution_revoked = false and lease_expires_at >= ?
           )
        """,
        Timestamp.from(finishedAt),
        startedAt == null ? null : finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
        errorMessage,
        runId,
        runId,
        executionToken,
        Timestamp.from(finishedAt));
    requireOwnedUpdate(updated, runId);
  }

  /** 数据库已登记成功产物后完成轮转阶段。 */
  @Transactional
  public void finishRetention(long runId, String executionToken, Instant now) {
    lockActiveOwner(runId, executionToken, now);
    int updated =
        jdbcTemplate.update(
            """
            update backup_runs set stage = 'DONE'
             where id = ? and status = 'SUCCESS'
               and exists (
                 select 1 from backup_state
                  where id = 1 and active_run_id = ? and execution_token = ?
                    and execution_revoked = false and lease_expires_at >= ?
               )
            """,
            runId,
            runId,
            executionToken,
            Timestamp.from(now));
    requireOwnedUpdate(updated, runId);
  }

  /** 仅返回本实例已登记成功的备份文件名，供安全轮转使用。 */
  public List<SuccessfulArtifact> successfulArtifacts(String storageMode) {
    return jdbcTemplate.query(
        "select distinct target_path, file_name from backup_runs "
            + "where status = 'SUCCESS' and storage_mode = ? "
            + "and target_path is not null and file_name is not null",
        (rs, rowNum) -> new SuccessfulArtifact(rs.getString("target_path"), rs.getString("file_name")),
        storageMode);
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

  private static void requireOwnedUpdate(int updated, long runId) {
    if (updated != 1) {
      throw new BackupLeaseLostException(runId);
    }
  }

  private void lockActiveOwner(long runId, String executionToken, Instant now) {
    List<Integer> rows =
        jdbcTemplate.query(
            "select id from backup_state where id = 1 and active_run_id = ? "
                + "and execution_token = ? and execution_revoked = false and lease_expires_at >= ? for update",
            (rs, rowNum) -> rs.getInt(1),
            runId,
            executionToken,
            Timestamp.from(now));
    if (rows.isEmpty()) {
      throw new BackupLeaseLostException(runId);
    }
  }

  /** 成功提交过的制品身份；轮转必须同时匹配存储目录与文件名。 */
  public record SuccessfulArtifact(String targetPath, String fileName) {}
}
