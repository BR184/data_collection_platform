package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 把页面稳定选择解析为 Issue 投影范围；显示名不参与身份。 */
@Service
public class IssueProjectionScopeResolver {
  private final IssueScopeCatalogService scopeCatalogService;

  public IssueProjectionScopeResolver(IssueScopeCatalogService scopeCatalogService) {
    this.scopeCatalogService = scopeCatalogService;
  }

  /**
   * 范围组选择只消费对应 group；没有有效 group 时消费项目范围。
   *
   * @param sourceInstance 来源实例
   * @param factType ISSUE 或 INTEGRATION_TEST
   * @param projectId GitLab 项目 ID
   * @param dimension 可选范围维度
   * @param businessKey 可选稳定业务键
   */
  public Set<FactProjectionScope> resolve(
      String sourceInstance,
      FactType factType,
      long projectId,
      IssueScopeDimension dimension,
      String businessKey) {
    if (factType != FactType.ISSUE && factType != FactType.INTEGRATION_TEST) {
      throw new IllegalArgumentException("议题投影范围只支持 Issue 类事实");
    }
    if (dimension != null && TextQuerySupport.trimToNull(businessKey) != null) {
      java.util.Optional<IssueScopeCatalogService.ScopeGroup> group =
          scopeCatalogService.findEnabledGroup(projectId, dimension, businessKey);
      if (group.isEmpty()) {
        String normalizedValue = TextQuerySupport.trimToNull(businessKey);
        group = scopeCatalogService.listEnabledGroups(projectId, dimension).stream()
            .filter(candidate -> candidate.members().stream()
                .map(IssueScopeCatalogService.ScopeMember::sourceValue)
                .map(TextQuerySupport::trimToNull)
                .anyMatch(value -> value != null && value.equalsIgnoreCase(normalizedValue)))
            .findFirst();
      }
      if (group.isPresent()) {
        return Set.of(
            new FactProjectionScope(
                sourceInstance,
                factType,
                ProjectionScopeType.ISSUE_SCOPE_GROUP,
                FactProjectionScopeKeyCodec.issueScopeGroup(
                    projectId, dimension, group.get().id())));
      }
    }
    return Set.of(
        new FactProjectionScope(
            sourceInstance,
            factType,
            ProjectionScopeType.PROJECT,
            FactProjectionScopeKeyCodec.project(projectId)));
  }
}
