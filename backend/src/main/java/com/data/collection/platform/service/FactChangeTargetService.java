package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactChangeIdentity;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.entity.VersionedFactChangeTarget;
import com.data.collection.platform.config.GitlabMirrorProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 在 ODS 事务内为真实业务变化分配版本并写入唯一事实发布 outbox。 */
@Service
public class FactChangeTargetService {
  private static final int MAX_BATCH_SIZE = 1000;

  private final JdbcTemplate jdbcTemplate;
  private final GitlabFactChangeResolver changeResolver;
  private final GitlabMirrorProperties properties;

  public FactChangeTargetService(
      JdbcTemplate jdbcTemplate,
      GitlabFactChangeResolver changeResolver,
      GitlabMirrorProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.changeResolver = changeResolver;
    this.properties = properties;
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
        normalizedIdentities(changeResolver.resolve(sourceInstance, sourceTable, changes));
    ArrayList<VersionedFactChangeTarget> targets = new ArrayList<>(identities.size());
    int batchSize =
        Math.min(MAX_BATCH_SIZE, Math.max(1, properties.getFactTargetBatchSize()));
    for (int start = 0; start < identities.size(); start += batchSize) {
      List<FactChangeIdentity> batch =
          identities.subList(start, Math.min(identities.size(), start + batchSize));
      Map<FactChangeKey, Long> versions = registerBatch(mirrorRunId, tableTaskId, batch);
      for (FactChangeIdentity identity : batch) {
        Long version = versions.get(FactChangeKey.of(identity));
        if (version == null || version <= 0L) {
          throw new IllegalStateException("无法为事实变化分配版本：" + identity);
        }
        targets.add(new VersionedFactChangeTarget(mirrorRunId, identity, version));
      }
    }
    return List.copyOf(targets);
  }

  private List<FactChangeIdentity> normalizedIdentities(
      List<FactChangeIdentity> resolved) {
    if (resolved == null || resolved.isEmpty()) {
      return List.of();
    }
    java.util.TreeMap<FactChangeKey, FactChangeIdentity> normalized = new java.util.TreeMap<>();
    for (FactChangeIdentity identity : resolved) {
      normalized.merge(FactChangeKey.of(identity), identity, this::mergeIdentityDetails);
    }
    return List.copyOf(normalized.values());
  }

  private FactChangeIdentity mergeIdentityDetails(
      FactChangeIdentity first, FactChangeIdentity second) {
    return new FactChangeIdentity(
        first.sourceInstance(),
        first.factType(),
        first.rootId(),
        mergeDetail("projectId", first.projectId(), second.projectId()),
        mergeDetail("iid", first.iid(), second.iid()));
  }

  private Long mergeDetail(String field, Long first, Long second) {
    if (first != null && second != null && !first.equals(second)) {
      throw new IllegalStateException("同一事实根包含冲突的 " + field + "：" + first + "/" + second);
    }
    return first == null ? second : first;
  }

  private Map<FactChangeKey, Long> registerBatch(
      long mirrorRunId, Long tableTaskId, List<FactChangeIdentity> identities) {
    String values =
        String.join(", ", java.util.Collections.nCopies(identities.size(), "(?, ?, ?, ?, ?, ?)"));
    String sql =
        """
        with input(ordinal, source_instance, fact_type, root_id, project_id, iid) as (
          values %s
        ), allocated as (
          select input.*, nextval('fact_change_version_seq') as change_version
            from input
           order by ordinal
        ), updated_heads as (
          insert into fact_change_heads(
              source_instance, fact_type, root_id,
              latest_change_version, published_version, updated_at)
          select source_instance, fact_type, root_id,
                 change_version, 0, current_timestamp
            from allocated
           order by ordinal
          on conflict (source_instance, fact_type, root_id) do update
             set latest_change_version = greatest(
                     fact_change_heads.latest_change_version,
                     excluded.latest_change_version),
                 updated_at = current_timestamp
          returning source_instance, fact_type, root_id, latest_change_version
        )
        insert into sync_run_fact_targets(
            mirror_run_id, source_instance, fact_type, root_id,
            change_version, project_id, iid, first_task_id, last_task_id,
            publication_status, created_at, updated_at)
        select ?, head.source_instance, head.fact_type, head.root_id,
               head.latest_change_version, input.project_id, input.iid, ?, ?,
               'PENDING', current_timestamp, current_timestamp
          from updated_heads head
          join input using (source_instance, fact_type, root_id)
         order by input.ordinal
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
        returning source_instance, fact_type, root_id, change_version
        """
            .formatted(values);
    ArrayList<Object> args = new ArrayList<>(identities.size() * 6 + 3);
    for (int index = 0; index < identities.size(); index++) {
      FactChangeIdentity identity = identities.get(index);
      args.add(index);
      args.add(identity.sourceInstance());
      args.add(identity.factType().name());
      args.add(identity.rootId());
      args.add(identity.projectId());
      args.add(identity.iid());
    }
    args.add(mirrorRunId);
    args.add(tableTaskId);
    args.add(tableTaskId);
    return jdbcTemplate.query(
        sql,
        resultSet -> {
          LinkedHashMap<FactChangeKey, Long> versions = new LinkedHashMap<>();
          while (resultSet.next()) {
            FactChangeKey key =
                new FactChangeKey(
                    resultSet.getString("source_instance"),
                    FactType.valueOf(resultSet.getString("fact_type")),
                    resultSet.getLong("root_id"));
            versions.put(key, resultSet.getLong("change_version"));
          }
          return Map.copyOf(versions);
        },
        args.toArray());
  }

  private record FactChangeKey(String sourceInstance, FactType factType, long rootId)
      implements Comparable<FactChangeKey> {
    private static FactChangeKey of(FactChangeIdentity identity) {
      return new FactChangeKey(
          identity.sourceInstance(), identity.factType(), identity.rootId());
    }

    @Override
    public int compareTo(FactChangeKey other) {
      int sourceOrder = sourceInstance.compareTo(other.sourceInstance);
      if (sourceOrder != 0) {
        return sourceOrder;
      }
      int typeOrder = factType.compareTo(other.factType);
      return typeOrder == 0 ? Long.compare(rootId, other.rootId) : typeOrder;
    }
  }
}
