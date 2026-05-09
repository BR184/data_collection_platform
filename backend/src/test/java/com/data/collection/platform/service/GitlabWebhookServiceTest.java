package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabHookEvent;
import com.data.collection.platform.entity.GitlabWebhookEvent;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabHookEventMapper;
import com.data.collection.platform.mapper.GitlabWebhookEventMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabWebhookServiceTest {

  private GitlabWebhookEventMapper webhookEventMapper;
  private GitlabHookEventMapper hookEventMapper;
  private GitlabConfigService configService;
  private GitlabWebhookAsyncDispatchService asyncDispatchService;
  private JsonUtils jsonUtils;
  private GitlabWebhookService webhookService;

  @BeforeEach
  void setUp() {
    webhookEventMapper = mock(GitlabWebhookEventMapper.class);
    hookEventMapper = mock(GitlabHookEventMapper.class);
    configService = mock(GitlabConfigService.class);
    asyncDispatchService = mock(GitlabWebhookAsyncDispatchService.class);
    jsonUtils = mock(JsonUtils.class);
    webhookService = new GitlabWebhookService(
        webhookEventMapper,
        hookEventMapper,
        configService,
        asyncDispatchService,
        jsonUtils);
  }

  @Test
  void acceptShouldStartPreciseWebhookSyncWhenWebhookEnabledEvenIfAutoSyncDisabled() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setAutoSyncEnabled(false);
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setWebhookEnabled(true);
    config.setWebhookSecret("secret");
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.ALL);
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "project_id", 10L,
        "object_attributes", Map.of("id", 101L));

    when(configService.getConfigForWebhook("secret")).thenReturn(config);
    when(jsonUtils.toJson(payload)).thenReturn("{\"object_kind\":\"issue\"}");

    webhookService.accept("Issue Hook", payload, "secret");

    verify(webhookEventMapper).insert(org.mockito.ArgumentMatchers.<GitlabWebhookEvent>any());
    verify(hookEventMapper).insert(org.mockito.ArgumentMatchers.<GitlabHookEvent>any());
    verify(asyncDispatchService).accept(eq(config), eq("Issue Hook"), eq(payload));
  }

  @Test
  void acceptShouldTolerateMalformedProjectId() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setAutoSyncEnabled(true);
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setWebhookEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.ALL);
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "project_id", "not-a-number",
        "object_attributes", Map.of("id", 101L));

    when(configService.getConfigForWebhook(null)).thenReturn(config);
    when(jsonUtils.toJson(payload)).thenReturn("{\"object_kind\":\"issue\"}");

    webhookService.accept("Issue Hook", payload, null);

    verify(webhookEventMapper).insert(argThat((GitlabWebhookEvent event) -> event.getProjectId() == null));
    verify(hookEventMapper).insert(argThat((GitlabHookEvent event) -> event.getProjectId() == null));
    verify(asyncDispatchService).accept(eq(config), eq("Issue Hook"), eq(payload));
  }
}
