package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.TextQuerySupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 表单管理项目候选的本地只读投影与分页搜索。
 *
 * <p>投影按来源配置的真实身份选择目录：DGM 来源只读 {@code code_review_dgm_project_options}，
 * GitLab 镜像来源只读由该配置实际写入的 ODS 项目行（经镜像任务归属）。两个来源不互相贴标签；
 * 无法确定身份或目录层级不完整时明确不可用。管理端 GET 只读投影；目录同步成功事件负责幂等刷新。
 */
@Slf4j
@Service
public class FormProjectCandidateService {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_KEYWORD_CODE_POINTS = 128;
  private static final int MAX_ECHO_IDS = 100;
  private static final int MAX_NAMESPACE_DEPTH = 32;
  private static final int MAX_MESSAGE_LENGTH = 500;
  private static final String DGM_SOURCE_INSTANCE = "dgm";
  private static final String ODS_SOURCE_INSTANCE = "default";

  static final String STATUS_UNSYNCED = "UNSYNCED";
  static final String STATUS_READY = "READY";
  static final String STATUS_EMPTY = "EMPTY";
  static final String STATUS_FAILED = "FAILED";
  static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

  private static final String PROJECT_SELECT =
      """
      select project_id, project_name, project_path, namespace_path, full_path
        from form_project_candidates
       where source_config_id = ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  public FormProjectCandidateService(
      JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  /**
   * 按来源配置的真实身份幂等重建项目候选投影。
   *
   * <p>DGM 来源只消费已同步的 DGM 项目目录；GitLab 镜像来源只消费归属于该配置的 ODS 行。
   * 成功为空写入 {@code EMPTY} 状态并清空旧行；刷新失败保留最后一次成功快照并把状态置为
   * {@code FAILED}；目录表缺失或来源身份不支持时明确不可用，不把全量 ODS 贴到任意配置。
   *
   * @param sourceConfigId 代码平台来源配置；不存在时跳过
   */
  public void refresh(long sourceConfigId) {
    SourceIdentity source = loadSource(sourceConfigId);
    if (source == null) {
      return;
    }
    if (!ODS_SOURCE_INSTANCE.equals(source.instance())
        && !DGM_SOURCE_INSTANCE.equals(source.instance())) {
      markUnavailable(
          sourceConfigId, "来源身份不受支持：" + source.instance());
      return;
    }
    List<Object[]> rows;
    try {
      rows =
          DGM_SOURCE_INSTANCE.equals(source.instance())
              ? loadDgmRows(sourceConfigId)
              : loadOdsRows(sourceConfigId);
    } catch (RuntimeException error) {
      markFailed(sourceConfigId, error);
      log.warn("表单项目候选目录读取失败：configId={}", sourceConfigId, error);
      return;
    }
    if (rows == null) {
      markUnsynced(sourceConfigId, "本地项目目录尚未同步");
      return;
    }
    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            jdbcTemplate.update(
                "delete from form_project_candidates where source_config_id = ?", sourceConfigId);
            if (!rows.isEmpty()) {
              jdbcTemplate.batchUpdate(
                  """
                  insert into form_project_candidates(
                      source_config_id, project_id, project_name, project_path, namespace_path,
                      full_path, search_text, search_compact, search_spell, search_initials,
                      refreshed_at)
                  values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                  """,
                  rows);
            }
            upsertSuccess(
                sourceConfigId, rows.isEmpty() ? STATUS_EMPTY : STATUS_READY);
          });
    } catch (RuntimeException error) {
      markFailed(sourceConfigId, error);
      log.warn("表单项目候选投影写入失败：configId={}", sourceConfigId, error);
    }
  }

  /** 服务模式启动时对已确认来源的既有本地目录做一次幂等初始化。 */
  public void initializeExistingSources() {
    List<Long> configIds =
        jdbcTemplate.queryForList(
            "select id from gitlab_sync_configs"
                + " where coalesce(source_enabled, enabled) = true order by id",
            Long.class);
    for (Long configId : configIds) {
      try {
        refresh(configId);
      } catch (RuntimeException error) {
        log.warn("表单项目候选启动初始化失败：configId={}", configId, error);
      }
    }
  }

  /** DGM 目录同步成功后刷新所有 DGM 来源配置。 */
  public void refreshDgmSources() {
    for (Long configId : dgmConfigIds()) {
      refresh(configId);
    }
  }

  /** DGM 目录同步失败时保留最后成功快照并报告失败。 */
  public void markDgmSourcesFailed(String message) {
    for (Long configId : dgmConfigIds()) {
      upsertStatus(configId, STATUS_FAILED, message);
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
    ProjectionState projection = projectionState(sourceConfigId);

    if (!echoIds.isEmpty()) {
      return echo(sourceConfigId, echoIds, projection);
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
    return new FormAdminViews.ProjectCandidatePage(
        projection.available(),
        projection.status(),
        projection.message(),
        items,
        nextCursor,
        List.of());
  }

  private FormAdminViews.ProjectCandidatePage echo(
      long sourceConfigId, List<Long> projectIds, ProjectionState projection) {
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
    return new FormAdminViews.ProjectCandidatePage(
        projection.available(),
        projection.status(),
        projection.message(),
        items,
        null,
        List.copyOf(missing));
  }

  private List<Object[]> loadOdsRows(long sourceConfigId) {
    if (!tableExists("ods_gitlab_projects")
        || !tableExists("ods_gitlab_namespaces")
        || !tableExists("sync_run_table_tasks")) {
      return null;
    }
    requireColumn("ods_gitlab_projects", "mirror_task_id");
    requireColumn("ods_gitlab_namespaces", "mirror_task_id");
    requireColumn("ods_gitlab_namespaces", "parent_id");

    Map<Long, NamespaceRow> namespaces = new LinkedHashMap<>();
    jdbcTemplate.query(
        """
        select n.id, btrim(n.path) as path, n.parent_id
          from ods_gitlab_namespaces n
          join sync_run_table_tasks t on t.id = n.mirror_task_id
         where t.config_id = ? and t.mirror_table = 'ods_gitlab_namespaces'
           and coalesce(n.mirror_deleted, false) = false
        """,
        rs -> {
          namespaces.put(
              rs.getLong("id"),
              new NamespaceRow(rs.getString("path"), rs.getObject("parent_id", Long.class)));
        },
        sourceConfigId);

    List<Object[]> rows = new ArrayList<>();
    jdbcTemplate.query(
        """
        select p.id as project_id,
               coalesce(nullif(btrim(p.name), ''), nullif(btrim(p.path), ''), p.id::text)
                   as project_name,
               coalesce(nullif(btrim(p.path), ''), '') as project_path,
               p.namespace_id
          from ods_gitlab_projects p
          join sync_run_table_tasks t on t.id = p.mirror_task_id
         where t.config_id = ? and t.mirror_table = 'ods_gitlab_projects'
           and coalesce(p.mirror_deleted, false) = false
        """,
        rs -> {
          String path = rs.getString("project_path");
          String namespace = namespacePath(rs.getObject("namespace_id", Long.class), namespaces);
          if (namespace == null) {
            return;
          }
          String fullPath = namespace.isEmpty() ? path : path.isEmpty() ? namespace : namespace + "/" + path;
          if (fullPath.isEmpty()) {
            return;
          }
          rows.add(
              new RowBuilder(
                      rs.getLong("project_id"),
                      rs.getString("project_name"),
                      path,
                      namespace,
                      fullPath)
                  .toRow(sourceConfigId));
        },
        sourceConfigId);
    return rows;
  }

  private List<Object[]> loadDgmRows(long sourceConfigId) {
    if (!tableExists("code_review_dgm_project_options")) {
      return null;
    }
    List<Object[]> rows = new ArrayList<>();
    jdbcTemplate.query(
        """
        select gitlab_project_id, name, path, path_with_namespace, namespace_full_path
          from code_review_dgm_project_options
         where source_instance = ? and active = true
        """,
        rs -> {
          long projectId = rs.getLong("gitlab_project_id");
          String path = nullToBlank(rs.getString("path"));
          String namespace = nullToBlank(rs.getString("namespace_full_path"));
          String fullPath = nullToBlank(rs.getString("path_with_namespace"));
          if (fullPath.isEmpty() && !namespace.isEmpty() && !path.isEmpty()) {
            fullPath = namespace + "/" + path;
          }
          if (fullPath.isEmpty()) {
            return;
          }
          if (namespace.isEmpty()) {
            namespace = parentPath(fullPath);
          }
          String name = nullToBlank(rs.getString("name"));
          if (name.isEmpty()) {
            name = path.isEmpty() ? String.valueOf(projectId) : path;
          }
          rows.add(
              new RowBuilder(projectId, name, path.isEmpty() ? leafPath(fullPath) : path, namespace, fullPath)
                  .toRow(sourceConfigId));
        },
        DGM_SOURCE_INSTANCE);
    return rows;
  }

  private String namespacePath(Long namespaceId, Map<Long, NamespaceRow> namespaces) {
    if (namespaceId == null) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    Set<Long> visited = new LinkedHashSet<>();
    Long current = namespaceId;
    int depth = 0;
    while (current != null) {
      if (depth++ >= MAX_NAMESPACE_DEPTH || !visited.add(current)) {
        return null;
      }
      NamespaceRow row = namespaces.get(current);
      if (row == null || !StringUtils.hasText(row.path())) {
        return null;
      }
      parts.add(0, row.path());
      current = row.parentId();
    }
    return String.join("/", parts);
  }

  private ProjectionState projectionState(long sourceConfigId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select status, message, last_success_at
              from form_project_candidate_states
             where source_config_id = ?
            """,
            sourceConfigId);
    if (rows.isEmpty()) {
      return new ProjectionState(STATUS_UNSYNCED, null, false);
    }
    Map<String, Object> row = rows.getFirst();
    String status = String.valueOf(row.get("status"));
    boolean available =
        STATUS_READY.equals(status)
            || STATUS_EMPTY.equals(status)
            || (STATUS_FAILED.equals(status) && row.get("last_success_at") != null);
    return new ProjectionState(status, (String) row.get("message"), available);
  }

  private void upsertSuccess(long sourceConfigId, String status) {
    LocalDateTime now = LocalDateTime.now();
    jdbcTemplate.update(
        """
        insert into form_project_candidate_states(
            source_config_id, status, message, last_success_at, last_attempt_at, updated_at)
        values (?, ?, null, ?, ?, ?)
        on conflict (source_config_id) do update
           set status = excluded.status,
               message = null,
               last_success_at = excluded.last_success_at,
               last_attempt_at = excluded.last_attempt_at,
               updated_at = excluded.updated_at
        """,
        sourceConfigId,
        status,
        now,
        now,
        now);
  }

  private void markUnsynced(long sourceConfigId, String message) {
    upsertStatus(sourceConfigId, STATUS_UNSYNCED, message);
  }

  private void markUnavailable(long sourceConfigId, String message) {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update(
              "delete from form_project_candidates where source_config_id = ?", sourceConfigId);
          upsertStatus(sourceConfigId, STATUS_UNAVAILABLE, message);
        });
  }

  private void markFailed(long sourceConfigId, RuntimeException error) {
    String message = error.getMessage();
    if (!StringUtils.hasText(message)) {
      message = error.getClass().getSimpleName();
    }
    upsertStatus(sourceConfigId, STATUS_FAILED, message);
  }

  private void upsertStatus(long sourceConfigId, String status, String message) {
    LocalDateTime now = LocalDateTime.now();
    String normalizedMessage =
        message != null && message.length() > MAX_MESSAGE_LENGTH
            ? message.substring(0, MAX_MESSAGE_LENGTH)
            : message;
    jdbcTemplate.update(
        """
        insert into form_project_candidate_states(
            source_config_id, status, message, last_success_at, last_attempt_at, updated_at)
        values (?, ?, ?, null, ?, ?)
        on conflict (source_config_id) do update
           set status = excluded.status,
               message = excluded.message,
               last_attempt_at = excluded.last_attempt_at,
               updated_at = excluded.updated_at
        """,
        sourceConfigId,
        status,
        normalizedMessage,
        now,
        now);
  }

  private List<Long> dgmConfigIds() {
    return jdbcTemplate.queryForList(
        "select id from gitlab_sync_configs"
            + " where lower(btrim(coalesce(source_instance, ''))) = ? order by id",
        Long.class,
        DGM_SOURCE_INSTANCE);
  }

  private SourceIdentity loadSource(long sourceConfigId) {
    List<String> rows =
        jdbcTemplate.queryForList(
            "select source_instance from gitlab_sync_configs where id = ?",
            String.class,
            sourceConfigId);
    if (rows.isEmpty()) {
      return null;
    }
    return new SourceIdentity(GitlabSourceInstanceSupport.normalizeSourceInstance(rows.getFirst()));
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

  private void requireColumn(String table, String column) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            select exists(
                select 1 from information_schema.columns
                 where table_schema = current_schema() and table_name = ? and column_name = ?)
            """,
            Boolean.class,
            table,
            column);
    if (!Boolean.TRUE.equals(exists)) {
      throw new IllegalStateException("项目目录缺少必需字段：" + table + "." + column);
    }
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

  private String nullToBlank(String value) {
    return value == null ? "" : value.trim();
  }

  private String parentPath(String fullPath) {
    int index = fullPath.lastIndexOf('/');
    return index <= 0 ? "" : fullPath.substring(0, index);
  }

  private String leafPath(String fullPath) {
    int index = fullPath.lastIndexOf('/');
    return index < 0 ? fullPath : fullPath.substring(index + 1);
  }

  private record SourceIdentity(String instance) {}

  private record NamespaceRow(String path, Long parentId) {}

  private record ProjectionState(String status, String message, boolean available) {}

  private static final class RowBuilder {
    private final long projectId;
    private final String name;
    private final String path;
    private final String namespace;
    private final String fullPath;

    private RowBuilder(long projectId, String name, String path, String namespace, String fullPath) {
      this.projectId = projectId;
      this.name = name;
      this.path = path;
      this.namespace = namespace;
      this.fullPath = fullPath;
    }

    private Object[] toRow(long sourceConfigId) {
      TextQuerySupport.SearchIndex index = TextQuerySupport.buildSearchIndex(name + " " + fullPath);
      return new Object[] {
        sourceConfigId,
        projectId,
        name,
        path,
        namespace,
        fullPath,
        index.normalized(),
        index.compact(),
        index.spell(),
        index.initials()
      };
    }
  }
}
