package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.GitlabConfigService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在镜像终态后为来源级事实 outbox 提交唯一消费运行。 */
@Service
public class SyncRunFactPublicationCoordinator {
  private static final String DEFAULT_REASON = "镜像变化已提交，发布定向事实";

  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;
  private final SyncFactPublicationStateService publicationStateService;

  public SyncRunFactPublicationCoordinator(
      JdbcTemplate jdbcTemplate,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService,
      SyncFactPublicationStateService publicationStateService) {
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
    this.submissionService = submissionService;
    this.publicationStateService = publicationStateService;
  }

  /**
   * 为镜像运行提交依赖代际，并创建或唤醒来源级事实消费者。
   *
   * <p>事实消费者不再绑定父镜像运行；失败消费者释放目标归属后由下一次来源终态接管。
   *
   * @param event 已提交的镜像终态事件
   */
  @Transactional
  public void onMirrorCompleted(SyncRunCompletionEvent event) {
    if (event == null || !event.mirrorRun()) {
      return;
    }
    GitlabSyncConfig config = configService.getConfigById(event.configId());
    boolean hasIntent = publicationStateService.recordMirrorCompletion(
        config,
        event.runId(),
        event.status(),
        event.requiresFullFactRefresh() && event.successful());
    String sourceInstance = event.sourceInstance();
    publicationStateService.releaseFailedFactAssignments(sourceInstance);
    if (!hasIntent) {
      return;
    }
    upgradeQueuedFullIntent(config, event.requiresFullFactRefresh() && event.successful());
    submissionService.submitFactRefresh(
        config, event.requiresFullFactRefresh() && event.successful(), DEFAULT_REASON);
  }

  private void upgradeQueuedFullIntent(GitlabSyncConfig config, boolean full) {
    if (!full || config == null) {
      return;
    }
    jdbcTemplate.update(
        """
        update sync_runs
           set payload_json = jsonb_set(
                 coalesce(nullif(payload_json, ''), '{}')::jsonb,
                 '{fullBuild}', 'true'::jsonb, true)::text,
               updated_at = current_timestamp
         where config_id = ?
           and source_instance = ?
           and run_type = 'FACT_REFRESH'
           and status in ('QUEUED', 'PAUSED', 'RETRYING')
        """,
        config.getId(),
        com.data.collection.platform.service.GitlabSourceInstanceSupport.sourceInstanceOf(config));
  }
}
