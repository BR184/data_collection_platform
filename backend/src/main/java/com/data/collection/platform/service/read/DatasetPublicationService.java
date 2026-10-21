package com.data.collection.platform.service.read;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomain;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 数据集输入捕获与最终发布分离；读取者只使用同一发布模型中的完整指针。 */
@Service
public class DatasetPublicationService {
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final ReadPublicationRepository publications;
  private final ReadPublicationService lifecycle;
  private final ReadDatasetRepository datasets;

  public DatasetPublicationService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils,
      ReadPublicationRepository publications, ReadPublicationService lifecycle, ReadDatasetRepository datasets) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.publications = publications;
    this.lifecycle = lifecycle;
    this.datasets = datasets;
  }

  /**
   * 在稳定输入事务内认证并捕获完整归一化关系，保留所有 NULL/类型和关联；不在此事务访问远端。
   * 输入正在写入或缺少完整证据时拒绝本次捕获，生产者重新排队，已公开版本继续服务。
   * 稳定键/模式复用未变化修订；完整对账不强制新代际。捕获仍可能扫描完整来源，成本随规模变化。
   * 最终发布另用事务更新可见区间和指针，不在发布事务复制完整载荷，但也不保证恒定耗时。
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public ReadPublication prepareCompleteDataset(DatasetPublicationRequest request) {
    TreeSet<String> relations = new TreeSet<>(request.evidence().evidenceRelations());
    request.relations().forEach(input -> relations.add(input.relationName()));
    relations.forEach(com.data.collection.platform.entity.read.DatasetRelationInput::requireIdentifier);
    // RR 保证行与装载清单落在同一个提交视图；只保护关系名字免受换表 DDL，不持全表 SHARE 阻塞同步写入。
    jdbcTemplate.execute("lock table " + String.join(", ", relations) + " in access share mode nowait");
    DatasetInputEvidence.VerifiedInput evidence = request.evidence().verify(request.domain(), jdbcTemplate, jsonUtils);
    UUID domainId = publications.ensureDomain(UUID.randomUUID(), request.domain());
    ReadPublicationDomain domain = publications.lockDomain(domainId);
    if (publications.findActiveCandidate(domainId).isPresent()) {
      throw new com.data.collection.platform.service.FactCandidateBusyException("该数据集已有候选，后来的生产者让位");
    }
    ReadPublication base = domain.currentPublicationId() == null ? null
        : publications.findPublication(domain.currentPublicationId()).orElseThrow();
    UUID definition = publications.freezeCurrentFactDefinitions(request.domain().sourceInstance());
    if (base != null && definition.equals(base.definitionSnapshotId())
        && datasets.matchesCompleteSource(base, request.relations())) {
      jdbcTemplate.update("update read_publication_domains set unsettled_since = null,"
          + " last_successful_verification_at = current_timestamp where id = ? and current_publication_id = ?",
          domainId, base.id());
      return base;
    }
    boolean newEpoch = !datasets.matchesRelationSchemas(base, request.relations())
        || request.relations().stream().anyMatch(input -> input.keyColumns().isEmpty());
    ReadPublication candidate = lifecycle.openCandidate(new CandidateOpenRequest(request.domain(),
        newEpoch ? UUID.randomUUID() : base.epochId(), domain.currentPublicationId(), evidence.sealJson(), 0,
        definition, request.producerRunId(), null, request.producerRunId(), evidence.dataAsOf()));
    for (var relation : request.relations()) {
      datasets.captureRelation(candidate, base, relation);
    }
    datasets.sealCapture(candidate, base);
    return lifecycle.markProjecting(candidate.id(), request.producerRunId());
  }

  /** 完整数据集已物化后，独立事务核验捕获证明并原子提交区间及指针；区间更新量影响耗时。 */
  @Transactional
  public ReadPublication publishPreparedDataset(UUID publicationId, String producerRunId) {
    ReadPublication publication = publications.findPublication(publicationId).orElseThrow();
    if (!publications.findDomain(publication.domainId()).orElseThrow().key().factType().equals("DATASET")) {
      throw new IllegalArgumentException("事实候选必须由事实唯一发布入口提交");
    }
    if (!producerRunId.equals(publication.producerRunId())) { throw new IllegalArgumentException("数据集生产归属已变化"); }
    ReadPublicationDomain domain = publications.lockDomain(publication.domainId());
    // 安装与调度可能同时看到同一候选；状态必须在域锁之后重查。
    publication = publications.findPublication(publicationId).orElseThrow();
    if (publication.state() == com.data.collection.platform.entity.read.ReadPublicationState.PUBLISHED) { return publication; }
    requireModeTargets(publication, domain);
    lifecycle.markReadyToPublish(publicationId, producerRunId);
    ReadPublication published = lifecycle.publish(publicationId, producerRunId);
    jdbcTemplate.update("delete from read_dataset_producer_intents where candidate_publication_id = ?", publicationId);
    return published;
  }

  /** 持久请求的最终发布必须仍持有当前租约；短事务中锁后复查，失租工作者不能切指针。 */
  @Transactional
  public ReadPublication publishRequestedDataset(UUID publicationId, String producerRunId,
      String owner, UUID leaseOwner) {
    var leases = jdbcTemplate.queryForList("""
        select requested_revision from read_dataset_refresh_requests
         where producer_kind = ? and source_instance = 'default' and table_key = ''
           and lease_owner = ? and lease_until > current_timestamp for update
        """, Long.class, owner, leaseOwner);
    if (leases.size() != 1) { throw new IllegalStateException("数据集请求的生产租约已失效"); }
    return publishPreparedDataset(publicationId, producerRunId);
  }

  /** 失败清理与当前请求租约的锁后核验同事务，避免旧工作者清除已被接管的候选。 */
  @Transactional
  public void abortRequestedDataset(String producerRunId, String owner, UUID leaseOwner, String reason) {
    var leases = jdbcTemplate.queryForList("select requested_revision from read_dataset_refresh_requests"
        + " where producer_kind = ? and source_instance = 'default' and table_key = ''"
        + " and lease_owner = ? and lease_until > current_timestamp for update", Long.class, owner, leaseOwner);
    if (leases.isEmpty()) { return; }
    for (UUID candidate : jdbcTemplate.queryForList("select id from read_publications where producer_run_id = ?"
        + " and state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')", UUID.class, producerRunId)) {
      lifecycle.abort(candidate, reason);
    }
  }

  private void requireModeTargets(ReadPublication publication, ReadPublicationDomain domain) {
    if (domain.key().producerKind() != com.data.collection.platform.entity.read.ReadPublicationProducerKind.FORMAL_RECORD
        || !domain.key().tableKey().equals("formal-records")) { return; }
    var next = datasets.capturedReadModes(publication);
    var previous = publication.basePublicationId() == null ? null
        : datasets.capturedReadModes(publications.findPublication(publication.basePublicationId()).orElseThrow());
    if (next.codeCompatibility() && (previous == null || !previous.codeCompatibility())) {
      requireDatasetTarget(com.data.collection.platform.entity.read.ReadPublicationProducerKind.COMPAT_WALKTHROUGH, "compat-walkthrough");
    }
    if (next.reviewCompatibility() && (previous == null || !previous.reviewCompatibility())) {
      requireDatasetTarget(com.data.collection.platform.entity.read.ReadPublicationProducerKind.COMPAT_REVIEW, "compat-review");
    }
    if (previous != null && previous.codeCompatibility() && !next.codeCompatibility()) {
      Boolean complete = jdbcTemplate.queryForObject("select exists(select 1 from read_publication_domains d"
          + " join read_publications p on p.id = d.current_publication_id"
          + " where d.fact_type = 'MERGE_REQUEST' and p.state = 'PUBLISHED')", Boolean.class);
      if (!Boolean.TRUE.equals(complete)) { throw new IllegalStateException("目标来源尚无完整产出：正式代码走查"); }
    }
  }

  private void requireDatasetTarget(com.data.collection.platform.entity.read.ReadPublicationProducerKind owner, String key) {
    var domain = new com.data.collection.platform.entity.read.ReadPublicationDomainKey(owner, "default", "DATASET", key);
    var point = publications.findDomain(domain).flatMap(value -> publications.findCurrentPublication(value.id())).orElse(null);
    if (point == null || point.state() != com.data.collection.platform.entity.read.ReadPublicationState.PUBLISHED) {
      throw new IllegalStateException("目标来源尚无完整产出：" + key);
    }
  }
}
