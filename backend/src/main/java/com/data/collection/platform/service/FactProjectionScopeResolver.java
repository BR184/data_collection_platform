package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 从事实发布前后的持久事实解析稳定投影范围。 */
@Service
public class FactProjectionScopeResolver {
  private final JdbcTemplate jdbcTemplate;

  public FactProjectionScopeResolver(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 解析当前事实版本中的项目、全局消费者和议题范围组。
   *
   * @param sourceInstance GitLab 来源实例
   * @param factType 事实类型
   * @param rootIds GitLab 根对象 ID，有界且非空时才查询
   * @return 已规范化、去重且排序的范围集合
   */
  public Set<FactProjectionScope> resolveCurrentScopes(
      String sourceInstance, FactType factType, List<Long> rootIds) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    List<Long> targets = normalizeRootIds(rootIds);
    if (targets.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<FactProjectionScope> scopes = new LinkedHashSet<>();
    for (Long projectId : loadProjectIds(normalizedSource, factType, targets)) {
      scopes.add(
          new FactProjectionScope(
              normalizedSource,
              factType,
              ProjectionScopeType.PROJECT,
              FactProjectionScopeKeyCodec.project(projectId)));
    }
    if (!scopes.isEmpty()) {
      scopes.add(
          new FactProjectionScope(
              normalizedSource,
              factType,
              ProjectionScopeType.GLOBAL_VIEW,
              FactProjectionScopeKeyCodec.SINGLETON_SCOPE_KEY));
    }
    if (factType == FactType.ISSUE || factType == FactType.INTEGRATION_TEST) {
      scopes.addAll(loadIssueScopeGroups(normalizedSource, factType, targets));
    }
    return java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(scopes));
  }

  private List<Long> loadProjectIds(
      String sourceInstance, FactType factType, List<Long> rootIds) {
    FactTable table = FactTable.forType(factType);
    String placeholders = placeholders(rootIds.size());
    ArrayList<Object> args = new ArrayList<>(1 + rootIds.size());
    args.add(sourceInstance);
    args.addAll(rootIds);
    return jdbcTemplate.queryForList(
        "select distinct project_id from " + table.tableName()
            + " where source_instance = ? and " + table.rootColumn()
            + " in (" + placeholders + ") and project_id is not null order by project_id",
        Long.class,
        args.toArray());
  }

  private Set<FactProjectionScope> loadIssueScopeGroups(
      String sourceInstance, FactType factType, List<Long> rootIds) {
    FactTable table = FactTable.forType(factType);
    String placeholders = placeholders(rootIds.size());
    ArrayList<Object> args = new ArrayList<>(1 + rootIds.size());
    args.add(sourceInstance);
    args.addAll(rootIds);
    String scopeMatch = factType == FactType.INTEGRATION_TEST
        ? "catalog.dimension = 'TESTING_PHASE' and member.source_value = fact.testing_phase"
        : """
            (catalog.dimension = 'TESTING_PHASE' and member.source_value = fact.testing_phase)
            or (catalog.dimension = 'MILESTONE' and member.source_value = fact.milestone_title)
            """;
    String sql = """
        select distinct fact.project_id, catalog.dimension, scope_group.id as group_id
          from %s fact
          join issue_scope_catalogs catalog
            on catalog.project_id = fact.project_id
           and catalog.enabled = true
          join issue_scope_members member
            on member.catalog_id = catalog.id
           and member.enabled = true
           and (%s)
          join issue_scope_groups scope_group
            on scope_group.id = member.group_id
           and scope_group.catalog_id = catalog.id
           and scope_group.enabled = true
         where fact.source_instance = ?
           and fact.%s in (%s)
         order by fact.project_id, catalog.dimension, scope_group.id
        """.formatted(table.tableName(), scopeMatch, table.rootColumn(), placeholders);
    return Set.copyOf(
        jdbcTemplate.query(
            sql,
            (resultSet, rowNum) -> {
              long projectId = resultSet.getLong("project_id");
              IssueScopeDimension dimension =
                  IssueScopeDimension.valueOf(resultSet.getString("dimension"));
              long groupId = resultSet.getLong("group_id");
              return new FactProjectionScope(
                  sourceInstance,
                  factType,
                  ProjectionScopeType.ISSUE_SCOPE_GROUP,
                  FactProjectionScopeKeyCodec.issueScopeGroup(projectId, dimension, groupId));
            },
            args.toArray()));
  }

  private List<Long> normalizeRootIds(List<Long> rootIds) {
    if (rootIds == null) {
      return List.of();
    }
    return rootIds.stream()
        .filter(rootId -> rootId != null && rootId > 0L)
        .distinct()
        .sorted()
        .toList();
  }

  private String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }

  private record FactTable(String tableName, String rootColumn) {
    private static FactTable forType(FactType factType) {
      return switch (factType) {
        case ISSUE -> new FactTable("issue_fact", "issue_id");
        case MERGE_REQUEST -> new FactTable("merge_request_fact", "merge_request_id");
        case INTEGRATION_TEST -> new FactTable("integration_test_fact", "issue_id");
      };
    }
  }
}
