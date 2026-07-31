package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 判断标签派生事实是否已完成过可验证的全量关系对账。
 *
 * <p>普通增量不能发现来源物理删除的关系，因此只要当前数据源从未成功完成包含
 * {@code label_links} 的全量对账，依赖标签的页面就不能宣称数据已经最新。
 */
@Service
public class AuthoritativeRelationReconciliationService {
  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;

  public AuthoritativeRelationReconciliationService(
      JdbcTemplate jdbcTemplate, GitlabConfigService configService) {
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
  }

  /**
   * 判断指定工作区是否必须先完成标签关系全量对账。
   *
   * @param workspaceKey 页面工作区稳定标识
   * @return 标签派生页面且当前来源没有已验证对账时返回 {@code true}
   */
  public boolean requiresLabelLinkReconciliation(String workspaceKey) {
    if (!usesLabelDerivedFacts(workspaceKey)) {
      return false;
    }
    GitlabSyncConfig config = configService.getConfig();
    if (config == null || config.getId() == null) {
      return true;
    }
    try {
      Boolean reconciled = jdbcTemplate.queryForObject(
          """
          select exists (
            select 1
              from sync_runs run
             where run.config_id = ?
               and run.source_instance = ?
               and run.run_type in ('FULL_SYNC', 'FULL_COMPENSATION_SCAN')
               and run.status = 'SUCCESS'
               and exists (
                 select 1
                   from sync_run_table_tasks task
                  where task.run_id = run.id
                    and lower(task.source_table) = 'label_links'
                    and task.row_strategy = 'FULL_RECONCILE'
                    and task.task_stage = 'RECONCILE'
                    and task.status = 'SUCCESS'
               )
          )
          """,
          Boolean.class,
          config.getId(),
          GitlabSourceInstanceSupport.sourceInstanceOf(config));
      return !Boolean.TRUE.equals(reconciled);
    } catch (DataAccessException error) {
      return true;
    }
  }

  private boolean usesLabelDerivedFacts(String workspaceKey) {
    String normalized = workspaceKey == null ? "" : workspaceKey.trim().toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith("system-test-")
        || normalized.startsWith("customer-issue-")
        || normalized.startsWith("code-review-");
  }
}
