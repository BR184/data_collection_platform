package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
            'COMPENSATION_SCAN',
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
    RealtimeWorkspaceFactRequirementResolver.Requirement requirement =
        resolveFactRequirement(snapshot.configId(), workspaceKey);
    String factStatus = resolveFactStatus(snapshot, requirement);
    return new RealtimeWorkspaceRefreshProgress(
        snapshot.mirrorRunId(),
        snapshot.mirrorStatus(),
        snapshot.factRunId(),
        factStatus,
        requirement.factRefreshRequired(),
        snapshot.factStartedAt() == null ? snapshot.mirrorStartedAt() : snapshot.factStartedAt(),
        snapshot.factFinishedAt() == null ? snapshot.mirrorFinishedAt() : snapshot.factFinishedAt());
  }

  private RealtimeWorkspaceFactRequirementResolver.Requirement resolveFactRequirement(
      Long configId, String workspaceKey) {
    if (configId == null || configService == null) {
      return new RealtimeWorkspaceFactRequirementResolver.Requirement(false, List.of());
    }
    GitlabSyncConfig config = configService.getConfigById(configId);
    if (config == null) {
      return new RealtimeWorkspaceFactRequirementResolver.Requirement(false, List.of());
    }
    return RealtimeWorkspaceFactRequirementResolver.resolve(workspaceKey, config);
  }

  private String resolveFactStatus(
      RefreshRunSnapshot snapshot,
      RealtimeWorkspaceFactRequirementResolver.Requirement requirement) {
    if (!requirement.factRefreshRequired() || snapshot.factRunId() == null) {
      return snapshot.factStatus();
    }
    List<String> taskStatuses = loadRequiredFactTaskStatuses(snapshot.factRunId(), requirement.factTypes());
    if (taskStatuses.size() < requirement.factTypes().size()) {
      return isTerminalFactRun(snapshot.factStatus()) ? "FAILED" : snapshot.factStatus();
    }
    if (taskStatuses.stream().allMatch("SUCCESS"::equals)) {
      return "SUCCESS";
    }
    if (taskStatuses.stream().anyMatch(this::isFailedFactTask)) {
      return "FAILED";
    }
    return taskStatuses.stream().anyMatch("PENDING"::equals) ? "QUEUED" : "RUNNING";
  }

  private List<String> loadRequiredFactTaskStatuses(Long factRunId, List<String> factTypes) {
    if (factRunId == null || factTypes == null || factTypes.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(factTypes.size(), "?"));
    List<Object> parameters = new ArrayList<>(factTypes.size() + 1);
    parameters.add(String.valueOf(factRunId));
    parameters.addAll(factTypes);
    return jdbcTemplate.query(
        """
        select status
          from fact_build_tasks
         where run_id = ?
           and upper(coalesce(fact_type, '')) in (%s)
        """.formatted(placeholders),
        (resultSet, rowNum) -> resultSet.getString("status"),
        parameters.toArray());
  }

  private boolean isTerminalFactRun(String status) {
    return "SUCCESS".equals(status)
        || "PARTIAL_SUCCESS".equals(status)
        || "FAILED".equals(status)
        || "CANCELLED".equals(status)
        || "MERGED".equals(status);
  }

  private boolean isFailedFactTask(String status) {
    return !"SUCCESS".equals(status) && !"PENDING".equals(status) && !"RUNNING".equals(status);
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
}
