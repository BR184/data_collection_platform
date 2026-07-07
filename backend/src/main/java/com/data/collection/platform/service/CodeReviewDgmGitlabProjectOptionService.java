package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CodeReviewDgmGitlabProjectOptionResponse;
import com.data.collection.platform.entity.CodeReviewDgmGitlabProjectSourceResponse;
import com.data.collection.platform.entity.CodeReviewDgmGitlabProjectSourceSaveRequest;
import com.data.collection.platform.entity.CodeReviewDgmGitlabProjectSyncResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeConnectionTestResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CodeReviewDgmGitlabProjectOptionService {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 1000;
  private static final String SOURCE_INSTANCE = "dgm";
  private static final String SETTINGS_SQL = """
      select enabled,
             gitlab_base_url,
             access_token,
             group_path,
             include_subgroups,
             include_archived,
             sync_interval_minutes,
             last_sync_status,
             last_sync_message,
             last_sync_record_count,
             last_sync_started_at,
             last_sync_finished_at,
             updated_at
        from code_review_dgm_gitlab_project_source_settings
       where id = 1
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final PageRecordSnapshotService pageRecordSnapshotService;
  private final HttpClient httpClient;
  private final AtomicBoolean syncRunning = new AtomicBoolean(false);

  public CodeReviewDgmGitlabProjectOptionService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      PageRecordSnapshotService pageRecordSnapshotService) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
    this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
  }

  @Scheduled(fixedDelayString = "${platform.code-review.dgm-project-options.scheduler-delay-ms:60000}", initialDelayString = "${platform.code-review.dgm-project-options.initial-delay-ms:60000}")
  public void syncScheduled() {
    DgmGitlabProjectSourceSettings settings = loadSettings();
    if (!settings.enabled() || !isSyncDue(settings)) {
      return;
    }
    try {
      syncNow(settings);
    } catch (RuntimeException error) {
      log.warn("DGM GitLab project options scheduled sync failed", error);
    }
  }

  public CodeReviewDgmGitlabProjectSourceResponse getSettings() {
    return toResponse(loadSettings());
  }

  public CodeReviewDgmGitlabProjectSourceResponse saveSettings(
      CodeReviewDgmGitlabProjectSourceSaveRequest request) {
    DgmGitlabProjectSourceSettings current = loadSettings();
    DgmGitlabProjectSourceSettings normalized = normalize(request, current);
    jdbcTemplate.update("""
        update code_review_dgm_gitlab_project_source_settings
           set enabled = ?,
               gitlab_base_url = ?,
               access_token = ?,
               group_path = ?,
               include_subgroups = ?,
               include_archived = ?,
               sync_interval_minutes = ?,
               updated_at = current_timestamp
         where id = 1
        """,
        normalized.enabled(),
        normalized.gitlabBaseUrl(),
        normalized.accessToken(),
        normalized.groupPath(),
        normalized.includeSubgroups(),
        normalized.includeArchived(),
        normalized.syncIntervalMinutes());
    pageRecordSnapshotService.invalidatePage(CodeReviewIllegalRecordService.WORKSPACE_KEY);
    return getSettings();
  }

  public CodeReviewMatchModeConnectionTestResponse testConnection(
      CodeReviewDgmGitlabProjectSourceSaveRequest request) {
    DgmGitlabProjectSourceSettings settings = normalize(request, loadSettings());
    validateReady(settings);
    try {
      JsonNode response = fetchProjectsPage(settings, 1, 1);
      int count = response.isArray() ? response.size() : 0;
      return new CodeReviewMatchModeConnectionTestResponse(
          true,
          count > 0 ? "DGM GitLab 项目接口连接成功" : "DGM GitLab 项目接口连接成功，但当前 Group 暂无项目",
          count);
    } catch (RuntimeException error) {
      return new CodeReviewMatchModeConnectionTestResponse(
          false,
          rootMessage(error, "DGM GitLab 项目接口连接失败"),
          0);
    }
  }

  public CodeReviewDgmGitlabProjectSyncResponse syncNow() {
    DgmGitlabProjectSourceSettings settings = loadSettings();
    return syncNow(settings);
  }

  private CodeReviewDgmGitlabProjectSyncResponse syncNow(DgmGitlabProjectSourceSettings settings) {
    validateReady(settings);
    if (!syncRunning.compareAndSet(false, true)) {
      throw new BizException("DGM GitLab 项目候选正在同步，请稍后再试");
    }
    LocalDateTime startedAt = LocalDateTime.now();
    markSyncStarted(startedAt);
    try {
      int count = syncProjects(settings);
      LocalDateTime finishedAt = LocalDateTime.now();
      markSyncFinished("SUCCESS", "DGM GitLab 项目候选同步成功", count, finishedAt);
      pageRecordSnapshotService.invalidatePage(CodeReviewIllegalRecordService.WORKSPACE_KEY);
      return new CodeReviewDgmGitlabProjectSyncResponse(
          true,
          "SUCCESS",
          "DGM GitLab 项目候选同步成功",
          count,
          startedAt,
          finishedAt);
    } catch (RuntimeException error) {
      LocalDateTime finishedAt = LocalDateTime.now();
      String message = rootMessage(error, "DGM GitLab 项目候选同步失败");
      markSyncFinished("FAILED", message, 0, finishedAt);
      throw new BizException(message);
    } finally {
      syncRunning.set(false);
    }
  }

  public List<CodeReviewDgmGitlabProjectOptionResponse> listOptions() {
    return jdbcTemplate.query("""
        select gitlab_project_id,
               name,
               path,
               path_with_namespace,
               web_url,
               namespace_name,
               namespace_full_path,
               archived,
               visibility,
               active
          from code_review_dgm_project_options
         where source_instance = ?
           and active = true
         order by lower(name), gitlab_project_id
        """,
        (rs, rowNum) ->
            new CodeReviewDgmGitlabProjectOptionResponse(
                rs.getLong("gitlab_project_id"),
                rs.getString("name"),
                rs.getString("path"),
                rs.getString("path_with_namespace"),
                rs.getString("web_url"),
                rs.getString("namespace_name"),
                rs.getString("namespace_full_path"),
                rs.getBoolean("archived"),
                rs.getString("visibility"),
                rs.getBoolean("active")),
        SOURCE_INSTANCE);
  }

  public List<String> listProjectNames() {
    if (!loadSettings().enabled()) {
      return List.of();
    }
    //兼容模式-MatchMode：DGM 项目下拉候选来自 GitLab API 本地缓存，供兼容读源和正式读源共同使用；
    //该表不参与老平台 MySQL 兼容表、merge_request_fact 或 webhook 数据写入。
    return jdbcTemplate.query(
        """
        select name
          from code_review_dgm_project_options
         where source_instance = ?
           and active = true
           and nullif(btrim(coalesce(name, '')), '') is not null
         group by name
         order by lower(name)
        """,
        (rs, rowNum) -> rs.getString("name"),
        SOURCE_INSTANCE);
  }

  private int syncProjects(DgmGitlabProjectSourceSettings settings) {
    List<GitlabProject> projects = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode response = fetchProjectsPage(settings, page, PAGE_SIZE);
      if (!response.isArray()) {
        throw new BizException("DGM GitLab 项目接口返回格式不正确");
      }
      if (response.isEmpty()) {
        break;
      }
      for (JsonNode item : response) {
        projects.add(toProject(item));
      }
      if (response.size() < PAGE_SIZE) {
        break;
      }
    }
    jdbcTemplate.update(
        "update code_review_dgm_project_options set active = false, updated_at = current_timestamp where source_instance = ?",
        SOURCE_INSTANCE);
    for (GitlabProject project : projects) {
      upsertProject(project);
    }
    return projects.size();
  }

  private JsonNode fetchProjectsPage(
      DgmGitlabProjectSourceSettings settings,
      int page,
      int pageSize) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(projectsUri(settings, page, pageSize))
              .timeout(REQUEST_TIMEOUT)
              .header("PRIVATE-TOKEN", settings.accessToken())
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new BizException("GitLab API 返回 " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (IOException error) {
      throw new BizException("GitLab API 响应解析失败");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new BizException("GitLab API 请求被中断");
    }
  }

  private URI projectsUri(DgmGitlabProjectSourceSettings settings, int page, int pageSize) {
    String baseUrl = settings.gitlabBaseUrl();
    String encodedGroup = encodePathSegment(settings.groupPath());
    String url =
        baseUrl
            + "/api/v4/groups/"
            + encodedGroup
            + "/projects?include_subgroups="
            + settings.includeSubgroups()
            + "&archived="
            + settings.includeArchived()
            + "&per_page="
            + pageSize
            + "&page="
            + page;
    return URI.create(url);
  }

  private String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private GitlabProject toProject(JsonNode node) {
    JsonNode namespace = node.path("namespace");
    long projectId = node.path("id").asLong(0);
    String name = text(node.path("name").asText(null));
    if (projectId <= 0 || !StringUtils.hasText(name)) {
      throw new BizException("GitLab 项目返回缺少 id 或 name");
    }
    return new GitlabProject(
        projectId,
        name,
        text(node.path("path").asText(null)),
        text(node.path("path_with_namespace").asText(null)),
        text(node.path("web_url").asText(null)),
        text(namespace.path("name").asText(null)),
        text(namespace.path("full_path").asText(null)),
        node.path("archived").asBoolean(false),
        text(node.path("visibility").asText(null)));
  }

  private void upsertProject(GitlabProject project) {
    jdbcTemplate.update("""
        insert into code_review_dgm_project_options(
            source_instance,
            gitlab_project_id,
            name,
            path,
            path_with_namespace,
            web_url,
            namespace_name,
            namespace_full_path,
            archived,
            visibility,
            active,
            last_seen_at,
            updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, current_timestamp, current_timestamp)
        on conflict (source_instance, gitlab_project_id)
        do update set
            name = excluded.name,
            path = excluded.path,
            path_with_namespace = excluded.path_with_namespace,
            web_url = excluded.web_url,
            namespace_name = excluded.namespace_name,
            namespace_full_path = excluded.namespace_full_path,
            archived = excluded.archived,
            visibility = excluded.visibility,
            active = true,
            last_seen_at = current_timestamp,
            updated_at = current_timestamp
        """,
        SOURCE_INSTANCE,
        project.gitlabProjectId(),
        project.name(),
        project.path(),
        project.pathWithNamespace(),
        project.webUrl(),
        project.namespaceName(),
        project.namespaceFullPath(),
        project.archived(),
        project.visibility());
  }

  private void markSyncStarted(LocalDateTime startedAt) {
    jdbcTemplate.update("""
        update code_review_dgm_gitlab_project_source_settings
           set last_sync_status = 'RUNNING',
               last_sync_message = 'DGM GitLab 项目候选同步中',
               last_sync_started_at = ?,
               last_sync_finished_at = null,
               updated_at = current_timestamp
         where id = 1
        """,
        startedAt);
  }

  private void markSyncFinished(String status, String message, long recordCount, LocalDateTime finishedAt) {
    jdbcTemplate.update("""
        update code_review_dgm_gitlab_project_source_settings
           set last_sync_status = ?,
               last_sync_message = ?,
               last_sync_record_count = ?,
               last_sync_finished_at = ?,
               updated_at = current_timestamp
         where id = 1
        """,
        status,
        message,
        recordCount,
        finishedAt);
  }

  private DgmGitlabProjectSourceSettings loadSettings() {
    return jdbcTemplate.queryForObject(SETTINGS_SQL, (rs, rowNum) ->
        new DgmGitlabProjectSourceSettings(
            rs.getBoolean("enabled"),
            text(rs.getString("gitlab_base_url")),
            rs.getString("access_token") == null ? "" : rs.getString("access_token"),
            text(rs.getString("group_path")),
            rs.getBoolean("include_subgroups"),
            rs.getBoolean("include_archived"),
            rs.getInt("sync_interval_minutes"),
            text(rs.getString("last_sync_status")),
            text(rs.getString("last_sync_message")),
            rs.getLong("last_sync_record_count"),
            rs.getTimestamp("last_sync_started_at") == null ? null : rs.getTimestamp("last_sync_started_at").toLocalDateTime(),
            rs.getTimestamp("last_sync_finished_at") == null ? null : rs.getTimestamp("last_sync_finished_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime()));
  }

  private DgmGitlabProjectSourceSettings normalize(
      CodeReviewDgmGitlabProjectSourceSaveRequest request,
      DgmGitlabProjectSourceSettings current) {
    if (request == null) {
      return current;
    }
    boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
    String baseUrl = normalizeOptionalBaseUrl(defaultText(request.gitlabBaseUrl(), current.gitlabBaseUrl(), ""));
    String groupPath = defaultText(request.groupPath(), current.groupPath(), "");
    if (enabled && !StringUtils.hasText(groupPath)) {
      throw new BizException("DGM GitLab Group 不能为空");
    }
    String token = resolveSecret(request.accessToken(), current.accessToken());
    int syncInterval =
        request.syncIntervalMinutes() == null ? current.syncIntervalMinutes() : request.syncIntervalMinutes();
    if (syncInterval < 1 || syncInterval > 10080) {
      throw new BizException("DGM GitLab 项目同步间隔必须在 1 到 10080 分钟之间");
    }
    return new DgmGitlabProjectSourceSettings(
        enabled,
        baseUrl,
        token,
        groupPath.trim(),
        request.includeSubgroups() == null ? current.includeSubgroups() : request.includeSubgroups(),
        request.includeArchived() == null ? current.includeArchived() : request.includeArchived(),
        syncInterval,
        current.lastSyncStatus(),
        current.lastSyncMessage(),
        current.lastSyncRecordCount(),
        current.lastSyncStartedAt(),
        current.lastSyncFinishedAt(),
        current.updatedAt());
  }

  private void validateReady(DgmGitlabProjectSourceSettings settings) {
    if (!StringUtils.hasText(settings.gitlabBaseUrl())
        || !StringUtils.hasText(settings.groupPath())
        || !StringUtils.hasText(settings.accessToken())) {
      throw new BizException("DGM GitLab 项目下拉数据源配置不完整");
    }
  }

  private CodeReviewDgmGitlabProjectSourceResponse toResponse(DgmGitlabProjectSourceSettings settings) {
    return new CodeReviewDgmGitlabProjectSourceResponse(
        settings.enabled(),
        settings.gitlabBaseUrl(),
        StringUtils.hasText(settings.accessToken()),
        settings.groupPath(),
        settings.includeSubgroups(),
        settings.includeArchived(),
        settings.syncIntervalMinutes(),
        settings.lastSyncStatus() == null ? "IDLE" : settings.lastSyncStatus(),
        settings.lastSyncMessage(),
        settings.lastSyncRecordCount(),
        settings.lastSyncStartedAt(),
        settings.lastSyncFinishedAt(),
        settings.updatedAt());
  }

  private String normalizeBaseUrl(String baseUrl) {
    if (!StringUtils.hasText(baseUrl)) {
      throw new BizException("DGM GitLab 地址不能为空");
    }
    String normalized = baseUrl.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    URI uri = URI.create(normalized);
    if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
      throw new BizException("DGM GitLab 地址必须包含协议和主机");
    }
    if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
      throw new BizException("DGM GitLab 地址只支持 http 或 https");
    }
    return normalized;
  }

  private String normalizeOptionalBaseUrl(String baseUrl) {
    if (!StringUtils.hasText(baseUrl)) {
      return "";
    }
    try {
      return normalizeBaseUrl(baseUrl);
    } catch (IllegalArgumentException error) {
      throw new BizException("DGM GitLab 地址格式不正确");
    }
  }

  private boolean isSyncDue(DgmGitlabProjectSourceSettings settings) {
    LocalDateTime finishedAt = settings.lastSyncFinishedAt();
    if (finishedAt == null) {
      return true;
    }
    return finishedAt
        .plusMinutes(Math.max(1, settings.syncIntervalMinutes()))
        .isBefore(LocalDateTime.now());
  }

  private String defaultText(String candidate, String current, String fallback) {
    if (StringUtils.hasText(candidate)) {
      return candidate.trim();
    }
    if (StringUtils.hasText(current)) {
      return current.trim();
    }
    return fallback;
  }

  private String resolveSecret(String candidate, String current) {
    return candidate == null || candidate.isBlank() ? current : candidate.trim();
  }

  private String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private String rootMessage(Throwable error, String fallback) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return StringUtils.hasText(current.getMessage()) ? current.getMessage() : fallback;
  }

  private record DgmGitlabProjectSourceSettings(
      boolean enabled,
      String gitlabBaseUrl,
      String accessToken,
      String groupPath,
      boolean includeSubgroups,
      boolean includeArchived,
      int syncIntervalMinutes,
      String lastSyncStatus,
      String lastSyncMessage,
      long lastSyncRecordCount,
      LocalDateTime lastSyncStartedAt,
      LocalDateTime lastSyncFinishedAt,
      LocalDateTime updatedAt) {
  }

  private record GitlabProject(
      long gitlabProjectId,
      String name,
      String path,
      String pathWithNamespace,
      String webUrl,
      String namespaceName,
      String namespaceFullPath,
      boolean archived,
      String visibility) {
  }
}
