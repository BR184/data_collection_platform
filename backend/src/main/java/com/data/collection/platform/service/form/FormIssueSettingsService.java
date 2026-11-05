package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 响应与回复按来源、项目共用接入配置；已登记资源的权威不随配置变化。 */
@Service
public class FormIssueSettingsService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Clock clock;

  public FormIssueSettingsService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 持久设置；revision用于并发管理员保存，空启用时间仅用于尚未配置。 */
  public record Settings(boolean enabled, Instant enabledAt, boolean linkEnabled,
                         String externalBaseUrl, long revision) {
    public boolean delivers() { return enabled && linkEnabled && !externalBaseUrl.isBlank(); }
  }

  /** 页面读取设置及双模板就绪情况，配置立即生效而不是随结构发布。 */
  public record View(Settings settings, boolean ready) {}

  /** 保存输入；enabledAt为空且开启时使用服务器当前时间。 */
  public record SaveRequest(boolean enabled, Instant enabledAt, boolean linkEnabled,
                            String externalBaseUrl, long expectedRevision) {}

  /** 获取指定来源项目的配置，未配置时关闭。调用方需要来源身份已经校验。 */
  public Settings read(long configId, long projectId) {
    List<String> values = jdbc.queryForList(
        "select (form_issue_settings -> ?)::text from gitlab_sync_configs where id = ?",
        String.class, Long.toString(projectId), configId);
    return decode(values.isEmpty() ? null : values.getFirst());
  }

  /** 登记或发送事务内固定来源配置；保存关闭等待在途短事务完成后返回。 */
  public Settings lockForUse(long configId, long projectId) {
    List<String> values = jdbc.queryForList(
        "select (form_issue_settings -> ?)::text from gitlab_sync_configs where id = ? for share",
        String.class, Long.toString(projectId), configId);
    return decode(values.isEmpty() ? null : values.getFirst());
  }

  /** 管理端按已绑定的范围读取该项目共用配置。 */
  public View view(long scopeId, long projectId) {
    long configId = requireBinding(scopeId, projectId);
    return new View(read(configId, projectId), ready(configId, projectId));
  }

  /** 单事务CAS保存；只改变后续接入/投放，不改写已有权威、表单或事实。 */
  @Transactional
  public View save(long scopeId, long projectId, SaveRequest request) {
    long configId = requireBinding(scopeId, projectId);
    jdbc.queryForObject("select id from gitlab_sync_configs where id = ? for update", Long.class, configId);
    Settings old = read(configId, projectId);
    if (old.revision() != request.expectedRevision()) {
      throw new BizException(ResultCode.CONFLICT, "配置已被其他管理员修改，请重新加载后保存");
    }
    boolean ready = ready(configId, projectId);
    if (request.enabled() && !ready) {
      throw new BizException(ResultCode.BAD_REQUEST, "请先发布此项目的问题响应和问题回复表单");
    }
    String base = normalizeBaseUrl(request.externalBaseUrl());
    if (request.enabled() && request.linkEnabled() && base.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "请填写开发人员可以访问的平台地址");
    }
    Settings next = new Settings(request.enabled(),
        request.enabled() && request.enabledAt() == null ? clock.instant() : request.enabledAt(),
        request.enabled() && request.linkEnabled(), base, old.revision() + 1);
    var stored = mapper.createObjectNode().put("enabled", next.enabled()).put("linkEnabled", next.linkEnabled())
        .put("externalBaseUrl", next.externalBaseUrl()).put("revision", next.revision());
    if (next.enabledAt() == null) stored.putNull("enabledAt");
    else stored.put("enabledAt", next.enabledAt().toString());
    jdbc.update("update gitlab_sync_configs set form_issue_settings = jsonb_set(form_issue_settings, array[?], ?::jsonb, true) where id = ?",
        Long.toString(projectId), stored.toString(), configId);
    return new View(next, ready);
  }

  private long requireBinding(long scopeId, long projectId) {
    List<Long> ids = jdbc.queryForList("""
        select source_config_id from form_template_scopes
         where id = ? and source_instance = 'default'
           and template_key in ('issue_response', 'issue_reply')
           and project_ids @> jsonb_build_array(?::bigint)
           and source_config_id is not null
        """, Long.class, scopeId, projectId);
    if (ids.size() != 1) { throw new BizException(ResultCode.BAD_REQUEST, "请先绑定CC项目的问题响应或问题回复范围"); }
    return ids.getFirst();
  }

  private boolean ready(long configId, long projectId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        select count(*) = 2 and count(distinct s.template_key) = 2
           and bool_and(coalesce(s.status = 'ACTIVE' and v.version_status = 'PUBLISHED'
                        and v.storage_kind = 'RECORDS', false))
          from form_template_scopes s left join form_template_versions v on v.id = s.current_version_id
         where s.source_config_id = ? and s.source_instance = 'default'
           and s.project_ids @> jsonb_build_array(?::bigint)
           and s.template_key in ('issue_response', 'issue_reply')
           and s.status in ('ACTIVE', 'RETIRED')
        """, Boolean.class, configId, projectId));
  }

  private Settings decode(String value) {
    if (value == null) { return new Settings(false, null, false, "", 0); }
    try {
      JsonNode node = mapper.readTree(value);
      return new Settings(node.path("enabled").asBoolean(),
          node.path("enabledAt").isTextual() ? Instant.parse(node.path("enabledAt").asText()) : null,
          node.path("linkEnabled").asBoolean(), node.path("externalBaseUrl").asText(""),
          node.path("revision").asLong());
    } catch (Exception error) { throw new IllegalStateException("表单接入配置无效", error); }
  }

  private String normalizeBaseUrl(String value) {
    if (value == null || value.isBlank()) { return ""; }
    try {
      URI uri = URI.create(value.trim());
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
        throw new IllegalArgumentException();
      }
      return value.trim().replaceAll("/+$", "");
    } catch (IllegalArgumentException error) { throw new BizException(ResultCode.BAD_REQUEST, "平台地址应为http或https地址，不包含页面路径"); }
  }
}
