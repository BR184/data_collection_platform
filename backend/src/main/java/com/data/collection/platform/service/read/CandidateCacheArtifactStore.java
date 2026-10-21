package com.data.collection.platform.service.read;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 候选预热缓存行的唯一取证入口。
 *
 * <p>写入时读回与发布核验读取走本类的同一条查询，校验口径只有一份真源。载荷原文不进清单，清单只保存
 * 身份与内容校验和；校验和公式与原实现一致，不引入新的证据类型。
 */
@Service
public class CandidateCacheArtifactStore {
  /** 统计看板候选缓存。 */
  public static final String KIND_STATISTIC = "STATISTIC";
  /** 记录页候选缓存。 */
  public static final String KIND_RECORD = "RECORD";

  private final JdbcTemplate jdbcTemplate;

  public CandidateCacheArtifactStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 读取单个候选缓存行的内容快照。
   *
   * @param kind 存储类别
   * @param publicationId 候选发布身份
   * @param id 缓存行主键
   * @return 内容快照；行不存在或已不是本候选的 {@code PREPARED} 行时为 {@code null}
   */
  public CandidateCacheArtifact read(String kind, UUID publicationId, long id) {
    List<CandidateCacheArtifact> rows =
        jdbcTemplate.query(selectSql(kind) + " and artifact.id = ? order by artifact.id",
            (rs, row) -> toArtifact(kind, rs), publicationId, id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /**
   * 读取某候选某来源版本下的全部候选缓存行内容快照，按主键定序。
   *
   * @param kind 存储类别
   * @param publicationId 候选发布身份
   * @param sourceVersion 候选锁定的来源版本
   * @return 不可变快照列表
   */
  public List<CandidateCacheArtifact> readAll(String kind, UUID publicationId, String sourceVersion) {
    return List.copyOf(jdbcTemplate.query(
        selectSql(kind) + " and artifact.source_version = ? order by artifact.id",
        (rs, row) -> toArtifact(kind, rs), publicationId, sourceVersion));
  }

  private static CandidateCacheArtifact toArtifact(String kind, java.sql.ResultSet rs) throws java.sql.SQLException {
    return CandidateCacheArtifact.of(kind, rs.getLong("artifact_id"), rs.getString("consumer_key"),
        rs.getString("payload"));
  }

  private String selectSql(String kind) {
    boolean statistic = KIND_STATISTIC.equals(kind);
    String payload = statistic
        ? "coalesce(artifact.definition_payload::text, '') || artifact.row_payload::text"
            + " || artifact.applied_filter_payload::text"
        : "artifact.response_payload::text";
    return "select artifact.id as artifact_id, artifact." + (statistic ? "board_key" : "page_key")
        + " as consumer_key, " + payload + " as payload from "
        + (statistic ? "statistic_board_snapshots" : "page_record_snapshots") + " artifact"
        + " where artifact.publication_id = ? and artifact.status = 'PREPARED'";
  }

  /**
   * 一条候选缓存行的身份与内容校验和。
   *
   * <p>同一行被改成不同内容时身份不变而校验和必变；校验和只覆盖载荷内容，不含时间戳一类的易变字段。
   */
  public record CandidateCacheArtifact(String kind, long id, String consumerKey, String checksum) {
    /** 以读回的载荷原文取证。 */
    public static CandidateCacheArtifact of(String kind, long id, String consumerKey, String payload) {
      return new CandidateCacheArtifact(kind, id, consumerKey,
          checksum(payload == null ? "" : payload));
    }

    private static String checksum(String value) {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException error) {
        throw new IllegalStateException("SHA-256 不可用", error);
      }
    }
  }
}
