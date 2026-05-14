package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSystemHookRegistrationStatus;
import com.data.collection.platform.entity.GitlabSystemHookRegistrationStatus.RegisteredGitlabSystemHook;
import com.data.collection.platform.entity.SourceMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabSystemHookRegistrationService {
  private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {};

  private final ConcurrentMap<String, CacheEntry> statusCache = new ConcurrentHashMap<>();
  private final GitlabMirrorProperties properties;
  private final ObjectMapper objectMapper;

  public GitlabSystemHookRegistrationService(GitlabMirrorProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public GitlabSystemHookRegistrationStatus getStatus(GitlabSyncConfig config, String systemHookUrl) {
    String cacheKey = buildCacheKey(config, systemHookUrl);
    CacheEntry cacheEntry = statusCache.get(cacheKey);
    long cacheSeconds = Math.max(5, properties.getSystemHookStatusCacheSeconds());
    if (cacheEntry != null && Duration.between(cacheEntry.loadedAt(), Instant.now()).getSeconds() < cacheSeconds) {
      return cacheEntry.status();
    }

    GitlabSystemHookRegistrationStatus status;
    if (config.getSourceMode() != SourceMode.DOCKER) {
      status = new GitlabSystemHookRegistrationStatus(
          false,
          Boolean.TRUE.equals(config.getSystemHookEnabled()),
          false,
          config.getSystemHookProjectId(),
          systemHookUrl,
          "直连模式需在 GitLab 手动注册 System Hook，平台无法自动检测注册状态",
          List.of());
    } else if (!isSystemHookConfigured(config)) {
      status = new GitlabSystemHookRegistrationStatus(
          true,
          false,
          false,
          null,
          systemHookUrl,
          "请先启用 System Hook 接收并配置密钥",
          List.of());
    } else {
      List<RegisteredGitlabSystemHook> hooks = listHooks(config, systemHookUrl);
      status = new GitlabSystemHookRegistrationStatus(
          true,
          true,
          !hooks.isEmpty(),
          null,
          systemHookUrl,
          hooks.isEmpty() ? "GitLab System Hook 尚未注册" : "GitLab System Hook 已注册",
          hooks);
    }

    statusCache.put(cacheKey, new CacheEntry(Instant.now(), status));
    return status;
  }

  public GitlabSystemHookRegistrationStatus ensureRegistered(GitlabSyncConfig config, String systemHookUrl) {
    if (config.getSourceMode() != SourceMode.DOCKER) {
      throw new BizException("直连模式需在 GitLab 手动注册 System Hook，平台无法自动注册");
    }
    if (!isSystemHookConfigured(config)) {
      throw new BizException("请先启用 System Hook 接收并配置密钥");
    }

    List<String> command = buildDockerExecCommand(
        config,
        systemHookUrl,
        config.getSystemHookSecret(),
        buildEnsureHookScript());
    String output = runCommand(command, "SystemHook_Register");
    try {
      objectMapper.readTree(output);
    } catch (Exception e) {
      throw new BizException("GitLab System Hook registration response could not be parsed: " + output);
    }
    invalidateCache(config, systemHookUrl);
    return getStatus(config, systemHookUrl);
  }

  public void invalidateCache(GitlabSyncConfig config, String systemHookUrl) {
    statusCache.remove(buildCacheKey(config, systemHookUrl));
  }

  private boolean isSystemHookConfigured(GitlabSyncConfig config) {
    return Boolean.TRUE.equals(config.getSystemHookEnabled())
        && config.getSystemHookSecret() != null
        && !config.getSystemHookSecret().isBlank();
  }

  private List<RegisteredGitlabSystemHook> listHooks(GitlabSyncConfig config, String systemHookUrl) {
    List<String> command = buildDockerExecCommand(
        config,
        systemHookUrl,
        config.getSystemHookSecret(),
        buildListHooksScript());
    String output = runCommand(command, "SystemHook_Status");
    try {
      List<Map<String, Object>> rows = objectMapper.readValue(output, LIST_TYPE);
      List<RegisteredGitlabSystemHook> result = new ArrayList<>(rows.size());
      for (Map<String, Object> row : rows) {
        result.add(new RegisteredGitlabSystemHook(
            asLong(row.get("id")),
            asString(row.get("url")),
            asBoolean(row.get("issues_events")),
            asBoolean(row.get("merge_requests_events")),
            asBoolean(row.get("note_events")),
            asBoolean(row.get("pipeline_events")),
            asBoolean(row.get("job_events")),
            asBoolean(row.get("releases_events")),
            asBoolean(row.get("enable_ssl_verification"))));
      }
      return result;
    } catch (Exception e) {
      throw new BizException("GitLab System Hook status parse failed: " + output);
    }
  }

  private List<String> buildDockerExecCommand(
      GitlabSyncConfig config,
      String systemHookUrl,
      String systemHookSecret,
      String rubyScript) {
    return List.of(
        properties.getDockerCommand(),
        "exec",
        "-e",
        "SYSTEM_HOOK_URL=" + systemHookUrl,
        "-e",
        "SYSTEM_HOOK_SECRET=" + (systemHookSecret == null ? "" : systemHookSecret),
        config.getDockerContainerName(),
        "gitlab-rails",
        "runner",
        rubyScript);
  }

  private String runCommand(List<String> command, String actionName) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
               new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.isBlank()) {
            output.append(line.trim());
          }
        }
      }
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new BizException("GitLab System Hook command failed: " + output);
      }
      return output.toString().trim();
    } catch (BizException e) {
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action(actionName)) {
        log.error("GitLab system hook operation failed", e);
      }
      throw e;
    } catch (Exception e) {
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action(actionName)) {
        log.error("GitLab system hook operation failed", e);
      }
      throw new BizException("GitLab System Hook operation failed: " + e.getMessage());
    }
  }

  private String buildListHooksScript() {
    return """
        system_hook_url = ENV.fetch('SYSTEM_HOOK_URL')
        hooks = SystemHook.all.select { |h| h.url == system_hook_url }.map do |h|
          {
            id: h.id,
            url: h.url,
            issues_events: h.respond_to?(:issues_events) ? h.issues_events : false,
            merge_requests_events: h.respond_to?(:merge_requests_events) ? h.merge_requests_events : false,
            note_events: h.respond_to?(:note_events) ? h.note_events : false,
            pipeline_events: h.respond_to?(:pipeline_events) ? h.pipeline_events : false,
            job_events: h.respond_to?(:job_events) ? h.job_events : false,
            releases_events: h.respond_to?(:releases_events) ? h.releases_events : false,
            enable_ssl_verification: h.respond_to?(:enable_ssl_verification) ? h.enable_ssl_verification : false
          }
        end
        puts hooks.to_json
        """;
  }

  private String buildEnsureHookScript() {
    return """
        system_hook_url = ENV.fetch('SYSTEM_HOOK_URL')
        system_hook_secret = ENV.fetch('SYSTEM_HOOK_SECRET', '')
        hook = SystemHook.all.detect { |existing_hook| existing_hook.url == system_hook_url }
        hook ||= SystemHook.new(url: system_hook_url)
        hook.push_events = true if hook.respond_to?(:push_events=)
        hook.tag_push_events = true if hook.respond_to?(:tag_push_events=)
        hook.merge_requests_events = true if hook.respond_to?(:merge_requests_events=)
        hook.repository_update_events = true if hook.respond_to?(:repository_update_events=)
        hook.enable_ssl_verification = false if hook.respond_to?(:enable_ssl_verification=)
        hook.token = system_hook_secret
        hook.save!
        puts({
          id: hook.id,
          url: hook.url,
          issues_events: hook.respond_to?(:issues_events) ? hook.issues_events : false,
          merge_requests_events: hook.respond_to?(:merge_requests_events) ? hook.merge_requests_events : false,
          note_events: hook.respond_to?(:note_events) ? hook.note_events : false,
          pipeline_events: hook.respond_to?(:pipeline_events) ? hook.pipeline_events : false,
          job_events: hook.respond_to?(:job_events) ? hook.job_events : false,
          releases_events: hook.respond_to?(:releases_events) ? hook.releases_events : false,
          enable_ssl_verification: hook.respond_to?(:enable_ssl_verification) ? hook.enable_ssl_verification : false
        }.to_json)
        """;
  }

  private Long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return value == null ? null : Long.parseLong(String.valueOf(value));
  }

  private String asString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private boolean asBoolean(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private String buildCacheKey(GitlabSyncConfig config, String systemHookUrl) {
    return String.join(
        "|",
        Objects.toString(config.getSourceMode(), ""),
        Objects.toString(config.getDockerContainerName(), ""),
        Objects.toString(config.getSystemHookEnabled(), ""),
        Objects.toString(systemHookUrl, ""));
  }

  private record CacheEntry(Instant loadedAt, GitlabSystemHookRegistrationStatus status) {
  }
}
