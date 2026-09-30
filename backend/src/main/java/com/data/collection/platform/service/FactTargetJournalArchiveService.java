package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 归档事实发布会话日志（{@code sync_run_fact_targets}）中已无控制面价值的历史行。
 *
 * <p>该表已降级为按轮登记的审计日志：待发布权威是版本头，控制面不再读取它。因此删除判据只有两条——
 * 行已超过保留窗口，且同一稳定根的版本栅栏已经发布到不低于该行登记的版本。满足两条的行在任何时刻都
 * 不可能被"未发布"统计数到，删除它不改变任何对外语义，只把常驻量收敛到日志窗口内。
 *
 * <p>{@code publication_status} 列不再是判据：日志降级后没有代码再把它从 {@code PENDING} 改写为
 * {@code PUBLISHED}，以它为条件会让新写入的行永远无法归档。
 */
@Service
public class FactTargetJournalArchiveService {
  private static final Logger log = LoggerFactory.getLogger(FactTargetJournalArchiveService.class);

  private final JdbcTemplate jdbcTemplate;
  private final GitlabMirrorProperties properties;

  public FactTargetJournalArchiveService(
      JdbcTemplate jdbcTemplate, GitlabMirrorProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  /**
   * 按批次归档历史日志行，返回本次删除的总行数。
   *
   * <p>每次只删除一页、逐页提交，页数上限由 {@code fact-target-journal-archive-batches-per-run}
   * 控制，避免单个调度节拍长时间占用连接与锁。
   */
  public int archiveExpiredBatches() {
    int pageSize = Math.max(1, properties.getFactTargetJournalArchivePageSize());
    int maxBatches = Math.max(1, properties.getFactTargetJournalArchiveBatchesPerRun());
    int retentionDays = Math.max(1, properties.getFactTargetJournalRetentionDays());
    int totalDeleted = 0;
    for (int batch = 0; batch < maxBatches; batch++) {
      int deleted = deleteExpiredPage(retentionDays, pageSize);
      totalDeleted += deleted;
      if (deleted < pageSize) {
        break;
      }
    }
    if (totalDeleted > 0) {
      log.info("事实发布会话日志已归档 {} 行（保留窗口 {} 天）", totalDeleted, retentionDays);
    }
    return totalDeleted;
  }

  private int deleteExpiredPage(int retentionDays, int pageSize) {
    return jdbcTemplate.update(
        """
        with expired as (
          select target.mirror_run_id, target.source_instance,
                 target.fact_type, target.root_id
            from sync_run_fact_targets target
            join fact_change_heads head
              on head.source_instance = target.source_instance
             and head.fact_type = target.fact_type
             and head.root_id = target.root_id
           where target.updated_at <= current_timestamp - make_interval(days => ?)
             and head.published_version >= target.change_version
           order by target.mirror_run_id
           limit ?
        )
        delete from sync_run_fact_targets target
         using expired
         where target.mirror_run_id = expired.mirror_run_id
           and target.source_instance = expired.source_instance
           and target.fact_type = expired.fact_type
           and target.root_id = expired.root_id
        """,
        retentionDays,
        pageSize);
  }
}
