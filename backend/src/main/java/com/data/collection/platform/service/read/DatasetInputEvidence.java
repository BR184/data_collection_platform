package com.data.collection.platform.service.read;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 数据集完整输入的正证据；只允许本地业务权威、已原子装载的来源清单或真实成功整表运行。 */
public sealed interface DatasetInputEvidence permits DatasetInputEvidence.FormalCommit,
    DatasetInputEvidence.CompatibilityInventory, DatasetInputEvidence.MirrorTableRun {
  /** 稳定捕获前需保护其名字的证据关系；只取 ACCESS SHARE 防换表，不阻塞普通同步写入。 */
  List<String> evidenceRelations();

  /** 在受保护事务内核验拥有者和完整输入，返回真实截止时刻及封印；缺证据直接拒绝，不推断为空。 */
  VerifiedInput verify(ReadPublicationDomainKey domain, JdbcTemplate jdbc, JsonUtils json);

  /** 完整输入认证结果，供候选身份保存；不以当前运行状态代替历史数据可读性。 */
  record VerifiedInput(String sealJson, Instant dataAsOf) {}

  /** 本地人工业务表本身是权威，稳定事务和真实关系闭包形成一个完整已提交输入。 */
  record FormalCommit() implements DatasetInputEvidence {
    @Override public List<String> evidenceRelations() { return List.of(); }
    @Override public VerifiedInput verify(ReadPublicationDomainKey domain, JdbcTemplate jdbc, JsonUtils json) {
      if (domain.producerKind() != ReadPublicationProducerKind.FORMAL_RECORD) {
        throw new IllegalArgumentException("本地业务提交不能认证外部采集来源");
      }
      Instant cutoff = jdbc.queryForObject("select transaction_timestamp()", java.time.OffsetDateTime.class).toInstant();
      return new VerifiedInput(json.toJson(java.util.Map.of("owner", "FORMAL_RECORD", "domain", domain,
          "committedInputCutoff", cutoff)), cutoff);
    }
  }

  /**
   * 原子装载的兼容来源清单。MySQL/Mongo 共用的显示状态不能证明具体来源完整性；
   * 这里检查各自专有清单的真实行数与原始行集合，以及完整装载时间，失败不会抹掉上一清单。
   */
  record CompatibilityInventory(Family family, List<String> keys) implements DatasetInputEvidence {
    public CompatibilityInventory {
      keys = List.copyOf(keys);
      if (family == null || keys.isEmpty() || keys.stream().anyMatch(key -> key == null || key.isBlank())
          || keys.stream().distinct().count() != keys.size()) {
        throw new IllegalArgumentException("兼容来源认证必须声明完整且唯一的来源清单");
      }
    }
    @Override public List<String> evidenceRelations() {
      return family == Family.MYSQL_WALKTHROUGH
          ? List.of("legacy_mysql_imported_tables", "legacy_mysql_imported_rows")
          : List.of("legacy_mongo_imported_collections", "legacy_mongo_imported_documents");
    }
    @Override public VerifiedInput verify(ReadPublicationDomainKey domain, JdbcTemplate jdbc, JsonUtils json) {
      ReadPublicationProducerKind expected = family == Family.MYSQL_WALKTHROUGH
          ? ReadPublicationProducerKind.COMPAT_WALKTHROUGH : ReadPublicationProducerKind.COMPAT_REVIEW;
      if (domain.producerKind() != expected) { throw new IllegalArgumentException("兼容清单与数据集拥有者不一致"); }
      String metadata = family == Family.MYSQL_WALKTHROUGH ? "legacy_mysql_imported_tables" : "legacy_mongo_imported_collections";
      String rows = family == Family.MYSQL_WALKTHROUGH ? "legacy_mysql_imported_rows" : "legacy_mongo_imported_documents";
      String column = family == Family.MYSQL_WALKTHROUGH ? "table_name" : "collection_name";
      java.util.List<java.util.Map<String, Object>> inventory = new java.util.ArrayList<>();
      Instant cutoff = null;
      for (String key : keys) {
        var entries = jdbc.query("select " + column + ", record_count, last_synced_at::timestamptz as cutoff from "
            + metadata + " where " + column + " = ? and last_synced_at is not null and record_count >= 0",
            (rs, row) -> new InventoryRow(rs.getString(1), rs.getLong(2), rs.getObject(3, java.time.OffsetDateTime.class).toInstant()), key);
        if (entries.size() != 1) { throw new IllegalStateException("兼容来源缺少完整装载证据：" + key); }
        InventoryRow entry = entries.getFirst();
        long actual = jdbc.queryForObject("select count(*) from " + rows + " where " + column + " = ?", Long.class, key);
        if (actual != entry.count()) { throw new IllegalStateException("兼容来源清单与原始内容不一致：" + key); }
        inventory.add(java.util.Map.of("key", entry.key(), "rows", actual, "dataAsOf", entry.cutoff()));
        if (cutoff == null || entry.cutoff().isBefore(cutoff)) { cutoff = entry.cutoff(); }
      }
      return new VerifiedInput(json.toJson(java.util.Map.of("owner", family.name(), "inventory", inventory)), cutoff);
    }
    private record InventoryRow(String key, long count, Instant cutoff) {}
  }

  /** 单个真实镜像运行的整表任务已成功闭合；不能用事实运行成功或其它表成功来认证本表。 */
  record MirrorTableRun(long runId, long stateId) implements DatasetInputEvidence {
    public MirrorTableRun {
      if (runId <= 0 || stateId <= 0) { throw new IllegalArgumentException("镜像认证必须关联真实运行和表状态"); }
    }
    @Override public List<String> evidenceRelations() { return List.of("sync_runs", "sync_run_table_states", "sync_run_table_tasks"); }
    @Override public VerifiedInput verify(ReadPublicationDomainKey domain, JdbcTemplate jdbc, JsonUtils json) {
      if (domain.producerKind() != ReadPublicationProducerKind.MIRROR_TABLE) {
        throw new IllegalArgumentException("镜像运行不能认证非镜像数据集");
      }
      var cutoffs = jdbc.queryForList("""
          select max(task.finished_at)::timestamptz from sync_run_table_tasks task
          join sync_run_table_states state on state.id = task.state_id
          join sync_runs run on run.id = task.run_id
           where task.run_id = ? and task.state_id = ? and state.mirror_table = ?
             and state.last_full_verified_at is not null and not state.dirty_flag
             and run.status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
             and not exists(select 1 from sync_run_table_tasks active
               where active.state_id = state.id and active.run_id <> run.id
                 and active.status not in ('SUCCESS', 'FAILED', 'CANCELLED', 'SKIPPED', 'TIMEOUT'))
             and (not exists(select 1 from sync_run_table_tasks full_task
                    where full_task.run_id = run.id and full_task.state_id = state.id and full_task.row_strategy = 'FULL_RECONCILE')
                  or state.last_full_verified_at >= run.started_at)
             and not exists(select 1 from sync_run_table_tasks interrupted_full
               where interrupted_full.state_id = state.id and interrupted_full.row_strategy = 'FULL_RECONCILE'
                 and interrupted_full.status in ('FAILED', 'CANCELLED', 'TIMEOUT')
                 and coalesce(interrupted_full.finished_at, interrupted_full.started_at) > state.last_full_verified_at)
             and not exists(select 1 from sync_run_table_tasks later_attempt
               join sync_runs later_run on later_run.id = later_attempt.run_id
               where later_attempt.state_id = state.id and later_attempt.run_id <> run.id
                 and later_run.id > run.id)
           having count(*) > 0 and bool_and(task.status = 'SUCCESS')
          """, java.time.OffsetDateTime.class, runId, stateId, domain.tableKey());
      if (cutoffs.size() != 1 || cutoffs.getFirst() == null) {
        throw new IllegalStateException("镜像表尚无本轮完整成功证据：" + domain.tableKey());
      }
      Instant cutoff = cutoffs.getFirst().toInstant();
      return new VerifiedInput(json.toJson(java.util.Map.of("owner", "MIRROR_TABLE", "runId", runId,
          "stateId", stateId, "dataAsOf", cutoff)), cutoff);
    }
  }

  /** 来源清单有不同所有权和形态，禁止把两种兼容输入的认证互换。 */
  enum Family { MYSQL_WALKTHROUGH, MONGO_REVIEW }
}
