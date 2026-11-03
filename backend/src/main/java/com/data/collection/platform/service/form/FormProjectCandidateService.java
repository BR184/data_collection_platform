package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 表单管理项目候选的本地只读投影与分页搜索。
 *
 * <p>投影只从平台已同步的 ODS 项目/命名空间目录构建，不连接 GitLab、不触发同步。管理端 GET 只读投影；
 * 目录同步成功事件负责幂等刷新。候选身份由来源配置与项目 ID 共同确定，projectId 对外使用十进制字符串。
 */
@Service
public class FormProjectCandidateService {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_KEYWORD_CODE_POINTS = 128;
  private static final int MAX_ECHO_IDS = 100;

  private static final String PROJECT_SELECT =
      """
      select project_id, project_name, project_path, namespace_path, full_path
        from form_project_candidates
       where source_config_id = ?
      """;

  private final JdbcTemplate jdbcTemplate;

  public FormProjectCandidateService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 从本地 ODS 目录幂等重建指定来源的项目候选投影。
   *
   * <p>删除与重建在同一事务内完成，读取方不会看到半成品。ODS 项目表尚未建立时保持既有投影不变，
   * 由管理端返回“目录尚未同步”，不把空目录误当成同步完成。
   *
   * @param sourceConfigId 代码平台来源配置；不存在时跳过
   */
  @Transactional
  public void refresh(long sourceConfigId) {
    if (sourceConfigId <= 0 || !sourceExists(sourceConfigId)) {
      return;
    }
    if (!tableExists("ods_gitlab_projects")) {
      return;
    }
    boolean namespacesPresent = tableExists("ods_gitlab_namespaces");
    String namespaceJoin =
        namespacesPresent
            ? "left join ods_gitlab_namespaces n on n.id = p.namespace_id"
                + " and coalesce(n.mirror_deleted, false) = false"
            : "";
    String namespacePath = namespacesPresent ? "coalesce(nullif(btrim(n.path), ''), '')" : "''";
    String sql =
        """
        select p.id as project_id,
               coalesce(nullif(btrim(p.name), ''), nullif(btrim(p.path), ''), p.id::text) as project_name,
               coalesce(nullif(btrim(p.path), ''), '') as project_path,
               %s as namespace_path
          from ods_gitlab_projects p
          %s
         where coalesce(p.mirror_deleted, false) = false
        """
            .formatted(namespacePath, namespaceJoin);

    List<Object[]> rows = new ArrayList<>();
    jdbcTemplate.query(
        sql,
        rs -> {
          String name = rs.getString("project_name");
          String path = rs.getString("project_path");
          String namespace = rs.getString("namespace_path");
          String fullPath =
              namespace.isEmpty() ? path : path.isEmpty() ? namespace : namespace + "/" + path;
          TextQuerySupport.SearchIndex index =
              TextQuerySupport.buildSearchIndex(name + " " + fullPath);
          rows.add(
              new Object[] {
                sourceConfigId,
                rs.getLong("project_id"),
                name,
                path,
                namespace,
                fullPath,
                index.normalized(),
                index.compact(),
                index.spell(),
                index.initials()
              });
        });
    jdbcTemplate.update(
        "delete from form_project_candidates where source_config_id = ?", sourceConfigId);
    if (!rows.isEmpty()) {
      jdbcTemplate.batchUpdate(
          """
          insert into form_project_candidates(
              source_config_id, project_id, project_name, project_path, namespace_path, full_path,
              search_text, search_compact, search_spell, search_initials, refreshed_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """,
          rows);
    }
  }

  /**
   * 分页搜索项目候选；关键词由服务端在分页前过滤名称与完整路径。
   *
   * @param sourceConfigId 代码平台来源配置
   * @param keyword 名称、路径、拼音或首字母；空白表示不限制
   * @param size 每页数量，默认 20、最大 100
   * @param cursor 上一页最后一条 projectId；空表示第一页
   * @param projectIds 精确回显模式；非空时忽略关键词与游标，并返回目录中缺失的 ID
   * @return 候选分页
   */
  @Transactional(readOnly = true)
  public FormAdminViews.ProjectCandidatePage search(
      long sourceConfigId, String keyword, Integer size, String cursor, List<Long> projectIds) {
    requireSource(sourceConfigId);
    int pageSize = normalizeSize(size);
    String normalizedKeyword = normalizeKeyword(keyword);
    String normalizedCursor = normalizeCursor(cursor);
    List<Long> echoIds = normalizeEchoIds(projectIds);
    boolean available =
        Boolean.TRUE.equals(
            jdbcTemplate.queryForObject(
                """
                select exists(
                    select 1 from form_project_candidates where source_config_id = ?)
                """,
                Boolean.class,
                sourceConfigId));

    if (!echoIds.isEmpty()) {
      return echo(sourceConfigId, echoIds, available);
    }

    StringBuilder sql = new StringBuilder(PROJECT_SELECT);
    List<Object> args = new ArrayList<>();
    args.add(sourceConfigId);
    if (!normalizedKeyword.isEmpty()) {
      appendKeywordFilter(sql, args, normalizedKeyword);
    }
    if (!normalizedCursor.isEmpty()) {
      sql.append(" and project_id > ?");
      args.add(Long.valueOf(normalizedCursor));
    }
    sql.append(" order by project_id asc limit ?");
    args.add(pageSize + 1);

    List<FormAdminViews.ProjectOption> rows =
        jdbcTemplate.query(sql.toString(), this::mapOption, args.toArray());
    boolean hasMore = rows.size() > pageSize;
    List<FormAdminViews.ProjectOption> items =
        hasMore ? List.copyOf(rows.subList(0, pageSize)) : List.copyOf(rows);
    String nextCursor = hasMore ? items.get(items.size() - 1).projectId() : null;
    return new FormAdminViews.ProjectCandidatePage(available, items, nextCursor, List.of());
  }

  private FormAdminViews.ProjectCandidatePage echo(
      long sourceConfigId, List<Long> projectIds, boolean available) {
    String placeholders = String.join(", ", java.util.Collections.nCopies(projectIds.size(), "?"));
    List<Object> args = new ArrayList<>(projectIds.size() + 1);
    args.add(sourceConfigId);
    args.addAll(projectIds);
    List<FormAdminViews.ProjectOption> items =
        jdbcTemplate.query(
            PROJECT_SELECT
                + " and project_id in ("
                + placeholders
                + ") order by project_id asc",
            this::mapOption,
            args.toArray());
    Set<Long> found = new LinkedHashSet<>();
    for (FormAdminViews.ProjectOption item : items) {
      found.add(Long.valueOf(item.projectId()));
    }
    List<String> missing = new ArrayList<>();
    for (Long projectId : projectIds) {
      if (!found.contains(projectId)) {
        missing.add(Long.toString(projectId));
      }
    }
    return new FormAdminViews.ProjectCandidatePage(available, items, null, List.copyOf(missing));
  }

  private void appendKeywordFilter(StringBuilder sql, List<Object> args, String keyword) {
    TextQuerySupport.SearchIndex needle = TextQuerySupport.buildSearchIndex(keyword);
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    addCandidate(candidates, needle.normalized());
    addCandidate(candidates, needle.compact());
    addCandidate(candidates, needle.spell());

    List<String> clauses = new ArrayList<>();
    for (String candidate : candidates) {
      clauses.add(
          "(search_text like ? escape '\\' or search_compact like ? escape '\\'"
              + " or search_spell like ? escape '\\' or search_initials like ? escape '\\')");
      String pattern = likePattern(candidate);
      for (int index = 0; index < 4; index++) {
        args.add(pattern);
      }
    }
    if (!clauses.isEmpty()) {
      sql.append(" and (").append(String.join(" or ", clauses)).append(")");
    }
  }

  private void addCandidate(Set<String> candidates, String value) {
    if (StringUtils.hasText(value)) {
      candidates.add(value.toLowerCase(Locale.ROOT));
    }
  }

  private FormAdminViews.ProjectOption mapOption(
      java.sql.ResultSet resultSet, int rowNum) throws java.sql.SQLException {
    return new FormAdminViews.ProjectOption(
        Long.toString(resultSet.getLong("project_id")),
        resultSet.getString("project_name"),
        resultSet.getString("project_path"),
        resultSet.getString("namespace_path"),
        resultSet.getString("full_path"));
  }

  private void requireSource(long sourceConfigId) {
    if (sourceConfigId <= 0) {
      throw new BizException(ResultCode.BAD_REQUEST, "来源配置必须为正整数");
    }
    if (!sourceExists(sourceConfigId)) {
      throw new BizException(ResultCode.BAD_REQUEST, "来源配置不存在");
    }
  }

  private boolean sourceExists(long sourceConfigId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from gitlab_sync_configs where id = ?", Integer.class, sourceConfigId);
    return count != null && count > 0;
  }

  private boolean tableExists(String table) {
    return jdbcTemplate.queryForObject("select to_regclass(?)", String.class, table) != null;
  }

  private int normalizeSize(Integer size) {
    if (size == null) {
      return DEFAULT_PAGE_SIZE;
    }
    if (size <= 0 || size > MAX_PAGE_SIZE) {
      throw new BizException(ResultCode.BAD_REQUEST, "分页数量必须在1到100之间");
    }
    return size;
  }

  private String normalizeKeyword(String keyword) {
    String normalized = keyword == null ? "" : keyword.trim();
    if (normalized.codePointCount(0, normalized.length()) > MAX_KEYWORD_CODE_POINTS) {
      throw new BizException(ResultCode.BAD_REQUEST, "搜索关键词不能超过128个字符");
    }
    return normalized;
  }

  private String normalizeCursor(String cursor) {
    String normalized = cursor == null ? "" : cursor.trim();
    if (normalized.isEmpty()) {
      return "";
    }
    if (normalized.length() > 20 || !normalized.chars().allMatch(Character::isDigit)) {
      throw new BizException(ResultCode.BAD_REQUEST, "分页游标无效");
    }
    return normalized;
  }

  private List<Long> normalizeEchoIds(List<Long> projectIds) {
    if (projectIds == null || projectIds.isEmpty()) {
      return List.of();
    }
    if (projectIds.size() > MAX_ECHO_IDS) {
      throw new BizException(ResultCode.BAD_REQUEST, "回显项目不能超过100个");
    }
    Set<Long> distinctIds = new LinkedHashSet<>();
    for (Long projectId : projectIds) {
      if (projectId == null || projectId <= 0) {
        throw new BizException(ResultCode.BAD_REQUEST, "项目 ID 必须为正整数");
      }
      distinctIds.add(projectId);
    }
    return List.copyOf(distinctIds);
  }

  private String likePattern(String keyword) {
    String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
