package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RealtimeWorkspaceService {
  private static final Duration REFRESH_COOLDOWN = Duration.ofSeconds(15);

  private final GitlabConfigService configService;
  private final RealtimeWorkspaceService self;
  private final Map<String, WorkspaceRefreshState> states = new ConcurrentHashMap<>();

  public RealtimeWorkspaceService(
      GitlabConfigService configService,
      @Lazy RealtimeWorkspaceService self) {
    this.configService = configService;
    this.self = self == null ? this : self;
  }

  public RealtimeWorkspaceStatusResponse getStatus(String workspaceKey) {
    WorkspaceRefreshState state = states.get(workspaceKey);
    LocalDateTime lastSyncedAt = resolveLastSyncedAt();
    if (state == null) {
      return new RealtimeWorkspaceStatusResponse(
          workspaceKey,
          true,
          lastSyncedAt == null ? "IDLE" : "READY",
          lastSyncedAt == null ? "暂无已完成的镜像同步时间" : "已展示当前可用数据",
          false,
          lastSyncedAt,
          null,
          null);
    }
    return toResponse(workspaceKey, state, lastSyncedAt);
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
      return toResponse(workspaceKey, state, resolveLastSyncedAt());
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
    return toResponse(workspaceKey, state, resolveLastSyncedAt());
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
        state.refreshing = false;
        state.status = "READY";
        state.message = result != null && result.message() != null
            ? result.message()
            : "刷新已完成";
        state.lastRefreshFinishedAt = LocalDateTime.now();
        applyResult(state, result);
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
      LocalDateTime lastSyncedAt) {
    return new RealtimeWorkspaceStatusResponse(
        workspaceKey,
        true,
        state.status,
        state.message,
        state.refreshing,
        lastSyncedAt,
        state.lastRefreshStartedAt,
        state.lastRefreshFinishedAt,
        state.jobId,
        state.sourceTables,
        state.plannedTasks,
        state.unsupportedTables,
        state.factRefreshPlanned,
        state.mirrorStatus,
        state.factStatus);
  }

  private LocalDateTime resolveLastSyncedAt() {
    GitlabSyncConfig config = configService.getConfig();
    if (config == null) {
      return null;
    }
    LocalDateTime incremental = config.getLastIncrementalSyncAt();
    LocalDateTime full = config.getLastFullSyncAt();
    if (incremental == null) {
      return full;
    }
    if (full == null) {
      return incremental;
    }
    return incremental.isAfter(full) ? incremental : full;
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
}
