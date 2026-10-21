package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.service.read.CandidateOpenRequest;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 把 CAT 原子发布的完整快照登记到平台同一读取生命周期，不复制上游或本地内容。 */
@Service
public class BiCatReadPublicationService {
  private final JdbcTemplate jdbc;
  private final ReadPublicationRepository publications;
  private final ReadPublicationService lifecycle;

  public BiCatReadPublicationService(JdbcTemplate jdbc, ReadPublicationRepository publications, ReadPublicationService lifecycle) {
    this.jdbc = jdbc;
    this.publications = publications;
    this.lifecycle = lifecycle;
  }

  /** 必须与原 CAT 内容及指针在同一提交事务；登记失败则整个新快照发布回滚。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public void publishSnapshot(UUID snapshotId, long publishedVersion) {
    var inputs = jdbc.query("""
        select snapshot.product_version_id, snapshot.product_version_key, snapshot.test_stage, snapshot.collection_finished_at,
          (select count(*) from bi_cat_test_modules where snapshot_id = snapshot.snapshot_id) as module_count,
          (select count(*) from bi_cat_test_functions where snapshot_id = snapshot.snapshot_id) as function_count
          from bi_cat_test_snapshots snapshot where snapshot.snapshot_id = ? for key share
        """, (rs, row) -> new Input(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
            rs.getLong(5), rs.getLong(6)), snapshotId);
    if (inputs.size() != 1) { throw new IllegalArgumentException("CAT 本地快照不存在"); }
    Input input = inputs.getFirst();
    var key = new ReadPublicationDomainKey(ReadPublicationProducerKind.CAT_MIRROR, "default", "DATASET",
        "cat:" + input.product() + ":" + input.stage());
    UUID domainId = publications.ensureDomain(UUID.randomUUID(), key);
    var domain = publications.lockDomain(domainId);
    if (domain.currentPublicationId() != null && Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from read_publication_cat_snapshots where publication_id = ? and snapshot_id = ?)",
        Boolean.class, domain.currentPublicationId(), snapshotId))) { return; }
    String owner = "cat:" + snapshotId;
    String seal = "{\"owner\":\"CAT_MIRROR\",\"snapshotId\":\"" + snapshotId + "\",\"publishedVersion\":" + publishedVersion + "}";
    var candidate = lifecycle.openCandidate(new CandidateOpenRequest(key, UUID.randomUUID(), domain.currentPublicationId(),
        seal, 0, publications.freezeCurrentFactDefinitions("default"), owner, null, owner, input.cutoff()));
    jdbc.update("""
        insert into read_publication_cat_snapshots(publication_id, snapshot_id, published_version, product_version_id,
          product_version_key, test_stage, module_count, function_count) values (?, ?, ?, ?, ?, ?, ?, ?)
        """, candidate.id(), snapshotId, publishedVersion, input.product(), input.key(), input.stage(), input.modules(), input.functions());
    lifecycle.markProjecting(candidate.id(), owner);
    lifecycle.markReadyToPublish(candidate.id(), owner);
    lifecycle.publish(candidate.id(), owner);
  }

  private record Input(long product, String key, String stage, Instant cutoff, long modules, long functions) {}
}
