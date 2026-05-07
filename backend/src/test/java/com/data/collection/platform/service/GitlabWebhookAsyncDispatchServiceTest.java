package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GitlabWebhookAsyncDispatchServiceTest {

  private GitlabMirrorProperties properties;
  private GitlabWebhookPreciseSyncPlanner planner;
  private GitlabMirrorSyncService syncService;
  private GitlabConfigService configService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private GitlabWebhookAsyncDispatchService service;

  @BeforeEach
  void setUp() {
    properties = new GitlabMirrorProperties();
    properties.setWebhookBatchSize(10);
    planner = mock(GitlabWebhookPreciseSyncPlanner.class);
    syncService = mock(GitlabMirrorSyncService.class);
    configService = mock(GitlabConfigService.class);
    mirrorSchemaService = mock(GitlabMirrorSchemaService.class);
    service = new GitlabWebhookAsyncDispatchService(properties, planner, syncService, configService, mirrorSchemaService);

    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    when(configService.getConfig()).thenReturn(config);
    when(configService.getConfigById(1L)).thenReturn(config);
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  @Test
  void shouldFallbackToLegacyWebhookTaskWhenPlannerHasNoPreciseTargets() {
    Map<String, Object> payload = Map.of("object_kind", "push");
    when(planner.plan(payload)).thenReturn(new GitlabWebhookPreciseSyncPlan("push:unknown", "unknown", List.of()));

    service.accept("Push Hook", payload);

    verify(syncService).startWebhookSync(any(), eq("Push Hook"), eq(payload));
  }

  @Test
  void shouldKeepOnlyLatestQueuedWebhookForSameObjectWithinBatchWindow() {
    Map<String, Object> payload1 = Map.of("object_kind", "issue", "object_attributes", Map.of("id", 101L, "title", "old"));
    Map<String, Object> payload2 = Map.of("object_kind", "issue", "object_attributes", Map.of("id", 101L, "title", "new"));
    Map<String, Object> payload3 = Map.of("object_kind", "issue", "object_attributes", Map.of("id", 202L, "title", "other"));

    when(planner.plan(payload1)).thenReturn(new GitlabWebhookPreciseSyncPlan("issue:101", "101", List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L))));
    when(planner.plan(payload2)).thenReturn(new GitlabWebhookPreciseSyncPlan("issue:101", "101", List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L))));
    when(planner.plan(payload3)).thenReturn(new GitlabWebhookPreciseSyncPlan("issue:202", "202", List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 202L))));

    service.accept("Issue Hook", payload1);
    service.accept("Issue Hook", payload2);
    service.accept("Issue Hook", payload3);
    service.flushPending();

    verify(syncService, timeout(1000).times(1)).executeRealtimeWebhookSync(any(), eq(payload2), eq("101"));
    verify(syncService, timeout(1000).times(1)).executeRealtimeWebhookSync(any(), eq(payload3), eq("202"));
    verify(syncService, Mockito.never()).executeRealtimeWebhookSync(any(), eq(payload1), eq("101"));
  }

  @Test
  void shouldSerializeExecutionForSameObjectKeyAcrossFlushes() throws Exception {
    Map<String, Object> payload1 = Map.of("object_kind", "issue", "object_attributes", Map.of("id", 101L, "title", "first"));
    Map<String, Object> payload2 = Map.of("object_kind", "issue", "object_attributes", Map.of("id", 101L, "title", "second"));

    when(planner.plan(payload1)).thenReturn(new GitlabWebhookPreciseSyncPlan("issue:101", "101", List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L))));
    when(planner.plan(payload2)).thenReturn(new GitlabWebhookPreciseSyncPlan("issue:101", "101", List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L))));

    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    AtomicInteger invocationCount = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(2);
    CountDownLatch firstCallStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstCall = new CountDownLatch(1);
    Mockito.doAnswer(invocation -> {
      int current = inFlight.incrementAndGet();
      maxInFlight.accumulateAndGet(current, Math::max);
      try {
        if (invocationCount.incrementAndGet() == 1) {
          firstCallStarted.countDown();
          org.junit.jupiter.api.Assertions.assertTrue(releaseFirstCall.await(2, TimeUnit.SECONDS));
        }
      } finally {
        inFlight.decrementAndGet();
        latch.countDown();
      }
      return 1;
    }).when(syncService).executeRealtimeWebhookSync(any(), any(), eq("101"));

    service.accept("Issue Hook", payload1);
    service.flushPending();
    org.junit.jupiter.api.Assertions.assertTrue(firstCallStarted.await(2, TimeUnit.SECONDS));
    service.accept("Issue Hook", payload2);
    service.flushPending();
    releaseFirstCall.countDown();

    org.junit.jupiter.api.Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS));
    org.junit.jupiter.api.Assertions.assertEquals(1, maxInFlight.get());
  }
}
