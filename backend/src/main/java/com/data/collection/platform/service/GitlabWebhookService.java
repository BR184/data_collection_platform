package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabHookEvent;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabWebhookEvent;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.mapper.GitlabHookEventMapper;
import com.data.collection.platform.mapper.GitlabWebhookEventMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabWebhookService {
  private final GitlabWebhookEventMapper webhookEventMapper;
  private final GitlabHookEventMapper hookEventMapper;
  private final GitlabConfigService configService;
  private final GitlabWebhookAsyncDispatchService asyncDispatchService;
  private final JsonUtils jsonUtils;

  public GitlabWebhookService(
      GitlabWebhookEventMapper webhookEventMapper,
      GitlabHookEventMapper hookEventMapper,
      GitlabConfigService configService,
      GitlabWebhookAsyncDispatchService asyncDispatchService,
      JsonUtils jsonUtils) {
    this.webhookEventMapper = webhookEventMapper;
    this.hookEventMapper = hookEventMapper;
    this.configService = configService;
    this.asyncDispatchService = asyncDispatchService;
    this.jsonUtils = jsonUtils;
  }

  public void accept(String eventType, Map<String, Object> payload, String secret) {
    GitlabSyncConfig config = configService.getConfigForWebhook(secret);
    String effectiveEventType = eventType == null || eventType.isBlank()
        ? String.valueOf(payload.getOrDefault("object_kind", "webhook"))
        : eventType;
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, SyncTriggerType.WEBHOOK.name());
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Webhook_Received")) {
      log.info(
          "Webhook received, eventType={}, projectId={}, objectKind={}",
          effectiveEventType,
          extractProjectId(payload),
          payload.get("object_kind"));
    }
    if (config.getWebhookSecret() != null && !config.getWebhookSecret().isBlank()) {
      if (!secretMatches(config.getWebhookSecret(), secret)) {
        try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, SyncTriggerType.WEBHOOK.name());
             GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Webhook_Received")) {
          log.warn("Webhook rejected because secret validation failed, eventType={}", effectiveEventType);
        }
        throw new BizException("Invalid webhook secret");
      }
    }

    String payloadJson = jsonUtils.toJson(payload);
    GitlabWebhookEvent event = new GitlabWebhookEvent();
    event.setConfigId(config.getId());
    event.setEventType(effectiveEventType);
    event.setProjectId(extractProjectId(payload));
    event.setObjectKind(String.valueOf(payload.get("object_kind")));
    event.setPayload(payloadJson);
    event.setProcessed(false);
    event.setReceivedAt(LocalDateTime.now());
    webhookEventMapper.insert(event);
    hookEventMapper.insert(createHookEvent(config, effectiveEventType, payload, payloadJson, event.getReceivedAt()));

    asyncDispatchService.accept(config, effectiveEventType, payload);
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
