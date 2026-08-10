package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.model.BiProductVersionOption;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeDimension;
import java.util.List;

/** 将平台议题测试阶段目录适配为 BI 的稳定产品版本端口。 */
public final class BiPlatformProductVersionAdapter implements BiProductVersionPort {
  private final IssueScopeCatalogService catalogService;

  public BiPlatformProductVersionAdapter(IssueScopeCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @Override
  public BiProductVersionCatalog catalog() {
    List<IssueScopeCatalogService.ScopeGroup> groups = groups();
    Long defaultId = groups.isEmpty() ? null : groups.getFirst().id();
    List<BiProductVersionOption> versions = groups.stream()
        .map(group -> new BiProductVersionOption(
            group.id(), group.businessKey(), group.displayName(), group.sortOrder()))
        .toList();
    return new BiProductVersionCatalog(defaultId, versions);
  }

  @Override
  public BiProductVersionScope requireScope(long productVersionId) {
    if (productVersionId <= 0) {
      throw new BizException("产品版本 ID 必须是正整数");
    }
    IssueScopeCatalogService.ScopeGroup group = groups().stream()
        .filter(candidate -> candidate.id() == productVersionId)
        .findFirst()
        .orElseThrow(() -> new BizException("BI 产品版本不存在或已停用：" + productVersionId));
    List<BiProductVersionScope.RoundScope> rounds = group.members().stream()
        .map(member -> new BiProductVersionScope.RoundScope(
            Long.toString(member.id()), member.sourceValue(), member.displayName(), member.sortOrder()))
        .toList();
    return new BiProductVersionScope(
        group.id(),
        group.projectId(),
        group.businessKey(),
        group.displayName(),
        group.sortOrder(),
        rounds);
  }

  private List<IssueScopeCatalogService.ScopeGroup> groups() {
    return catalogService.listEnabledGroups(
        IssueScopeCatalogService.CROWN_CAD_PROJECT_ID,
        IssueScopeDimension.TESTING_PHASE);
  }
}
