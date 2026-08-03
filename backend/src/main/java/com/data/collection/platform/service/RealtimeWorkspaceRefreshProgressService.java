package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimeWorkspaceRefreshProgressService {
  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;

  RealtimeWorkspaceRefreshProgressService(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, null);
  }

  @Autowired
  public RealtimeWorkspaceRefreshProgressService(
      JdbcTemplate jdbcTemplate, GitlabConfigService configService) {
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
  }

  /**
   * 查询指定镜像运行及其最新事实子运行的阶段状态。
   *
   * @param mirrorRunId 页面刷新提交或复用的镜像运行 ID
   * @param workspaceKey 页面工作区稳定标识，用于确定需要等待的事实类型
   * @return 已持久化的两阶段状态；运行不存在时返回 {@code null}
   */
  public RealtimeWorkspaceRefreshProgress findByMirrorRunId(Long mirrorRunId, String workspaceKey) {
    if (mirrorRunId == null) {
      return null;
    }
    return queryProgress(workspaceKey, "where mirror.id = ?", mirrorRunId);
  }

  /**
   * 查询某工作区最近一次页面触发的镜像运行及其事实子运行状态。
   *
   * @param workspaceKey 页面工作区稳定标识
   * @return 最近的两阶段状态；未找到页面运行时返回 {@code null}
   */
  public RealtimeWorkspaceRefreshProgress findLatestForWorkspace(String workspaceKey) {
    if (workspaceKey == null || workspaceKey.isBlank()) {
      return null;
    }
    return queryProgress(
        workspaceKey,
        """
        where mirror.request_reason = ?
          and mirror.run_type in (
            'FULL_SYNC',
            'INCREMENTAL_SYNC',
            'TABLE_REFRESH',
            'SYSTEM_HOOK',
            'FULL_COMPENSATION_SCAN'
          )
        """,
        workspaceKey.trim());
  }

  private RealtimeWorkspaceRefreshProgress queryProgress(
      String workspaceKey, String condition, Object... args) {
    List<RefreshRunSnapshot> rows =
        jdbcTemplate.query(
            """
            select mirror.id as mirror_run_id,
                   mirror.config_id as config_id,
                   mirror.status as mirror_status,
                   mirror.started_at as mirror_started_at,
                   mirror.finished_at as mirror_finished_at,
                   fact.id as fact_run_id,
                   fact.status as fact_status,
                   fact.started_at as fact_started_at,
                   fact.finished_at as fact_finished_at
              from sync_runs mirror
              left join lateral (
                select child.*
                  from sync_runs child
                 where child.parent_run_id = mirror.id
                   and child.run_type = 'FACT_REFRESH'
                 order by child.created_at desc, child.id desc
                 limit 1
              ) fact on true
            %s
             order by mirror.created_at desc, mirror.id desc
             limit 1
            """.formatted(condition),
            this::mapSnapshot,
            args);
    return rows.isEmpty() ? null : toProgress(rows.getFirst(), workspaceKey);
  }

  private RefreshRunSnapshot mapSnapshot(ResultSet resultSet, int rowNum)
      throws SQLException {
    return new RefreshRunSnapshot(
        resultSet.getLong("mirror_run_id"),
        nullableLong(resultSet, "config_id"),
        resultSet.getString("mirror_status"),
        toLocalDateTime(resultSet.getTimestamp("mirror_started_at")),
        toLocalDateTime(resultSet.getTimestamp("mirror_finished_at")),
        nullableLong(resultSet, "fact_run_id"),
        resultSet.getString("fact_status"),
        toLocalDateTime(resultSet.getTimestamp("fact_started_at")),
        toLocalDateTime(resultSet.getTimestamp("fact_finished_at")));
  }

  private RealtimeWorkspaceRefreshProgress toProgress(
      RefreshRunSnapshot snapshot, String workspaceKey) {
    RealtimeWorkspaceDependencyCatalog.Requirement requirement =
        resolveFactRequirement(snapshot.configId(), workspaceKey);
    FenceSummary fence = loadFenceSummary(snapshot.mirrorRunId(), workspaceKey, requirement);
    String factStatus = resolveFactStatus(snapshot, requirement, fence);
    return new RealtimeWorkspaceRefreshProgress(
        snapshot.mirrorRunId(),
        snapshot.mirrorStatus(),
        snapshot.factRunId(),
        factStatus,
        requirement.factRefreshRequired(),
        fence.startedAt() == null
            ? (snapshot.factStartedAt() == null
                ? snapshot.mirrorStartedAt()
                : snapshot.factStartedAt())
            : fence.startedAt(),
        fence.finishedAt() == null
            ? (snapshot.factFinishedAt() == null
                ? snapshot.mirrorFinishedAt()
                : snapshot.factFinishedAt())
            : fence.finishedAt());
  }

  private RealtimeWorkspaceDependencyCatalog.Requirement resolveFactRequirement(
      Long configId, String workspaceKey) {
    if (configId == null || configService == null) {
      return new RealtimeWorkspaceDependencyCatalog.Requirement(false, List.of());
    }
    GitlabSyncConfig config = configService.getConfigById(configId);
    if (config == null) {
      return new RealtimeWorkspaceDependencyCatalog.Requirement(false, List.of());
    }
    return RealtimeWorkspaceDependencyCatalog.resolve(workspaceKey, config);
  }

  private String resolveFactStatus(
      RefreshRunSnapshot snapshot,
      RealtimeWorkspaceDependencyCatalog.Requirement requirement,
      FenceSummary fence) {
    if (!requirement.factRefreshRequired()) {
      return snapshot.factStatus();
    }
    if (fence.factTypes() < requirement.factTypes().size()) {
      return isTerminalMirrorRun(snapshot.mirrorStatus()) ? "FAILED" : "QUEUED";
    }
    if (fence.failed() > 0) {
      return "FAILED";
    }
    if (fence.pending() > 0) {
      return "RUNNING";
    }
    return "SUCCESS";
  }

  private FenceSummary loadFenceSummary(
      long mirrorRunId,
      String workspaceKey,
      RealtimeWorkspaceDependencyCatalog.Requirement requirement) {
    if (!requirement.factRefreshRequired()) {
      return FenceSummary.empty();
    }
    return jdbcTemplate.queryForObject(
        """
        select count(distinct fact_type) as fact_types,
               count(*) filter (where status = 'PENDING') as pending,
               count(*) filter (where status = 'FAILED') as failed,
               min(created_at) as started_at,
               max(completed_at) filter (where status = 'SUCCESS') as finished_at
          from sync_run_publication_fences
         where run_id = ? and workspace_key = ?
        """,
        (resultSet, rowNumber) ->
            new FenceSummary(
                resultSet.getInt("fact_types"),
                resultSet.getInt("pending"),
                resultSet.getInt("failed"),
                toLocalDateTime(resultSet.getTimestamp("started_at")),
                toLocalDateTime(resultSet.getTimestamp("finished_at"))),
        mirrorRunId,
        workspaceKey.trim().toLowerCase(java.util.Locale.ROOT));
  }

  private boolean isTerminalMirrorRun(String status) {
    return "SUCCESS".equals(status)
        || "PARTIAL_SUCCESS".equals(status)
        || "FAILED".equals(status)
        || "CANCELLED".equals(status)
        || "MERGED".equals(status);
  }

  private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
    Object value = resultSet.getObject(column);
    return value instanceof Number number ? number.longValue() : null;
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private record RefreshRunSnapshot(
      Long mirrorRunId,
      Long configId,
      String mirrorStatus,
      LocalDateTime mirrorStartedAt,
      LocalDateTime mirrorFinishedAt,
      Long factRunId,
      String factStatus,
      LocalDateTime factStartedAt,
      LocalDateTime factFinishedAt) {
  }

  private record FenceSummary(
      int factTypes,
      int pending,
      int failed,
      LocalDateTime startedAt,
      LocalDateTime finishedAt) {
    private static FenceSummary empty() {
      return new FenceSummary(0, 0, 0, null, null);
    }
  }
}
