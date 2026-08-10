package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 防止活动镜像运行在执行期间切换连接参数或并发预算。 */
@Service
public class SyncRuntimeConfigGuard {
  private final JdbcTemplate jdbcTemplate;

  public SyncRuntimeConfigGuard(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 校验运行时敏感配置是否可修改。
   *
   * @param current 当前已持久化配置
   * @param replacement 待保存的新配置
   * @throws BizException 同一数据源存在活动镜像运行且敏感字段发生变化
   */
  public void verifyChangeAllowed(GitlabSyncConfig current, GitlabSyncConfig replacement) {
    if (current == null
        || current.getId() == null
        || replacement == null
        || !runtimeSettingsChanged(current, replacement)) {
      return;
    }
    Boolean active =
        jdbcTemplate.queryForObject(
            """
            select exists (
              select 1
                from sync_runs
               where config_id = ?
                 and source_instance = ?
                 and run_type in (
                   'FULL_SYNC', 'INCREMENTAL_SYNC', 'TABLE_REFRESH', 'SYSTEM_HOOK',
                   'FULL_COMPENSATION_SCAN', 'DELETE_RECONCILIATION'
                 )
                 and status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
            )
            """,
            Boolean.class,
            current.getId(),
            current.getSourceInstance());
    if (Boolean.TRUE.equals(active)) {
      throw new BizException("当前数据源存在活动同步任务，连接参数和同步线程配置暂不可修改");
    }
  }

  private boolean runtimeSettingsChanged(
      GitlabSyncConfig current, GitlabSyncConfig replacement) {
    return !Objects.equals(current.getSourceMode(), replacement.getSourceMode())
        || !Objects.equals(current.getDbHost(), replacement.getDbHost())
        || !Objects.equals(current.getDbPort(), replacement.getDbPort())
        || !Objects.equals(current.getDbName(), replacement.getDbName())
        || !Objects.equals(current.getDbUsername(), replacement.getDbUsername())
        || !Objects.equals(current.getDbPassword(), replacement.getDbPassword())
        || !Objects.equals(current.getDockerContainerName(), replacement.getDockerContainerName())
        || !Objects.equals(current.getSyncThreadMode(), replacement.getSyncThreadMode())
        || !Objects.equals(current.getSyncThreadValue(), replacement.getSyncThreadValue())
        || !Objects.equals(current.getMaxSyncThreads(), replacement.getMaxSyncThreads());
  }
}
