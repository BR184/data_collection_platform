package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 生成由稳定 generation 组成的确定性快照来源版本。 */
@Service
public class FactProjectionVersionService {
  private static final String VERSION_PREFIX = "fact-projection:v1";

  private final JdbcTemplate jdbcTemplate;

  public FactProjectionVersionService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 编码全量 epoch、请求消费的范围 generation 和范围组定义 generation。
   *
   * @param sourceInstance 来源实例
   * @param factType 事实类型
   * @param requestedScopes 请求实际消费的稳定范围
   * @return 与集合迭代顺序无关的规范版本串
   */
  public String sourceVersion(
      String sourceInstance,
      FactType factType,
      Set<FactProjectionScope> requestedScopes) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    java.util.TreeSet<FactProjectionScope> scopes =
        new java.util.TreeSet<>(requestedScopes == null ? Set.of() : requestedScopes);
    for (FactProjectionScope scope : scopes) {
      if (!scope.sourceInstance().equals(normalizedSource) || scope.factType() != factType) {
        throw new IllegalArgumentException("快照范围与来源或事实类型不一致：" + scope);
      }
    }
    List<String> components = new ArrayList<>();
    components.add(VERSION_PREFIX);
    components.add(
        "FULL_EPOCH:*=<" + generation(
            normalizedSource,
            factType,
            ProjectionScopeType.FULL_EPOCH,
            FactProjectionScopeKeyCodec.SINGLETON_SCOPE_KEY) + ">");
    for (FactProjectionScope scope : scopes) {
      long generation = generation(
          normalizedSource, factType, scope.scopeType(), scope.scopeKey());
      components.add(scope.scopeType().name() + ":" + scope.scopeKey() + "=<" + generation + ">");
      if (scope.scopeType() == ProjectionScopeType.ISSUE_SCOPE_GROUP) {
        long definitionGeneration = groupDefinitionGeneration(scope.scopeKey());
        components.add("GROUP_DEFINITION:" + scope.scopeKey() + "=<" + definitionGeneration + ">");
      }
    }
    return VERSION_PREFIX + ":" + sha256(String.join("|", components));
  }

  /**
   * 为一次可能读取多个来源实例的查询生成合并版本。
   *
   * <p>范围自带来源实例，按来源分组后逐个套用 {@link #sourceVersion}，再对排序后的分量取哈希。
   * 这样单源读取的版本精确锁定该源，多源读取的版本覆盖每一个实际读取的来源，
   * 不会用某一个来源（例如隐式默认源）的版本替代其余来源的变化。
   *
   * @param factType 事实类型
   * @param requestedScopes 实际读取的全部分量范围；不能为空
   * @return 与来源集合和范围集合迭代顺序无关的规范版本串
   */
  public String combinedSourceVersion(
      FactType factType, Set<FactProjectionScope> requestedScopes) {
    if (requestedScopes == null || requestedScopes.isEmpty()) {
      throw new IllegalArgumentException("快照来源版本必须声明实际读取的范围");
    }
    java.util.TreeMap<String, java.util.TreeSet<FactProjectionScope>> scopesBySource =
        new java.util.TreeMap<>();
    for (FactProjectionScope scope : requestedScopes) {
      if (scope.factType() != factType) {
        throw new IllegalArgumentException("快照范围与事实类型不一致：" + scope);
      }
      scopesBySource
          .computeIfAbsent(scope.sourceInstance(), key -> new java.util.TreeSet<>())
          .add(scope);
    }
    List<String> components = new ArrayList<>();
    for (Map.Entry<String, java.util.TreeSet<FactProjectionScope>> entry :
        scopesBySource.entrySet()) {
      components.add(
          "SOURCE:" + entry.getKey() + "=" + sourceVersion(entry.getKey(), factType, entry.getValue()));
    }
    return VERSION_PREFIX + ":" + sha256(String.join("|", components));
  }

  /**
   * 列出指定事实类型已经产生投影代际的来源实例。
   *
   * <p>统计查询在未选择具体来源时读取全部来源，因此版本必须覆盖每一个已存在代际的来源；
   * 新来源一旦发布事实就会出现新代际，使旧快照自然失效。
   *
   * @param factType 事实类型
   * @return 已规范化、排序的来源实例集合；缺失时返回默认来源
   */
  public Set<String> knownSourceInstances(FactType factType) {
    List<String> sources =
        jdbcTemplate.queryForList(
            "select distinct source_instance from fact_projection_generations where fact_type = ?",
            String.class,
            factType.name());
    java.util.TreeSet<String> normalized = new java.util.TreeSet<>();
    for (String source : sources) {
      normalized.add(GitlabSourceInstanceSupport.normalizeSourceInstance(source));
    }
    if (normalized.isEmpty()) {
      normalized.add(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    }
    return java.util.Collections.unmodifiableSet(normalized);
  }

  /** 为真正的全局消费者生成稳定来源版本。 */
  public String globalSourceVersion(String sourceInstance, FactType factType) {
    FactProjectionScope scope =
        new FactProjectionScope(
            sourceInstance,
            factType,
            ProjectionScopeType.GLOBAL_VIEW,
            FactProjectionScopeKeyCodec.SINGLETON_SCOPE_KEY);
    return sourceVersion(sourceInstance, factType, Set.of(scope));
  }

  private long generation(
      String sourceInstance,
      FactType factType,
      ProjectionScopeType scopeType,
      String scopeKey) {
    Long value =
        jdbcTemplate.queryForObject(
            """
            select coalesce(max(generation), 0)
              from fact_projection_generations
             where source_instance = ? and fact_type = ? and scope_type = ? and scope_key = ?
            """,
            Long.class,
            sourceInstance,
            factType.name(),
            scopeType.name(),
            scopeKey);
    return value == null ? 0L : value;
  }

  private long groupDefinitionGeneration(String scopeKey) {
    FactProjectionScopeKeyCodec.IssueScopeGroupKey key =
        FactProjectionScopeKeyCodec.parseIssueScopeGroup(scopeKey);
    Long value =
        jdbcTemplate.queryForObject(
            """
            select coalesce(max(scope_group.definition_generation), 0)
              from issue_scope_groups scope_group
              join issue_scope_catalogs catalog on catalog.id = scope_group.catalog_id
             where scope_group.id = ?
               and catalog.project_id = ?
               and catalog.dimension = ?
            """,
            Long.class,
            key.groupId(),
            key.projectId(),
            key.dimension().name());
    return value == null ? 0L : value;
  }

  private String sha256(String canonicalVersion) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(canonicalVersion.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }
}
