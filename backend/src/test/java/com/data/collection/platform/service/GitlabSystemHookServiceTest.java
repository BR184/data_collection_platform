package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabHookEvent;
import com.data.collection.platform.entity.GitlabSystemHookEvent;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabHookEventMapper;
import com.data.collection.platform.mapper.GitlabSystemHookEventMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSystemHookServiceTest {

  private GitlabSystemHookEventMapper systemHookEventMapper;
  private GitlabHookEventMapper hookEventMapper;
  private GitlabConfigService configService;
  private GitlabSystemHookAsyncDispatchService asyncDispatchService;
  private JsonUtils jsonUtils;
  private GitlabSystemHookService systemHookService;

  @BeforeEach
  void setUp() {
    systemHookEventMapper = mock(GitlabSystemHookEventMapper.class);
    hookEventMapper = mock(GitlabHookEventMapper.class);
    configService = mock(GitlabConfigService.class);
    asyncDispatchService = mock(GitlabSystemHookAsyncDispatchService.class);
    jsonUtils = mock(JsonUtils.class);
    systemHookService = new GitlabSystemHookService(
        systemHookEventMapper,
        hookEventMapper,
        configService,
        asyncDispatchService,
        jsonUtils,
        new GitlabMirrorProperties());
  }

  @Test
  void acceptShouldStartPreciseSystemHookSyncWhenSystemHookEnabledEvenIfAutoSyncDisabled() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setAutoSyncEnabled(false);
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setSystemHookEnabled(true);
    config.setSystemHookSecret("secret");
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.ALL);
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "project_id", 10L,
        "object_attributes", Map.of("id", 101L));

    when(configService.getConfigForSystemHook("secret")).thenReturn(config);
    when(jsonUtils.toJson(payload)).thenReturn("{\"object_kind\":\"issue\"}");

    systemHookService.accept("Issue Hook", payload, "secret");

    verify(systemHookEventMapper).insert(org.mockito.ArgumentMatchers.<GitlabSystemHookEvent>any());
    verify(systemHookEventMapper)
        .updateById(argThat((GitlabSystemHookEvent event) -> event.isProcessed()));
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
    config.setSystemHookEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.ALL);
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "project_id", "not-a-number",
        "object_attributes", Map.of("id", 101L));

    when(configService.getConfigForSystemHook(null)).thenReturn(config);
    when(jsonUtils.toJson(payload)).thenReturn("{\"object_kind\":\"issue\"}");

    systemHookService.accept("Issue Hook", payload, null);

    verify(systemHookEventMapper).insert(argThat((GitlabSystemHookEvent event) -> event.getProjectId() == null));
    verify(hookEventMapper).insert(argThat((GitlabHookEvent event) -> event.getProjectId() == null));
    verify(asyncDispatchService).accept(eq(config), eq("Issue Hook"), eq(payload));
  }

  @Test
  void acceptShouldCoalesceDuplicateHookWithinBatchWindow() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setSystemHookEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.ALL);
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "project_id", 10L,
        "object_attributes", Map.of("id", 101L));
    GitlabHookEvent existing = new GitlabHookEvent();
    existing.setId(99L);
    existing.setConfigId(1L);
    existing.setDedupeKey("default:Issue Hook:issue:10");
    existing.setStatus("RECEIVED");
    existing.setCoalescedCount(1);

    when(configService.getConfigForSystemHook(null)).thenReturn(config);
    when(jsonUtils.toJson(payload)).thenReturn("{\"object_kind\":\"issue\"}");
    when(hookEventMapper.selectOne(any())).thenReturn(existing);

    systemHookService.accept("Issue Hook", payload, null);

    verify(hookEventMapper, never()).insert(org.mockito.ArgumentMatchers.<GitlabHookEvent>any());
    verify(hookEventMapper).updateById(argThat((GitlabHookEvent event) ->
        "COALESCED".equals(event.getStatus()) && Integer.valueOf(2).equals(event.getCoalescedCount())));
    verify(systemHookEventMapper)
        .updateById(argThat((GitlabSystemHookEvent event) -> event.isProcessed()));
    verify(asyncDispatchService, never()).accept(eq(config), eq("Issue Hook"), eq(payload));
  }
}
