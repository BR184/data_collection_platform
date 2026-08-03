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
