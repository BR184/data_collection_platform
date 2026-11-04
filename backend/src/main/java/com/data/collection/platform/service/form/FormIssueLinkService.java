package com.data.collection.platform.service.form;

import com.data.collection.platform.config.FormIssueLinkProperties;
import com.data.collection.platform.config.PlatformAsyncConfiguration;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabResourceRootResolver;
import com.data.collection.platform.service.read.FactReadVisibility;
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
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * CCProduct 议题表单就绪投放：发布事务内登记，提交后发一条双入口评论。
 *
 * <p>投放状态只记在响应模板的 {@code form_resource_authorities} 行：{@code entry_registered_at}
 * 表示已就绪登记，{@code entry_comment_id} 表示评论已发送成功。发送用资源行锁复查编号，
 * 并发触发只会发送一条评论；HTTP 明确失败时编号保持为空，由补偿调度下一轮重试。
 * 默认关闭；未开启、缺少平台对外地址或来源缺少 Web 地址/令牌时不登记、不发送。
 */
@Service
@Slf4j
public class FormIssueLinkService {

  static final String RESPONSE_TEMPLATE = FormTemplateSpecCatalog.ISSUE_RESPONSE;
  static final String REPLY_TEMPLATE = FormTemplateSpecCatalog.ISSUE_REPLY;
  static final long TARGET_PROJECT_ID = CustomerIssueFactQueryService.CC_PRODUCT_PROJECT_ID;
  private static final String ENTRY_PATH = "/external/forms";
  private static final String COMMENT_HEADING = "【表单填写入口】本议题已启用平台表单，请使用平台账号分别填写：";

  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;
  private final FormIssueLinkProperties properties;
  private final ObjectMapper objectMapper;
  private final Executor platformAsyncExecutor;
  private final TransactionTemplate transactionTemplate;
  private final HttpClient httpClient;

  public FormIssueLinkService(
      JdbcTemplate jdbcTemplate,
      GitlabConfigService configService,
      FormIssueLinkProperties properties,
      ObjectMapper objectMapper,
      @Qualifier(PlatformAsyncConfiguration.PLATFORM_ASYNC_EXECUTOR) Executor platformAsyncExecutor,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
    this.properties = properties;
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
   * @param publicationId 正在提交的 ISSUE 发布身份
   * @return 本次登记的权威行数
   */
  public int registerPublishedIssues(UUID publicationId) {
    if (!deliveryConfigured()) {
      return 0;
    }
    return jdbcTemplate.update(
        """
        update form_resource_authorities a
           set entry_registered_at = current_timestamp,
               updated_at = current_timestamp
         where a.resource_type = 'issue'
           and a.project_id = ?
           and a.template_key = ?
           and a.authority = 'FORM'
           and a.entry_registered_at is null
           and exists (
             select 1
               from read_publication_fact_roots r
               join ods_gitlab_issues i
                 on i.id = r.root_id and i.project_id = r.project_id
              where r.publication_id = ?
                and r.fact_type = 'ISSUE'
                and r.operation = 'UPSERT'
                and r.source_instance = a.source_instance
                and i.project_id = a.project_id
                and i.iid = a.resource_id)
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
        TARGET_PROJECT_ID,
        RESPONSE_TEMPLATE,
        publicationId,
        REPLY_TEMPLATE);
  }

  /** 提交发布后由平台异步执行器补发；不阻塞发布线程，失败由下一轮调度重试。 */
  public void dispatchPendingAsync() {
    if (!deliveryConfigured()) {
      return;
    }
    try {
      platformAsyncExecutor.execute(() -> dispatchPending(properties.getBatchSize()));
    } catch (RejectedExecutionException error) {
      log.warn("表单就绪投放任务未能提交，等待补偿调度下一轮：{}", error.getMessage());
    }
  }

  /**
   * 处理一批已登记且评论编号为空的记录。
   *
   * @param limit 本次最多处理条数
   * @return 本次成功发出评论的数量
   */
  public int dispatchPending(int limit) {
    if (!deliveryConfigured() || limit <= 0) {
      return 0;
    }
    List<Long> ids =
        jdbcTemplate.queryForList(
            """
            select id from form_resource_authorities
             where resource_type = 'issue' and project_id = ? and template_key = ?
               and entry_registered_at is not null and entry_comment_id is null
               and authority = 'FORM'
             order by entry_registered_at, id
             limit ?
            """,
            Long.class,
            TARGET_PROJECT_ID,
            RESPONSE_TEMPLATE,
            limit);
    int sent = 0;
    for (Long id : ids) {
      try {
        if (sendOne(id)) {
          sent++;
        }
      } catch (RuntimeException error) {
        log.warn("表单就绪投放失败，保留空评论编号等待下一轮，authorityId={}：{}", id, error.getMessage());
      }
    }
    return sent;
  }

  /**
   * 独立事务发送一条评论：锁定响应权威行、复查编号与就绪状态、带超时发送、保存评论编号。
   *
   * @param authorityId 响应模板权威行 id
   * @return 本次是否新发出评论
   */
  public boolean sendOne(long authorityId) {
    if (!deliveryConfigured()) {
      return false;
    }
    Boolean sent =
        transactionTemplate.execute(
            status -> {
              List<Map<String, Object>> rows =
                  jdbcTemplate.queryForList(
                      """
                      select id, source_config_id, source_instance, project_id, resource_id,
                             scope_id, template_key, authority, entry_registered_at,
                             entry_comment_id
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
                  || !RESPONSE_TEMPLATE.equals(row.get("template_key"))
                  || ((Number) row.get("project_id")).longValue() != TARGET_PROJECT_ID) {
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
                  postComment(config, projectId, issueIid, commentBody(config, projectId, issueIid));
              jdbcTemplate.update(
                  """
                  update form_resource_authorities
                     set entry_comment_id = ?, updated_at = current_timestamp
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
                + FactReadVisibility.liveRevisionPredicate("f")
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

  /** 一条评论同时给出响应与回复两个填写入口。 */
  String commentBody(GitlabSyncConfig config, long projectId, long issueIid) {
    String base = stripTrailingSlash(properties.getExternalBaseUrl());
    return COMMENT_HEADING
        + "\n\n- 问题响应："
        + entryUrl(base, config, projectId, issueIid, RESPONSE_TEMPLATE)
        + "\n- 问题回复："
        + entryUrl(base, config, projectId, issueIid, REPLY_TEMPLATE);
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

  private long postComment(
      GitlabSyncConfig config, long projectId, long issueIid, String body) {
    URI uri =
        URI.create(
            stripTrailingSlash(config.getWebBaseUrl())
                + "/api/v4/projects/"
                + projectId
                + "/issues/"
                + issueIid
                + "/notes");
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(properties.getRequestTimeout())
            .header("PRIVATE-TOKEN", config.getApiToken())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "body=" + URLEncoder.encode(body, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitLab 评论失败：HTTP " + response.statusCode() + " " + response.body());
      }
      long id = objectMapper.readTree(response.body()).path("id").asLong(0L);
      if (id <= 0L) {
        throw new IllegalStateException("GitLab 评论响应缺少编号：" + response.body());
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
    return properties.isEnabled() && StringUtils.hasText(properties.getExternalBaseUrl());
  }

  private static String stripTrailingSlash(String value) {
    String trimmed = value == null ? "" : value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}
