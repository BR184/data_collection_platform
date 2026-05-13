package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildTaskResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.SyncStatus;
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

  private static final String CODE_REVIEW_WORKSPACE_KEY = "code-review-illegal-records";

  private final GitlabConfigService configService;
  private final GitlabTableSyncPlanningService tableSyncPlanningService;
  private final FactBuildTaskService factBuildTaskService;
  private final RealtimeWorkspaceService self;
  private final Map<String, WorkspaceRefreshState> states = new ConcurrentHashMap<>();

  public RealtimeWorkspaceService(
      GitlabConfigService configService,
      GitlabTableSyncPlanningService tableSyncPlanningService,
      FactBuildTaskService factBuildTaskService,
      @Lazy RealtimeWorkspaceService self) {
    this.configService = configService;
    this.tableSyncPlanningService = tableSyncPlanningService;
    this.factBuildTaskService = factBuildTaskService;
    this.self = self == null ? this : self;
  }

  public RealtimeWorkspaceStatusResponse getStatus(String workspaceKey) {
    WorkspaceRefreshState state = states.get(workspaceKey);
    LocalDateTime lastSyncedAt = resolveLastSyncedAt();
    GitlabSyncJob persistedJob = resolvePersistedJob(workspaceKey, state);
    if (persistedJob != null) {
      return toResponse(
          workspaceKey,
          buildStateFromPersistence(workspaceKey, persistedJob, state),
          lastSyncedAt);
    }
    if (state == null) {
      return new RealtimeWorkspaceStatusResponse(
          workspaceKey,
          true,
          lastSyncedAt == null ? "IDLE" : "READY",
          lastSyncedAt == null ? "尚未完成镜像同步" : "当前展示最近一次成功同步结果",
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
    if (state.refreshing) {
      return toResponse(workspaceKey, state, resolveLastSyncedAt());
    }
    LocalDateTime now = LocalDateTime.now();
    state.refreshing = true;
    state.status = "REFRESHING";
    state.message = "正在刷新最新数据";
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
            : "已刷新为最新数据";
        state.lastRefreshFinishedAt = LocalDateTime.now();
        applyResult(state, result);
      }
    } catch (Exception ex) {
      log.warn("Realtime workspace refresh failed, workspaceKey={}", workspaceKey, ex);
      synchronized (this) {
        WorkspaceRefreshState state = states.computeIfAbsent(workspaceKey, key -> new WorkspaceRefreshState());
        state.refreshing = false;
        state.status = "FAILED";
        state.message = "刷新失败，当前展示最近一次成功同步结果";
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

  private GitlabSyncJob resolvePersistedJob(String workspaceKey, WorkspaceRefreshState state) {
    if (state != null && state.jobId != null) {
      GitlabSyncJob job = tableSyncPlanningService.findJob(state.jobId);
      if (job != null) {
        return job;
      }
    }
    return tableSyncPlanningService.findLatestManualRefreshJobByReason(workspaceKey);
  }

  private WorkspaceRefreshState buildStateFromPersistence(
      String workspaceKey,
      GitlabSyncJob job,
      WorkspaceRefreshState currentState) {
    List<GitlabTableSyncTask> tasks = tableSyncPlanningService.listTasksForJob(job.getId());
    FactBuildTaskResponse factTask = latestFactTaskForWorkspace(workspaceKey, job);
    WorkspaceRefreshState state = new WorkspaceRefreshState();
    state.jobId = job.getId();
    state.sourceTables = resolveSourceTables(tasks, currentState);
    state.plannedTasks = tasks.isEmpty() && currentState != null ? currentState.plannedTasks : tasks.size();
    state.unsupportedTables = sameJob(currentState, job.getId()) ? currentState.unsupportedTables : List.of();
    state.factRefreshPlanned = factTask != null || (currentState != null && currentState.factRefreshPlanned != null)
        ? Boolean.TRUE
        : null;
    state.mirrorStatus = normalizeStatus(job.getStatus()).name();
    state.factStatus = factTask == null
        ? (sameJob(currentState, job.getId()) ? currentState.factStatus : null)
        : factTask.status();
    state.lastRefreshStartedAt = firstNonNull(job.getStartedAt(), firstNonNull(job.getCreatedAt(),
        currentState == null ? null : currentState.lastRefreshStartedAt));
    state.lastRefreshFinishedAt = max(
        max(job.getFinishedAt(), factTask == null ? null : factTask.finishedAt()),
        currentState == null ? null : currentState.lastRefreshFinishedAt);

    boolean mirrorActive = isActive(state.mirrorStatus);
    boolean factActive = isActive(state.factStatus);
    boolean factPendingInMemory =
        sameJob(currentState, job.getId())
            && currentState.refreshing
            && factTask == null
            && !isFailure(state.mirrorStatus);
    state.refreshing = mirrorActive || factActive || factPendingInMemory;
    state.status = resolveWorkspaceStatus(state.mirrorStatus, state.factStatus, state.refreshing);
    state.message = resolveWorkspaceMessage(state);
    return state;
  }

  private List<String> resolveSourceTables(
      List<GitlabTableSyncTask> tasks,
      WorkspaceRefreshState currentState) {
    List<String> sourceTables = tasks.stream()
        .map(GitlabTableSyncTask::getSourceTable)
        .filter(table -> table != null && !table.isBlank())
        .distinct()
        .toList();
    if (!sourceTables.isEmpty()) {
      return sourceTables;
    }
    return currentState == null ? List.of() : currentState.sourceTables;
  }

  private FactBuildTaskResponse latestFactTaskForWorkspace(String workspaceKey, GitlabSyncJob job) {
    FactBuildTaskResponse latest = factBuildTaskService.latest(factScopeForWorkspace(workspaceKey, job));
    if (latest == null || job.getCreatedAt() == null || latest.createdAt() == null) {
      return latest;
    }
    return latest.createdAt().isBefore(job.getCreatedAt()) ? null : latest;
  }

  private String factScopeForWorkspace(String workspaceKey, GitlabSyncJob job) {
    String baseScope = CODE_REVIEW_WORKSPACE_KEY.equals(workspaceKey) ? "merge-request" : "issue";
    String sourceInstance = GitlabSourceInstanceSupport.normalizeSourceInstance(job.getSourceInstance());
    return GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE.equals(sourceInstance)
        ? baseScope
        : sourceInstance + ":" + baseScope;
  }

  private String resolveWorkspaceStatus(String mirrorStatus, String factStatus, boolean refreshing) {
    if (refreshing) {
      return "REFRESHING";
    }
    if (isFailure(mirrorStatus) || isFailure(factStatus)) {
      return "FAILED";
    }
    return "READY";
  }

  private String resolveWorkspaceMessage(WorkspaceRefreshState state) {
    if (state.refreshing) {
      return isActive(state.factStatus) ? "事实数据刷新中" : "镜像同步中";
    }
    if (isFailure(state.mirrorStatus)) {
      return "镜像同步失败，当前展示最近一次成功同步结果";
    }
    if (isFailure(state.factStatus)) {
      return "事实数据刷新失败，当前展示最近一次成功同步结果";
    }
    return "已刷新为最新数据";
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
    LocalDateTime persistedActivityAt = tableSyncPlanningService.resolveLatestActivityAt(config.getId());
    if (persistedActivityAt != null) {
      return persistedActivityAt;
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

  private boolean sameJob(WorkspaceRefreshState state, Long jobId) {
    return state != null && jobId != null && jobId.equals(state.jobId);
  }

  private SyncStatus normalizeStatus(SyncStatus status) {
    return status == null ? SyncStatus.PENDING : status;
  }

  private boolean isActive(String status) {
    return "PENDING".equals(status)
        || "QUEUED".equals(status)
        || "RUNNING".equals(status)
        || "RETRYING".equals(status)
        || "CANCELLING".equals(status)
        || "REFRESHING".equals(status);
  }

  private boolean isFailure(String status) {
    return "FAILED".equals(status)
        || "TIMEOUT".equals(status)
        || "CANCELLED".equals(status);
  }

  private LocalDateTime firstNonNull(LocalDateTime left, LocalDateTime right) {
    return left == null ? right : left;
  }

  private LocalDateTime max(LocalDateTime left, LocalDateTime right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.isAfter(right) ? left : right;
  }

  private static final class WorkspaceRefreshState {
    private boolean refreshing;
    private String status = "IDLE";
    private String message = "尚未触发刷新";
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
