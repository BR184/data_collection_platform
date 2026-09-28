package com.data.collection.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** 为不验证镜像链路的事实测试明确声明其标签事件来源已完成全量核验。 */
public final class LabelEventHistoryTestSupport {
  private LabelEventHistoryTestSupport() {}

  /**
   * 在测试数据库中建立指定来源的已核验事件历史记录。
   *
   * <p>仅用于其他行为已有独立夹具、且不验证标签事件镜像过程的测试；真实全链路测试必须通过同步任务写入
   * {@code last_full_verified_at}，不得调用本方法替代链路验收。
   *
   * @param jdbcTemplate 当前测试数据库访问器
   * @param sourceInstance 来源实例
   */
  public static void markComplete(JdbcTemplate jdbcTemplate, String sourceInstance) {
    jdbcTemplate.update(
        """
        insert into gitlab_sync_configs(
          name, source_instance, source_mode, enabled, auto_sync_enabled,
          db_name, db_username, db_password)
        values (?, ?, 'DOCKER', false, false, 'test_database', 'test_user', 'test_password')
        on conflict (source_instance) do update
          set source_mode = 'DOCKER'
          where gitlab_sync_configs.source_mode not in ('DIRECT', 'DOCKER')
        """,
        "label-event-history-test-" + sourceInstance,
        sourceInstance);
    Long configId =
        jdbcTemplate.queryForObject(
            "select id from gitlab_sync_configs where source_instance = ?",
            Long.class,
            sourceInstance);
    jdbcTemplate.update(
        """
        insert into sync_run_table_states(
          config_id, source_instance, source_table, mirror_table,
          primary_key_columns, row_strategy, cursor_strategy,
          sync_enabled, dirty_flag, last_full_verified_at)
        values (?, ?, 'resource_label_events', 'ods_gitlab_test_resource_label_events',
                'id', 'MONOTONIC_PRIMARY_KEY', 'PRIMARY_KEY_KEYSET', true, false, current_timestamp)
        on conflict (config_id, source_instance, source_table) do update
          set sync_enabled = true,
              dirty_flag = false,
              last_full_verified_at = excluded.last_full_verified_at,
              updated_at = current_timestamp
        """,
        configId,
        sourceInstance);
  }

  /** 将已存在的标签事件来源标为未完成，用于验证资格拒绝行为。 */
  public static void markIncomplete(JdbcTemplate jdbcTemplate, String sourceInstance) {
    jdbcTemplate.update(
        """
        update sync_run_table_states
           set dirty_flag = true, updated_at = current_timestamp
         where source_instance = ? and source_table = 'resource_label_events'
        """,
        sourceInstance);
  }
}
