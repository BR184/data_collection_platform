package com.data.collection.platform.service.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 同步运行事件的统一写入通道。 */
@Service
@Slf4j
public class SyncRunEventRecorder {
  private final JdbcTemplate jdbcTemplate;

  public SyncRunEventRecorder(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 记录一次同步运行事件。
   *
   * <p>事件只承担可观测性职责：写入失败降级为应用日志，不阻塞业务；无关联运行（手工任务等）时静默跳过。
   *
   * @param runId 所属同步运行编号；为空或非正表示无关联运行
   * @param configId 数据源配置编号，可为空
   * @param sourceInstance 来源实例标识，可为空
   * @param eventType 事件类型标识
   * @param message 用户可见的事件文案
   */
  public void record(
      Long runId, Long configId, String sourceInstance, String eventType, String message) {
    if (runId == null || runId <= 0) {
      return;
    }
    try {
      jdbcTemplate.update(
          """
          insert into sync_run_events (run_id, config_id, source_instance, event_type, message, created_at)
          values (?, ?, ?, ?, ?, current_timestamp)
          """,
          runId,
          configId,
          sourceInstance,
          eventType,
          message);
    } catch (DataAccessException error) {
      log.warn("Failed to record sync run event, runId={}, eventType={}", runId, eventType, error);
    }
  }
}
