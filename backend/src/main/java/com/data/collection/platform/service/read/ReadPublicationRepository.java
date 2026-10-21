package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.read.FactRootEntry;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomain;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.entity.read.ReadPublicationState;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 读发布域、发布身份、候选根清单与范围 generation 的唯一持久化入口。 */
@Repository
public class ReadPublicationRepository {
  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ReadPublicationRepository.class);
  private final JdbcTemplate jdbcTemplate;
  private final ReadDatasetRepository datasetRows;

  public ReadPublicationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.datasetRows = new ReadDatasetRepository(jdbcTemplate);
  }

  /** 非事实数据集的最终短事务提交，复用同一域序号和发布指针。 */
  public void publishDatasetRevisions(ReadPublication candidate, ReadPublication base, long sequence) {
    if (findDomain(candidate.domainId()).orElseThrow().key().producerKind() == ReadPublicationProducerKind.CAT_MIRROR) {
      requireDatasetCaptureProof(candidate);
      return;
    }
    datasetRows.publishRevisions(candidate, base, sequence);
  }

  /** 在进入 READY 前核验非事实数据集的完整捕获证明。 */
  public void requireDatasetCaptureProof(ReadPublication publication) {
    if (findDomain(publication.domainId()).orElseThrow().key().producerKind() == ReadPublicationProducerKind.CAT_MIRROR) {
      Boolean complete = jdbcTemplate.queryForObject("""
          select exists(select 1 from read_publication_cat_snapshots ref
            join bi_cat_test_snapshots snapshot on snapshot.snapshot_id = ref.snapshot_id
           where ref.publication_id = ? and ref.product_version_id = snapshot.product_version_id
             and ref.product_version_key = snapshot.product_version_key and ref.test_stage = snapshot.test_stage
             and snapshot.data_status in ('READY', 'EMPTY')
             and (snapshot.data_status = 'EMPTY' or (snapshot.overall_pass_rate is not null
               and snapshot.attained_function_count is not null and snapshot.total_function_count is not null))
             and ref.module_count = (select count(*) from bi_cat_test_modules where snapshot_id = ref.snapshot_id)
             and ref.function_count = (select count(*) from bi_cat_test_functions where snapshot_id = ref.snapshot_id))
          """, Boolean.class, publication.id());
      if (!Boolean.TRUE.equals(complete)) { throw new IllegalStateException("CAT 发布尚无匹配的完整本地快照"); }
      return;
    }
    datasetRows.requireCaptureProof(publication);
  }

  /** 中止时释放未公开的数据集行、模式及生产归属。 */
  public int abortDatasetCapture(UUID publicationId) {
    jdbcTemplate.update("delete from read_publication_cat_snapshots where publication_id = ?", publicationId);
    return datasetRows.abort(publicationId);
  }

  /** 中止原因独立保存供当前管理员诊断，不能放进全员共用的业务缓存。 */
  public void recordAbortReason(UUID publicationId, String reason) {
    jdbcTemplate.update("update read_publications set abort_reason = ? where id = ? and state = 'ABORTED'", reason, publicationId);
  }

  /** 同一保留序号集合控制普通事实和数据集的历史回收。 */
  public int deleteUnreferencedDatasetRows(UUID epochId, Collection<Long> retained, int batchSize) {
    return datasetRows.deleteUnreferenced(epochId, retained, batchSize);
  }

  /** 元数据仍被当前数据集的复用修订引用时不能删除。 */
  public long countDatasetRowsCreatedBy(UUID publicationId) { return datasetRows.countCreatedRows(publicationId); }

  // ---- 定义快照 ----

  /**
   * 登记不可变定义快照；内容相同则以同一种类与校验和复用已有快照。
   *
   * @return 快照标识
   */
  public UUID ensureDefinitionSnapshot(
      UUID id, String definitionKind, String schemaVersion, String payloadJson, String checksum) {
    jdbcTemplate.update(
        """
        insert into read_definition_snapshots(id, definition_kind, schema_version, payload, checksum)
        values (?, ?, ?, ?::jsonb, ?)
        on conflict (definition_kind, checksum) do nothing
        """,
        id,
        definitionKind,
        schemaVersion,
        payloadJson,
        checksum);
    return jdbcTemplate.queryForObject(
        "select id from read_definition_snapshots where definition_kind = ? and checksum = ?",
        UUID.class,
        definitionKind,
        checksum);
  }

  // ---- 域 ----

  /** 成熟数据只能整体接管未版本化的唯一业务根；半回填和重复根不能被认证成空或重复完整版本。 */
  public boolean hasAdmissibleLegacyRevisions(PublicationFactTable table, String sourceSystem, String sourceInstance) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select not exists(select 1 from %s where source_system = ? and source_instance = ?
          and (publication_epoch_id is not null or created_by_publication_id is not null))
          and not exists(select 1 from %s where source_system = ? and source_instance = ?
            group by source_system, source_instance, project_id, %s having count(*) > 1)
        """.formatted(table.tableName(), table.tableName(), table.rootColumn()), Boolean.class,
        sourceSystem, sourceInstance, sourceSystem, sourceInstance));
  }

  /** 幂等登记一致性域，返回域标识。 */
  public UUID ensureDomain(UUID id, ReadPublicationDomainKey key) {
    jdbcTemplate.update(
        """
        insert into read_publication_domains(id, producer_kind, source_instance, fact_type, table_key)
        values (?, ?, ?, ?, ?)
        on conflict (producer_kind, source_instance, fact_type, table_key) do nothing
        """,
        id,
        key.producerKind().name(),
        key.sourceInstance(),
        key.factType(),
        key.tableKey());
    return findDomainId(key);
  }

  /** 按域键取域标识（内部使用）。 */
  private Optional<UUID> findDomainIdIfPresent(ReadPublicationDomainKey key) {
    List<UUID> ids =
        jdbcTemplate.query(
            """
            select id from read_publication_domains
             where producer_kind = ? and source_instance = ? and fact_type = ? and table_key = ?
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            key.producerKind().name(),
            key.sourceInstance(),
            key.factType(),
            key.tableKey());
    return ids.stream().findFirst();
  }

  private UUID findDomainId(ReadPublicationDomainKey key) {
    return findDomainIdIfPresent(key)
        .orElseThrow(() -> new IllegalStateException("读发布域登记失败：" + key));
  }

  /** 按域键取域快照（候选分配与发布 CAS 之外只读使用）。 */
  public Optional<ReadPublicationDomain> findDomain(ReadPublicationDomainKey key) {
    return findDomainIdIfPresent(key).flatMap(this::findDomain);
  }

  /** 按域标识取域快照。 */
  public Optional<ReadPublicationDomain> findDomain(UUID domainId) {
    List<ReadPublicationDomain> rows =
        jdbcTemplate.query(
            """
            select id, producer_kind, source_instance, fact_type, table_key,
                   current_publication_id, next_sequence
              from read_publication_domains where id = ?
            """,
            (rs, rowNum) -> mapDomain(rs),
            domainId);
    return rows.stream().findFirst();
  }

  /** 行级排他锁定域，串行化候选分配、发布与回收。 */
  public ReadPublicationDomain lockDomain(UUID domainId) {
    return jdbcTemplate.queryForObject(
        """
        select id, producer_kind, source_instance, fact_type, table_key,
               current_publication_id, next_sequence
          from read_publication_domains where id = ? for update
        """,
        (rs, rowNum) -> mapDomain(rs),
        domainId);
  }

  private ReadPublicationDomain mapDomain(ResultSet rs) throws SQLException {
    ReadPublicationDomainKey key =
        new ReadPublicationDomainKey(
            ReadPublicationProducerKind.valueOf(rs.getString("producer_kind")),
            rs.getString("source_instance"),
            rs.getString("fact_type"),
            rs.getString("table_key"));
    return new ReadPublicationDomain(
        rs.getObject("id", UUID.class),
        key,
        rs.getObject("current_publication_id", UUID.class),
        rs.getLong("next_sequence"));
  }

  // ---- 发布身份 ----

  /** 登记候选发布身份；候选序号为 0，发布时才分配域内序号。 */
  public void insertCandidate(ReadPublication publication) {
    jdbcTemplate.update(
        """
        insert into read_publications(
            id, domain_id, epoch_id, sequence_no, base_publication_id, source_seal,
            covered_change_version, definition_snapshot_id, state, producer_run_id,
            producer_task_id, producer_lease_owner, data_as_of, published_at)
        values (?, ?, ?, 0, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, null)
        """,
        publication.id(),
        publication.domainId(),
        publication.epochId(),
        publication.basePublicationId(),
        publication.sourceSealJson(),
        publication.coveredChangeVersion(),
        publication.definitionSnapshotId(),
        publication.state().name(),
        publication.producerRunId(),
        publication.producerTaskId(),
        publication.producerLeaseOwner(),
        Timestamp.from(publication.dataAsOf()));
    jdbcTemplate.update("update read_publication_domains set unsettled_since = coalesce(unsettled_since, current_timestamp) where id = ?",
        publication.domainId());
  }

  /** 按标识取发布身份。 */
  public Optional<ReadPublication> findPublication(UUID publicationId) {
    return queryPublications(
        """
        select id, domain_id, epoch_id, sequence_no, base_publication_id, source_seal,
               covered_change_version, definition_snapshot_id, state, producer_run_id,
               producer_task_id, producer_lease_owner, data_as_of, published_at
          from read_publications where id = ?
        """,
        publicationId);
  }

  /** 行级排他锁定发布身份（发布 CAS 与回收使用）。 */
  public Optional<ReadPublication> lockPublication(UUID publicationId) {
    return queryPublications(
        """
        select id, domain_id, epoch_id, sequence_no, base_publication_id, source_seal,
               covered_change_version, definition_snapshot_id, state, producer_run_id,
               producer_task_id, producer_lease_owner, data_as_of, published_at
          from read_publications where id = ? for update
        """,
        publicationId);
  }

  /** 共享锁定已发布身份，使回收不能在读取期间删除其修订。 */
  public Optional<ReadPublication> lockPublicationForShare(UUID publicationId) {
    return queryPublications(
        """
        select id, domain_id, epoch_id, sequence_no, base_publication_id, source_seal,
               covered_change_version, definition_snapshot_id, state, producer_run_id,
               producer_task_id, producer_lease_owner, data_as_of, published_at
          from read_publications where id = ? for key share
        """,
        publicationId);
  }

  /** 当前已发布身份。 */
  public Optional<ReadPublication> findCurrentPublication(UUID domainId) {
    return queryPublications(
        """
        select p.id, p.domain_id, p.epoch_id, p.sequence_no, p.base_publication_id, p.source_seal,
               p.covered_change_version, p.definition_snapshot_id, p.state, p.producer_run_id,
               p.producer_task_id, p.producer_lease_owner, p.data_as_of, p.published_at
          from read_publications p
          join read_publication_domains d on d.current_publication_id = p.id
         where d.id = ?
        """,
        domainId);
  }

  /** 域内活动候选（最多一条）。 */
  public Optional<ReadPublication> findActiveCandidate(UUID domainId) {
    return queryPublications(
        """
        select id, domain_id, epoch_id, sequence_no, base_publication_id, source_seal,
               covered_change_version, definition_snapshot_id, state, producer_run_id,
               producer_task_id, producer_lease_owner, data_as_of, published_at
          from read_publications
         where domain_id = ?
           and state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')
         order by created_at desc
         limit 1
        """,
        domainId);
  }

  private Optional<ReadPublication> queryPublications(String sql, Object... args) {
    return jdbcTemplate
        .query(sql, (rs, rowNum) -> mapPublication(rs), args)
        .stream()
        .findFirst();
  }

  private ReadPublication mapPublication(ResultSet rs) throws SQLException {
    Timestamp publishedAt = rs.getTimestamp("published_at");
    return new ReadPublication(
        rs.getObject("id", UUID.class),
        rs.getObject("domain_id", UUID.class),
        rs.getObject("epoch_id", UUID.class),
        rs.getLong("sequence_no"),
        rs.getObject("base_publication_id", UUID.class),
        rs.getString("source_seal"),
        rs.getLong("covered_change_version"),
        rs.getObject("definition_snapshot_id", UUID.class),
        ReadPublicationState.valueOf(rs.getString("state")),
        rs.getString("producer_run_id"),
        (Long) rs.getObject("producer_task_id"),
        rs.getString("producer_lease_owner"),
        rs.getTimestamp("data_as_of").toInstant(),
        publishedAt == null ? null : publishedAt.toInstant());
  }

  /** 按期望状态做 CAS 状态迁移；返回受影响行数。 */
  public int transitionState(
      UUID publicationId, ReadPublicationState expected, ReadPublicationState next) {
    return jdbcTemplate.update(
        """
        update read_publications
           set state = ?, updated_at = current_timestamp
         where id = ? and state = ?
        """,
        next.name(),
        publicationId,
        expected.name());
  }

  /** 分配并保留域内下一个发布序号。 */
  public long allocateSequence(UUID domainId) {
    Long sequence =
        jdbcTemplate.queryForObject(
            """
            update read_publication_domains
               set next_sequence = next_sequence + 1, updated_at = current_timestamp
             where id = ?
             returning next_sequence - 1
            """,
            Long.class,
            domainId);
    if (sequence == null) {
      throw new IllegalStateException("读发布域不存在，无法分配发布序号：" + domainId);
    }
    return sequence;
  }

  /** 置为已发布并写入已分配序号；要求当前仍处于 READY_TO_PUBLISH。 */
  public int markPublished(UUID publicationId, long sequence, Instant publishedAt) {
    return jdbcTemplate.update(
        """
        update read_publications
           set state = 'PUBLISHED', sequence_no = ?, published_at = ?, updated_at = current_timestamp
         where id = ? and state = 'READY_TO_PUBLISH'
        """,
        sequence,
        Timestamp.from(publishedAt),
        publicationId);
  }

  /**
   * 以基线为期望值推进域当前发布指针；基线为空时要求域当前指针也为空。
   *
   * @return 受影响行数；0 表示基线已被其它发布前移，调用方必须回滚
   */
  public int updateDomainCurrentPublication(
      UUID domainId, UUID expectedCurrent, UUID nextPublication) {
    return jdbcTemplate.update(
        """
        update read_publication_domains
           set current_publication_id = ?, unsettled_since = null, last_successful_verification_at = current_timestamp,
               updated_at = current_timestamp
         where id = ? and current_publication_id is not distinct from ?
        """,
        nextPublication,
        domainId,
        expectedCurrent);
  }

  // ---- 候选根清单 ----

  /**
   * 幂等登记候选根清单条目。
   *
   * <p>唯一键为完整业务身份（来源系统 / 来源实例 / 事实类型 / 项目 / 根），不同来源或项目的同号根各自成条。
   * 重复登记只抬高捕获水位，绝不把已登记的水位降回更低版本（乱序重试不得回退覆盖证明）。
   */
  public int recordFactRoot(FactRootEntry entry) {
    return jdbcTemplate.update(
        """
        insert into read_publication_fact_roots(
            publication_id, source_system, source_instance, fact_type, project_id, root_id,
            captured_change_version, previous_revision_id, next_revision_id, operation)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (publication_id, source_system, source_instance, fact_type, project_id, root_id)
        do update
          set captured_change_version = greatest(
                read_publication_fact_roots.captured_change_version, excluded.captured_change_version),
              previous_revision_id = coalesce(
                excluded.previous_revision_id, read_publication_fact_roots.previous_revision_id),
              next_revision_id = excluded.next_revision_id,
              operation = excluded.operation
          where read_publication_fact_roots.captured_change_version <= excluded.captured_change_version
        """,
        entry.publicationId(),
        entry.sourceSystem(),
        entry.sourceInstance(),
        entry.factType(),
        entry.projectId(),
        entry.rootId(),
        entry.capturedChangeVersion(),
        entry.previousRevisionId(),
        entry.nextRevisionId(),
        entry.operation().name());
  }

  /**
   * 为本次候选**考虑过的业务根**登记删除证明，按完整业务身份逐条判定。
   *
   * <p>判定标准是身份而非根号：取仍在服务的开放修订，排除本候选已为其同一完整身份写出修订的那些，
   * 剩下的每个（来源系统 + 来源实例 + 项目 + 根）各登记一条 DELETE。因此同号的不同项目根不会被连带标记，
   * 而跨项目移动的老议题会以"旧身份 DELETE + 新身份 UPSERT"在同一发布内同时成立。
   *
   * @param table 目标主事实表
   * @param publicationId 候选发布身份
   * @param sourceSystem 来源系统
   * @param sourceInstance 来源实例
   * @param rootIds 本候选考虑过的业务根集合（删除判定范围，不要求全部命中）
   * @param capturedChangeVersion 本候选覆盖水位；作为删除根的捕获水位登记
   * @return 登记的删除条目数
   */
  public int recordDeletionsForRoots(
      PublicationFactTable table,
      UUID publicationId,
      String sourceSystem,
      String sourceInstance,
      Collection<Long> rootIds,
      long capturedChangeVersion) {
    if (rootIds == null || rootIds.isEmpty()) {
      return 0;
    }
    return jdbcTemplate.update(
        """
        insert into read_publication_fact_roots(
            publication_id, source_system, source_instance, fact_type, project_id, root_id,
            captured_change_version, previous_revision_id, next_revision_id, operation)
        select ?, f.source_system, f.source_instance, ?, f.project_id, f.%s, ?, f.id, null, 'DELETE'
          from %s f
         where f.source_system = ?
           and f.source_instance = ?
           and f.visible_from is not null
           and f.visible_to is null
           and f.%s = any (?::bigint[])
           and not exists (
             select 1 from %s written
              where written.created_by_publication_id = ?
                and written.source_system = f.source_system
                and written.source_instance = f.source_instance
                and written.project_id = f.project_id
                and written.%s = f.%s)
        on conflict (publication_id, source_system, source_instance, fact_type, project_id, root_id)
        do update set
              operation = 'DELETE',
              captured_change_version = greatest(
                read_publication_fact_roots.captured_change_version, excluded.captured_change_version),
              previous_revision_id = coalesce(
                excluded.previous_revision_id, read_publication_fact_roots.previous_revision_id),
              next_revision_id = null
        """
            .formatted(
                table.rootColumn(),
                table.tableName(),
                table.rootColumn(),
                table.tableName(),
                table.rootColumn(),
                table.rootColumn()),
        publicationId,
        table.name(),
        capturedChangeVersion,
        sourceSystem,
        sourceInstance,
        longArrayLiteral(rootIds),
        publicationId);
  }

  /**
   * 把本候选已写入的修订批量登记为 UPSERT 根清单，业务身份取自真实修订行。
   *
   * <p>任务路径随后用 {@link #applyRootCapturedVersions} 覆盖为逐根捕获水位；旁路生产者没有变化头，
   * 保留候选覆盖水位即可。项目号一律来自写入结果，不由版本头的空值推断。
   *
   * @return 登记或刷新的条目数
   */
  public int registerUpsertManifest(
      PublicationFactTable table, UUID publicationId, long capturedChangeVersion) {
    return jdbcTemplate.update(
        """
        insert into read_publication_fact_roots(
            publication_id, source_system, source_instance, fact_type, project_id, root_id,
            captured_change_version, previous_revision_id, next_revision_id, operation)
        select f.created_by_publication_id, f.source_system, f.source_instance, ?, f.project_id,
               f.%s, ?, null, f.id, 'UPSERT'
          from %s f
         where f.created_by_publication_id = ?
        on conflict (publication_id, source_system, source_instance, fact_type, project_id, root_id)
        do update set
              captured_change_version = greatest(
                read_publication_fact_roots.captured_change_version, excluded.captured_change_version),
              next_revision_id = excluded.next_revision_id
        """
            .formatted(table.rootColumn(), table.tableName()),
        table.name(),
        capturedChangeVersion,
        publicationId);
  }

  /**
   * 按逐根捕获水位修正候选根清单（任务路径）。
   *
   * <p>水位只抬高不回退；同一根号下不同项目身份共享该根号的水位，因为变化头本身按来源实例 + 事实类型 +
   * 根号定位，与项目无关。
   *
   * @return 更新条目数
   */
  public int applyRootCapturedVersions(
      UUID publicationId, PublicationFactTable table, Map<Long, Long> rootChangeVersions) {
    if (rootChangeVersions == null || rootChangeVersions.isEmpty()) {
      return 0;
    }
    List<Long> rootIds = new ArrayList<>(rootChangeVersions.keySet());
    List<Long> versions = rootIds.stream().map(rootChangeVersions::get).toList();
    return jdbcTemplate.update(
        """
        update read_publication_fact_roots r
           set captured_change_version = greatest(r.captured_change_version, v.captured)
          from (select * from unnest(?::bigint[], ?::bigint[]) as v(root_id, captured)) v
         where r.publication_id = ?
           and r.fact_type = ?
           and r.root_id = v.root_id
        """,
        longArrayLiteral(rootIds),
        longArrayLiteral(versions),
        publicationId,
        table.name());
  }

  /** 记录候选根清单关闭的旧修订标识（审计：本候选在被触及根上关闭的上一开放修订）。 */
  public int linkManifestPreviousRevisions(PublicationFactTable table, UUID publicationId) {
    return jdbcTemplate.update(
        """
        update read_publication_fact_roots r
           set previous_revision_id = f.id
          from %s f
         where r.publication_id = ?
           and f.source_system = r.source_system
           and f.source_instance = r.source_instance
           and f.project_id = r.project_id
           and f.%s = r.root_id
           and f.visible_from is not null
           and f.visible_to = (
                 select p.sequence_no from read_publications p where p.id = r.publication_id)
        """
            .formatted(table.tableName(), table.rootColumn()),
        publicationId);
  }

  /** 记录全量候选捕获的来源全量意图修订（结算时 CAS，防止较旧全量清掉较新意图）。 */
  public int updateCapturedFullRequestRevision(UUID publicationId, long fullRequestRevision) {
    return jdbcTemplate.update(
        "update read_publications set captured_full_request_revision = ? where id = ?",
        fullRequestRevision,
        publicationId);
  }

  /** 读取候选捕获的来源全量意图修订。 */
  public long findCapturedFullRequestRevision(UUID publicationId) {
    Long revision =
        jdbcTemplate.queryForObject(
            "select captured_full_request_revision from read_publications where id = ?",
            Long.class,
            publicationId);
    return revision == null ? 0L : revision;
  }

  /** 候选根清单条目数。 */
  public long countFactRoots(UUID publicationId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from read_publication_fact_roots where publication_id = ?",
            Long.class,
            publicationId);
    return count == null ? 0L : count;
  }

  /** 删除候选根清单（中止候选后释放）。 */
  public int deleteFactRoots(UUID publicationId) {
    return jdbcTemplate.update(
        "delete from read_publication_fact_roots where publication_id = ?", publicationId);
  }

  // ---- 范围 generation ----

  /** 原子捕获全部待发布根；一次 SQL 的快照固定集合与每根水位，后续批次不得重新枚举缩小它。 */
  public long captureRootInputs(UUID publicationId, String sourceInstance, String factType, boolean full) {
    Long upper = jdbcTemplate.queryForObject("""
        with input_heads as materialized (
          select source_instance, fact_type, root_id, latest_change_version, published_version
            from fact_change_heads where source_instance = ? and fact_type = ?),
        inserted as (
          insert into read_publication_root_inputs(
              publication_id, source_instance, fact_type, root_id, captured_change_version)
          select ?, source_instance, fact_type, root_id, latest_change_version from input_heads
           where ? or published_version < latest_change_version returning root_id),
        bounded as (
          update read_publications set covered_change_version =
              (select coalesce(max(latest_change_version), 0) from input_heads)
           where id = ? returning covered_change_version)
        select covered_change_version from bounded
        """, Long.class, sourceInstance, factType, publicationId, full, publicationId);
    return upper == null ? 0L : upper;
  }

  /** 本批次只读取捕获集合中尚未完成的根；先锁变化头并验证当前 ODS 尚对应捕获水位。 */
  public Map<Long, Long> lockTaskInputVersions(UUID publicationId, long taskId) {
    Map<Long, Long> versions = new java.util.LinkedHashMap<>();
    jdbcTemplate.query("""
        select input.root_id, input.captured_change_version, head.latest_change_version
          from read_publication_root_inputs input
          join fact_change_heads head on head.source_instance = input.source_instance
             and head.fact_type = input.fact_type and head.root_id = input.root_id
          join fact_build_task_roots assigned on assigned.task_id = ?
             and assigned.source_instance = input.source_instance
             and assigned.fact_type = input.fact_type and assigned.root_id = input.root_id
         where input.publication_id = ? and input.built_at is null
         order by input.root_id for update of head
        """, rs -> {
          long captured = rs.getLong("captured_change_version");
          if (captured != rs.getLong("latest_change_version")) {
            throw new IllegalStateException("候选输入已变化，必须整体重建：根 " + rs.getLong("root_id"));
          }
          versions.put(rs.getLong("root_id"), captured);
        }, taskId, publicationId);
    return versions;
  }

  /** 在事实 DML 的同一事务标记本批输入已完成；不得把未写入的输入提前认证。 */
  public void completeRootInputs(UUID publicationId, long taskId, java.util.Collection<Long> roots) {
    for (Long root : roots) {
      jdbcTemplate.update("""
          update read_publication_root_inputs set built_by_task_id = ?, built_at = current_timestamp
           where publication_id = ? and root_id = ? and built_at is null
          """, taskId, publicationId, root);
    }
  }

  /** 全量扫描成功后认证固定输入集合；扫描期间任一输入推进则拒绝混合代际。 */
  public void completeFullRootInputs(UUID publicationId, long taskId) {
    Integer changed = jdbcTemplate.queryForObject("""
        select count(*) from read_publication_root_inputs input
          left join fact_change_heads head on head.source_instance = input.source_instance
             and head.fact_type = input.fact_type and head.root_id = input.root_id
         where input.publication_id = ?
           and (head.root_id is null or head.latest_change_version <> input.captured_change_version)
        """, Integer.class, publicationId);
    if (changed != null && changed > 0) {
      throw new IllegalStateException("全量构建期间捕获输入已变化，必须整体重建");
    }
    jdbcTemplate.update("""
        update read_publication_root_inputs set built_by_task_id = ?, built_at = current_timestamp
         where publication_id = ?
        """, taskId, publicationId);
  }

  /** 返回固定集合中未完成根的数量（用于门禁与显式阻塞提示）。 */
  public long countUnbuiltInputs(UUID publicationId) {
    Long count = jdbcTemplate.queryForObject("""
        select count(*) from read_publication_root_inputs where publication_id = ? and built_at is null
        """, Long.class, publicationId);
    return count == null ? 0L : count;
  }

  /** 批次门禁只查是否存在未完成根；沿未完成输入索引取一行，不按每批反复计数整个固定集合。 */
  public boolean hasUnbuiltInputs(UUID publicationId) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select exists(select 1 from read_publication_root_inputs
          where publication_id = ? and built_at is null)
        """, Boolean.class, publicationId));
  }

  /** 冻结影响事实分类/范围的定义，排除连接秘密与同步运行时间。 */
  public String currentFactDefinitions(String sourceInstance) {
    return jdbcTemplate.queryForObject("""
        select jsonb_build_object(
          'configs', (select coalesce(jsonb_agg(jsonb_build_object(
              'id', c.id, 'sourceInstance', c.source_instance,
              'sourceEnabled', c.source_enabled, 'whitelistMode', c.whitelist_mode,
              'whitelistTables', c.whitelist_tables) order by c.id), '[]'::jsonb)
              from gitlab_sync_configs c where c.source_instance = ?),
          'modules', (select coalesce(jsonb_agg(to_jsonb(m) order by m.id), '[]'::jsonb)
              from module_dictionary m),
          'qualityMemberScopes', (select coalesce(jsonb_agg(to_jsonb(m) order by to_jsonb(m)::text), '[]'::jsonb)
              from quality_board_member_scopes m),
          'catalogs', (select coalesce(jsonb_agg(to_jsonb(c) order by c.id), '[]'::jsonb)
              from issue_scope_catalogs c),
          'groups', (select coalesce(jsonb_agg(to_jsonb(g) order by g.id), '[]'::jsonb)
              from issue_scope_groups g),
          'members', (select coalesce(jsonb_agg(to_jsonb(m) order by m.id), '[]'::jsonb)
              from issue_scope_members m),
          'customerAliases', (select coalesce(jsonb_agg(to_jsonb(a) order by a.alias_name), '[]'::jsonb)
              from issue_customer_name_aliases a),
          'labelGroups', (select coalesce(jsonb_agg(to_jsonb(g) order by g.id), '[]'::jsonb)
              from label_groups g),
          'labelGroupMembers', (select coalesce(jsonb_agg(to_jsonb(m) order by m.id), '[]'::jsonb)
              from label_group_members m),
          'labelGroupReferences', (select coalesce(jsonb_agg(to_jsonb(r) order by r.id), '[]'::jsonb)
              from label_group_references r),
          'labelGroupRules', (select coalesce(jsonb_agg(to_jsonb(r) order by r.id), '[]'::jsonb)
              from label_group_dynamic_rules r),
          'factCalendars', (select coalesce(jsonb_agg(jsonb_build_object(
              'projectId', c.project_id, 'sourceValue', m.source_value, 'activeFrom', m.active_from)
              order by c.project_id, m.source_value), '[]'::jsonb)
              from issue_scope_catalogs c join issue_scope_groups g on g.catalog_id = c.id
              join issue_scope_members m on m.catalog_id = c.id and m.group_id = g.id
              where c.dimension = 'TESTING_PHASE' and c.enabled and g.enabled and m.enabled),
          'integrationCalendars', (select coalesce(jsonb_agg(jsonb_build_object(
              'projectId', c.project_id, 'sourceValue', m.source_value,
              'activeFrom', m.active_from, 'activeUntil', m.active_until)
              order by c.project_id, m.source_value), '[]'::jsonb)
              from issue_scope_catalogs c join issue_scope_groups g on g.catalog_id = c.id
              join issue_scope_members m on m.catalog_id = c.id and m.group_id = g.id
              where c.dimension = 'TESTING_PHASE' and c.enabled and g.enabled and m.enabled))::text
        """, String.class, sourceInstance);
  }

  /** 以内容校验和复用本来源当前定义快照；调用者持有对应输入/域锁，不能包含连接凭据。 */
  public UUID freezeCurrentFactDefinitions(String sourceInstance) {
    String payload = currentFactDefinitions(sourceInstance);
    try {
      String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return ensureDefinitionSnapshot(UUID.randomUUID(), "FACT_BUILD", "2", payload, checksum);
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 不可用", error);
    }
  }

  /**
   * 增量只能复用按相同事实计算定义生成的基线，按事实族实际依赖比较，避免阻塞无关生产者。
   * ISSUE 比较词典、客户别名和阶段起点；MR 只比较词典；集成测试比较词典及含终点的阶段区间。
   * 里程碑显示和分组属于视图定义，可以随候选重新预热；不把旧派生列标成新计算定义的结果。
   */
  public void requireReusableCalculationDefinitions(UUID basePublicationId, UUID definitionId,
      ReadPublicationDomainKey key) {
    if (basePublicationId == null || key.producerKind() != ReadPublicationProducerKind.GITLAB_FACT) {
      return;
    }
    Boolean same = jdbcTemplate.queryForObject("""
        select case ?
                 when 'ISSUE' then jsonb_build_array(old.payload->'modules', old.payload->'customerAliases', old.payload->'factCalendars')
                 when 'MERGE_REQUEST' then jsonb_build_array(old.payload->'modules')
                 when 'INTEGRATION_TEST' then jsonb_build_array(old.payload->'modules', old.payload->'integrationCalendars') end
             = case ?
                 when 'ISSUE' then jsonb_build_array(current.payload->'modules', current.payload->'customerAliases', current.payload->'factCalendars')
                 when 'MERGE_REQUEST' then jsonb_build_array(current.payload->'modules')
                 when 'INTEGRATION_TEST' then jsonb_build_array(current.payload->'modules', current.payload->'integrationCalendars') end
          from read_publications base left join read_definition_snapshots old on old.id = base.definition_snapshot_id
          join read_definition_snapshots current on current.id = ? where base.id = ?
        """, Boolean.class, key.factType(), key.factType(), definitionId, basePublicationId);
    if (Boolean.TRUE.equals(same)) {
      return;
    }
    ReadPublication base = findPublication(basePublicationId).orElseThrow();
    PublicationFactTable table = PublicationFactTable.ofFactType(key.factType());
    Boolean hasFacts = jdbcTemplate.queryForObject("select exists(select 1 from " + table.tableName()
        + " where publication_epoch_id = ? and visible_from <= ? and (visible_to is null or ? < visible_to))",
        Boolean.class, base.epochId(), base.sequenceNo(), base.sequenceNo());
    if (Boolean.TRUE.equals(hasFacts)) {
      throw new com.data.collection.platform.service.FactCandidateBusyException(
          "事实计算定义已变化，需要完整重建后发布，继续保留上一完整版本：" + key);
    }
  }

  /** 定义发生变化时不能继续复用同一个候选；输入内容按 jsonb 比较，格式差异不影响判断。 */
  public void requireUnchangedDefinitions(ReadPublication publication, String sourceInstance) {
    Boolean same = jdbcTemplate.queryForObject("""
        select payload = ?::jsonb from read_definition_snapshots where id = ?
        """, Boolean.class, currentFactDefinitions(sourceInstance), publication.definitionSnapshotId());
    if (!Boolean.TRUE.equals(same)) {
      throw new IllegalStateException("候选定义已变化，必须整体重建：" + publication.id());
    }
  }

  /** 登记需要 P3 预热的范围；目标 generation 不提前修改当前可读版本。 */
  public void requireProjection(UUID publicationId, String sourceInstance, String factType,
      ProjectionScopeType scopeType, String scopeKey) {
    jdbcTemplate.update("""
        insert into read_publication_scope_generations(publication_id, scope_type, scope_key,
            candidate_generation)
        select ?, ?, ?, case when d.producer_kind = 'GITLAB_FACT' then
          coalesce((select generation from fact_projection_generations
            where source_instance = ? and fact_type = ? and scope_type = ? and scope_key = ?), 0) + 1
          else coalesce((select committed_generation from read_publication_scope_generations
            where publication_id = p.base_publication_id and scope_type = ? and scope_key = ?), 0) + 1 end
          from read_publications p join read_publication_domains d on d.id = p.domain_id where p.id = ?
        on conflict (publication_id, scope_type, scope_key) do nothing
        """, publicationId, scopeType.name(), scopeKey, sourceInstance, factType,
        scopeType.name(), scopeKey, scopeType.name(), scopeKey, publicationId);
  }

  /** 必需投影仍缺少针对本候选冻结输入/定义的完成证明时，拒绝 READY。 */
  public void requireProjectionProofs(ReadPublication publication) {
    Boolean complete = jdbcTemplate.queryForObject("""
        select not exists(select 1 from read_publication_scope_generations g
          where publication_id = ? and (prepared_at is null
            or prepared_source_seal <> ?::jsonb
            or prepared_definition_snapshot_id is distinct from ?::uuid
            or not exists(select 1 from read_projection_artifacts artifact
              where artifact.publication_id = g.publication_id and artifact.scope_type = g.scope_type
                and artifact.scope_key = g.scope_key and artifact.generation = g.candidate_generation
                and artifact.source_seal = g.prepared_source_seal
                and artifact.definition_snapshot_id = g.prepared_definition_snapshot_id)))
        """, Boolean.class, publication.id(), publication.sourceSealJson(), publication.definitionSnapshotId());
    if (!Boolean.TRUE.equals(complete)) {
      throw new IllegalStateException("必需投影尚未完成，不能发布候选：" + publication.id());
    }
  }

  /** P3 专用：只有目标 generation 和冻结身份全部一致的 PROJECTING 候选才接受完成证明。 */
  public int recordProjectionPrepared(UUID publicationId, ProjectionScopeType scopeType,
      String scopeKey, long generation, String sourceSealJson, UUID definitionSnapshotId) {
    return jdbcTemplate.update("""
        update read_publication_scope_generations g
           set prepared_at = current_timestamp, prepared_source_seal = ?::jsonb,
               prepared_definition_snapshot_id = ?
          from read_publications p where g.publication_id = p.id and p.id = ?
            and p.state = 'PROJECTING' and g.scope_type = ? and g.scope_key = ?
            and g.candidate_generation = ? and p.source_seal = ?::jsonb
            and p.definition_snapshot_id is not distinct from ?::uuid
        """, sourceSealJson, definitionSnapshotId, publicationId, scopeType.name(), scopeKey,
        generation, sourceSealJson, definitionSnapshotId);
  }

  /** 发布时按每根捕获水位结算，较新的镜像变化继续留在待发布头上。 */
  public void settleCapturedInputs(UUID publicationId) {
    jdbcTemplate.update("""
        update fact_change_heads h set published_version = greatest(h.published_version,
            input.captured_change_version), published_by_fact_build_task_id = input.built_by_task_id,
            updated_at = current_timestamp
          from read_publication_root_inputs input where input.publication_id = ?
            and input.built_at is not null and h.source_instance = input.source_instance
            and h.fact_type = input.fact_type and h.root_id = input.root_id
        """, publicationId);
  }

  /** 候选统一发布或中止后才释放已完成批次的归属，避免构建阶段重复派发同一根。 */
  public void releaseCandidateTaskRoots(UUID publicationId) {
    jdbcTemplate.update("""
        delete from fact_build_task_roots roots using fact_build_tasks task
         where roots.task_id = task.id and task.publication_id = ?
        """, publicationId);
  }

  /** 完整发布或中止后释放候选输入暂存清单；输出根证明与冻结来源封印仍在发布元数据中保留。 */
  public int releaseCapturedInputs(UUID publicationId) {
    return jdbcTemplate.update("delete from read_publication_root_inputs where publication_id = ?", publicationId);
  }

  /** 预热及发布结束后释放跨来源输入引用；在此前其它域的历史版本不能退休。 */
  public int releaseProjectionDependencies(UUID publicationId) {
    return jdbcTemplate.update("delete from read_publication_dependencies where publication_id = ?", publicationId);
  }

  /** 中止只清未公开候选的缓存/产物/投影任务，不触及旧已发布缓存。 */
  public void deleteCandidateProjections(UUID publicationId) {
    jdbcTemplate.update("delete from statistic_board_snapshots where publication_id = ?", publicationId);
    jdbcTemplate.update("delete from page_record_snapshots where publication_id = ?", publicationId);
    jdbcTemplate.update("delete from read_projection_artifacts where publication_id = ?", publicationId);
    jdbcTemplate.update("update fact_projection_refresh_tasks set status = 'FAILED', lease_owner = null, lease_until = null,"
        + " error_message = coalesce(error_message, '所属候选已中止'), finished_at = current_timestamp where publication_id = ? and status <> 'SUCCESS'",
        publicationId);
    releaseProjectionDependencies(publicationId);
  }

  /**
   * 中止入口在持有域锁后重查任务当前令牌；绑定失效或任务仍被批次锁定时返回 false。
   * 批次先持父/子共享锁再取域锁，此处不能持域锁等待任务排他锁，否则会形成逆序死锁；未清候选由后续巡检收口。
   */
  public boolean taskStillOwnsCandidate(long taskId, String taskOwner, UUID publicationId, String runLeaseToken) {
    return !jdbcTemplate.queryForList("""
        select task.id from fact_build_tasks task join read_publications p on p.id = task.publication_id
         where task.id = ? and task.publication_id = ?
          and (task.lock_owner = ? or task.status <> 'RUNNING')
          and not exists(select 1 from sync_runs r where r.id::text = p.producer_run_id
            and r.status in ('RUNNING', 'RETRYING') and r.lease_until >= clock_timestamp()
            and r.lease_owner is distinct from ?)
         for update of task skip locked
        """, Long.class, taskId, publicationId, taskOwner, runLeaseToken).isEmpty();
  }

  /** 登记候选范围 generation（重复登记覆盖为最新候选值）。 */
  public int recordScopeGeneration(
      UUID publicationId, ProjectionScopeType scopeType, String scopeKey, long candidateGeneration) {
    return jdbcTemplate.update(
        """
        insert into read_publication_scope_generations(
            publication_id, scope_type, scope_key, candidate_generation, committed_generation)
        values (?, ?, ?, ?, null)
        on conflict (publication_id, scope_type, scope_key) do update
          set candidate_generation = excluded.candidate_generation,
              prepared_at = null, prepared_source_seal = null, prepared_definition_snapshot_id = null
          where read_publication_scope_generations.candidate_generation <> excluded.candidate_generation
        """,
        publicationId,
        scopeType.name(),
        scopeKey,
        candidateGeneration);
  }

  /** 发布时提交候选 generation 为可读 generation。 */
  public int commitScopeGenerations(UUID publicationId) {
    return jdbcTemplate.update(
        """
        update read_publication_scope_generations
           set committed_generation = candidate_generation
         where publication_id = ?
        """,
        publicationId);
  }

  /** 删除候选范围 generation（中止候选后释放）。 */
  public int deleteScopeGenerations(UUID publicationId) {
    return jdbcTemplate.update(
        "delete from read_publication_scope_generations where publication_id = ?", publicationId);
  }

  // ---- 事实修订 ----

  /**
   * 无父 FACT_REFRESH 的 GitLab 旁路只在完整、稳定的镜像视图上构建。
   * REPEATABLE READ 保证检查后新启动的镜像写入不可见；已有未结算镜像直接让位，不等待上游网络。
   */
  public void requireStableBypassInputs(ReadPublicationDomainKey key) {
    Boolean stableSnapshot = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
        connection.getTransactionIsolation() >= java.sql.Connection.TRANSACTION_REPEATABLE_READ);
    if (!Boolean.TRUE.equals(stableSnapshot)) {
      throw new IllegalStateException("旁路事实构建必须处于 REPEATABLE READ 或更强的稳定输入事务");
    }
    if (key.producerKind() != ReadPublicationProducerKind.GITLAB_FACT) {
      return;
    }
    Boolean writing = jdbcTemplate.queryForObject("""
        select exists(select 1 from sync_runs where source_instance = ? and run_type <> 'FACT_REFRESH'
          and (status in ('RUNNING', 'RETRYING', 'CANCELLING')
            or (status in ('QUEUED', 'PAUSED') and started_at is not null)))
        """, Boolean.class, key.sourceInstance());
    if (Boolean.TRUE.equals(writing)) {
      throw new com.data.collection.platform.service.FactCandidateBusyException(
          "镜像来源尚未结算，旁路事实构建让位：" + key.sourceInstance());
    }
  }

  /**
   * 延期重算复制原事实整行并只替换三个判定列；关系按旧父修订原样复制到新父修订。
   * 使用 PostgreSQL 行类型保留全部业务列（包括 null 与未来新增列），不经来源归一化再次解释存量事实。
   * 必须在持有域/候选锁的事务内调用；只接受本来源当前开放的已发布修订。
   */
  public void copyIssueDelayRevision(com.data.collection.platform.entity.read.FactRevisionTarget target,
      com.data.collection.platform.entity.IssueFact fact) {
    requireWritableTarget(target);
    int copied = jdbcTemplate.update("""
        insert into issue_fact
        select (jsonb_populate_record(null::issue_fact, to_jsonb(old) || jsonb_build_object(
          'id', nextval(pg_get_serial_sequence('issue_fact', 'id')),
          'publication_epoch_id', ?::uuid, 'created_by_publication_id', ?::uuid,
          'visible_from', null, 'visible_to', null,
          'is_response_delayed', ?::boolean, 'response_overdue', ?::boolean,
          'is_resolve_delayed', ?::boolean, 'created_at', current_timestamp,
          'updated_at', current_timestamp, 'fact_refreshed_at', current_timestamp))).*
          from issue_fact old where old.id = ? and old.source_system = ? and old.source_instance = ?
            and old.visible_from is not null and old.visible_to is null
        on conflict (created_by_publication_id, source_system, source_instance, project_id, issue_id)
          do update set is_response_delayed = excluded.is_response_delayed,
            response_overdue = excluded.response_overdue, is_resolve_delayed = excluded.is_resolve_delayed,
            updated_at = excluded.updated_at, fact_refreshed_at = excluded.fact_refreshed_at
        """, target.epochId(), target.publicationId(), fact.getResponseDelayed(), fact.getResponseOverdue(),
        fact.getResolveDelayed(), fact.getId(), target.sourceSystem(), target.sourceInstance());
    if (copied != 1) {
      throw new IllegalStateException("延期重算的原修订已变化，拒绝写入：" + fact.getId());
    }
    jdbcTemplate.update("""
        insert into issue_fact_customer_members
        select (jsonb_populate_record(null::issue_fact_customer_members, to_jsonb(member) ||
            jsonb_build_object('issue_fact_revision_id', fresh.id, 'publication_epoch_id', ?::uuid))).*
          from issue_fact_customer_members member join issue_fact old on old.id = member.issue_fact_revision_id
          join issue_fact fresh on fresh.created_by_publication_id = ?
            and fresh.source_system = old.source_system and fresh.source_instance = old.source_instance
            and fresh.project_id = old.project_id and fresh.issue_id = old.issue_id
         where old.id = ?
        on conflict (issue_fact_revision_id, customer_name) where issue_fact_revision_id is not null do nothing
        """, target.epochId(), target.publicationId(), fact.getId());
  }

  /** 每个写入事务先锁域再锁候选，禁止复用已发布/中止身份重写历史；与中止和发布保持同一锁序。 */
  public void requireWritableTarget(com.data.collection.platform.entity.read.FactRevisionTarget target) {
    ReadPublication existing = findPublication(target.publicationId()).orElseThrow(
        () -> new IllegalStateException("候选写入身份不存在：" + target.publicationId()));
    ReadPublicationDomain domain = lockDomain(existing.domainId());
    ReadPublication publication = lockPublication(existing.id()).orElseThrow();
    if (publication.state() != ReadPublicationState.BUILDING
        || !publication.epochId().equals(target.epochId())
        || !domain.key().sourceInstance().equals(target.sourceInstance())
        || !domain.key().factType().equals(target.table().factType().name())) {
      throw new IllegalStateException("事实写入身份不是匹配的 BUILDING 候选：" + target.publicationId());
    }
  }

  /**
   * 初始接管专用稳定输入栅栏：阻止证据与既有事实在核验至采纳之间被其它事务修改。
   *
   * <p>仅在安装/回填事务调用，不用于日常发布。SHARE ROW EXCLUSIVE 允许普通读取，阻止并发写入及另一次接管；
   * NOWAIT 在已有生产事务时立即返回 false。保存点回滚释放本次已取锁，避免锁冲突破坏外层事务。
   * 成功取得的锁保持到接管事务结束，包含来源控制证据、事实及父子关系；调用方必须在同一事务内核验与采纳。
   *
   * @param table 待采纳的主事实表
   * @return 输入已稳定时为 true；已有写入占用时为 false
   */
  public boolean tryLockInitialVersionInputs(PublicationFactTable table) {
    List<String> tables = new ArrayList<>(List.of(
        "gitlab_sync_configs", "source_fact_publication_states", "source_fact_dependency_states",
        "code_review_match_mode_records", "code_review_match_mode_sync_state",
        "code_review_match_mode_db_settings", "legacy_platform_formal_import_runs",
        "module_dictionary", "issue_scope_catalogs", "issue_scope_groups", "issue_scope_members",
        "issue_customer_name_aliases",
        "sync_run_table_states", "fact_change_heads", "fact_build_tasks",
        "fact_projection_generations", "fact_projection_refresh_tasks", table.tableName()));
    if (table == PublicationFactTable.ISSUE) {
      tables.add("issue_fact_customer_members");
    } else if (table == PublicationFactTable.MERGE_REQUEST) {
      tables.add("merge_request_commit_fact");
    }
    tables.sort(String::compareTo);
    return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
      if (connection.getAutoCommit()) {
        throw new IllegalStateException("初始版本接管必须在显式事务内取得稳定输入栅栏");
      }
      Savepoint boundary = connection.setSavepoint();
      try (Statement statement = connection.createStatement()) {
        statement.execute("lock table " + String.join(", ", tables)
            + " in share row exclusive mode nowait");
        connection.releaseSavepoint(boundary);
        return true;
      } catch (SQLException error) {
        connection.rollback(boundary);
        connection.releaseSavepoint(boundary);
        if ("55P03".equals(error.getSQLState())) {
          LOGGER.info("初始完整版本接管让位，factTable={}, SQLState=55P03, reason={}",table.tableName(),error.getMessage());
          return false;
        }
        throw error;
      }
    }));
  }

  /** 关闭本候选触及根在该代际的已发布开放修订，使它们停在序号 S。 */
  public int closeTouchedOpenRevisions(
      PublicationFactTable table, UUID epochId, UUID publicationId, long sequence) {
    return jdbcTemplate.update(
        """
        update %s f
           set visible_to = ?, updated_at = current_timestamp
          from read_publication_fact_roots r
         where r.publication_id = ?
           and f.publication_epoch_id = ?
           and f.visible_from is not null
           and f.visible_to is null
           and r.source_system = f.source_system
           and r.source_instance = f.source_instance
           and r.project_id is not distinct from f.project_id
           and r.root_id = f.%s
        """
            .formatted(table.tableName(), table.rootColumn()),
        sequence,
        publicationId,
        epochId);
  }

  /** 切换代际时关闭旧代际的全部已发布开放修订，使旧代际可被回收。 */
  public int closeEpochOpenRevisions(PublicationFactTable table, UUID epochId, long sequence) {
    return jdbcTemplate.update(
        """
        update %s
           set visible_to = ?, updated_at = current_timestamp
         where publication_epoch_id = ?
           and visible_from is not null
           and visible_to is null
        """
            .formatted(table.tableName()),
        sequence,
        epochId);
  }

  /** 把本候选产生的修订置为自序号 S 起的可见开放修订。 */
  public int openCandidateRevisions(
      PublicationFactTable table, UUID publicationId, long sequence) {
    return jdbcTemplate.update(
        """
        update %s
           set visible_from = ?, updated_at = current_timestamp
         where created_by_publication_id = ?
           and visible_from is null
           and visible_to is null
        """
            .formatted(table.tableName()),
        sequence,
        publicationId);
  }

  /** 在 ABORTED 事务内登记/完成事实物理清理；调用者持有域及发布行锁，不能用于活动或已发布版本。 */
  public void setAbortCleanupPending(UUID publicationId, boolean pending) {
    int updated = jdbcTemplate.update("update read_publications set abort_cleanup_pending = ?,"
        + " updated_at = current_timestamp where id = ? and state = 'ABORTED'", pending, publicationId);
    if (updated != 1) {
      throw new IllegalStateException("只能登记已中止候选的物理清理：" + publicationId);
    }
  }

  /** 发布行锁内核验持久清理标识；无标识或非 ABORTED 时不得执行物理删除。 */
  public boolean isAbortCleanupPending(UUID publicationId) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject("select abort_cleanup_pending from read_publications"
        + " where id = ? and state = 'ABORTED'", Boolean.class, publicationId));
  }

  /** 通过创建者身份索引判断未公开修订是否仍待清理；不读取其它候选或已公开历史修订。 */
  public boolean hasUnpublishedCandidateRevisions(PublicationFactTable table, UUID publicationId) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "select exists(select 1 from %s where created_by_publication_id = ? and visible_from is null)"
            .formatted(table.tableName()), Boolean.class, publicationId));
  }

  /** 有界删除已中止候选从未公开的父修订及精确子关系；单批事务由调用者管理。 */
  public int deleteCandidateRevisions(PublicationFactTable table, UUID publicationId, int batchSize) {
    List<Long> revisionIds =
        jdbcTemplate.query(
            "select id from %s where created_by_publication_id = ? and visible_from is null order by id limit ?"
                .formatted(table.tableName()),
            (rs, rowNum) -> rs.getLong("id"),
            publicationId,
            batchSize);
    if (revisionIds.isEmpty()) {
      return 0;
    }
    deleteChildRelations(table, revisionIds);
    return jdbcTemplate.update(
        "delete from %s where id = any (?::bigint[])".formatted(table.tableName()),
        longArrayLiteral(revisionIds));
  }

  /**
   * 先删除挂在待删父修订上的子关系，再删父修订本身。
   *
   * <p>子关系（客户成员、MR 提交）通过外键绑定父修订，必须先清；且只针对本次精确选中的父修订，
   * 不按裸业务根波及同一根的其它版本关系。
   */
  private void deleteChildRelations(PublicationFactTable table, Collection<Long> parentRevisionIds) {
    String ids = longArrayLiteral(parentRevisionIds);
    if (table == PublicationFactTable.ISSUE) {
      jdbcTemplate.update(
          "delete from issue_fact_customer_members where issue_fact_revision_id = any (?::bigint[])",
          ids);
    } else if (table == PublicationFactTable.MERGE_REQUEST) {
      jdbcTemplate.update(
          "delete from merge_request_commit_fact where merge_request_revision_id = any (?::bigint[])",
          ids);
    }
  }

  /** 统计仍引用该发布身份的事实修订数，用于判断元数据能否回收。 */
  public long countRevisionsCreatedBy(PublicationFactTable table, UUID publicationId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from %s where created_by_publication_id = ?".formatted(table.tableName()),
            Long.class,
            publicationId);
    return count == null ? 0L : count;
  }

  /**
   * 分批删除某代际中不覆盖任何保留序号的已发布修订。
   *
   * <p>开放修订若覆盖保留序号（含当前版本）则保留，因此不会误删仍在服务的内容；删除前先清挂在这些
   * 精确父修订上的子关系，避免外键阻止回收。
   *
   * @param epochId 目标代际
   * @param retainedSequences 仍被当前版本、活跃页面、未获回收锁的版本或活动候选基线引用的序号
   * @param batchSize 单批上限
   * @return 删除行数
   */
  public int deleteUnreferencedRevisions(
      PublicationFactTable table, UUID epochId, Collection<Long> retainedSequences, int batchSize) {
    String retainedLiteral = longArrayLiteral(retainedSequences);
    List<Long> revisionIds =
        jdbcTemplate.query(
            """
            select f.id from %s f
             where f.publication_epoch_id = ?
               and f.visible_from is not null
               and not exists (
                 select 1 from unnest(?::bigint[]) as retained(seq)
                  where f.visible_from <= retained.seq
                    and (f.visible_to is null or retained.seq < f.visible_to))
             order by f.id
             limit ?
            """
                .formatted(table.tableName()),
            (rs, rowNum) -> rs.getLong("id"),
            epochId,
            retainedLiteral,
            batchSize);
    if (revisionIds.isEmpty()) {
      return 0;
    }
    deleteChildRelations(table, revisionIds);
    return jdbcTemplate.update(
        "delete from %s where id = any (?::bigint[])".formatted(table.tableName()),
        longArrayLiteral(revisionIds));
  }

  /** 已发布但非当前、且早于宽限时刻的候选回收对象。 */
  public List<UUID> listRetirablePublications(UUID domainId, Instant graceBefore) {
    return jdbcTemplate.query(
        """
        select p.id
          from read_publications p
         where p.domain_id = ?
           and p.state = 'PUBLISHED'
           and p.sequence_no > 0
           and p.published_at < ?
           and p.id is distinct from (
                 select d.current_publication_id from read_publication_domains d where d.id = ?)
           and not exists (
                 select 1 from read_view_publications vp
                   join read_view_contexts vc on vc.id = vp.view_id
                  where vp.publication_id = p.id and vc.expires_at > current_timestamp)
           and not exists (
                 select 1 from read_publications b
                  where b.base_publication_id = p.id
                    and b.state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH'))
           and not exists (
                 select 1 from read_publication_dependencies input
                 join read_publications candidate on candidate.id = input.publication_id
                  where input.dependency_publication_id = p.id
                    and candidate.state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH'))
        """,
        (rs, rowNum) -> rs.getObject("id", UUID.class),
        domainId,
        Timestamp.from(graceBefore),
        domainId);
  }

  /** 把指定发布身份标记为正在回收（不再接受新引用）。 */
  public int markRetiring(Collection<UUID> publicationIds) {
    if (publicationIds.isEmpty()) {
      return 0;
    }
    return jdbcTemplate.update(
        "update read_publications set state = 'RETIRING', updated_at = current_timestamp"
            + " where state = 'PUBLISHED' and id = any (?::uuid[])",
        uuidArrayLiteral(publicationIds));
  }

  /** 可最终回收的 RETIRING 发布身份：已过宽限且无任何视图引用。 */
  public List<UUID> listReclaimableRetiringPublications(UUID domainId, Instant graceBefore) {
    return jdbcTemplate.query(
        """
        select p.id
          from read_publications p
         where p.domain_id = ?
           and p.state = 'RETIRING'
           and p.published_at < ?
           and not exists (
                 select 1 from read_view_publications vp where vp.publication_id = p.id)
           and not exists (
                 select 1 from read_publications b
                  where b.base_publication_id = p.id
                    and b.state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH'))
           and not exists (
                 select 1 from read_publication_dependencies input
                 join read_publications candidate on candidate.id = input.publication_id
                  where input.dependency_publication_id = p.id
                    and candidate.state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH'))
        """,
        (rs, rowNum) -> rs.getObject("id", UUID.class),
        domainId,
        Timestamp.from(graceBefore));
  }

  /** 删除发布元数据行；调用方须先确认无修订与视图引用。 */
  public int deletePublicationMeta(UUID publicationId) {
    deletePublicationCaches(publicationId);
    jdbcTemplate.update("delete from fact_projection_refresh_tasks where publication_id = ?", publicationId);
    return jdbcTemplate.update("delete from read_publications where id = ?", publicationId);
  }

  /** 持有版本排他锁且确认没有视图/候选引用后，可先回收结果缓存；事实创建者元数据可以继续保留。 */
  public void deletePublicationCaches(UUID publicationId) {
    jdbcTemplate.update("delete from statistic_board_snapshots where publication_id = ?", publicationId);
    jdbcTemplate.update("delete from page_record_snapshots where publication_id = ?", publicationId);
  }

  /**
   * 按标识顺序对指定发布身份取排他锁，跳过已被读取共享锁占用的行。
   *
   * <p>回收必须显式加排他锁：普通状态 UPDATE 与读取的 {@code FOR KEY SHARE} 兼容，会让回收在读取期间
   * 删除仍在服务的修订。返回实际锁定的行，未锁定的行留待下轮回收。
   *
   * @param publicationIds 候选回收对象
   * @return 已成功加锁的发布身份（按标识升序）
   */
  public List<UUID> lockPublicationsForUpdateSkippingLocked(Collection<UUID> publicationIds) {
    if (publicationIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(
        """
        select id from read_publications
         where id = any (?::uuid[])
         order by id
         for update skip locked
        """,
        (rs, rowNum) -> rs.getObject("id", UUID.class),
        uuidArrayLiteral(publicationIds));
  }

  /** 域内仍有正在回收（RETIRING）发布身份的代际标识。 */
  public List<UUID> listEpochsWithRetiring(UUID domainId) {
    return jdbcTemplate.query(
        """
        select distinct epoch_id from read_publications
         where domain_id = ? and state in ('RETIRING', 'RETIRED')
        """,
        (rs, rowNum) -> rs.getObject("epoch_id", UUID.class),
        domainId);
  }

  /**
   * 仍必须保持可读的域内序号集合：**除本轮回收对象外的全部版本**。
   *
   * <p>覆盖面刻意宽松：当前版本、未过期页面视图引用的版本、活动候选绑定的基线版本，以及本轮未获回收排他锁的
   * 版本（在途读取占用或尚未达到回收条件）都包含在内。任何未被本轮回收的版本都可能仍被读取或后续还要服务，
   * 其可见序号必须让相关修订保留，除非某修订的可见区间完全不覆盖任何保留序号。
   *
   * @param domainId 目标域
   * @param excludedPublicationIds 本轮真正要回收的发布身份（已获排他锁并标记回收）
   */
  public List<Long> retainedSequences(UUID domainId, Collection<UUID> excludedPublicationIds) {
    String excludedLiteral = uuidArrayLiteral(excludedPublicationIds);
    List<Long> sequences =
        jdbcTemplate.query(
            """
            select distinct p.sequence_no
              from read_publications p
             where p.domain_id = ?
               and p.sequence_no > 0
               and not (p.id = any (?::uuid[]))
            """,
            (rs, rowNum) -> rs.getLong("sequence_no"),
            domainId,
            excludedLiteral);
    return sequences;
  }

  /**
   * 把某来源尚未版本化的既有事实行登记为指定候选的修订（初始版本回填使用）。
   *
   * <p>只置代际与来源发布身份，可见区间留空，随后由发布流程统一切换为已发布开放修订。
   *
   * @return 采纳行数
   */
  public int adoptExistingRevisions(
      PublicationFactTable table,
      String sourceSystem,
      String sourceInstance,
      UUID epochId,
      UUID publicationId) {
    return jdbcTemplate.update(
        """
        update %s
           set publication_epoch_id = ?,
               created_by_publication_id = ?,
               updated_at = current_timestamp
         where source_system = ?
           and source_instance = ?
           and publication_epoch_id is null
        """
            .formatted(table.tableName()),
        epochId,
        publicationId,
        sourceSystem,
        sourceInstance);
  }

  /** 把既有客户成员关系绑定到对应根修订（初始版本回填使用）。 */
  public int adoptIssueCustomerMemberBindings(UUID epochId, UUID publicationId) {
    return jdbcTemplate.update(
        """
        update issue_fact_customer_members m
           set issue_fact_revision_id = f.id
          from issue_fact f
         where f.publication_epoch_id = ?
           and f.created_by_publication_id = ?
           and m.issue_fact_revision_id is null
           and m.source_system = f.source_system
           and m.source_instance = f.source_instance
           and m.project_id = f.project_id
           and m.issue_id = f.issue_id
        """,
        epochId,
        publicationId);
  }

  /** 把既有 MR 提交关系绑定到对应 MR 父修订（初始版本回填使用）。 */
  public int adoptMergeRequestCommitBindings(UUID epochId, UUID publicationId) {
    return jdbcTemplate.update(
        """
        update merge_request_commit_fact c
           set merge_request_revision_id = f.id
          from merge_request_fact f
         where f.publication_epoch_id = ?
           and f.created_by_publication_id = ?
           and c.merge_request_revision_id is null
           and c.source_system = f.source_system
           and c.source_instance = f.source_instance
           and c.project_id = f.project_id
           and c.merge_request_id = f.merge_request_id
        """,
        epochId,
        publicationId);
  }

  private static String uuidArrayLiteral(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  private static String longArrayLiteral(Collection<Long> values) {
    return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
  }
}
