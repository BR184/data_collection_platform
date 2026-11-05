package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.model.BiProductVersionOption;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeDimension;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * CAT 设置页使用的原始议题测试阶段目录适配器。
 *
 * <p>CAT 映射必须保持 R1、R2 等原目录业务键和顺序，不消费两个看板的版本合并分组。
 */
public final class BiCatProductVersionAdapter implements BiProductVersionPort {
  private final IssueScopeCatalogService catalogService;

  public BiCatProductVersionAdapter(IssueScopeCatalogService catalogService) {
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
        .orElseThrow(() -> new BizException("CAT 产品版本不存在或已停用：" + productVersionId));
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
        rounds,
        List.of(group.businessKey()),
        fingerprint(group, rounds));
  }

  private List<IssueScopeCatalogService.ScopeGroup> groups() {
    return catalogService.listEnabledGroups(
        IssueScopeCatalogService.CROWN_CAD_PROJECT_ID,
        IssueScopeDimension.TESTING_PHASE);
  }

  private String fingerprint(
      IssueScopeCatalogService.ScopeGroup group,
      List<BiProductVersionScope.RoundScope> rounds) {
    StringBuilder source = new StringBuilder()
        .append(group.projectId()).append('|')
        .append(group.id()).append('|')
        .append(group.businessKey()).append('|')
        .append(group.displayName());
    rounds.forEach(round -> source.append('|').append(round.id()).append(':')
        .append(round.sourceValue()).append(':').append(round.sortOrder()));
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(source.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", error);
    }
  }
}
