package com.data.collection.platform.service.read;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactPublicationContext;
import com.data.collection.platform.entity.FactPublicationMode;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.service.read.CandidateCacheArtifactStore.CandidateCacheArtifact;
import com.data.collection.platform.service.FactProjectionPublicationGuardService;
import com.data.collection.platform.service.FactProjectionTaskService;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 实际候选预热产物的登记与核验；任务状态、最新 generation 或空 no-op 都不能代替产物。 */
@Service
public class CandidateProjectionArtifactService {
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final ReadPublicationRepository repository;
  private final FactReadSelectionService selections;
  private final FactProjectionPublicationGuardService guard;
  private final FactProjectionTaskService tasks;
  private final CandidateCacheArtifactStore artifacts;

  public CandidateProjectionArtifactService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils,
      ReadPublicationRepository repository, FactReadSelectionService selections,
      FactProjectionPublicationGuardService guard, FactProjectionTaskService tasks,
      CandidateCacheArtifactStore artifacts) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.repository = repository;
    this.selections = selections;
    this.guard = guard;
    this.tasks = tasks;
    this.artifacts = artifacts;
  }

  /** 在父/子执行权核验后冻结本候选的多源组合，所有业务预热显式消费此上下文。 */
  @Transactional
  public FactPublicationContext contextForTask(QueuedFactProjectionTask task) {
    guard.requireTaskAuthorization(task);
    FactReadSelection selection = selections.forCandidate(task.publicationId());
    ReadPublication publication = repository.findPublication(task.publicationId()).orElseThrow();
    repository.requireUnchangedDefinitions(publication, task.scope().sourceInstance());
    return new FactPublicationContext(task.scope().sourceInstance(), task.scope().factType(),
        task.scope().scopeType() == ProjectionScopeType.FULL_EPOCH ? FactPublicationMode.FULL : FactPublicationMode.TARGETED,
        Set.of(task.scope()), selection);
  }

  /** 真实消费者全部返回后，先与本任务写入时读回的快照比对，再登记自身产物证明；证明与任务成功同事务提交。 */
  @Transactional
  public void complete(QueuedFactProjectionTask task, FactPublicationContext context, List<String> consumers,
      List<CandidateCacheArtifact> written) {
    guard.requireTaskAuthorization(task);
    ReadPublication publication = repository.findPublication(task.publicationId()).orElseThrow();
    repository.requireUnchangedDefinitions(publication, task.scope().sourceInstance());
    String sourceVersion = context.readSelection().sourceVersion();
    List<CandidateCacheArtifact> entries = written.stream()
        .sorted(Comparator.comparing(CandidateCacheArtifact::kind).thenComparingLong(CandidateCacheArtifact::id))
        .toList();
    for (CandidateCacheArtifact entry : entries) {
      CandidateCacheArtifact current = artifacts.read(entry.kind(), publication.id(), entry.id());
      if (current == null) {
        throw new IllegalStateException(evidence("候选预热缓存已缺失，不能证明", sourceVersion, task, entry, null));
      }
      if (!current.checksum().equals(entry.checksum())) {
        throw new IllegalStateException(
            evidence("候选预热缓存在本任务写入后被改写，不能证明", sourceVersion, task, entry, current));
      }
    }
    PublicationFactTable table = PublicationFactTable.of(task.scope().factType());
    String rootsSql = "select count(*) as row_count, count(distinct (source_system, source_instance, project_id, "
        + table.rootColumn() + ")) as root_count from " + table.tableName();
    VersionedFactSql.Query query = VersionedFactSql.bind(context.readSelection(), rootsSql, List.of());
    FactSummary summary = jdbcTemplate.queryForObject(query.sql(), (rs, row) ->
        new FactSummary(rs.getLong("row_count"), rs.getLong("root_count")), query.arguments().toArray());
    if (summary == null || summary.rows() != summary.roots()) {
      throw new IllegalStateException("候选逻辑事实存在重复业务根，不能发布");
    }
    ArtifactManifest manifest = new ArtifactManifest(sourceVersion,
        consumers.stream().sorted().distinct().toList(), entries, summary);
    String payload = jsonUtils.toJson(manifest);
    jdbcTemplate.update("""
        insert into read_projection_artifacts(publication_id, scope_type, scope_key, generation,
            definition_snapshot_id, source_seal, consumers, artifact_checksum)
        values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
        on conflict(publication_id, scope_type, scope_key) do update
          set generation = excluded.generation, definition_snapshot_id = excluded.definition_snapshot_id,
              source_seal = excluded.source_seal, consumers = excluded.consumers,
              artifact_checksum = excluded.artifact_checksum, prepared_at = current_timestamp
        """, publication.id(), task.scope().scopeType().name(), task.scope().scopeKey(), task.targetGeneration(),
        publication.definitionSnapshotId(), publication.sourceSealJson(), payload, checksum(payload));
    if (repository.recordProjectionPrepared(publication.id(), task.scope().scopeType(), task.scope().scopeKey(),
        task.targetGeneration(), publication.sourceSealJson(), publication.definitionSnapshotId()) != 1) {
      throw new IllegalStateException("预热完成身份已失效");
    }
    tasks.finishOwned(task, "候选业务投影与产物清单已核验");
  }

  /**
   * 完整发布前双向复核：每份证明的缓存行当前内容必须与证明一致，且当前每条候选缓存行都必须有证明。
   *
   * <p>失败信息带来源版本、证明范围与代际、行主键与业务键及预期/实际校验和，使一次报错即可指认被改写的行。
   */
  public void verifyForPublication(UUID publicationId) {
    List<ManifestProof> proofs = jdbcTemplate.query("""
        select scope_type, scope_key, generation, consumers::text, artifact_checksum
          from read_projection_artifacts where publication_id = ?
         order by scope_type, scope_key, generation
        """, (rs, row) -> {
      ArtifactManifest manifest = jsonUtils.fromJson(rs.getString(4), ArtifactManifest.class);
      if (!checksum(jsonUtils.toJson(manifest)).equals(rs.getString(5))) {
        throw new IllegalStateException("候选产物清单校验和不匹配，必须重新预热：发布 " + publicationId
            + "，证明范围 " + rs.getString(1) + "/" + rs.getString(2) + " 代际 " + rs.getLong(3));
      }
      return new ManifestProof(rs.getString(1), rs.getString(2), rs.getLong(3), manifest);
    }, publicationId);
    Map<CacheGroup, Map<Long, CandidateCacheArtifact>> persisted = new HashMap<>();
    Map<CacheGroup, Set<Long>> attested = new HashMap<>();
    for (ManifestProof proof : proofs) {
      for (CandidateCacheArtifact entry : proof.manifest().snapshots()) {
        CacheGroup group = new CacheGroup(entry.kind(), proof.manifest().sourceVersion());
        CandidateCacheArtifact actual = persisted
            .computeIfAbsent(group, key -> index(publicationId, key))
            .get(entry.id());
        if (actual == null) {
          throw new IllegalStateException(
              evidence("候选预热缓存已缺失", proof.manifest().sourceVersion(), proof, entry, null));
        }
        if (!actual.checksum().equals(entry.checksum())) {
          throw new IllegalStateException(evidence("候选预热缓存内容已变化，必须重建", proof.manifest().sourceVersion(),
              proof, entry, actual));
        }
        attested.computeIfAbsent(group, key -> new java.util.HashSet<>()).add(entry.id());
      }
    }
    for (Map.Entry<CacheGroup, Map<Long, CandidateCacheArtifact>> group : persisted.entrySet()) {
      Set<Long> proven = attested.getOrDefault(group.getKey(), Set.of());
      for (CandidateCacheArtifact row : group.getValue().values()) {
        if (!proven.contains(row.id())) {
          throw new IllegalStateException("候选预热缓存缺少完成证明，禁止发布：发布 " + publicationId
              + "，来源版本 " + group.getKey().sourceVersion() + "，产物 " + row);
        }
      }
    }
  }

  private Map<Long, CandidateCacheArtifact> index(UUID publicationId, CacheGroup group) {
    Map<Long, CandidateCacheArtifact> index = new HashMap<>();
    for (CandidateCacheArtifact artifact : artifacts.readAll(group.kind(), publicationId, group.sourceVersion())) {
      index.put(artifact.id(), artifact);
    }
    return index;
  }

  private static String evidence(String reason, String sourceVersion, Object prover,
      CandidateCacheArtifact expected, CandidateCacheArtifact actual) {
    String proverText = prover instanceof QueuedFactProjectionTask task
        ? "，证明范围 " + task.scope().scopeType() + "/" + task.scope().scopeKey() + " 代际 " + task.targetGeneration()
        : prover instanceof ManifestProof proof
            ? "，证明范围 " + proof.scopeType() + "/" + proof.scopeKey() + " 代际 " + proof.generation()
            : "";
    return reason + "：来源版本 " + sourceVersion + proverText + "，预期 " + expected + "，实际 "
        + (actual == null ? "<缺失>" : actual);
  }

  private record CacheGroup(String kind, String sourceVersion) {}

  /** 一条候选范围任务的完成证明及其范围身份。 */
  private record ManifestProof(String scopeType, String scopeKey, long generation, ArtifactManifest manifest) {}

  private static String checksum(String value) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 不可用", error);
    }
  }

  /** 完整逻辑事实的身份唯一性核验，真实空集合仍可通过。 */
  public record FactSummary(long rows, long roots) {}

  /** 持久预热清单；只登记本任务写入时读回的缓存行快照，没有适用范围时仍执行完整事实唯一性核验。 */
  public record ArtifactManifest(String sourceVersion, List<String> consumers,
      List<CandidateCacheArtifact> snapshots, FactSummary facts) {}
}
