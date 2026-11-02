package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.form.FormDirectoryEventCursorService;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 目录事件与既有事实任务之间的桥。
 *
 * <p>目录事件只声明范围与发布版本；消费后按被唤醒的 FORM 资源推进 {@code fact_change_heads}，
 * 再由既有 FACT_REFRESH 领取根并走同一构建/公开链路。不新增旁路写回器，也不在事件消费事务
 * 内调用外部系统。
 */
@Service
@Slf4j
public class FormDirectoryEventBridge {
  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;

  public FormDirectoryEventBridge(
      JdbcTemplate jdbcTemplate,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService) {
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
    this.submissionService = submissionService;
  }

  /**
   * 在独立事务内为被唤醒资源登记待发布事实变化；同一资源多模板去重后只推进一次。
   *
   * @param woken 目录事件消费返回的资源
   */
  @Transactional
  public void register(List<FormDirectoryEventCursorService.WokenResource> woken) {
    Set<String> seen = new LinkedHashSet<>();
    for (FormDirectoryEventCursorService.WokenResource resource : woken) {
      String key =
          resource.sourceInstance()
              + '|'
              + resource.resourceType()
              + '|'
              + resource.projectId()
              + '|'
              + resource.resourceId();
      if (!seen.add(key)) {
        continue;
      }
      boolean mergeRequest = "merge_request".equals(resource.resourceType());
      if (!mergeRequest && !"issue".equals(resource.resourceType())) {
        continue;
      }
      Long rootId =
          mergeRequest
              ? GitlabResourceRootResolver.findMergeRequestRootId(
                  jdbcTemplate,
                  resource.sourceInstance(),
                  resource.projectId(),
                  resource.resourceId())
              : GitlabResourceRootResolver.findIssueRootId(
                  jdbcTemplate,
                  resource.sourceInstance(),
                  resource.projectId(),
                  resource.resourceId());
      if (rootId == null) {
        log.warn(
            "Directory event woke a resource without a fact root, sourceInstance={}, resourceType={},"
                + " projectId={}, resourceId={}",
            resource.sourceInstance(),
            resource.resourceType(),
            resource.projectId(),
            resource.resourceId());
        continue;
      }
      jdbcTemplate.update(
          """
          insert into fact_change_heads(
              source_instance, fact_type, root_id, project_id, latest_change_version,
              published_version, updated_at)
          values (?, ?, ?, ?, nextval('fact_change_version_seq'), 0, current_timestamp)
          on conflict (source_instance, fact_type, root_id) do update
             set latest_change_version = greatest(
                     fact_change_heads.latest_change_version,
                     excluded.latest_change_version),
                 project_id = excluded.project_id,
                 updated_at = current_timestamp
          """,
          resource.sourceInstance(),
          mergeRequest ? "MERGE_REQUEST" : "ISSUE",
          rootId,
          resource.projectId());
    }
  }

  /**
   * 提交既有 FACT_REFRESH；提交失败只记录日志，待发布变化仍由 {@code fact_change_heads} 保留。
   *
   * @param woken 本次被唤醒资源
   */
  public void submit(List<FormDirectoryEventCursorService.WokenResource> woken) {
    Set<String> sources = new LinkedHashSet<>();
    for (FormDirectoryEventCursorService.WokenResource resource : woken) {
      sources.add(resource.sourceInstance());
    }
    if (sources.isEmpty()) {
      return;
    }
    for (GitlabSyncConfig config : configService.listConfigs()) {
      String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
      if (!sources.contains(sourceInstance) || !configService.isSourceEnabled(config)) {
        continue;
      }
      try {
        submissionService.submitFactRefresh(config, false, "表单目录事件触发事实刷新");
      } catch (RuntimeException error) {
        log.warn(
            "Failed to submit fact refresh after directory events, configId={}, sourceInstance={}",
            config.getId(),
            sourceInstance,
            error);
      }
    }
  }
}
