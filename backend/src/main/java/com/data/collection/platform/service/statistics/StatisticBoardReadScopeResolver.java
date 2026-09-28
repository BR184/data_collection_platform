package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.service.FactProjectionVersionService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.IssueProjectionScopeResolver;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 解析统计读取实际覆盖的 Issue 稳定范围。
 *
 * <p>方法签名中的“稳定范围”是版本、发布资格和缓存键的共同前提：显式选择来源时只声明该来源，
 * 未选择来源时查询会读取全部来源，因此范围覆盖每一个已存在代际的来源，不以默认来源替代其余来源。
 */
@Component
public class StatisticBoardReadScopeResolver {
  public static final String SOURCE_INSTANCE_PARAM = "sourceInstance";

  private final FactProjectionVersionService projectionVersionService;
  private final IssueProjectionScopeResolver scopeResolver;

  public StatisticBoardReadScopeResolver(
      FactProjectionVersionService projectionVersionService,
      IssueProjectionScopeResolver scopeResolver) {
    this.projectionVersionService = projectionVersionService;
    this.scopeResolver = scopeResolver;
  }

  /**
   * 解析本次读取实际覆盖的范围。
   *
   * @param filters 本次请求的真实筛选（或等价快照载荷），来源选择取自其中的
   *     {@value #SOURCE_INSTANCE_PARAM}
   * @param projectId GitLab 项目 ID
   * @param dimension 可选范围维度
   * @param scopeBusinessKey 可选范围业务键
   * @return 每个实际读取来源对应的稳定范围；非空
   */
  public Set<FactProjectionScope> resolve(
      Map<String, String> filters,
      long projectId,
      IssueScopeDimension dimension,
      String scopeBusinessKey) {
    Set<String> sourceInstances = new TreeSet<>();
    String selectedSource =
        TextQuerySupport.trimToNull(filters == null ? null : filters.get(SOURCE_INSTANCE_PARAM));
    if (selectedSource == null) {
      sourceInstances.addAll(projectionVersionService.knownSourceInstances(FactType.ISSUE));
    } else {
      sourceInstances.add(GitlabSourceInstanceSupport.normalizeSourceInstance(selectedSource));
    }
    Set<FactProjectionScope> scopes = new LinkedHashSet<>();
    for (String sourceInstance : sourceInstances) {
      scopes.addAll(
          scopeResolver.resolve(
              sourceInstance, FactType.ISSUE, projectId, dimension, scopeBusinessKey));
    }
    if (scopes.isEmpty()) {
      throw new IllegalStateException("统计读取范围解析为空，无法构造快照来源版本");
    }
    return Set.copyOf(scopes);
  }
}
