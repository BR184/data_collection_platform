package com.data.collection.platform.service.backup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** backup_settings 单行配置的读写；版本号作为乐观锁，行不存在视为版本 0。 */
@Repository
public class BackupSettingsRepository {
  private static final String SELECT =
      """
      select enabled, schedule_time, retention_copies, storage_mode, local_subdirectory,
             remote_host, remote_port, remote_username, remote_password_cipher,
             remote_directory, remote_host_key_fingerprint, version, updated_by, updated_at
        from backup_settings
       where id = 1
      """;
  private static final RowMapper<BackupSettings> MAPPER =
      (rs, rowNum) ->
          new BackupSettings(
              rs.getBoolean("enabled"),
              rs.getObject("schedule_time", LocalTime.class),
              rs.getInt("retention_copies"),
              rs.getString("storage_mode"),
              rs.getString("local_subdirectory"),
              rs.getString("remote_host"),
              rs.getInt("remote_port"),
              rs.getString("remote_username"),
              rs.getString("remote_password_cipher"),
              rs.getString("remote_directory"),
              rs.getString("remote_host_key_fingerprint"),
              rs.getLong("version"),
              rs.getString("updated_by"),
              rs.getTimestamp("updated_at").toInstant());

  private final JdbcTemplate jdbcTemplate;

  public BackupSettingsRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 读取当前配置；行不存在返回 empty（调用侧以 defaults 呈现）。 */
  public Optional<BackupSettings> load() {
    List<BackupSettings> rows = jdbcTemplate.query(SELECT, MAPPER);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** 首次保存：插入配置行，版本置 1；行已存在时由数据库唯一约束拒绝。 */
  public BackupSettings insert(BackupSettings settings, Instant now) {
    jdbcTemplate.update(
        """
        insert into backup_settings (id, enabled, schedule_time, retention_copies, storage_mode,
                                     local_subdirectory, remote_host, remote_port, remote_username,
                                     remote_password_cipher, remote_directory, remote_host_key_fingerprint,
                                     version, updated_by, created_at, updated_at)
        values (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
        """,
        settings.enabled(),
        settings.scheduleTime(),
        settings.retentionCopies(),
        settings.storageMode(),
        settings.localSubdirectory(),
        settings.remoteHost(),
        settings.remotePort(),
        settings.remoteUsername(),
        settings.remotePasswordCipher(),
        settings.remoteDirectory(),
        settings.remoteHostKeyFingerprint(),
        settings.updatedBy(),
        Timestamp.from(now),
        Timestamp.from(now));
    return load().orElseThrow(() -> new IllegalStateException("备份配置写入后读取失败"));
  }

  /** 条件更新：仅当库内版本与期望一致时生效并自增；不一致抛 CONFLICT 语义业务异常。 */
  public BackupSettings update(BackupSettings settings, long expectedVersion, Instant now) {
    int updated =
        jdbcTemplate.update(
            """
            update backup_settings
               set enabled = ?, schedule_time = ?, retention_copies = ?, storage_mode = ?,
                   local_subdirectory = ?, remote_host = ?, remote_port = ?, remote_username = ?,
                   remote_password_cipher = ?, remote_directory = ?, remote_host_key_fingerprint = ?,
                   version = ?, updated_by = ?, updated_at = ?
             where id = 1 and version = ?
            """,
            settings.enabled(),
            settings.scheduleTime(),
            settings.retentionCopies(),
            settings.storageMode(),
            settings.localSubdirectory(),
            settings.remoteHost(),
            settings.remotePort(),
            settings.remoteUsername(),
            settings.remotePasswordCipher(),
            settings.remoteDirectory(),
            settings.remoteHostKeyFingerprint(),
            expectedVersion + 1,
            settings.updatedBy(),
            Timestamp.from(now),
            expectedVersion);
    if (updated != 1) {
      throw new BizException(
          ResultCode.CONFLICT, "备份配置已被他人修改（版本 " + expectedVersion + " 已失效），请刷新后重试");
    }
    return load().orElseThrow(() -> new IllegalStateException("备份配置更新后读取失败"));
  }
}
