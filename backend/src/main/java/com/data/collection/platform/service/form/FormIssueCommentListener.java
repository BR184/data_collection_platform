package com.data.collection.platform.service.form;

import com.data.collection.platform.service.FormIssueChangeBridge.FormWriteEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import com.data.collection.platform.config.PlatformJacksonConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 保存事务登记评论修订，提交后唤醒；定时触发只派发工作，不等待GitLab。 */
@Component
public class FormIssueCommentListener {
  private final JdbcTemplate jdbc;
  private final FormIssueLinkService links;
  private final Clock clock;

  public FormIssueCommentListener(JdbcTemplate jdbc, FormIssueLinkService links, Clock clock) {
    this.jdbc = jdbc;
    this.links = links;
    this.clock = clock;
  }

  /** 与表单变更一同提交；即使进程在提交后退出，待更新版本仍可恢复。 */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void markPending(FormWriteEvent event) {
    if (!applicable(event)) return;
    jdbc.update("""
        update form_resource_authorities
           set entry_comment_revision = entry_comment_revision + 1,
               entry_comment_changed_at = ?
         where source_config_id = ? and source_instance = ? and project_id = ?
           and resource_type = 'issue' and resource_id = ?
           and template_key = 'issue_response' and authority = 'FORM'
        """, LocalDateTime.ofInstant(clock.instant(), PlatformJacksonConfiguration.API_TIME_ZONE), event.configId(), event.sourceInstance(), event.projectId(), event.resourceId());
  }

  /** 只在事务成功后提交异步任务，失败由补偿节拍继续。 */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void dispatch(FormWriteEvent event) {
    if (applicable(event)) links.dispatchChangedAsync(event);
  }

  /** 使用平台后台调度总开关；单次扫描受投放批大小约束。 */
  @Scheduled(fixedDelayString = "${platform.form-issue-link.retry-delay-ms:60000}")
  public void retryPending() {
    links.dispatchPendingAsync();
  }

  private static boolean applicable(FormWriteEvent event) {
    return "issue".equals(event.resourceType()) && (FormIssueLinkService.RESPONSE_TEMPLATE.equals(event.templateKey())
        || FormIssueLinkService.REPLY_TEMPLATE.equals(event.templateKey()));
  }
}
