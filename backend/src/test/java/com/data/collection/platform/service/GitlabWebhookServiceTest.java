package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabWebhookEvent;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabWebhookEventMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabWebhookServiceTest {

  private GitlabWebhookEventMapper webhookEventMapper;
  private GitlabConfigService configService;
  private GitlabWebhookAsyncDispatchService asyncDispatchService;
  private JsonUtils jsonUtils;
  private GitlabWebhookService webhookService;

  @BeforeEach
  void setUp() {
    webhookEventMapper = mock(GitlabWebhookEventMapper.class);
    configService = mock(GitlabConfigService.class);
    asyncDispatchService = mock(GitlabWebhookAsyncDispatchService.class);
    jsonUtils = mock(JsonUtils.class);
    webhookService = new GitlabWebhookService(webhookEventMapper, configService, asyncDispatchService, jsonUtils);
  }

  @Test
  void acceptShouldStartPreciseWebhookSyncWhenAutoSyncEnabled() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setAutoSyncEnabled(true);
    config.setEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.ALL);
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "project_id", 10L,
        "object_attributes", Map.of("id", 101L));

    when(configService.getConfig()).thenReturn(config);
    when(jsonUtils.toJson(payload)).thenReturn("{\"object_kind\":\"issue\"}");

    webhookService.accept("Issue Hook", payload, null);

    verify(webhookEventMapper).insert(org.mockito.ArgumentMatchers.<GitlabWebhookEvent>any());
    verify(asyncDispatchService).accept(eq("Issue Hook"), eq(payload));
  }

  @Test
  void acceptShouldTolerateMalformedProjectId() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setAutoSyncEnabled(true);
    config.setEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.ALL);
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "project_id", "not-a-number",
        "object_attributes", Map.of("id", 101L));

    when(configService.getConfig()).thenReturn(config);
    when(jsonUtils.toJson(payload)).thenReturn("{\"object_kind\":\"issue\"}");

    webhookService.accept("Issue Hook", payload, null);

    verify(webhookEventMapper).insert(argThat((GitlabWebhookEvent event) -> event.getProjectId() == null));
    verify(asyncDispatchService).accept(eq("Issue Hook"), eq(payload));
  }
}
