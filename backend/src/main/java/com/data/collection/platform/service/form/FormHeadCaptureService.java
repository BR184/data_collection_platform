package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.service.form.FormRecordDirectoryReader.SourceSet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表单变更 head 的领取、捕获与结算。
 *
 * <p>捕获短事务按“范围 FOR SHARE → head FOR UPDATE”锁定并持久化集合修订/哈希、版本清单、
 * head 修订、pending 逐项快照与租约身份；构建在事务外；结算在同一事务内逐项复核后推进
 * consumed。任一项漂移都抛异常，由事务回滚事实与结算（零公开零结算）。
 */
@Service
public class FormHeadCaptureService {
  private static final int CHUNK = 500;

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordDirectoryReader directoryReader;
  private final ObjectMapper objectMapper;

  public FormHeadCaptureService(
      JdbcTemplate jdbcTemplate,
      FormRecordDirectoryReader directoryReader,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.directoryReader = directoryReader;
    this.objectMapper = objectMapper;
  }

  /** 完整 head 身份：项目 + 议题 IID + 模板。 */
  public record HeadKey(long projectId, long issueIid, String templateKey) {}

  /** 消费侧读取的 head 状态。 */
  public record HeadRow(
      long id,
      long scopeId,
      long latestChangeVersion,
      long consumedChangeVersion,
      String status,
      String leaseOwner,
      long fencingVersion,
      Long authorityRevision,
      Long pendingRecordId,
      Integer pendingRecordVersion,
      Long pendingStorageVersionId,
      Long pendingSourceSetRevision,
      String pendingSourceSetHash,
      Long pendingAuthorityRevision) {

    public boolean unconsumed() {
      return consumedChangeVersion < latestChangeVersion;
    }
  }


  /**
   * 议题是否存在已推进但尚未消费的 FORM 证据。
   *
   * <p>写回发送前复核：事实版本可能还没刷新，但表单 head 已前进时同样不得按旧事实发送。
   *
   * @param sourceInstance 来源实例
   * @param projectId 项目
   * @param issueIid 议题 IID
   * @return 存在未消费 FORM 变更时返回 {@code true}
   */
  public boolean hasUnconsumedFormEvidence(
      String sourceInstance, long projectId, long issueIid) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1 from form_change_heads h
                join form_resource_authorities a
                  on a.source_config_id = h.source_config_id
                 and a.project_id = h.project_id
                 and a.resource_type = h.resource_type
                 and a.resource_id = h.resource_id
                 and a.template_key = h.template_key
                 and a.scope_id = h.scope_id
               where h.source_instance = ? and h.project_id = ? and h.resource_id = ?
                 and h.template_key in ('issue_response', 'issue_reply')
                 and a.authority = 'FORM'
                 and h.latest_change_version > h.consumed_change_version)
            """,
            Boolean.class,
            sourceInstance,
            projectId,
            issueIid);
    return Boolean.TRUE.equals(exists);
  }

  /** 捕获结果，供结算逐项复核。 */
  public record Capture(
      long headId,
      long scopeId,
      String captureToken,
      long sourceSetRevision,
      String sourceSetHash,
      String versionsJson,
      long latestChangeVersion,
      Long authorityRevision,
      Long pendingRecordId,
      Integer pendingRecordVersion,
      Long pendingStorageVersionId,
      Long pendingSourceSetRevision,
      String pendingSourceSetHash,
      Long pendingAuthorityRevision,
      String leaseOwner,
      long fencingVersion) {}

  /**
   * 候选构建输入证据。
   *
   * <p>{@code CAPTURED} 表示本次候选领取并捕获、待最终公开结算；{@code CONSUMED} 表示该 head
   * 在冻结时已消费，只能读取并在公开事务内复核仍为同一已消费修订。
   */
  public record InputEvidence(Capture capture, String status) {
    public boolean captured() {
      return "CAPTURED".equals(status);
    }
  }

  /**
   * 读取业务值前冻结一条 FORM 侧证据。
   *
   * <p>未消费 head 必须领取成功；已被他人有效持有时直接拒绝，候选不得无证继续。已消费 head
   * 生成只读证据，最终公开事务复核其仍停在冻结修订。
   *
   * @param scopeId 范围
   * @param headId 变更 head
   * @param worker 候选消费身份
   * @param now 冻结时刻
   * @return 已捕获或已消费证据
   */
  @Transactional
  public InputEvidence ensureEvidence(long scopeId, long headId, String worker, LocalDateTime now) {
    HeadRow head = headById(headId);
    if (head.consumedChangeVersion() >= head.latestChangeVersion()) {
      if (!"CONSUMED".equals(head.status())) {
        throw new FormConflictException("表单变更 head 状态与消费水位不一致，拒绝无证构建");
      }
      Map<String, Object> scope = requireScope(scopeId);
      Authority authority = requireFormAuthority(head);
      Capture evidence =
          new Capture(
              headId,
              scopeId,
              UUID.randomUUID().toString(),
              ((Number) scope.get("source_set_revision")).longValue(),
              (String) scope.get("source_set_hash"),
              null,
              head.latestChangeVersion(),
              authority.revision(),
              null,
              null,
              null,
              null,
              null,
              null,
              worker,
              0L);
      return new InputEvidence(evidence, "CONSUMED");
    }
    Optional<HeadRow> leased = claimHead(headId, worker, now);
    if (leased.isEmpty()) {
      throw new FormConflictException(
          "表单变更 head 正由其他消费者持有，本次候选无证让位：head=" + headId);
    }
    HeadRow claimed = leased.get();
    Capture capture = capture(scopeId, headId, worker, claimed.fencingVersion(), now);
    return new InputEvidence(capture, "CAPTURED");
  }





  /** 按完整来源身份批量读取指定资源类型的 head；同一身份出现多行视为登记冲突。 */
  public Map<HeadKey, HeadRow> loadHeads(
      String sourceInstance, String resourceType, Collection<HeadKey> keys) {
    Map<HeadKey, HeadRow> result = new LinkedHashMap<>();
    Map<Long, List<HeadKey>> byProject = new LinkedHashMap<>();
    for (HeadKey key : keys) {
      byProject.computeIfAbsent(key.projectId(), value -> new ArrayList<>()).add(key);
    }
    for (Map.Entry<Long, List<HeadKey>> project : byProject.entrySet()) {
      List<Long> ids =
          project.getValue().stream().map(HeadKey::issueIid).distinct().toList();
      for (int offset = 0; offset < ids.size(); offset += CHUNK) {
        List<Long> chunk = ids.subList(offset, Math.min(offset + CHUNK, ids.size()));
        List<Map.Entry<HeadKey, HeadRow>> rows =
            jdbcTemplate.query(
                """
                select id, scope_id, latest_change_version, consumed_change_version, status,
                       lease_owner, fencing_version, pending_authority_revision,
                       pending_record_id, pending_record_version, pending_storage_version_id,
                       pending_source_set_revision, pending_source_set_hash,
                       pending_authority_revision, resource_id, template_key
                  from form_change_heads
                 where source_instance = ? and resource_type = ? and project_id = ?
                   and resource_id in (%s)
                """
                    .formatted(placeholders(chunk.size())),
                (rs, rowNum) ->
                    Map.entry(
                        new HeadKey(
                            project.getKey(), rs.getLong("resource_id"), rs.getString("template_key")),
                        new HeadRow(
                            rs.getLong("id"),
                            rs.getLong("scope_id"),
                            rs.getLong("latest_change_version"),
                            rs.getLong("consumed_change_version"),
                            rs.getString("status"),
                            rs.getString("lease_owner"),
                            rs.getLong("fencing_version"),
                            nullableLong(rs, "pending_authority_revision"),
                            nullableLong(rs, "pending_record_id"),
                            nullableInt(rs, "pending_record_version"),
                            nullableLong(rs, "pending_storage_version_id"),
                            nullableLong(rs, "pending_source_set_revision"),
                            rs.getString("pending_source_set_hash"),
                            nullableLong(rs, "pending_authority_revision"))),
                args(sourceInstance, resourceType, project.getKey(), chunk));
        for (Map.Entry<HeadKey, HeadRow> row : rows) {
          if (result.putIfAbsent(row.getKey(), row.getValue()) != null) {
            throw new FormConflictException("同一议题模板存在多份变更 head");
          }
        }
      }
    }
    return result;
  }

  /** 按 ID 领取单个 head；已被他人有效持有时返回空，交给租约到期或持有者处理。 */
  public Optional<HeadRow> claimHead(long headId, String worker, LocalDateTime now) {
    List<HeadRow> rows =
        jdbcTemplate.query(
            """
            update form_change_heads h
               set status = 'LEASED', lease_owner = ?,
                   lease_until = clock_timestamp() + interval '5 minutes',
                   fencing_version = fencing_version + 1, next_attempt_at = null,
                   updated_at = clock_timestamp()
             where h.id = ?
               and (h.status in ('PENDING', 'RETRY_WAIT')
                    or (h.status = 'LEASED'
                        and (h.lease_until < clock_timestamp() or h.lease_owner = ?)))
            returning id, scope_id, latest_change_version, consumed_change_version, status,
                      lease_owner, fencing_version, pending_authority_revision,
                      pending_record_id, pending_record_version, pending_storage_version_id,
                      pending_source_set_revision, pending_source_set_hash,
                      pending_authority_revision
            """,
            (rs, rowNum) ->
                new HeadRow(
                    rs.getLong("id"),
                    rs.getLong("scope_id"),
                    rs.getLong("latest_change_version"),
                    rs.getLong("consumed_change_version"),
                    rs.getString("status"),
                    rs.getString("lease_owner"),
                    rs.getLong("fencing_version"),
                    nullableLong(rs, "pending_authority_revision"),
                    nullableLong(rs, "pending_record_id"),
                    nullableInt(rs, "pending_record_version"),
                    nullableLong(rs, "pending_storage_version_id"),
                    nullableLong(rs, "pending_source_set_revision"),
                    rs.getString("pending_source_set_hash"),
                    nullableLong(rs, "pending_authority_revision")),
            worker,
            headId,
            worker);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }












  /** 已消费证据只能复核：先范围后 head（与发布/捕获同一锁序），再核集合与权威修订未变化。 */
  private void verifyConsumedEvidence(Capture capture) {
    Map<String, Object> scope = requireScope(capture.scopeId());
    HeadRow head = lockHead(capture.headId());
    if (!"CONSUMED".equals(head.status())
        || head.consumedChangeVersion() < head.latestChangeVersion()
        || head.latestChangeVersion() != capture.latestChangeVersion()) {
      throw new FormConflictException("已消费表单证据在公开前又发生变化，零公开");
    }
    long revision = ((Number) scope.get("source_set_revision")).longValue();
    String hash = (String) scope.get("source_set_hash");
    if (revision != capture.sourceSetRevision() || !hash.equals(capture.sourceSetHash())) {
      throw new FormConflictException("已消费表单证据的源集合已漂移，零公开");
    }
    Authority authority = requireFormAuthority(head);
    if (!same(authority.revision(), capture.authorityRevision())) {
      throw new FormConflictException("已消费表单证据的权威修订已漂移，零公开");
    }
  }

  private Map<String, Object> requireScope(long scopeId) {
    return jdbcTemplate
        .queryForList(
            "select source_set_revision, source_set_hash from form_template_scopes"
                + " where id = ? for share",
            scopeId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new FormConflictException("表单范围不存在：" + scopeId));
  }

  private HeadRow headById(long headId) {
    return jdbcTemplate
        .query(
            """
            select id, scope_id, latest_change_version, consumed_change_version, status,
                   lease_owner, fencing_version, pending_authority_revision,
                   pending_record_id, pending_record_version, pending_storage_version_id,
                   pending_source_set_revision, pending_source_set_hash,
                   pending_authority_revision
              from form_change_heads where id = ?
            """,
            (rs, rowNum) ->
                new HeadRow(
                    rs.getLong("id"),
                    rs.getLong("scope_id"),
                    rs.getLong("latest_change_version"),
                    rs.getLong("consumed_change_version"),
                    rs.getString("status"),
                    rs.getString("lease_owner"),
                    rs.getLong("fencing_version"),
                    nullableLong(rs, "pending_authority_revision"),
                    nullableLong(rs, "pending_record_id"),
                    nullableInt(rs, "pending_record_version"),
                    nullableLong(rs, "pending_storage_version_id"),
                    nullableLong(rs, "pending_source_set_revision"),
                    rs.getString("pending_source_set_hash"),
                    nullableLong(rs, "pending_authority_revision")),
            headId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new FormConflictException("表单变更 head 不存在：" + headId));
  }

  private static long longOf(Object value) {
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static Long longOfNullable(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static Integer intOfNullable(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  /** 领取一个待消费 head；多进程安全。 */
  public Optional<HeadRow> claim(String worker, LocalDateTime now) {
    List<HeadRow> rows =
        jdbcTemplate.query(
            """
            update form_change_heads h
               set status = 'LEASED', lease_owner = ?,
                   lease_until = clock_timestamp() + interval '5 minutes',
                   fencing_version = fencing_version + 1, next_attempt_at = null,
                   updated_at = clock_timestamp()
             where h.id = (
               select id from form_change_heads
                where status in ('PENDING', 'RETRY_WAIT')
                   or (status = 'LEASED' and lease_until < clock_timestamp())
                order by next_attempt_at nulls first, id
                for update skip locked
                limit 1)
            returning id, scope_id, latest_change_version, consumed_change_version, status,
                      lease_owner, fencing_version, pending_authority_revision,
                      pending_record_id, pending_record_version, pending_storage_version_id,
                      pending_source_set_revision, pending_source_set_hash,
                      pending_authority_revision
            """,
            (rs, rowNum) ->
                new HeadRow(
                    rs.getLong("id"),
                    rs.getLong("scope_id"),
                    rs.getLong("latest_change_version"),
                    rs.getLong("consumed_change_version"),
                    rs.getString("status"),
                    rs.getString("lease_owner"),
                    rs.getLong("fencing_version"),
                    nullableLong(rs, "pending_authority_revision"),
                    nullableLong(rs, "pending_record_id"),
                    nullableInt(rs, "pending_record_version"),
                    nullableLong(rs, "pending_storage_version_id"),
                    nullableLong(rs, "pending_source_set_revision"),
                    rs.getString("pending_source_set_hash"),
                    nullableLong(rs, "pending_authority_revision")),
            worker);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  /**
   * 捕获短事务：范围 FOR SHARE → head FOR UPDATE，复核租约后冻结本次消费身份。
   *
   * @return 捕获快照；head 失配时抛出冲突，调用方不得公开任何事实
   */
  @Transactional
  public Capture capture(
      long scopeId, long headId, String worker, long fence, LocalDateTime now) {
    Map<String, Object> scope =
        jdbcTemplate
            .queryForList(
                "select source_set_revision, source_set_hash from form_template_scopes"
                    + " where id = ? for share",
                scopeId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new FormConflictException("表单范围不存在：" + scopeId));
    HeadRow head = lockHead(headId);
    requireLease(head, worker, fence);
    Authority authority = requireFormAuthority(head);
    SourceSet sourceSet = directoryReader.captureSourceSet(scopeId);
    String versionsJson;
    try {
      versionsJson = objectMapper.writeValueAsString(sourceSet.versions());
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("表单版本清单无法序列化", error);
    }
    long revision = ((Number) scope.get("source_set_revision")).longValue();
    String hash = (String) scope.get("source_set_hash");
    if (sourceSet.revision() != revision || !sourceSet.hash().equals(hash)) {
      throw new FormConflictException("表单源集合在捕获期间漂移");
    }
    String token =
        jdbcTemplate.queryForObject(
            """
            insert into form_head_captures(
                head_id, source_set_revision, source_set_hash, versions, latest_change_version,
                authority_revision, pending_record_id, pending_record_version,
                pending_storage_version_id, pending_source_set_revision, pending_source_set_hash,
                pending_authority_revision, lease_owner, fencing_version, status, captured_at)
            values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CAPTURED', ?)
            on conflict (head_id) do update
               set capture_token = gen_random_uuid(), source_set_revision = excluded.source_set_revision,
                   source_set_hash = excluded.source_set_hash, versions = excluded.versions,
                   latest_change_version = excluded.latest_change_version,
                   authority_revision = excluded.authority_revision,
                   pending_record_id = excluded.pending_record_id,
                   pending_record_version = excluded.pending_record_version,
                   pending_storage_version_id = excluded.pending_storage_version_id,
                   pending_source_set_revision = excluded.pending_source_set_revision,
                   pending_source_set_hash = excluded.pending_source_set_hash,
                   pending_authority_revision = excluded.pending_authority_revision,
                   lease_owner = excluded.lease_owner, fencing_version = excluded.fencing_version,
                   status = 'CAPTURED', consumed_at = null, captured_at = excluded.captured_at
            returning capture_token::text
            """,
            String.class,
            headId,
            revision,
            hash,
            versionsJson,
            head.latestChangeVersion(),
            authority.revision(),
            head.pendingRecordId(),
            head.pendingRecordVersion(),
            head.pendingStorageVersionId(),
            head.pendingSourceSetRevision(),
            head.pendingSourceSetHash(),
            head.pendingAuthorityRevision(),
            worker,
            fence,
            Timestamp.valueOf(now));
    return new Capture(
        headId,
        scopeId,
        token,
        revision,
        hash,
        versionsJson,
        head.latestChangeVersion(),
        authority.revision(),
        head.pendingRecordId(),
        head.pendingRecordVersion(),
        head.pendingStorageVersionId(),
        head.pendingSourceSetRevision(),
        head.pendingSourceSetHash(),
        head.pendingAuthorityRevision(),
        worker,
        fence);
  }

  /**
   * 结算事务：范围 FOR SHARE → head FOR UPDATE，逐项复核后执行公开并推进 consumed。
   *
   * @param capture 捕获快照
   * @param publication 事实与投影的公开动作，在同一事务内执行
   */
  @Transactional
  public void settle(Capture capture, Runnable publication) {
    settleCapture(capture, publication);
  }

  /** 最终公开事务内的逐项复核与结算；{@code publication} 为空表示公开动作已在本事务内完成。 */
  private void settleCapture(Capture capture, Runnable publication) {
    Map<String, Object> scope =
        jdbcTemplate
            .queryForList(
                "select source_set_revision, source_set_hash from form_template_scopes"
                    + " where id = ? for share",
                capture.scopeId())
            .stream()
            .findFirst()
            .orElseThrow(() -> new FormConflictException("表单范围不存在：" + capture.scopeId()));
    HeadRow head = lockHead(capture.headId());
    requireLease(head, capture.leaseOwner(), capture.fencingVersion());
    Map<String, Object> stored =
        jdbcTemplate
            .queryForList(
                "select * from form_head_captures where head_id = ? for update",
                capture.headId())
            .stream()
            .findFirst()
            .orElseThrow(() -> new FormConflictException("表单捕获不存在"));
    if (!capture.captureToken().equals(String.valueOf(stored.get("capture_token")))
        || !"CAPTURED".equals(stored.get("status"))) {
      throw new FormConflictException("表单捕获已被替换或已结算");
    }
    long currentRevision = ((Number) scope.get("source_set_revision")).longValue();
    String currentHash = (String) scope.get("source_set_hash");
    if (currentRevision != capture.sourceSetRevision()
        || !currentHash.equals(capture.sourceSetHash())) {
      throw new FormConflictException("表单源集合已漂移，零公开零结算");
    }
    if (head.latestChangeVersion() != capture.latestChangeVersion()) {
      throw new FormConflictException("表单变更 head 已前进，零公开零结算");
    }
    if (head.pendingSourceSetRevision() != null
        && (!same(head.pendingSourceSetRevision(), currentRevision)
            || !same(head.pendingSourceSetHash(), currentHash))) {
      throw new FormConflictException("表单 pending 源集合与当前集合不一致，零公开零结算");
    }
    if (!same(head.pendingSourceSetRevision(), capture.pendingSourceSetRevision())
        || !same(head.pendingSourceSetHash(), capture.pendingSourceSetHash())
        || !same(head.pendingRecordId(), capture.pendingRecordId())
        || !same(head.pendingRecordVersion(), capture.pendingRecordVersion())
        || !same(head.pendingStorageVersionId(), capture.pendingStorageVersionId())) {
      throw new FormConflictException("表单 pending 快照已漂移，零公开零结算");
    }
    Authority authority = requireFormAuthority(head);
    if (!same(authority.revision(), capture.authorityRevision())
        || !same(authority.revision(), capture.pendingAuthorityRevision())
        || !same(head.pendingAuthorityRevision(), capture.pendingAuthorityRevision())) {
      throw new FormConflictException("表单权威修订已漂移，零公开零结算");
    }
    if (publication != null) {
      publication.run();
    }
    int headUpdated =
        jdbcTemplate.update(
            """
            update form_change_heads
               set consumed_change_version = latest_change_version,
                   pending_record_id = null, pending_record_version = null,
                   pending_storage_version_id = null, pending_source_set_revision = null,
                   pending_source_set_hash = null, pending_authority_revision = null,
                   status = 'CONSUMED', lease_owner = null, lease_until = null, last_error = null,
                   updated_at = clock_timestamp()
             where id = ? and status = 'LEASED' and lease_owner = ?
               and fencing_version = ? and lease_until > clock_timestamp()
               and latest_change_version = ?
            """,
            capture.headId(),
            capture.leaseOwner(),
            capture.fencingVersion(),
            capture.latestChangeVersion());
    int captureUpdated =
        jdbcTemplate.update(
            "update form_head_captures set status = 'CONSUMED', consumed_at = clock_timestamp()"
                + " where head_id = ? and capture_token = ?::uuid and status = 'CAPTURED'",
            capture.headId(),
            capture.captureToken());
    if (headUpdated != 1 || captureUpdated != 1) {
      throw new FormConflictException("表单结算 CAS 未命中，回滚事实与结算");
    }
  }

  private HeadRow lockHead(long headId) {
    return jdbcTemplate
        .query(
            """
            select id, scope_id, latest_change_version, consumed_change_version, status,
                   lease_owner, fencing_version, pending_authority_revision,
                   pending_record_id, pending_record_version, pending_storage_version_id,
                   pending_source_set_revision, pending_source_set_hash,
                   pending_authority_revision
              from form_change_heads where id = ? for update
            """,
            (rs, rowNum) ->
                new HeadRow(
                    rs.getLong("id"),
                    rs.getLong("scope_id"),
                    rs.getLong("latest_change_version"),
                    rs.getLong("consumed_change_version"),
                    rs.getString("status"),
                    rs.getString("lease_owner"),
                    rs.getLong("fencing_version"),
                    nullableLong(rs, "pending_authority_revision"),
                    nullableLong(rs, "pending_record_id"),
                    nullableInt(rs, "pending_record_version"),
                    nullableLong(rs, "pending_storage_version_id"),
                    nullableLong(rs, "pending_source_set_revision"),
                    rs.getString("pending_source_set_hash"),
                    nullableLong(rs, "pending_authority_revision")),
            headId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new FormConflictException("表单变更 head 不存在：" + headId));
  }

  private Authority requireFormAuthority(HeadRow head) {
    List<Authority> rows =
        jdbcTemplate.query(
            """
            select a.authority, a.authority_revision
              from form_resource_authorities a
              join form_change_heads h on h.source_config_id = a.source_config_id
               and h.project_id = a.project_id and h.resource_type = a.resource_type
               and h.resource_id = a.resource_id and h.template_key = a.template_key
             where h.id = ?
            """,
            (rs, rowNum) ->
                new Authority(rs.getString("authority"), nullableLong(rs, "authority_revision")),
            head.id());
    if (rows.isEmpty() || !"FORM".equals(rows.getFirst().authority())
        || rows.getFirst().revision() == null) {
      throw new FormConflictException("变更 head 缺少 FORM 持久权威");
    }
    return rows.getFirst();
  }

  private void requireLease(HeadRow head, String worker, long fence) {
    if (!"LEASED".equals(head.status())
        || !worker.equals(head.leaseOwner())
        || head.fencingVersion() != fence
        || !leaseValid(head.id())) {
      throw new FormConflictException("表单变更 head 租约失配");
    }
  }

  private boolean leaseValid(long headId) {
    Boolean valid =
        jdbcTemplate.queryForObject(
            "select lease_until > clock_timestamp() from form_change_heads where id = ?",
            Boolean.class,
            headId);
    return Boolean.TRUE.equals(valid);
  }

  private static boolean same(Object left, Object right) {
    return left == null ? right == null : left.equals(right);
  }

  private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private record Authority(String authority, Long revision) {}

  private static String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }

  private static Object[] args(
      String sourceInstance, String resourceType, long projectId, List<Long> ids) {
    Object[] args = new Object[ids.size() + 3];
    args[0] = sourceInstance;
    args[1] = resourceType;
    args[2] = projectId;
    for (int index = 0; index < ids.size(); index++) {
      args[index + 3] = ids.get(index);
    }
    return args;
  }

  /** 在调用者的事实批次事务中复核全部证据并结算；任一失败使事实和水位共同回滚。 */
  public void settleEvidence(List<InputEvidence> evidence) {
    for (InputEvidence entry : evidence) {
      if (entry.captured()) { settleCapture(entry.capture(), null); }
      else { verifyConsumedEvidence(entry.capture()); }
    }
  }
}
