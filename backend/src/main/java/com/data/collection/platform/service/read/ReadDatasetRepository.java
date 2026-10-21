package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.DatasetRelationInput;
import com.data.collection.platform.entity.read.ReadPublication;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 完整数据集的行修订与模式存储；未变化行复用，发布只修改变化行的可见区间。 */
@Repository
public class ReadDatasetRepository {
  private final JdbcTemplate jdbcTemplate;

  public ReadDatasetRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 从候选与其固定基线合成来源模式；不以当前可变设置代替候选捕获的值。 */
  public DatasetReadModes capturedReadModes(ReadPublication publication) {
    boolean candidate = publication.activeCandidate();
    long visibleSequence = !candidate ? publication.sequenceNo() : publication.basePublicationId() == null ? 0
        : java.util.Objects.requireNonNull(jdbcTemplate.queryForObject(
            "select sequence_no from read_publications where id = ?", Long.class, publication.basePublicationId()));
    List<DatasetReadModes> modes = jdbcTemplate.query("""
        select (row_payload->>'enabled')::boolean and row_payload->>'code_review_read_mode' = 'compatibility',
               (row_payload->>'enabled')::boolean and row_payload->>'review_data_read_mode' = 'compatibility'
          from read_dataset_rows stored
         where stored.domain_id = ? and stored.table_key = 'code_review_match_mode_db_settings'
           and stored.row_payload->>'id' = '1'
           and ((? and stored.created_by_publication_id = ?) or
             (stored.epoch_id = ? and stored.visible_from <= ?
               and (stored.visible_to is null or ? < stored.visible_to)
               and (not ? or not exists(select 1 from read_dataset_changes changed
                 where changed.publication_id = ? and changed.table_key = stored.table_key
                   and changed.row_key = stored.row_key))))
        """, (rs, row) -> new DatasetReadModes(rs.getBoolean(1), rs.getBoolean(2)),
        publication.domainId(), candidate, publication.id(), publication.epochId(), visibleSequence, visibleSequence,
        candidate, publication.id());
    if (modes.size() != 1) { throw new IllegalStateException("来源模式缺少唯一完整捕获行"); }
    return modes.getFirst();
  }

  /** 捕获或公开的有效模式，disabled 时两个兼容入口均不生效。 */
  public record DatasetReadModes(boolean codeCompatibility, boolean reviewCompatibility) {}

  /**
   * 在生产者已取得完整稳定输入的事务中捕获一张完整关系；参数和字段类型不经 Java 文本转换。
   * 同键重复是缺陷，由真实唯一约束拒绝；没有稳定键时只允许完整新代际并保留重复行。
   */
  public long captureRelation(ReadPublication candidate, ReadPublication base, DatasetRelationInput input) {
    List<UUID> mutable = jdbcTemplate.queryForList("select id from read_publications where id = ? and state = 'BUILDING' for update",
        UUID.class, candidate.id());
    if (mutable.isEmpty()) { throw new IllegalStateException("数据集捕获只能写 BUILDING 候选"); }
    boolean newEpoch = base == null || !base.epochId().equals(candidate.epochId());
    if (input.keyColumns().isEmpty() && !newEpoch) {
      throw new IllegalArgumentException("无稳定行键的数据集必须完整构建新代际");
    }
    String schema = jdbcTemplate.queryForObject("""
        select coalesce(jsonb_agg(jsonb_build_object('name', a.attname,
            'type', format_type(a.atttypid, a.atttypmod), 'nullable', not a.attnotnull)
            order by a.attnum), '[]'::jsonb)::text
          from pg_attribute a where a.attrelid = ?::regclass and a.attnum > 0 and not a.attisdropped
        """, String.class, input.relationName());
    VersionedDatasetSql.columns(schema);
    String rowKey = input.keyColumns().isEmpty()
        ? "row_number() over(order by to_jsonb(source_row)::text)::text"
        : "jsonb_build_array(" + input.keyColumns().stream().map(column -> "source_row." + column)
            .collect(java.util.stream.Collectors.joining(", ")) + ")::text";
    List<Object> arguments = new ArrayList<>(input.arguments());
    arguments.add(candidate.domainId());
    arguments.add(candidate.epochId());
    arguments.add(input.tableKey());
    arguments.add(candidate.id());
    arguments.add(newEpoch);
    arguments.add(base == null ? candidate.epochId() : base.epochId());
    arguments.add(base == null ? 0 : base.sequenceNo());
    arguments.add(base == null ? 0 : base.sequenceNo());
    arguments.add(stringArray(input.ignoredIngestColumns()));
    arguments.add(stringArray(input.ignoredIngestColumns()));
    jdbcTemplate.update("""
        with source_rows as (%s), captured as (
          select %s as row_key, to_jsonb(source_row) as payload from source_rows source_row
        ) insert into read_dataset_rows(domain_id, epoch_id, table_key, row_key, row_payload, created_by_publication_id)
          select ?, ?, ?, captured.row_key, captured.payload, ? from captured
           where ? or not exists (
             select 1 from read_dataset_rows baseline where baseline.epoch_id = ?
               and baseline.visible_from <= ? and (baseline.visible_to is null or ? < baseline.visible_to)
               and baseline.table_key = '%s' and baseline.row_key = captured.row_key
               and baseline.row_payload - ?::text[] = captured.payload - ?::text[])
        """.formatted(input.selectSql(), rowKey, input.tableKey()), arguments.toArray());
    jdbcTemplate.update("""
        insert into read_dataset_changes(publication_id, table_key, row_key, operation, next_revision_id)
        select created_by_publication_id, table_key, row_key, 'UPSERT', id from read_dataset_rows
         where created_by_publication_id = ? and table_key = ?
        """, candidate.id(), input.tableKey());
    long count = jdbcTemplate.queryForObject("select count(*) from (" + input.selectSql() + ") complete_rows",
        Long.class, input.arguments().toArray());
    jdbcTemplate.update("""
        insert into read_dataset_tables(publication_id, table_key, relation_name, schema_payload,
            key_columns, ignored_ingest_columns, captured_row_count)
        values (?, ?, ?, ?::jsonb, to_jsonb(?::text[]), to_jsonb(?::text[]), ?)
        """, candidate.id(), input.tableKey(), input.relationName(), schema,
        stringArray(input.keyColumns()), stringArray(input.ignoredIngestColumns()), count);
    if (!newEpoch) {
      List<Object> deletionArguments = new ArrayList<>(input.arguments());
      deletionArguments.add(candidate.id());
      deletionArguments.add(input.tableKey());
      deletionArguments.add(base.epochId());
      deletionArguments.add(base.sequenceNo());
      deletionArguments.add(base.sequenceNo());
      deletionArguments.add(input.tableKey());
      jdbcTemplate.update("""
          with source_rows as (%s), captured as (select %s as row_key from source_rows source_row)
          insert into read_dataset_changes(publication_id, table_key, row_key, operation, previous_revision_id)
          select ?, ?, baseline.row_key, 'DELETE', baseline.id from read_dataset_rows baseline
           where baseline.epoch_id = ? and baseline.visible_from <= ?
             and (baseline.visible_to is null or ? < baseline.visible_to) and baseline.table_key = ?
             and not exists(select 1 from captured where captured.row_key = baseline.row_key)
          """.formatted(input.selectSql(), rowKey), deletionArguments.toArray());
    }
    return count;
  }

  /** 仅完整关系闭包通过实际行数核验的数据集才可发布；空表仍必须有模式和明确捕获记录。 */
  public void requireCompleteCapture(ReadPublication candidate, ReadPublication base) {
    Long tableCount = jdbcTemplate.queryForObject("select count(*) from read_dataset_tables where publication_id = ?",
        Long.class, candidate.id());
    if (tableCount == null || tableCount == 0) {
      throw new IllegalStateException("数据集没有完整捕获的关系闭包");
    }
    List<String> incomplete = jdbcTemplate.queryForList("""
        select tables.table_key from read_dataset_tables tables where tables.publication_id = ?
          and tables.captured_row_count <> (
            (select count(*) from read_dataset_rows candidate_rows
              where candidate_rows.created_by_publication_id = tables.publication_id
                and candidate_rows.table_key = tables.table_key)
            + case when ? then 0 else (select count(*) from read_dataset_rows baseline
                where baseline.epoch_id = ? and baseline.table_key = tables.table_key
                  and baseline.visible_from <= ? and (baseline.visible_to is null or ? < baseline.visible_to)
                  and not exists(select 1 from read_dataset_changes change
                    where change.publication_id = tables.publication_id and change.table_key = baseline.table_key
                      and change.row_key = baseline.row_key)) end)
        """, String.class, candidate.id(), base == null || !base.epochId().equals(candidate.epochId()),
        base == null ? candidate.epochId() : base.epochId(), base == null ? 0 : base.sequenceNo(),
        base == null ? 0 : base.sequenceNo());
    if (!incomplete.isEmpty()) {
      throw new IllegalStateException("数据集完整行数不匹配：" + incomplete);
    }
  }

  /** 唯一发布事务中关闭被替换/删除的旧行、打开候选修订；不会复制全表或移动业务编辑表。 */
  public void publishRevisions(ReadPublication candidate, ReadPublication base, long sequence) {
    requireCaptureProof(candidate);
    if (base != null && !base.epochId().equals(candidate.epochId())) {
      jdbcTemplate.update("update read_dataset_rows set visible_to = ? where epoch_id = ? and visible_from is not null and visible_to is null",
          sequence, base.epochId());
    }
    jdbcTemplate.update("""
        update read_dataset_rows baseline set visible_to = ?
         where baseline.epoch_id = ? and baseline.visible_from is not null and baseline.visible_to is null
           and exists(select 1 from read_dataset_changes change where change.publication_id = ?
             and change.table_key = baseline.table_key and change.row_key = baseline.row_key)
        """, sequence, candidate.epochId(), candidate.id());
    jdbcTemplate.update("update read_dataset_rows set visible_from = ? where created_by_publication_id = ? and visible_from is null",
        sequence, candidate.id());
  }

  /** 中止只删除本候选从未公开的行；历史和当前数据保持可读。 */
  public int abort(UUID publicationId) {
    jdbcTemplate.update("delete from read_dataset_producer_intents where candidate_publication_id = ?", publicationId);
    jdbcTemplate.update("delete from read_dataset_changes where publication_id = ?", publicationId);
    jdbcTemplate.update("delete from read_dataset_tables where publication_id = ?", publicationId);
    jdbcTemplate.update("delete from read_dataset_capture_proofs where publication_id = ?", publicationId);
    return jdbcTemplate.update("delete from read_dataset_rows where created_by_publication_id = ? and visible_from is null", publicationId);
  }

  /** 回收只删除不覆盖任何保留序号的历史行；由上层先对发布身份取排他锁并复核资格。 */
  public int deleteUnreferenced(UUID epochId, Collection<Long> retained, int batchSize) {
    String sequences = "{" + retained.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")) + "}";
    return jdbcTemplate.update("""
        delete from read_dataset_rows where id in (
          select row.id from read_dataset_rows row where row.epoch_id = ? and row.visible_from is not null
            and not exists(select 1 from unnest(?::bigint[]) keep(sequence)
              where row.visible_from <= keep.sequence and (row.visible_to is null or keep.sequence < row.visible_to))
          order by row.id limit ?)
        """, epochId, sequences, batchSize);
  }

  /** 尚有被当前版本复用的修订时，保留其创建发布元数据。 */
  public long countCreatedRows(UUID publicationId) {
    return jdbcTemplate.queryForObject("select count(*) from read_dataset_rows where created_by_publication_id = ?",
        Long.class, publicationId);
  }

  /** 同代际没有任何业务增删改时可沿用当前版本；不以 count/max 或装载时刻判等。 */
  public boolean hasChanges(UUID publicationId) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject("select exists(select 1 from read_dataset_changes where publication_id = ?)",
        Boolean.class, publicationId));
  }

  /**
   * 用完整业务行多重集合与真实模式比较来源和当前版本；保留重复行及 NULL，不用 count/max 近似判等。
   * 完全无业务变化时在创建候选之前返回，避免每轮零变化同步积累空候选审计与复制全量行。
   */
  public boolean matchesCompleteSource(ReadPublication base, List<DatasetRelationInput> inputs) {
    if (!matchesRelationSchemas(base, inputs)) { return false; }
    for (DatasetRelationInput input : inputs) {
      List<Object> arguments = new ArrayList<>(input.arguments());
      arguments.add(stringArray(input.ignoredIngestColumns()));
      arguments.add(stringArray(input.ignoredIngestColumns()));
      arguments.add(base.epochId());
      arguments.add(input.tableKey());
      arguments.add(base.sequenceNo());
      arguments.add(base.sequenceNo());
      Boolean sameRows = jdbcTemplate.queryForObject("""
          with source_rows as (%s), actual as (
            select to_jsonb(source_row) - ?::text[] as payload, count(*) as rows
              from source_rows source_row group by 1), published as (
            select row_payload - ?::text[] as payload, count(*) as rows from read_dataset_rows
              where epoch_id = ? and table_key = ? and visible_from <= ?
                and (visible_to is null or ? < visible_to) group by 1)
          select not exists(select 1 from actual full join published using(payload)
            where actual.rows is distinct from published.rows)
          """.formatted(input.selectSql()), Boolean.class, arguments.toArray());
      if (!Boolean.TRUE.equals(sameRows)) { return false; }
    }
    return true;
  }

  /** 字段类型、稳定键或关系闭包变化时必须开新代际，不能复用旧模式下的行修订。 */
  public boolean matchesRelationSchemas(ReadPublication base, List<DatasetRelationInput> inputs) {
    if (base == null) { return false; }
    List<String> retainedTables = jdbcTemplate.queryForList("select table_key from read_dataset_tables where publication_id = ? order by table_key",
        String.class, base.id());
    if (!retainedTables.equals(inputs.stream().map(DatasetRelationInput::tableKey).sorted().toList())) { return false; }
    for (DatasetRelationInput input : inputs) {
      Boolean sameSchema = jdbcTemplate.queryForObject("""
          select exists(select 1 from read_dataset_tables snapshot where snapshot.publication_id = ?
            and snapshot.table_key = ? and snapshot.relation_name = ?
            and snapshot.key_columns = to_jsonb(?::text[]) and snapshot.ignored_ingest_columns = to_jsonb(?::text[])
            and snapshot.schema_payload = (select jsonb_agg(jsonb_build_object('name', a.attname,
                'type', format_type(a.atttypid, a.atttypmod), 'nullable', not a.attnotnull) order by a.attnum)
              from pg_attribute a where a.attrelid = ?::regclass and a.attnum > 0 and not a.attisdropped))
          """, Boolean.class, base.id(), input.tableKey(), input.relationName(), stringArray(input.keyColumns()),
          stringArray(input.ignoredIngestColumns()), input.relationName());
      if (!Boolean.TRUE.equals(sameSchema)) { return false; }
    }
    return true;
  }

  /** 完整捕获结束后核验实际行数并冻结关系清单；最终发布不重新扫描或聚合业务行。 */
  public void sealCapture(ReadPublication candidate, ReadPublication base) {
    requireCompleteCapture(candidate, base);
    jdbcTemplate.update("""
        insert into read_dataset_capture_proofs(publication_id, source_seal, definition_snapshot_id, table_manifest)
        select p.id, p.source_seal, p.definition_snapshot_id,
          (select jsonb_agg(to_jsonb(t) - 'captured_at' order by t.table_key)
             from read_dataset_tables t where t.publication_id = p.id)
          from read_publications p where p.id = ? and p.state = 'BUILDING'
        """, candidate.id());
  }

  /** 完成证明必须匹配该候选的封印、定义与完整模式清单；状态名称不能代替捕获产物。 */
  public void requireCaptureProof(ReadPublication candidate) {
    Boolean complete = jdbcTemplate.queryForObject("""
        select exists(select 1 from read_dataset_capture_proofs proof where proof.publication_id = ?
          and proof.source_seal = ?::jsonb and proof.definition_snapshot_id = ?
          and proof.table_manifest = (select jsonb_agg(to_jsonb(t) - 'captured_at' order by t.table_key)
            from read_dataset_tables t where t.publication_id = proof.publication_id))
        """, Boolean.class, candidate.id(), candidate.sourceSealJson(), candidate.definitionSnapshotId());
    if (!Boolean.TRUE.equals(complete)) { throw new IllegalStateException("数据集尚无匹配完整捕获证明"); }
  }

  private static String stringArray(Collection<String> values) {
    // 此处均为构造器已核验的简单标识符，不接受任意字符串数组拼接。
    return "{" + String.join(",", values) + "}";
  }
}
