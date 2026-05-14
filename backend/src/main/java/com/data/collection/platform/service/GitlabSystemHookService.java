package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabHookEvent;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSystemHookEvent;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.mapper.GitlabHookEventMapper;
import com.data.collection.platform.mapper.GitlabSystemHookEventMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabSystemHookService {
  private final GitlabSystemHookEventMapper systemHookEventMapper;
  private final GitlabHookEventMapper hookEventMapper;
  private final GitlabConfigService configService;
  private final GitlabSystemHookAsyncDispatchService asyncDispatchService;
  private final JsonUtils jsonUtils;
  private final GitlabMirrorProperties properties;

  public GitlabSystemHookService(
      GitlabSystemHookEventMapper systemHookEventMapper,
      GitlabHookEventMapper hookEventMapper,
      GitlabConfigService configService,
      GitlabSystemHookAsyncDispatchService asyncDispatchService,
      JsonUtils jsonUtils,
      GitlabMirrorProperties properties) {
    this.systemHookEventMapper = systemHookEventMapper;
    this.hookEventMapper = hookEventMapper;
    this.configService = configService;
    this.asyncDispatchService = asyncDispatchService;
    this.jsonUtils = jsonUtils;
    this.properties = properties;
  }

  public void accept(String eventType, Map<String, Object> payload, String secret) {
    GitlabSyncConfig config = configService.getConfigForSystemHook(secret);
    String effectiveEventType = eventType == null || eventType.isBlank()
        ? String.valueOf(payload.getOrDefault("object_kind", "system_hook"))
        : eventType;
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, SyncTriggerType.WEBHOOK.name());
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("SystemHook_Received")) {
      log.info(
          "System Hook received, eventType={}, projectId={}, objectKind={}",
          effectiveEventType,
          extractProjectId(payload),
          payload.get("object_kind"));
    }
    if (config.getWebhookSecret() != null && !config.getWebhookSecret().isBlank()) {
      if (!secretMatches(config.getWebhookSecret(), secret)) {
        try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, SyncTriggerType.WEBHOOK.name());
             GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("SystemHook_Received")) {
          log.warn("System Hook rejected because secret validation failed, eventType={}", effectiveEventType);
        }
        throw new BizException("System Hook 密钥校验失败");
      }
    }

    String payloadJson = jsonUtils.toJson(payload);
    GitlabSystemHookEvent event = new GitlabSystemHookEvent();
    event.setConfigId(config.getId());
    event.setEventType(effectiveEventType);
    event.setProjectId(extractProjectId(payload));
    event.setObjectKind(String.valueOf(payload.get("object_kind")));
    event.setPayload(payloadJson);
    event.setProcessed(false);
    event.setReceivedAt(LocalDateTime.now());
    systemHookEventMapper.insert(event);
    GitlabHookEvent hookEvent = createHookEvent(config, effectiveEventType, payload, payloadJson, event.getReceivedAt());
    GitlabHookEvent coalescedEvent = coalesceRecentHookEvent(hookEvent);
    if (coalescedEvent != null) {
      log.info(
          "System Hook event coalesced, configId={}, dedupeKey={}, coalescedCount={}",
          config.getId(),
          coalescedEvent.getDedupeKey(),
          coalescedEvent.getCoalescedCount());
      return;
    }

    hookEventMapper.insert(hookEvent);
    try {
      asyncDispatchService.accept(config, effectiveEventType, payload);
      hookEvent.setProcessedAt(LocalDateTime.now());
      hookEventMapper.updateById(hookEvent);
    } catch (RuntimeException e) {
      hookEvent.setStatus("ERROR");
      hookEvent.setProcessedAt(LocalDateTime.now());
      hookEventMapper.updateById(hookEvent);
      throw e;
    }
  }

  private GitlabHookEvent coalesceRecentHookEvent(GitlabHookEvent incomingEvent) {
    LocalDateTime since = incomingEvent.getReceivedAt()
        .minusSeconds(Math.max(1, properties.getWebhookBatchWindowSeconds()));
    GitlabHookEvent existing = hookEventMapper.selectOne(new LambdaQueryWrapper<GitlabHookEvent>()
        .eq(GitlabHookEvent::getConfigId, incomingEvent.getConfigId())
        .eq(GitlabHookEvent::getDedupeKey, incomingEvent.getDedupeKey())
        .ge(GitlabHookEvent::getReceivedAt, since)
        .in(GitlabHookEvent::getStatus, java.util.List.of("RECEIVED", "COALESCED"))
        .orderByDesc(GitlabHookEvent::getReceivedAt)
        .last("limit 1"));
    if (existing == null) {
      return null;
    }
    existing.setStatus("COALESCED");
    existing.setCoalescedCount((existing.getCoalescedCount() == null ? 1 : existing.getCoalescedCount()) + 1);
    existing.setPayload(incomingEvent.getPayload());
    existing.setReceivedAt(incomingEvent.getReceivedAt());
    existing.setProcessedAt(incomingEvent.getReceivedAt());
    hookEventMapper.updateById(existing);
    return existing;
  }

  private GitlabHookEvent createHookEvent(
      GitlabSyncConfig config,
      String eventType,
      Map<String, Object> payload,
      String payloadJson,
      LocalDateTime receivedAt) {
    Long projectId = extractProjectId(payload);
    String objectKind = String.valueOf(payload.get("object_kind"));
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    GitlabHookEvent event = new GitlabHookEvent();
    event.setConfigId(config.getId());
    event.setSourceInstance(sourceInstance);
    event.setEventType(eventType);
    event.setProjectId(projectId);
    event.setObjectKind(objectKind);
    event.setDedupeKey(sourceInstance + ":" + eventType + ":" + objectKind + ":" + projectId);
    event.setDirtyScope("source:" + sourceInstance);
    event.setCoalescedCount(1);
    event.setStatus("RECEIVED");
    event.setPayload(payloadJson);
    event.setReceivedAt(receivedAt);
    return event;
  }

  private Long extractProjectId(Map<String, Object> payload) {
    Object value = payload.get("project_id");
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private boolean secretMatches(String expectedSecret, String actualSecret) {
    if (actualSecret == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expectedSecret.getBytes(StandardCharsets.UTF_8),
        actualSecret.getBytes(StandardCharsets.UTF_8));
  }
}
