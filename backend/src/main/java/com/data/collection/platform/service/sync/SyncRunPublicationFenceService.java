package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.WorkspaceScopeSelectionType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.FactProjectionScopeKeyCodec;
import com.data.collection.platform.service.IssueScopeDimension;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久化并推进页面手动刷新的事实版本与投影发布栅栏。 */
@Service
public class SyncRunPublicationFenceService {
  private final JdbcTemplate jdbcTemplate;

  public SyncRunPublicationFenceService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 把一个页面工作区附着到仍活动的手动表刷新运行。
   *
   * <p>运行行锁与最终完成提交共用，因此返回 {@code true} 后该请求一定会被本次运行捕获；若运行
   * 已在锁竞争期间完成则返回 {@code false}，提交方必须创建新的运行。
   */
  @Transactional
  public boolean registerRequest(
      long runId,
      String sourceInstance,
      SyncRunPayload.WorkspaceRefreshSpec requestedRefresh) {
    SyncRunPayload.WorkspaceRefreshSpec refresh =
        requestedRefresh == null ? null : requestedRefresh.normalized();
    if (refresh == null) {
      return true;
    }
    List<Long> runs =
        jdbcTemplate.queryForList(
            """
            select id
              from sync_runs
             where id = ?
               and source_instance = ?
               and run_type = 'TABLE_REFRESH'
               and status in ('QUEUED', 'RUNNING', 'RETRYING', 'PAUSED')
             for update
            """,
            Long.class,
            runId,
            sourceInstance);
    if (runs.size() != 1) {
      return false;
    }
    for (FactType factType : refresh.factTypes()) {
      for (String selectorKey : refresh.selectorKeys()) {
        TargetSelector selector = normalizeSelector(refresh.selectorType(), selectorKey);
        jdbcTemplate.update(
            """
            insert into sync_run_publication_fences(
                run_id, workspace_key, source_instance, fact_type,
                target_selector_type, target_selector_key,
                required_change_version, status, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, 0, 'PENDING', current_timestamp, current_timestamp)
            on conflict (
                run_id, workspace_key, fact_type,
                target_selector_type, target_selector_key) do nothing
            """,
            runId,
            refresh.workspaceKey(),
            sourceInstance,
            factType.name(),
            selector.type().name(),
            selector.key());
      }
    }
    return true;
  }

  /**
   * 在页面镜像运行释放同源 writer 前捕获其事实版本水位。
   *
   * <p>重复调用会复用同一栅栏并重新计算状态。调用者必须仍持有有效运行租约；普通单表刷新和
   * 自动增量没有已登记工作区，因此不会创建页面栅栏。
   */
  @Transactional
  public void captureOwnedRun(SyncRun run) {
    if (run == null
        || run.getRunType() != SyncRunType.TABLE_REFRESH
        || run.getStatus() != SyncRunStatus.SUCCESS) {
      return;
    }
    lockOwnedRun(run);
    List<Long> fenceIds =
        jdbcTemplate.queryForList(
            "select id from sync_run_publication_fences where run_id = ? order by id",
            Long.class,
            run.getId());
    for (Long fenceId : fenceIds) {
      Fence fence = loadFence(fenceId);
      long requiredVersion =
          loadRequiredVersion(fence.sourceInstance(), fence.factType(), fence.selector());
      jdbcTemplate.update(
          """
          update sync_run_publication_fences
             set required_change_version = greatest(required_change_version, ?),
                 status = 'PENDING', error_message = null,
                 completed_at = null, updated_at = current_timestamp
           where id = ?
          """,
          requiredVersion,
          fenceId);
      refreshFence(fenceId);
    }
  }

  /** 在事实事务提交目标、generation 和投影任务后推进相关待处理栅栏。 */
  @Transactional
  public void advanceAfterFactPublication(String sourceInstance, FactType factType) {
    if (sourceInstance == null || sourceInstance.isBlank() || factType == null) {
      return;
    }
    List<Long> fenceIds =
        jdbcTemplate.queryForList(
            """
            select id
              from sync_run_publication_fences
             where source_instance = ?
               and fact_type = ?
               and status <> 'SUCCESS'
             order by id
            """,
            Long.class,
            sourceInstance,
            factType.name());
    fenceIds.forEach(this::refreshFence);
  }

  /** 在投影任务成功、重试或最终失败后推进直接等待该任务的栅栏。 */
  @Transactional
  public void advanceAfterProjectionTask(long projectionTaskId) {
    if (projectionTaskId <= 0L) {
      return;
    }
    List<Long> fenceIds =
        jdbcTemplate.queryForList(
            """
            select distinct fence_id
              from sync_run_publication_fence_scopes
             where projection_task_id = ?
             order by fence_id
            """,
            Long.class,
            projectionTaskId);
    fenceIds.forEach(this::refreshFence);
  }

  private void lockOwnedRun(SyncRun run) {
    if (run.getId() == null
        || run.getLeaseOwner() == null
        || run.getLeaseOwner().isBlank()) {
      throw new IllegalArgumentException("页面发布栅栏必须关联已领取的镜像运行");
    }
    List<Long> rows =
        jdbcTemplate.queryForList(
            """
            select id
              from sync_runs
             where id = ?
               and run_type = 'TABLE_REFRESH'
               and status = 'RUNNING'
               and lease_owner = ?
               and lease_until >= current_timestamp
             for update
            """,
            Long.class,
            run.getId(),
            run.getLeaseOwner());
    if (rows.size() != 1) {
      throw new SyncRunLeaseLostException(run.getId());
    }
  }

  private long loadRequiredVersion(
      String sourceInstance, FactType factType, TargetSelector selector) {
    QueryArguments query = targetQueryArguments(sourceInstance, factType, selector);
    Long version =
        jdbcTemplate.queryForObject(
            "select coalesce(max(target.change_version), 0) "
                + "from sync_run_fact_targets target where "
                + query.predicate(),
            Long.class,
            query.arguments().toArray());
    return version == null ? 0L : version;
  }

  private void refreshFence(long fenceId) {
    Fence fence = loadFence(fenceId);
    synchronizeOutstandingScopes(fence);
    synchronizeScopeStatuses(fenceId);
    boolean targetsPending = hasPendingTargets(fence);
    ScopeSummary scopes = loadScopeSummary(fenceId);
    if (scopes.failed() > 0) {
      updateFenceStatus(fenceId, "FAILED", scopes.errorMessage());
    } else if (targetsPending || scopes.pending() > 0) {
      updateFenceStatus(fenceId, "PENDING", null);
    } else {
      updateFenceStatus(fenceId, "SUCCESS", null);
    }
  }

  private Fence loadFence(long fenceId) {
    List<Fence> fences =
        jdbcTemplate.query(
            """
            select id, source_instance, fact_type, target_selector_type,
                   target_selector_key, required_change_version
              from sync_run_publication_fences
             where id = ?
             for update
            """,
            (resultSet, rowNumber) ->
                new Fence(
                    resultSet.getLong("id"),
                    resultSet.getString("source_instance"),
                    FactType.valueOf(resultSet.getString("fact_type")),
                    normalizeSelector(
                        WorkspaceScopeSelectionType.valueOf(
                            resultSet.getString("target_selector_type")),
                        resultSet.getString("target_selector_key")),
                    resultSet.getLong("required_change_version")),
            fenceId);
    if (fences.size() != 1) {
      throw new IllegalStateException("页面发布栅栏不存在：" + fenceId);
    }
    return fences.getFirst();
  }

  private boolean hasPendingTargets(Fence fence) {
    QueryArguments query =
        targetQueryArguments(fence.sourceInstance(), fence.factType(), fence.selector());
    ArrayList<Object> arguments = new ArrayList<>(query.arguments());
    arguments.add(fence.requiredVersion());
    Boolean pending =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from sync_run_fact_targets target
               where %s
                 and target.change_version <= ?
                 and (
                   target.publication_status <> 'PUBLISHED'
                   or target.published_version is null
                   or target.published_version < target.change_version)
            )
            """.formatted(query.predicate()),
            Boolean.class,
            arguments.toArray());
    return Boolean.TRUE.equals(pending);
  }

  private void synchronizeOutstandingScopes(Fence fence) {
    QueryArguments targetQuery =
        targetQueryArguments(fence.sourceInstance(), fence.factType(), fence.selector());
    ProjectionPredicate projection = projectionPredicate(fence.selector());
    ArrayList<Object> arguments = new ArrayList<>();
    arguments.add(fence.id());
    arguments.addAll(targetQuery.arguments());
    arguments.add(fence.requiredVersion());
    arguments.addAll(projection.arguments());
    jdbcTemplate.update(
        """
        insert into sync_run_publication_fence_scopes(
            fence_id, scope_type, scope_key, required_generation,
            projection_task_id, status, updated_at)
        select ?, candidate.scope_type, candidate.scope_key,
               candidate.target_generation, candidate.task_id,
               case when candidate.task_status = 'FAILED' then 'FAILED' else 'PENDING' end,
               current_timestamp
          from (
            select distinct on (task.scope_type, task.scope_key)
                   task.scope_type, task.scope_key, task.target_generation,
                   task.id as task_id, task.status as task_status
              from sync_run_fact_targets target
              join fact_projection_refresh_tasks task
                on task.fact_build_task_id = target.published_by_fact_build_task_id
               and task.source_instance = target.source_instance
               and task.fact_type = target.fact_type
             where %s
               and target.change_version <= ?
               and target.publication_status = 'PUBLISHED'
               and task.status <> 'SUCCESS'
               and (%s)
             order by task.scope_type, task.scope_key,
                      task.target_generation desc, task.id desc
          ) candidate
        on conflict (fence_id, scope_type, scope_key) do update
           set required_generation = greatest(
                   sync_run_publication_fence_scopes.required_generation,
                   excluded.required_generation),
               projection_task_id = case
                   when excluded.required_generation >=
                        sync_run_publication_fence_scopes.required_generation
                     then excluded.projection_task_id
                   else sync_run_publication_fence_scopes.projection_task_id
                 end,
               status = case
                   when excluded.required_generation >=
                        sync_run_publication_fence_scopes.required_generation
                     then excluded.status
                   else sync_run_publication_fence_scopes.status
                 end,
               updated_at = current_timestamp
        """.formatted(targetQuery.predicate(), projection.predicate()),
        arguments.toArray());
  }

  private void synchronizeScopeStatuses(long fenceId) {
    jdbcTemplate.update(
        """
        with resolved as (
          select scope.fence_id, scope.scope_type, scope.scope_key,
                 candidate.id as task_id, candidate.status as task_status
            from sync_run_publication_fence_scopes scope
            join sync_run_publication_fences fence on fence.id = scope.fence_id
            left join lateral (
              select task.id, task.status
                from fact_projection_refresh_tasks task
               where task.source_instance = fence.source_instance
                 and task.fact_type = fence.fact_type
                 and task.scope_type = scope.scope_type
                 and task.scope_key = scope.scope_key
                 and task.target_generation >= scope.required_generation
               order by case
                          when task.status = 'SUCCESS' then 0
                          when task.status = 'FAILED' then 2
                          else 1
                        end,
                        task.target_generation desc,
                        task.id desc
               limit 1
            ) candidate on true
           where scope.fence_id = ?
        )
        update sync_run_publication_fence_scopes scope
           set projection_task_id = coalesce(resolved.task_id, scope.projection_task_id),
               status = case
                 when resolved.task_status = 'SUCCESS' then 'SUCCESS'
                 when resolved.task_status = 'FAILED' then 'FAILED'
                 else 'PENDING'
               end,
               updated_at = current_timestamp
          from resolved
         where scope.fence_id = resolved.fence_id
           and scope.scope_type = resolved.scope_type
           and scope.scope_key = resolved.scope_key
        """,
        fenceId);
  }

  private ScopeSummary loadScopeSummary(long fenceId) {
    return jdbcTemplate.queryForObject(
        """
        select count(*) filter (where scope.status = 'FAILED') as failed,
               count(*) filter (where scope.status = 'PENDING') as pending,
               min(task.error_message) filter (where scope.status = 'FAILED') as error_message
          from sync_run_publication_fence_scopes scope
          left join fact_projection_refresh_tasks task on task.id = scope.projection_task_id
         where scope.fence_id = ?
        """,
        (resultSet, rowNumber) ->
            new ScopeSummary(
                resultSet.getInt("failed"),
                resultSet.getInt("pending"),
                resultSet.getString("error_message")),
        fenceId);
  }

  private void updateFenceStatus(long fenceId, String status, String errorMessage) {
    jdbcTemplate.update(
        """
        update sync_run_publication_fences
           set status = ?,
               error_message = ?,
               completed_at = case when ? = 'SUCCESS' then current_timestamp else null end,
               updated_at = current_timestamp
         where id = ?
        """,
        status,
        errorMessage,
        status,
        fenceId);
  }

  private QueryArguments targetQueryArguments(
      String sourceInstance,
      FactType factType,
      TargetSelector selector) {
    String predicate = "target.source_instance = ? and target.fact_type = ?";
    ArrayList<Object> arguments = new ArrayList<>();
    arguments.add(sourceInstance);
    arguments.add(factType.name());
    if (selector.type() == WorkspaceScopeSelectionType.PROJECT) {
      predicate += " and target.project_id = ?";
      arguments.add(parsePositiveId(selector.key(), "projectId"));
    }
    return new QueryArguments(predicate, List.copyOf(arguments));
  }

  private ProjectionPredicate projectionPredicate(TargetSelector selector) {
    return switch (selector.type()) {
      case GLOBAL -> new ProjectionPredicate("true", List.of());
      case PROJECT -> {
        long projectId = parsePositiveId(selector.key(), "projectId");
        yield new ProjectionPredicate(
            "task.scope_type = 'GLOBAL_VIEW' "
                + "or (task.scope_type = 'PROJECT' and task.scope_key = ?) "
                + "or (task.scope_type = 'ISSUE_SCOPE_GROUP' and task.scope_key like ?)",
            List.of(
                FactProjectionScopeKeyCodec.project(projectId),
                "project=" + projectId + ";%"));
      }
      case ISSUE_SCOPE_GROUP -> {
        String scopeKey = resolveIssueScopeGroupKey(selector.key());
        yield new ProjectionPredicate(
            "task.scope_type = 'GLOBAL_VIEW' "
                + "or (task.scope_type = 'ISSUE_SCOPE_GROUP' and task.scope_key = ?)",
            List.of(scopeKey));
      }
    };
  }

  private String resolveIssueScopeGroupKey(String selectorKey) {
    long groupId = parsePositiveId(selectorKey, "issueScopeGroupId");
    List<String> keys =
        jdbcTemplate.query(
            """
            select catalog.project_id, catalog.dimension
              from issue_scope_groups scope_group
              join issue_scope_catalogs catalog on catalog.id = scope_group.catalog_id
             where scope_group.id = ?
            """,
            (resultSet, rowNumber) ->
                FactProjectionScopeKeyCodec.issueScopeGroup(
                    resultSet.getLong("project_id"),
                    IssueScopeDimension.valueOf(resultSet.getString("dimension")),
                    groupId),
            groupId);
    if (keys.size() != 1) {
      throw new IllegalArgumentException("议题范围组不存在：" + groupId);
    }
    return keys.getFirst();
  }

  private TargetSelector normalizeSelector(
      WorkspaceScopeSelectionType type, String selectorKey) {
    if (type == null || selectorKey == null || selectorKey.isBlank()) {
      throw new IllegalArgumentException("页面发布栅栏选择器不完整");
    }
    String key = selectorKey.trim();
    switch (type) {
      case GLOBAL -> {
        if (!"*".equals(key)) {
          throw new IllegalArgumentException("全局页面发布栅栏键必须为 *");
        }
      }
      case PROJECT -> parsePositiveId(key, "projectId");
      case ISSUE_SCOPE_GROUP -> parsePositiveId(key, "issueScopeGroupId");
    }
    return new TargetSelector(type, key);
  }

  private long parsePositiveId(String value, String field) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L) {
        throw new IllegalArgumentException(field + " 必须是正整数");
      }
      return parsed;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " 必须是正整数", error);
    }
  }

  private record TargetSelector(WorkspaceScopeSelectionType type, String key) {}

  private record QueryArguments(String predicate, List<Object> arguments) {}

  private record ProjectionPredicate(String predicate, List<Object> arguments) {}

  private record Fence(
      long id,
      String sourceInstance,
      FactType factType,
      TargetSelector selector,
      long requiredVersion) {}

  private record ScopeSummary(int failed, int pending, String errorMessage) {}
}
