package com.data.collection.platform.service;

import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RealtimeWorkspaceService {
  private static final Duration REFRESH_COOLDOWN = Duration.ofSeconds(15);
  private static final java.util.Set<String> ACTIVE_SYNC_STATUSES =
      java.util.Set.of("SUBMITTED", "QUEUED", "RUNNING", "RETRYING", "CANCELLING");
  private static final java.util.Set<String> SUCCESS_SYNC_STATUSES =
      java.util.Set.of("SUCCESS");

  private final RealtimeWorkspaceSyncMetadataService syncMetadataService;
  private final RealtimeWorkspaceRefreshProgressService refreshProgressService;
  private final RealtimeWorkspaceService self;
  private final Map<String, WorkspaceRefreshState> states = new ConcurrentHashMap<>();

  @Autowired
  public RealtimeWorkspaceService(
      RealtimeWorkspaceSyncMetadataService syncMetadataService,
      RealtimeWorkspaceRefreshProgressService refreshProgressService,
      @Lazy RealtimeWorkspaceService self) {
    this.syncMetadataService = syncMetadataService;
    this.refreshProgressService = refreshProgressService;
    this.self = self == null ? this : self;
  }

  RealtimeWorkspaceService(
      RealtimeWorkspaceSyncMetadataService syncMetadataService,
      @Lazy RealtimeWorkspaceService self) {
    this(syncMetadataService, null, self);
  }

  public RealtimeWorkspaceStatusResponse getStatus(String workspaceKey) {
    return getStatus(workspaceKey, Map.of());
  }

  public RealtimeWorkspaceStatusResponse getStatus(String workspaceKey, Map<String, String> filters) {
    WorkspaceRefreshState state = states.get(workspaceKey);
    RealtimeWorkspaceSyncMetadata metadata = syncMetadataService.resolve(workspaceKey, filters);
    if (state == null) {
      RealtimeWorkspaceRefreshProgress progress = findLatestProgress(workspaceKey);
      if (progress != null) {
        return responseForProgress(
            workspaceKey,
            null,
            metadata,
            progress);
      }
      return new RealtimeWorkspaceStatusResponse(
          workspaceKey,
          true,
          metadata.lastSyncedAt() == null ? "IDLE" : "READY",
          metadata.lastSyncedAt() == null ? "暂无已完成的同步时间" : "已展示当前可用数据",
          false,
          metadata.lastSyncedAt(),
          metadata.taskStartedAt(),
          metadata.taskFinishedAt());
    }
    return toResponse(workspaceKey, state, metadata);
  }

  public synchronized RealtimeWorkspaceStatusResponse requestRefresh(
      String workspaceKey,
      Runnable refreshAction) {
    return requestRefreshWithResult(
        workspaceKey,
        () -> {
          refreshAction.run();
          return new RealtimeWorkspaceRefreshResult(
              null, List.of(), 0, List.of(), true, "SUCCESS", "SUCCESS", null);
        });
  }

  public synchronized RealtimeWorkspaceStatusResponse requestRefreshWithResult(
      String workspaceKey,
      Supplier<RealtimeWorkspaceRefreshResult> refreshAction) {
    WorkspaceRefreshState state = states.computeIfAbsent(workspaceKey, key -> new WorkspaceRefreshState());
    LocalDateTime now = LocalDateTime.now();
    if (state.refreshing || isCoolingDown(state, now)) {
      return toResponse(workspaceKey, state, syncMetadataService.resolve(workspaceKey, Map.of()));
    }
    state.refreshing = true;
    state.status = "REFRESHING";
    state.message = "已开始刷新最新数据";
    state.lastRefreshAcceptedAt = now;
    state.lastRefreshStartedAt = now;
    state.lastRefreshFinishedAt = null;
    state.jobId = null;
    state.sourceTables = List.of();
    state.plannedTasks = null;
    state.unsupportedTables = List.of();
    state.factRefreshPlanned = null;
    state.mirrorStatus = "REFRESHING";
    state.factStatus = null;
    self.executeRefreshWithResultAsync(workspaceKey, refreshAction);
    return toResponse(workspaceKey, state, syncMetadataService.resolve(workspaceKey, Map.of()));
  }

  @Async
  public void executeRefreshAsync(String workspaceKey, Runnable refreshAction) {
    executeRefreshWithResultAsync(
        workspaceKey,
        () -> {
          refreshAction.run();
          return new RealtimeWorkspaceRefreshResult(
              null, List.of(), 0, List.of(), true, "SUCCESS", "SUCCESS", null);
        });
  }

  @Async
  public void executeRefreshWithResultAsync(
      String workspaceKey,
      Supplier<RealtimeWorkspaceRefreshResult> refreshAction) {
    try {
      RealtimeWorkspaceRefreshResult result = refreshAction.get();
      synchronized (this) {
        WorkspaceRefreshState state = states.computeIfAbsent(workspaceKey, key -> new WorkspaceRefreshState());
        state.message = result != null && result.message() != null
            ? result.message()
            : "刷新已完成";
        applyResult(state, result);
        if (state.jobId == null || refreshProgressService == null) {
          state.refreshing = false;
          state.status = "READY";
          state.lastRefreshFinishedAt = LocalDateTime.now();
        }
      }
    } catch (Exception ex) {
      log.warn("Realtime workspace refresh failed, workspaceKey={}", workspaceKey, ex);
      synchronized (this) {
        WorkspaceRefreshState state = states.computeIfAbsent(workspaceKey, key -> new WorkspaceRefreshState());
        state.refreshing = false;
        state.status = "FAILED";
        state.message = "刷新未完成，已展示当前可用数据";
        state.lastRefreshFinishedAt = LocalDateTime.now();
        if (state.mirrorStatus == null || "REFRESHING".equals(state.mirrorStatus)) {
          state.mirrorStatus = "FAILED";
        }
        if (Boolean.TRUE.equals(state.factRefreshPlanned)
            && (state.factStatus == null || "REFRESHING".equals(state.factStatus))) {
          state.factStatus = "FAILED";
        }
      }
    }
  }

  private void applyResult(WorkspaceRefreshState state, RealtimeWorkspaceRefreshResult result) {
    if (result == null) {
      return;
    }
    state.jobId = result.jobId();
    state.sourceTables = result.sourceTables();
    state.plannedTasks = result.plannedTasks();
    state.unsupportedTables = result.unsupportedTables();
    state.factRefreshPlanned = result.factRefreshPlanned();
    state.mirrorStatus = result.mirrorStatus();
    state.factStatus = result.factStatus();
  }

  private RealtimeWorkspaceStatusResponse toResponse(
      String workspaceKey,
      WorkspaceRefreshState state,
      RealtimeWorkspaceSyncMetadata metadata) {
    RealtimeWorkspaceRefreshProgress progress = state.jobId == null
        ? null
        : findProgress(state.jobId, workspaceKey);
    if (progress != null) {
      return responseForProgress(
          workspaceKey,
          state,
          metadata,
          progress);
    }
    LocalDateTime taskStartedAt = state.lastRefreshStartedAt != null
        ? state.lastRefreshStartedAt
        : metadata.taskStartedAt();
    LocalDateTime taskFinishedAt = state.lastRefreshFinishedAt != null
        ? state.lastRefreshFinishedAt
        : metadata.taskFinishedAt();
    return new RealtimeWorkspaceStatusResponse(
        workspaceKey,
        true,
        state.status,
        state.message,
        state.refreshing,
        metadata.lastSyncedAt(),
        taskStartedAt,
        taskFinishedAt,
        state.jobId,
        state.sourceTables,
        state.plannedTasks,
        state.unsupportedTables,
        state.factRefreshPlanned,
        state.mirrorStatus,
        state.factStatus);
  }

  private RealtimeWorkspaceStatusResponse responseForProgress(
      String workspaceKey,
      WorkspaceRefreshState state,
      RealtimeWorkspaceSyncMetadata metadata,
      RealtimeWorkspaceRefreshProgress progress) {
    ProgressState progressState = progressState(progress);
    if (state != null && progressState.terminal()) {
      synchronized (this) {
        state.refreshing = false;
        state.status = progressState.status();
        state.message = progressState.message();
        state.lastRefreshFinishedAt = progress.finishedAt() == null
            ? LocalDateTime.now()
            : progress.finishedAt();
        state.mirrorStatus = progress.mirrorStatus();
        state.factStatus = progress.factStatus();
      }
    }
    Long jobId = state == null ? progress.mirrorRunId() : state.jobId;
    List<String> sourceTables = state == null ? List.of() : state.sourceTables;
    Integer plannedTasks = state == null ? null : state.plannedTasks;
    List<String> unsupportedTables = state == null ? List.of() : state.unsupportedTables;
    Boolean factRefreshPlanned = progress.factRefreshRequired();
    return new RealtimeWorkspaceStatusResponse(
        workspaceKey,
        true,
        progressState.status(),
        progressState.message(),
        progressState.refreshing(),
        metadata.lastSyncedAt(),
        progress.startedAt(),
        progressState.terminal() ? progress.finishedAt() : null,
        jobId,
        sourceTables,
        plannedTasks,
        unsupportedTables,
        factRefreshPlanned,
        progress.mirrorStatus(),
        progress.factStatus());
  }

  private ProgressState progressState(RealtimeWorkspaceRefreshProgress progress) {
    String mirrorStatus = progress.mirrorStatus();
    if (ACTIVE_SYNC_STATUSES.contains(mirrorStatus)) {
      return ProgressState.refreshing("镜像同步中");
    }
    if (!SUCCESS_SYNC_STATUSES.contains(mirrorStatus)) {
      return ProgressState.failed("镜像同步未完成，已展示当前可用数据");
    }
    if (!progress.factRefreshRequired()) {
      return ProgressState.ready("已展示最新数据");
    }
    if (progress.factRunId() == null) {
      return ProgressState.refreshing("镜像同步已完成，等待事实刷新任务提交");
    }
    if (ACTIVE_SYNC_STATUSES.contains(progress.factStatus())) {
      return ProgressState.refreshing("事实刷新中");
    }
    if (SUCCESS_SYNC_STATUSES.contains(progress.factStatus())) {
      return ProgressState.ready("已展示最新事实数据");
    }
    return ProgressState.failed("事实刷新未完成，已展示当前可用数据");
  }

  private RealtimeWorkspaceRefreshProgress findProgress(Long mirrorRunId, String workspaceKey) {
    return refreshProgressService == null
        ? null
        : refreshProgressService.findByMirrorRunId(mirrorRunId, workspaceKey);
  }

  private RealtimeWorkspaceRefreshProgress findLatestProgress(String workspaceKey) {
    return refreshProgressService == null ? null : refreshProgressService.findLatestForWorkspace(workspaceKey);
  }

  private boolean isCoolingDown(WorkspaceRefreshState state, LocalDateTime now) {
    return state.lastRefreshAcceptedAt != null
        && Duration.between(state.lastRefreshAcceptedAt, now).compareTo(REFRESH_COOLDOWN) < 0;
  }

  private static final class WorkspaceRefreshState {
    private boolean refreshing;
    private String status = "IDLE";
    private String message = "尚未请求刷新";
    private LocalDateTime lastRefreshAcceptedAt;
    private LocalDateTime lastRefreshStartedAt;
    private LocalDateTime lastRefreshFinishedAt;
    private Long jobId;
    private List<String> sourceTables = List.of();
    private Integer plannedTasks;
    private List<String> unsupportedTables = List.of();
    private Boolean factRefreshPlanned;
    private String mirrorStatus;
    private String factStatus;
  }

  private record ProgressState(String status, String message, boolean refreshing, boolean terminal) {
    private static ProgressState refreshing(String message) {
      return new ProgressState("REFRESHING", message, true, false);
    }

    private static ProgressState ready(String message) {
      return new ProgressState("READY", message, false, true);
    }

    private static ProgressState failed(String message) {
      return new ProgressState("FAILED", message, false, true);
    }
  }
}
