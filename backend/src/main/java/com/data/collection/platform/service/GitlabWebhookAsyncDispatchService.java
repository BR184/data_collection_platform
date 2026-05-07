package com.data.collection.platform.service;

import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabWebhookAsyncDispatchService {
  private final ConcurrentLinkedQueue<QueuedWebhookEntry> pendingQueue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger queuedCount = new AtomicInteger();
  private final ConcurrentMap<String, ReentrantLock> objectLocks = new ConcurrentHashMap<>();
  private final ExecutorService workerPool =
      Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));

  private final GitlabMirrorProperties properties;
  private final GitlabWebhookPreciseSyncPlanner planner;
  private final GitlabMirrorSyncService syncService;
  private final GitlabConfigService configService;
  private final GitlabMirrorSchemaService mirrorSchemaService;

  public GitlabWebhookAsyncDispatchService(
      GitlabMirrorProperties properties,
      GitlabWebhookPreciseSyncPlanner planner,
      GitlabMirrorSyncService syncService,
      GitlabConfigService configService,
      GitlabMirrorSchemaService mirrorSchemaService) {
    this.properties = properties;
    this.planner = planner;
    this.syncService = syncService;
    this.configService = configService;
    this.mirrorSchemaService = mirrorSchemaService;
  }

  public void accept(String eventType, Map<String, Object> payload) {
    accept(configService.getConfig(), eventType, payload);
  }

  public void accept(GitlabSyncConfig config, String eventType, Map<String, Object> payload) {
    GitlabWebhookPreciseSyncPlan plan = planner.plan(payload);
    if (plan.targets().isEmpty()) {
      syncService.startWebhookSync(config, eventType, payload);
      return;
    }

    QueuedWebhookEntry entry = new QueuedWebhookEntry(
        config.getId(),
        plan.objectKey(),
        plan.objectId(),
        eventType,
        payload,
        LocalDateTime.now());
    pendingQueue.add(entry);
    int currentQueued = queuedCount.incrementAndGet();
    try (GitlabSyncLogContext.Scope context =
             GitlabSyncLogContext.openConfig(config, "WEBHOOK_ASYNC", plan.objectKey());
         GitlabSyncLogContext.Scope objectScope = GitlabSyncLogContext.object(plan.objectId());
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("QUEUED")) {
      log.info(
          "Webhook queued for async precise sync, eventType={}, objectKey={}, objectId={}, queuedSize={}",
          eventType,
          plan.objectKey(),
          plan.objectId(),
          currentQueued);
    }
    if (currentQueued >= properties.getWebhookBatchSize()) {
      flushPending();
    }
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.webhook-batch-window-seconds:3}000")
  public void scheduledFlush() {
    flushPending();
  }

  public void flushPending() {
    List<QueuedWebhookEntry> drained = new ArrayList<>();
    QueuedWebhookEntry entry;
    while ((entry = pendingQueue.poll()) != null) {
      drained.add(entry);
    }
    if (drained.isEmpty()) {
      mirrorSchemaService.recoverStaleSyncingStatuses();
      return;
    }
    queuedCount.addAndGet(-drained.size());
    mirrorSchemaService.recoverStaleSyncingStatuses();

    Map<String, QueuedWebhookEntry> latestByObject = new java.util.LinkedHashMap<>();
    for (QueuedWebhookEntry queued : drained) {
      latestByObject.merge(
          queued.objectKey(),
          queued,
          (left, right) -> left.receivedAt().isAfter(right.receivedAt()) ? left : right);
    }

    latestByObject.values().forEach(this::dispatch);
  }

  private void dispatch(QueuedWebhookEntry entry) {
    workerPool.submit(() -> executeSerial(entry));
  }

  private void executeSerial(QueuedWebhookEntry entry) {
    ReentrantLock lock = objectLocks.computeIfAbsent(entry.objectKey(), ignored -> new ReentrantLock());
    lock.lock();
    try {
      GitlabSyncConfig config = configService.getConfigById(entry.configId());
      try (GitlabSyncLogContext.Scope context =
               GitlabSyncLogContext.openConfig(config, "WEBHOOK_ASYNC", entry.objectKey());
           GitlabSyncLogContext.Scope objectScope = GitlabSyncLogContext.object(entry.objectId());
           GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("EXECUTING")) {
        log.info(
            "Executing queued webhook precise sync, eventType={}, objectKey={}, objectId={}",
            entry.eventType(),
            entry.objectKey(),
            entry.objectId());
      }
      syncService.executeRealtimeWebhookSync(config, entry.payload(), entry.objectId());
    } finally {
      lock.unlock();
    }
  }

  @PreDestroy
  public void shutdown() {
    workerPool.shutdownNow();
  }

  record QueuedWebhookEntry(
      Long configId,
      String objectKey,
      String objectId,
      String eventType,
      Map<String, Object> payload,
      LocalDateTime receivedAt) {
  }
}
