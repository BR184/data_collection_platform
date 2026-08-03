package com.data.collection.platform.entity;

import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.Set;
import java.util.TreeSet;

/** 交给统计和记录投影刷新器的强类型发布上下文。 */
public record FactPublicationContext(
    String sourceInstance,
    FactType factType,
    FactPublicationMode mode,
    Set<FactProjectionScope> affectedScopes) {

  public FactPublicationContext {
    sourceInstance = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    if (factType == null || mode == null) {
      throw new IllegalArgumentException("事实发布上下文必须声明事实类型和模式");
    }
    affectedScopes = affectedScopes == null
        ? Set.of()
        : java.util.Collections.unmodifiableSet(new TreeSet<>(affectedScopes));
  }

  /** 判断当前发布是否覆盖指定消费者的稳定范围。 */
  public boolean covers(
      FactType consumerFactType, ProjectionScopeType scopeType, String scopeKey) {
    if (consumerFactType == null || factType != consumerFactType) {
      return false;
    }
    if (mode == FactPublicationMode.FULL) {
      return true;
    }
    return affectedScopes.contains(
        new FactProjectionScope(sourceInstance, factType, scopeType, scopeKey));
  }
}
