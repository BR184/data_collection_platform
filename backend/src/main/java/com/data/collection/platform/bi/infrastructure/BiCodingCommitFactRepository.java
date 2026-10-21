package com.data.collection.platform.bi.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 读取已随 MR 事实发布的 GitLab 提交明细及其版本。 */
public final class BiCodingCommitFactRepository {
  private static final String QUERY = """
      select commit.source_instance, commit.project_id, mr.project_name,
             commit.commit_sha, commit.committed_at_source
        from merge_request_commit_fact commit
        join merge_request_fact mr
          on mr.id = commit.merge_request_revision_id
         and mr.source_system = commit.source_system
         and mr.source_instance = commit.source_instance
         and mr.project_id = commit.project_id
         and mr.merge_request_id = commit.merge_request_id
       order by commit.committed_at_source asc, commit.source_instance asc,
                commit.project_id asc, commit.commit_sha asc
      """;

  private final JdbcTemplate jdbcTemplate;
  private final com.data.collection.platform.entity.read.FactReadSelection fixedRead;

  /** 提交关系必须与本页的 MR 父修订一致，不按业务根连接到所有历史修订。 */
  public BiCodingCommitFactRepository forRead(com.data.collection.platform.entity.read.FactReadSelection selection) {
    return new BiCodingCommitFactRepository(jdbcTemplate, selection);
  }

  private BiCodingCommitFactRepository(JdbcTemplate jdbcTemplate,
      com.data.collection.platform.entity.read.FactReadSelection selection) {
    this.jdbcTemplate = jdbcTemplate;
    this.fixedRead = java.util.Objects.requireNonNull(selection);
  }

  public BiCodingCommitFactRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.fixedRead = null;
  }

  /** 返回当前已发布提交关系；调用方按稳定提交身份去重。 */
  public List<CommitFactRow> load() {
    var bounded = com.data.collection.platform.service.read.VersionedFactSql.bind(
        java.util.Objects.requireNonNull(fixedRead, "提交读取必须绑定完整页面身份"), QUERY, List.of());
    return jdbcTemplate.query(bounded.sql(), this::mapRow, bounded.arguments().toArray());
  }

  /** 返回能检测同请求内提交事实替换的轻量版本。 */
  public String sourceVersion() {
    return jdbcTemplate.queryForObject(
        """
        select concat(
                 'merge-request-commits:',
                 count(*),
                 ':',
                 coalesce(to_char(max(fact_refreshed_at), 'YYYY-MM-DD"T"HH24:MI:SS.US'), 'empty')
               )
          from merge_request_commit_fact
        """,
        String.class);
  }

  private CommitFactRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
    LocalDate committedOn =
        resultSet.getTimestamp("committed_at_source") == null
            ? null
            : resultSet.getTimestamp("committed_at_source").toLocalDateTime().toLocalDate();
    return new CommitFactRow(
        resultSet.getString("source_instance"),
        BiJdbcValueReader.nullableLong(resultSet, "project_id"),
        resultSet.getString("project_name"),
        resultSet.getString("commit_sha"),
        committedOn);
  }

  /** 提交事实的筛选和稳定身份所需字段。 */
  public record CommitFactRow(
      String sourceInstance,
      Long projectId,
      String projectName,
      String commitSha,
      LocalDate committedOn) {}
}
