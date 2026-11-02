package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.form.FormVersionContext;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 表单记录变化与既有议题事实任务之间的桥。
 *
 * <p>写事务内推进 {@code fact_change_heads} 的 ISSUE 版本，提交后再提交一次既有 FACT_REFRESH；
 * 事实公开事务提交后唤醒原来源级延期写回轮次。不新增旁路写回器，也不在事务提交前调用外部系统。
 */
@Service
@Slf4j
public class FormIssueChangeBridge {
  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;
  private final CustomerIssueDelayClosureOrchestrator orchestrator;
  private final ApplicationEventPublisher events;

  FormIssueChangeBridge(
      JdbcTemplate jdbcTemplate,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService,
      CustomerIssueDelayClosureOrchestrator orchestrator,
      ApplicationEventPublisher events) {
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
    this.submissionService = submissionService;
    this.orchestrator = orchestrator;
    this.events = events;
  }

  /**
   * 在表单写事务内登记议题事实待发布变化。
   *
   * @param context 已通过鉴权的写入上下文
   */
  public void record(FormVersionContext context) {
    if (context == null
        || !"issue".equals(context.resourceType())
        || context.rootId() == null) {
      return;
    }
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, project_id, latest_change_version,
            published_version, updated_at)
        values (?, 'ISSUE', ?, ?, nextval('fact_change_version_seq'), 0, current_timestamp)
        on conflict (source_instance, fact_type, root_id) do update
           set latest_change_version = greatest(
                   fact_change_heads.latest_change_version,
                   excluded.latest_change_version),
               project_id = excluded.project_id,
               updated_at = current_timestamp
        """,
        context.sourceInstance(),
        context.rootId(),
        context.projectId());
    events.publishEvent(
        new FormIssueWriteEvent(
            context.sourceConfigId(),
            context.sourceInstance(),
            context.projectId(),
            context.resourceId()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onFormWrite(FormIssueWriteEvent event) {
    GitlabSyncConfig config = configService.getConfigById(event.configId());
    if (config == null || !configService.isSourceEnabled(config)) {
      log.warn(
          "Form write did not enqueue fact refresh because source config is missing or disabled,"
              + " configId={}, sourceInstance={}",
          event.configId(),
          event.sourceInstance());
      return;
    }
    try {
      submissionService.submitFactRefresh(config, false, "表单记录变化触发议题事实刷新");
    } catch (RuntimeException error) {
      log.warn(
          "Failed to submit fact refresh after form write, configId={}, sourceInstance={}, projectId={}, issueIid={}",
          event.configId(),
          event.sourceInstance(),
          event.projectId(),
          event.issueIid(),
          error);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPublication(FormFactPublicationEvent event) {
    orchestrator.requestSourceRound(event.sourceInstance(), "表单输入已消费并公开，重算延期标签");
  }

  record FormIssueWriteEvent(
      long configId, String sourceInstance, long projectId, long issueIid) {}
}
