package com.data.collection.platform.service.form;

import com.data.collection.platform.config.FormIssueLinkProperties;
import com.data.collection.platform.config.PlatformAsyncConfiguration;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabResourceRootResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 议题表单评论投放与更新：复用响应权威行保存评论编号及待更新修订。
 *
 * <p>首次投放保留既有就绪规则；已有评论按资源串行PUT，读取一致性快照，成功仅确认捕获修订。
 * 保存事务不等待HTTP，失败留待60秒补偿；网络结果不明确的首次POST仍沿用既有重试边界。
 */
@Service
@Slf4j
public class FormIssueLinkService {

  static final String RESPONSE_TEMPLATE = FormTemplateSpecCatalog.ISSUE_RESPONSE;
  static final String REPLY_TEMPLATE = FormTemplateSpecCatalog.ISSUE_REPLY;
  // 前端使用 createWebHashHistory；资源上下文必须位于哈希路由内。
  private static final String ENTRY_PATH = "/#/external/forms";

  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;
  private final FormIssueLinkProperties properties;
  private final FormIssueSettingsService settingsService;
  private final ObjectMapper objectMapper;
  private final Executor platformAsyncExecutor;
  private final TransactionTemplate transactionTemplate;
  private final HttpClient httpClient;
  private final FormIssueCommentContentService contentService;
  private final TransactionTemplate snapshotTemplate;
  private final AtomicBoolean dispatchRunning = new AtomicBoolean();
  private final AtomicBoolean dispatchRequested = new AtomicBoolean();
  private final AtomicLong dispatchCursor = new AtomicLong(0L);

  public FormIssueLinkService(
      JdbcTemplate jdbcTemplate,
      GitlabConfigService configService,
      FormIssueLinkProperties properties,
      FormIssueSettingsService settingsService,
      FormIssueCommentContentService contentService,
      ObjectMapper objectMapper,
      @Qualifier(PlatformAsyncConfiguration.PLATFORM_ASYNC_EXECUTOR) Executor platformAsyncExecutor,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
    this.properties = properties;
    this.settingsService = settingsService;
    this.contentService = contentService;
    this.snapshotTemplate = new TransactionTemplate(transactionManager);
    this.snapshotTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.snapshotTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshotTemplate.setReadOnly(true);
    this.objectMapper = objectMapper;
    this.platformAsyncExecutor = platformAsyncExecutor;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(properties.getRequestTimeout()).build();
  }

  /**
   * 在 ISSUE 完整发布事务内登记就绪议题；回滚时登记随发布一并回滚。
   *
   * <p>只登记本项目、响应与回复均为持久 {@code FORM}、两侧范围与当前版本均有效的议题；已登记的行
   * 不重复写入，因此重复发布不会重复登记。
   *
   * @param sourceInstance 正在提交的来源
   * @param rootIds 当前批次的议题根ID
   * @return 本次登记的权威行数
   */
  public int registerPublishedIssues(String sourceInstance, Long[] rootIds) {
    if (!deliveryConfigured()) {
      return 0;
    }
    return jdbcTemplate.update(
        """
        update form_resource_authorities a
           set entry_registered_at = current_timestamp,
               updated_at = current_timestamp
         where a.resource_type = 'issue'
           and a.template_key = ?
           and a.authority = 'FORM'
           and exists (select 1 from gitlab_sync_configs c where c.id = a.source_config_id
             and c.form_issue_settings -> a.project_id::text @> '{"enabled":true,"linkEnabled":true}'::jsonb)
           and a.entry_registered_at is null
           and exists (
             select 1
               from issue_fact f
              where f.source_system = 'GITLAB'
                and f.source_instance = ?
                and f.issue_id = any(?)
                and f.project_id = a.project_id
                and f.issue_iid = a.resource_id
                and coalesce(f.deleted, false) = false)
           and exists (
             select 1
               from form_resource_authorities b
               join form_template_scopes bs on bs.id = b.scope_id
               join form_template_versions bv on bv.id = bs.current_version_id
              where b.source_config_id = a.source_config_id
                and b.project_id = a.project_id
                and b.resource_type = 'issue'
                and b.resource_id = a.resource_id
                and b.template_key = ?
                and b.authority = 'FORM'
                and bs.status = 'ACTIVE'
                and bv.version_status = 'PUBLISHED'
                and bv.storage_kind = 'RECORDS'
                and bv.version_label is not null)
           and exists (
             select 1
               from form_template_scopes s
               join form_template_versions v on v.id = s.current_version_id
              where s.id = a.scope_id
                and s.status = 'ACTIVE'
                and v.version_status = 'PUBLISHED'
                and v.storage_kind = 'RECORDS'
                and v.version_label is not null)
        """,
        RESPONSE_TEMPLATE,
        sourceInstance,
        rootIds,
        REPLY_TEMPLATE);
  }

  /** 提交发布后由平台异步执行器补发；不阻塞发布线程，失败由下一轮调度重试。 */
  public void dispatchPendingAsync() {
    dispatchRequested.set(true);
    submitIfIdle();
  }

  private void submitIfIdle() {
    if (!dispatchRunning.compareAndSet(false, true)) return;
    try {
      platformAsyncExecutor.execute(() -> {
        try {
          dispatchRequested.set(false);
          dispatchPending(properties.getBatchSize());
        } finally {
          dispatchRunning.set(false);
          if (dispatchRequested.get()) submitIfIdle();
        }
      });
    } catch (RejectedExecutionException error) {
      dispatchRunning.set(false);
      log.warn("表单就绪投放任务未能提交，等待补偿调度下一轮：{}", error.getMessage());
    }
  }

  /**
   * 处理一批待首次投放或待更新的记录。
   *
   * <p>候选先过滤当前不就绪的权威行，避免少量长期不就绪或持续失败的行永久占据队首；批内按稳定
   * 主键向后扫描，失败也推进游标，到尾回绕。发送仍在资源锁内复核，游标只决定处理顺序。
   *
   * @param limit 本次最多处理条数
   * @return 本次成功发出评论的数量
   */
  public int dispatchPending(int limit) {
    if (!deliveryConfigured() || limit <= 0) {
      return 0;
    }
    long cursor = dispatchCursor.get();
    List<Long> ids = pendingCandidates(cursor, limit);
    if (ids.isEmpty() && cursor > 0L) {
      dispatchCursor.set(0L);
      ids = pendingCandidates(0L, limit);
    }
    int sent = 0;
    for (Long id : ids) {
      dispatchCursor.set(id);
      try {
        if (sendOne(id)) {
          sent++;
        }
      } catch (RuntimeException error) {
        log.warn("表单就绪投放失败，保留待更新修订等待下一轮，authorityId={}：{}", id, error.getMessage());
      }
    }
    return sent;
  }

  private List<Long> pendingCandidates(long afterId, int limit) {
    return jdbcTemplate.queryForList(
        """
        select a.id from form_resource_authorities a
         where a.resource_type = 'issue' and a.template_key = ?
           and a.entry_registered_at is not null
           and (a.entry_comment_id is null or a.entry_comment_revision > a.entry_comment_synced_revision)
           and a.authority = 'FORM'
           and exists (select 1 from gitlab_sync_configs c where c.id = a.source_config_id
             and c.form_issue_settings -> a.project_id::text @> '{"enabled":true,"linkEnabled":true}'::jsonb)
           and a.id > ?
           and exists (
             select 1
               from issue_fact f
              where f.source_system = ?
                and f.source_instance = a.source_instance
                and f.project_id = a.project_id
                and f.issue_iid = a.resource_id
                and f.deleted = false
                and """
            + " coalesce(f.deleted, false) = false "
            + """
           )
           and exists (
             select 1
               from form_resource_authorities b
               join form_template_scopes bs on bs.id = b.scope_id
               join form_template_versions bv on bv.id = bs.current_version_id
              where b.source_config_id = a.source_config_id
                and b.project_id = a.project_id
                and b.resource_type = 'issue'
                and b.resource_id = a.resource_id
                and b.template_key = ?
                and b.authority = 'FORM'
                and bs.status = 'ACTIVE'
                and bv.version_status = 'PUBLISHED'
                and bv.storage_kind = 'RECORDS'
                and bv.version_label is not null
           )
           and exists (
             select 1
               from form_template_scopes s
               join form_template_versions v on v.id = s.current_version_id
              where s.id = a.scope_id
                and s.status = 'ACTIVE'
                and v.version_status = 'PUBLISHED'
                and v.storage_kind = 'RECORDS'
                and v.version_label is not null
           )
         order by a.id
         limit ?
        """,
        Long.class,
        RESPONSE_TEMPLATE,
        afterId,
        GitlabResourceRootResolver.DEFAULT_SOURCE_SYSTEM,
        REPLY_TEMPLATE,
        limit);
  }

  /**
   * 独立事务串行处理一个议题：首次POST登记编号，后续PUT确认已发送修订。
   *
   * @param authorityId 响应模板权威行 id
   * @return 本次是否成功创建或更新评论
   */
  public boolean sendOne(long authorityId) {
    if (!deliveryConfigured()) {
      return false;
    }
    Boolean sent =
        transactionTemplate.execute(
            status -> {
              Boolean locked = jdbcTemplate.queryForObject(
                  "select true from (select pg_advisory_xact_lock(hashtextextended(?, 0))) locked", Boolean.class,
                  "form-issue-comment:" + authorityId);
              if (!Boolean.TRUE.equals(locked)) return false;
              List<Long> posted = jdbcTemplate.queryForList(
                  "select entry_comment_id from form_resource_authorities where id = ? and entry_comment_id is not null",
                  Long.class, authorityId);
              if (!posted.isEmpty()) return refreshComment(authorityId);
              List<Map<String, Object>> identities = jdbcTemplate.queryForList(
                  "select source_config_id, project_id from form_resource_authorities where id = ?", authorityId);
              if (identities.isEmpty()) { return false; }
              var identity = identities.getFirst();
              var settings = settingsService.lockForUse(
                  ((Number) identity.get("source_config_id")).longValue(),
                  ((Number) identity.get("project_id")).longValue());
              if (!settings.delivers()) { return false; }
              List<Map<String, Object>> rows =
                  jdbcTemplate.queryForList(
                      """
                      select id, source_config_id, source_instance, project_id, resource_id,
                             scope_id, template_key, authority, entry_registered_at,
                             entry_comment_id, entry_comment_revision, entry_comment_changed_at
                        from form_resource_authorities
                       where id = ? for update
                      """,
                      authorityId);
              if (rows.isEmpty()) {
                return false;
              }
              Map<String, Object> row = rows.getFirst();
              if (row.get("entry_comment_id") != null
                  || row.get("entry_registered_at") == null
                  || !"FORM".equals(row.get("authority"))
                  || !RESPONSE_TEMPLATE.equals(row.get("template_key"))) {
                return false;
              }
              long projectId = ((Number) row.get("project_id")).longValue();
              long issueIid = ((Number) row.get("resource_id")).longValue();
              String sourceInstance = (String) row.get("source_instance");
              if (!ready(row, sourceInstance, projectId, issueIid)) {
                return false;
              }
              long configId = ((Number) row.get("source_config_id")).longValue();
              GitlabSyncConfig config = configService.getConfigById(configId);
              if (!StringUtils.hasText(config.getWebBaseUrl())
                  || !StringUtils.hasText(config.getApiToken())) {
                return false;
              }
              long commentId =
                  writeComment(config, projectId, issueIid, null, commentBody(row, config, projectId, issueIid, settings.externalBaseUrl()));
              jdbcTemplate.update(
                  """
                  update form_resource_authorities
                     set entry_comment_id = ?, entry_comment_synced_revision = entry_comment_revision, updated_at = current_timestamp
                   where id = ? and entry_comment_id is null
                  """,
                  commentId,
                  authorityId);
              return true;
            });
    return Boolean.TRUE.equals(sent);
  }

  /** 运行期就绪复核：响应/回复仍为 FORM，两侧范围与当前版本仍有效，事实已公开。 */
  private boolean ready(
      Map<String, Object> row, String sourceInstance, long projectId, long issueIid) {
    long scopeId = ((Number) row.get("scope_id")).longValue();
    long configId = ((Number) row.get("source_config_id")).longValue();
    Boolean factPublished =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from issue_fact f
               where f.source_system = ?
                 and f.source_instance = ?
                 and f.project_id = ?
                 and f.issue_iid = ?
                 and f.deleted = false
                 and """
                + " coalesce(f.deleted, false) = false "
                + ")",
            Boolean.class,
            GitlabResourceRootResolver.DEFAULT_SOURCE_SYSTEM,
            sourceInstance,
            projectId,
            issueIid);
    Boolean replyValid =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from form_resource_authorities b
                join form_template_scopes bs on bs.id = b.scope_id
                join form_template_versions bv on bv.id = bs.current_version_id
               where b.source_config_id = ? and b.project_id = ? and b.resource_type = 'issue'
                 and b.resource_id = ? and b.template_key = ? and b.authority = 'FORM'
                 and bs.status = 'ACTIVE'
                 and bv.version_status = 'PUBLISHED'
                 and bv.storage_kind = 'RECORDS'
                 and bv.version_label is not null)
            """,
            Boolean.class,
            configId,
            projectId,
            issueIid,
            REPLY_TEMPLATE);
    Boolean scopeValid =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from form_template_scopes s
                join form_template_versions v on v.id = s.current_version_id
               where s.id = ? and s.status = 'ACTIVE'
                 and v.version_status = 'PUBLISHED'
                 and v.storage_kind = 'RECORDS'
                 and v.version_label is not null)
            """,
            Boolean.class,
            scopeId);
    return Boolean.TRUE.equals(factPublished)
        && Boolean.TRUE.equals(replyValid)
        && Boolean.TRUE.equals(scopeValid);
  }

  private record CommentSnapshot(GitlabSyncConfig config, long projectId, long issueIid,
      long commentId, long revision, String body) {}

  /** 快照事务不跨HTTP；确认捕获修订，发送期间的新修改继续保持待更新。 */
  private boolean refreshComment(long authorityId) {
    CommentSnapshot snapshot = snapshotTemplate.execute(status -> {
      List<Map<String, Object>> rows = jdbcTemplate.queryForList(
          "select * from form_resource_authorities where id = ?", authorityId);
      if (rows.isEmpty()) return null;
      Map<String, Object> row = rows.getFirst();
      if (row.get("entry_comment_id") == null || row.get("entry_registered_at") == null
          || !"FORM".equals(row.get("authority"))
          || !RESPONSE_TEMPLATE.equals(row.get("template_key"))) return null;
      long revision = ((Number) row.get("entry_comment_revision")).longValue();
      if (revision <= ((Number) row.get("entry_comment_synced_revision")).longValue()) return null;
      long configId = ((Number) row.get("source_config_id")).longValue();
      long projectId = ((Number) row.get("project_id")).longValue();
      long issueIid = ((Number) row.get("resource_id")).longValue();
      var settings = settingsService.read(configId, projectId);
      if (!settings.delivers() || !ready(row, (String) row.get("source_instance"), projectId, issueIid)) return null;
      GitlabSyncConfig config = configService.getConfigById(configId);
      if (!StringUtils.hasText(config.getWebBaseUrl()) || !StringUtils.hasText(config.getApiToken())) return null;
      return new CommentSnapshot(config, projectId, issueIid,
          ((Number) row.get("entry_comment_id")).longValue(), revision,
          commentBody(row, config, projectId, issueIid, settings.externalBaseUrl()));
    });
    if (snapshot == null) return false;
    writeComment(snapshot.config(), snapshot.projectId(), snapshot.issueIid(), snapshot.commentId(), snapshot.body());
    jdbcTemplate.update("""
        update form_resource_authorities set entry_comment_synced_revision = greatest(entry_comment_synced_revision, ?)
         where id = ? and entry_comment_id = ?
        """, snapshot.revision(), authorityId, snapshot.commentId());
    return true;
  }

  private String commentBody(Map<String, Object> authority, GitlabSyncConfig config,
      long projectId, long issueIid, String externalBaseUrl) {
    String base = stripTrailingSlash(externalBaseUrl);
    return contentService.render(authority,
        entryUrl(base, config, projectId, issueIid, RESPONSE_TEMPLATE),
        entryUrl(base, config, projectId, issueIid, REPLY_TEMPLATE));
  }

  private String entryUrl(
      String base, GitlabSyncConfig config, long projectId, long issueIid, String templateKey) {
    return base
        + ENTRY_PATH
        + "?gitlabBaseUrl="
        + URLEncoder.encode(stripTrailingSlash(config.getWebBaseUrl()), StandardCharsets.UTF_8)
        + "&projectId="
        + projectId
        + "&resourceType=issue&resourceId="
        + issueIid
        + "&templateKey="
        + templateKey;
  }

  private long writeComment(
      GitlabSyncConfig config, long projectId, long issueIid, Long commentId, String body) {
    URI uri =
        URI.create(
            stripTrailingSlash(config.getWebBaseUrl())
                + "/api/v4/projects/"
                + projectId
                + "/issues/"
                + issueIid
                + "/notes" + (commentId == null ? "" : "/" + commentId));
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(properties.getRequestTimeout())
            .header("PRIVATE-TOKEN", config.getApiToken())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .method(commentId == null ? "POST" : "PUT",
                HttpRequest.BodyPublishers.ofString(
                    "body=" + URLEncoder.encode(body, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitLab 评论失败：HTTP " + response.statusCode());
      }
      long id = objectMapper.readTree(response.body()).path("id").asLong(0L);
      if (id <= 0L || (commentId != null && id != commentId)) {
        throw new IllegalStateException("GitLab 评论响应编号无效");
      }
      return id;
    } catch (IOException error) {
      throw new IllegalStateException("GitLab 评论请求失败：" + error.getMessage(), error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitLab 评论请求被中断", error);
    }
  }

  private boolean deliveryConfigured() {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "select exists(select 1 from gitlab_sync_configs c, lateral jsonb_each(c.form_issue_settings) e where e.value @> '{\"enabled\":true,\"linkEnabled\":true}'::jsonb)", Boolean.class));
  }

  private static String stripTrailingSlash(String value) {
    String trimmed = value == null ? "" : value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}
