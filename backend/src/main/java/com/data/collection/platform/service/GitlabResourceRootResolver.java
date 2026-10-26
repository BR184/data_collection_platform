package com.data.collection.platform.service;

import com.data.collection.platform.service.read.FactReadVisibility;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 以项目内 IID 解析 GitLab 全局根 ID 的唯一入口。
 *
 * <p>先读 ODS 镜像，再回退到当前活动事实修订；同一数字项目/IID 在不同来源或资源类型下互不串用。
 * 返回 {@code null} 表示资源尚未镜像，调用方必须据此拒绝写入而不是猜造身份。
 */
public final class GitlabResourceRootResolver {

  public static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";

  private GitlabResourceRootResolver() {}

  public static Long findIssueRootId(
      JdbcTemplate jdbcTemplate, String sourceInstance, long projectId, long issueIid) {
    List<Long> roots =
        jdbcTemplate.query(
            """
            select root_id
              from (
                select id as root_id, 0 as source_order
                  from ods_gitlab_issues
                 where project_id = ? and iid = ?
                union all
                select issue_id as root_id, 1 as source_order
                  from issue_fact f
                 where f.source_system = ? and f.source_instance = ?
                   and f.project_id = ? and f.issue_iid = ?
                   and """
                + FactReadVisibility.liveRevisionPredicate("f")
                + """
              ) candidates
             order by source_order
             limit 1
            """,
            (resultSet, rowNum) -> resultSet.getLong("root_id"),
            projectId,
            issueIid,
            DEFAULT_SOURCE_SYSTEM,
            sourceInstance,
            projectId,
            issueIid);
    return roots.isEmpty() ? null : roots.getFirst();
  }

  public static Long findMergeRequestRootId(
      JdbcTemplate jdbcTemplate, String sourceInstance, long projectId, long mergeRequestIid) {
    List<Long> roots =
        jdbcTemplate.query(
            """
            select root_id
              from (
                select id as root_id, 0 as source_order
                  from ods_gitlab_merge_requests
                 where target_project_id = ? and iid = ?
                union all
                select merge_request_id as root_id, 1 as source_order
                  from merge_request_fact f
                 where f.source_system = ? and f.source_instance = ?
                   and f.project_id = ? and f.merge_request_iid = ?
                   and """
                + FactReadVisibility.liveRevisionPredicate("f")
                + """
              ) candidates
             order by source_order
             limit 1
            """,
            (resultSet, rowNum) -> resultSet.getLong("root_id"),
            projectId,
            mergeRequestIid,
            DEFAULT_SOURCE_SYSTEM,
            sourceInstance,
            projectId,
            mergeRequestIid);
    return roots.isEmpty() ? null : roots.getFirst();
  }
}
