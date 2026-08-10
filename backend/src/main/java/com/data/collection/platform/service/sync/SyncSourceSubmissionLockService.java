package com.data.collection.platform.service.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 串行化同一 GitLab 来源的运行提交与增量终态转换。 */
@Service
public class SyncSourceSubmissionLockService {
  private final JdbcTemplate jdbcTemplate;

  public SyncSourceSubmissionLockService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 获取当前事务持有的来源级 PostgreSQL advisory lock。
   *
   * <p>调用方必须处于事务中；锁在事务完成时自动释放。
   */
  public void lock(Long configId, String sourceInstance) {
    if (configId == null || sourceInstance == null || sourceInstance.isBlank()) {
      throw new IllegalArgumentException("来源提交锁缺少 configId 或 sourceInstance");
    }
    jdbcTemplate.queryForObject(
        "select pg_advisory_xact_lock(hashtext(?))",
        Object.class,
        lockKey(configId, sourceInstance));
  }

  static String lockKey(Long configId, String sourceInstance) {
    return "source:" + configId + ":" + sourceInstance + ":submission";
  }
}
