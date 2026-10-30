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

  /** 构建前捕获的 head 修订；构建成功后仅在修订未前进时结算。 */
  public record Settlement(HeadKey key, long headId, long capturedLatestVersion) {}

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

  /** 按完整来源身份批量读取 head；同一身份出现多行视为登记冲突。 */
  public Map<HeadKey, HeadRow> loadHeads(
      String sourceInstance, Collection<HeadKey> keys) {
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
                 where source_instance = ? and resource_type = 'issue' and project_id = ?
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
                args(sourceInstance, project.getKey(), chunk));
        for (Map.Entry<HeadKey, HeadRow> row : rows) {
          if (result.putIfAbsent(row.getKey(), row.getValue()) != null) {
            throw new FormConflictException("同一议题模板存在多份变更 head");
          }
        }
      }
    }
    return result;
  }

  /** 构建前读取当前 head 修订；用于构建完成后的 CAS 结算。 */
  public List<Settlement> captureLatest(Map<HeadKey, HeadRow> heads) {
    List<Settlement> settlements = new ArrayList<>();
    for (Map.Entry<HeadKey, HeadRow> entry : heads.entrySet()) {
      settlements.add(
          new Settlement(entry.getKey(), entry.getValue().id(), entry.getValue().latestChangeVersion()));
    }
    return settlements;
  }

  /** 构建已发布后结算：只有 head 修订未前进时才推进 consumed；返回实际结算数量。 */
  @Transactional
  public int settleConsumed(List<Settlement> settlements, LocalDateTime now) {
    int settled = 0;
    for (Settlement settlement : settlements) {
      settled +=
          jdbcTemplate.update(
              """
              update form_change_heads
                 set consumed_change_version = ?, status = 'CONSUMED', lease_owner = null,
                     lease_until = null, last_error = null, updated_at = ?
               where id = ? and latest_change_version = ?
                 and consumed_change_version < latest_change_version
              """,
              settlement.capturedLatestVersion(),
              Timestamp.valueOf(now),
              settlement.headId(),
              settlement.capturedLatestVersion());
    }
    return settled;
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
    publication.run();
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

  private static Object[] args(String sourceInstance, long projectId, List<Long> ids) {
    Object[] args = new Object[ids.size() + 2];
    args[0] = sourceInstance;
    args[1] = projectId;
    for (int index = 0; index < ids.size(); index++) {
      args[index + 2] = ids.get(index);
    }
    return args;
  }
}
