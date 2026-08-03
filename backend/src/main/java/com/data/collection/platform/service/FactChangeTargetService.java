package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactChangeIdentity;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.entity.VersionedFactChangeTarget;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 在 ODS 事务内为真实业务变化分配版本并写入唯一事实发布 outbox。 */
@Service
public class FactChangeTargetService {
  private final JdbcTemplate jdbcTemplate;
  private final GitlabFactChangeResolver changeResolver;

  public FactChangeTargetService(
      JdbcTemplate jdbcTemplate, GitlabFactChangeResolver changeResolver) {
    this.jdbcTemplate = jdbcTemplate;
    this.changeResolver = changeResolver;
  }

  /**
   * 登记一批镜像业务变化影响的稳定根。
   *
   * <p>调用者必须与 ODS DML 位于同一平台事务。目标按稳定键排序获取版本头行锁，
   * 同一运行同一根再次变化会重置为 PENDING 并清除旧归属和发布元数据。
   */
  public List<VersionedFactChangeTarget> registerChanges(
      long mirrorRunId,
      Long tableTaskId,
      String sourceInstance,
      String sourceTable,
      List<MirrorRowChange> changes) {
    if (mirrorRunId <= 0L) {
      throw new IllegalArgumentException("事实变化登记必须包含镜像运行 ID");
    }
    List<FactChangeIdentity> identities =
        changeResolver.resolve(sourceInstance, sourceTable, changes).stream().sorted().toList();
    ArrayList<VersionedFactChangeTarget> targets = new ArrayList<>(identities.size());
    for (FactChangeIdentity identity : identities) {
      long version = advanceHead(identity);
      upsertRunTarget(mirrorRunId, tableTaskId, identity, version);
      targets.add(new VersionedFactChangeTarget(mirrorRunId, identity, version));
    }
    return List.copyOf(targets);
  }

  private long advanceHead(FactChangeIdentity identity) {
    Long version =
        jdbcTemplate.queryForObject(
            """
            with allocated as (
              select nextval('fact_change_version_seq') as change_version
            )
            insert into fact_change_heads(
                source_instance, fact_type, root_id,
                latest_change_version, published_version, updated_at)
            select ?, ?, ?, change_version, 0, current_timestamp
              from allocated
            on conflict (source_instance, fact_type, root_id) do update
               set latest_change_version = greatest(
                       fact_change_heads.latest_change_version,
                       excluded.latest_change_version),
                   updated_at = current_timestamp
            returning latest_change_version
            """,
            Long.class,
            identity.sourceInstance(),
            identity.factType().name(),
            identity.rootId());
    if (version == null || version <= 0L) {
      throw new IllegalStateException("无法为事实变化分配版本：" + identity);
    }
    return version;
  }

  private void upsertRunTarget(
      long mirrorRunId,
      Long tableTaskId,
      FactChangeIdentity identity,
      long changeVersion) {
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            mirror_run_id, source_instance, fact_type, root_id,
            change_version, project_id, iid, first_task_id, last_task_id,
            publication_status, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', current_timestamp, current_timestamp)
        on conflict (mirror_run_id, source_instance, fact_type, root_id) do update
           set change_version = excluded.change_version,
               project_id = coalesce(excluded.project_id, sync_run_fact_targets.project_id),
               iid = coalesce(excluded.iid, sync_run_fact_targets.iid),
               last_task_id = excluded.last_task_id,
               publication_status = 'PENDING',
               assigned_fact_run_id = null,
               assigned_fact_build_task_id = null,
               published_version = null,
               published_by_fact_build_task_id = null,
               published_at = null,
               updated_at = current_timestamp
        """,
        mirrorRunId,
        identity.sourceInstance(),
        identity.factType().name(),
        identity.rootId(),
        changeVersion,
        identity.projectId(),
        identity.iid(),
        tableTaskId,
        tableTaskId);
  }
}
