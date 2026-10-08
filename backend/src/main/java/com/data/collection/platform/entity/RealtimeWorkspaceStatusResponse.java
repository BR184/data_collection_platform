package com.data.collection.platform.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 实时工作区状态响应。
 *
 * <p>读取状态（GET /status）只填充工作区与刷新结果字段；提交刷新（POST /refresh）额外填充
 * {@code submissionOutcome} 与 {@code trackingId}，让调用方区分“本次提交被接受”“同一刷新已在跟踪”
 * 与“冷却未接受”，并按跟踪身份复用同一条后台跟踪，而不是把“按钮窗口结束”当成刷新成功。
 */
public record RealtimeWorkspaceStatusResponse(
    String workspaceKey,
    boolean supported,
    String status,
    String message,
    boolean refreshing,
    LocalDateTime lastSyncedAt,
    LocalDateTime lastRefreshStartedAt,
    LocalDateTime lastRefreshFinishedAt,
    Long jobId,
    List<String> sourceTables,
    Integer plannedTasks,
    List<String> unsupportedTables,
    Boolean factRefreshPlanned,
    String mirrorStatus,
    String factStatus,
    @JsonInclude(JsonInclude.Include.NON_NULL) String submissionOutcome,
    @JsonInclude(JsonInclude.Include.NON_NULL) String trackingId) {

  /** 本次提交已被接受并开始跟踪。 */
  public static final String SUBMISSION_ACCEPTED = "ACCEPTED";
  /** 同一工作区已有等价刷新在跟踪中，调用方应复用现有跟踪而非叠加新请求。 */
  public static final String SUBMISSION_ALREADY_REFRESHING = "ALREADY_REFRESHING";
  /** 处于刷新冷却期，本次提交未被接受，调用方不得提示“已开始刷新”。 */
  public static final String SUBMISSION_COOLDOWN = "COOLDOWN";

  public RealtimeWorkspaceStatusResponse {
    sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
    unsupportedTables = unsupportedTables == null ? List.of() : List.copyOf(unsupportedTables);
  }

  public RealtimeWorkspaceStatusResponse(
      String workspaceKey,
      boolean supported,
      String status,
      String message,
      boolean refreshing,
      LocalDateTime lastSyncedAt,
      LocalDateTime lastRefreshStartedAt,
      LocalDateTime lastRefreshFinishedAt) {
    this(
        workspaceKey,
        supported,
        status,
        message,
        refreshing,
        lastSyncedAt,
        lastRefreshStartedAt,
        lastRefreshFinishedAt,
        null,
        List.of(),
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * 附加提交结论、跟踪身份与提交说明，供 POST 刷新响应使用。
   *
   * @param submissionOutcome 提交结论常量；读取状态响应传 null
   * @param trackingId 本次刷新的跟踪身份；无可用身份或读取状态响应传 null
   * @param message 提交说明；传 null 时保留原消息
   * @return 带提交信息的新响应
   */
  public RealtimeWorkspaceStatusResponse withRefreshSubmit(
      String submissionOutcome, String trackingId, String message) {
    return new RealtimeWorkspaceStatusResponse(
        workspaceKey,
        supported,
        status,
        message == null ? this.message : message,
        refreshing,
        lastSyncedAt,
        lastRefreshStartedAt,
        lastRefreshFinishedAt,
        jobId,
        sourceTables,
        plannedTasks,
        unsupportedTables,
        factRefreshPlanned,
        mirrorStatus,
        factStatus,
        submissionOutcome,
        trackingId);
  }
}
