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
          on mr.source_system = commit.source_system
         and mr.source_instance = commit.source_instance
         and mr.project_id = commit.project_id
         and mr.merge_request_id = commit.merge_request_id
       order by commit.committed_at_source asc, commit.source_instance asc,
                commit.project_id asc, commit.commit_sha asc
      """;

  private final JdbcTemplate jdbcTemplate;

  public BiCodingCommitFactRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 返回当前已发布提交关系；调用方按稳定提交身份去重。 */
  public List<CommitFactRow> load() {
    return jdbcTemplate.query(QUERY, this::mapRow);
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
