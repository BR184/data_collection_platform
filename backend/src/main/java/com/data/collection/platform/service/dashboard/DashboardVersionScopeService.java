package com.data.collection.platform.service.dashboard;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.TextQuerySupport;
import com.data.collection.platform.service.dashboard.DashboardVersionScope.PhaseMember;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 两个看板共用的版本范围解析入口。
 *
 * <p>分组只在看板查询范围内把原测试阶段成员合并为单一入口，原议题范围目录、
 * 独立系统测试模块和客户问题模块都继续使用各自的原始业务键。
 */
@Service
public class DashboardVersionScopeService {
  private final IssueScopeCatalogService catalogService;
  private final DashboardVersionScopeConfigLoader configLoader;

  public DashboardVersionScopeService(
      IssueScopeCatalogService catalogService,
      DashboardVersionScopeConfigLoader configLoader) {
    this.catalogService = catalogService;
    this.configLoader = configLoader;
  }

  /**
   * 绑定页面已发布目录，保证组合范围与页面其余数据使用同一读取版本。
   *
   * @param selection 页面已固定的读取身份
   * @return 仅持有该读取身份的新实例，配置定义仍共用只读加载器
   */
  public DashboardVersionScopeService forRead(
      com.data.collection.platform.entity.read.ReadSelection selection) {
    return new DashboardVersionScopeService(
        catalogService.forRead(java.util.Objects.requireNonNull(selection)), configLoader);
  }

  /** 返回看板候选；分组项替代原成员，位置取成员中最靠前的一项。 */
  public List<VersionOption> listOptions(long projectId, IssueScopeDimension dimension) {
    List<IssueScopeCatalogService.ScopeGroup> groups = groups(projectId, dimension);
    return optionsFor(groups, activeScopes(groups, projectId, dimension));
  }

  /**
   * 按看板请求值解析版本范围。
   *
   * @param requestedValue 分组稳定 key 或未参与分组的原业务键；为空时使用默认候选
   * @throws BizException 值不存在、已停用或已被分组替代
   */
  public DashboardVersionScope resolve(
      long projectId, IssueScopeDimension dimension, String requestedValue) {
    String normalized = TextQuerySupport.trimToNull(requestedValue);
    Optional<DashboardVersionScope> selection =
        findSelection(projectId, dimension, normalized);
    if (selection.isPresent()) {
      return selection.get();
    }
    if (normalized != null) {
      throw new BizException("看板版本不存在或已停用：" + normalized);
    }
    List<IssueScopeCatalogService.ScopeGroup> groups = groups(projectId, dimension);
    return resolveDefault(
        projectId, dimension, groups, activeScopes(groups, projectId, dimension));
  }

  /**
   * 查找已登记候选；未匹配时返回空，允许调用方保留非版本业务键的既有精确查询语义。
   *
   * @throws BizException 值已被看板分组替代
   */
  public Optional<DashboardVersionScope> findSelection(
      long projectId, IssueScopeDimension dimension, String requestedValue) {
    String normalized = TextQuerySupport.trimToNull(requestedValue);
    if (normalized == null) {
      return Optional.empty();
    }
    List<IssueScopeCatalogService.ScopeGroup> groups = groups(projectId, dimension);
    List<ActiveScope> activeScopes = activeScopes(groups, projectId, dimension);
    Optional<ActiveScope> scope = activeScopes.stream()
        .filter(candidate -> candidate.definition().key().equalsIgnoreCase(normalized))
        .findFirst();
    if (scope.isPresent()) {
      return Optional.of(toScope(scope.get(), projectId, dimension));
    }
    Optional<IssueScopeCatalogService.ScopeGroup> group = groups.stream()
        .filter(candidate -> candidate.businessKey().equalsIgnoreCase(normalized))
        .findFirst();
    if (group.isEmpty()) {
      return Optional.empty();
    }
    requireNotCombinedMember(group.get(), activeScopes);
    return Optional.of(toSingle(group.get(), projectId, dimension));
  }

  /**
   * 按稳定原目录组 ID 解析版本范围，供 BI 正数 productVersionId 使用。
   *
   * @param groupId 分组入口为锚点成员的原目录 ID，普通版本为自身组 ID
   */
  public DashboardVersionScope resolveByGroupId(
      long projectId, IssueScopeDimension dimension, long groupId) {
    if (groupId <= 0) {
      throw new BizException("产品版本 ID 必须是正整数");
    }
    List<IssueScopeCatalogService.ScopeGroup> groups = groups(projectId, dimension);
    List<ActiveScope> activeScopes = activeScopes(groups, projectId, dimension);
    Optional<ActiveScope> scope = activeScopes.stream()
        .filter(candidate -> candidate.anchor().id() == groupId)
        .findFirst();
    if (scope.isPresent()) {
      return toScope(scope.get(), projectId, dimension);
    }
    IssueScopeCatalogService.ScopeGroup group = groups.stream()
        .filter(candidate -> candidate.id() == groupId)
        .findFirst()
        .orElseThrow(() -> new BizException("产品版本不存在或已停用：" + groupId));
    requireNotCombinedMember(group, activeScopes);
    return toSingle(group, projectId, dimension);
  }

  private DashboardVersionScope resolveDefault(
      long projectId,
      IssueScopeDimension dimension,
      List<IssueScopeCatalogService.ScopeGroup> groups,
      List<ActiveScope> activeScopes) {
    if (groups.isEmpty()) {
      throw new BizException("当前没有启用的看板版本");
    }
    List<VersionOption> options = optionsFor(groups, activeScopes);
    if (options.isEmpty()) {
      throw new BizException("当前没有启用的看板版本");
    }
    VersionOption first = options.getFirst();
    Optional<ActiveScope> scope = activeScopes.stream()
        .filter(candidate -> candidate.definition().key().equals(first.businessKey()))
        .findFirst();
    return scope.isPresent()
        ? toScope(scope.get(), projectId, dimension)
        : toSingle(
            groups.stream()
                .filter(group -> group.businessKey().equals(first.businessKey()))
                .findFirst()
                .orElseThrow(() -> new BizException("看板默认版本不存在：" + first.businessKey())),
            projectId,
            dimension);
  }

  private List<VersionOption> optionsFor(
      List<IssueScopeCatalogService.ScopeGroup> groups, List<ActiveScope> activeScopes) {
    Map<Long, ActiveScope> memberByGroupId = new LinkedHashMap<>();
    for (ActiveScope activeScope : activeScopes) {
      for (IssueScopeCatalogService.ScopeGroup member : activeScope.members()) {
        memberByGroupId.put(member.id(), activeScope);
      }
    }
    List<VersionOption> options = new ArrayList<>();
    Set<String> emittedScopes = new LinkedHashSet<>();
    for (IssueScopeCatalogService.ScopeGroup group : groups) {
      ActiveScope activeScope = memberByGroupId.get(group.id());
      if (activeScope == null) {
        options.add(singleOption(group));
        continue;
      }
      if (emittedScopes.add(activeScope.definition().key())) {
        options.add(new VersionOption(
            activeScope.anchor().id(),
            activeScope.definition().key(),
            activeScope.definition().displayName(),
            activeScope.sortOrder()));
      }
    }
    return options;
  }

  private void requireNotCombinedMember(
      IssueScopeCatalogService.ScopeGroup group, List<ActiveScope> activeScopes) {
    for (ActiveScope activeScope : activeScopes) {
      boolean replaced = activeScope.definition().memberBusinessKeys().stream()
          .anyMatch(member -> member.equalsIgnoreCase(group.businessKey()));
      if (replaced) {
        throw new BizException(
            "看板版本已合并为 " + activeScope.definition().displayName() + "，不能单独查询：" + group.businessKey());
      }
    }
  }

  private DashboardVersionScope toSingle(
      IssueScopeCatalogService.ScopeGroup group, long projectId, IssueScopeDimension dimension) {
    List<PhaseMember> phases = group.members().stream()
        .map(member -> new PhaseMember(
            member.id(), member.sourceValue(), member.displayName(), member.sortOrder()))
        .toList();
    String fingerprint = fingerprint(
        "single|" + projectId + "|" + dimension + "|" + group.id() + "|"
            + group.businessKey() + "|" + group.displayName() + "|" + describePhases(phases));
    return new DashboardVersionScope(
        group.businessKey(),
        group.displayName(),
        projectId,
        dimension,
        group.id(),
        group.sortOrder(),
        List.of(group.businessKey()),
        phases,
        List.of(group.businessKey()),
        List.of(group.businessKey() + "集成测试"),
        false,
        fingerprint);
  }

  private DashboardVersionScope toScope(
      ActiveScope activeScope, long projectId, IssueScopeDimension dimension) {
    DashboardVersionScopeConfig.ScopeDefinition definition = activeScope.definition();
    List<IssueScopeCatalogService.ScopeGroup> members = activeScope.members();
    List<PhaseMember> phases = combinedPhases(definition, members);
    List<String> memberKeys = members.stream()
        .map(IssueScopeCatalogService.ScopeGroup::businessKey)
        .toList();
    List<String> reviewProjectNames = distinctTexts(
        concat(memberKeys, definition.reviewProjectNames()));
    List<String> integrationPhases = definition.integrationTestPhases().isEmpty()
        ? memberKeys.stream().map(member -> member + "集成测试").toList()
        : distinctTexts(definition.integrationTestPhases());
    String fingerprint = fingerprint(
        "combined|" + projectId + "|" + dimension + "|" + definition.key() + "|"
            + definition.displayName() + "|" + activeScope.anchor().id() + "|"
            + describeMembers(members) + "|" + describePhases(phases) + "|"
            + configLoader.resourceHash());
    return new DashboardVersionScope(
        definition.key(),
        definition.displayName(),
        projectId,
        dimension,
        activeScope.anchor().id(),
        activeScope.sortOrder(),
        List.copyOf(memberKeys),
        phases,
        reviewProjectNames,
        integrationPhases,
        true,
        fingerprint);
  }

  private List<PhaseMember> combinedPhases(
      DashboardVersionScopeConfig.ScopeDefinition definition,
      List<IssueScopeCatalogService.ScopeGroup> members) {
    Map<String, Long> sourceValueOwners = new HashMap<>();
    Map<Long, PhaseMember> phases = new LinkedHashMap<>();
    for (IssueScopeCatalogService.ScopeGroup member : members) {
      for (IssueScopeCatalogService.ScopeMember phase : member.members()) {
        String normalizedValue = phase.sourceValue().toLowerCase(Locale.ROOT);
        Long previousOwner = sourceValueOwners.putIfAbsent(normalizedValue, phase.id());
        if (previousOwner != null && previousOwner != phase.id()) {
          throw new BizException(
              "看板版本分组配置冲突：测试阶段值 "
                  + phase.sourceValue()
                  + " 同时属于成员 "
                  + previousOwner
                  + " 和 "
                  + phase.id());
        }
        phases.putIfAbsent(phase.id(), new PhaseMember(
            phase.id(), phase.sourceValue(), phase.displayName(), phase.sortOrder()));
      }
    }
    return new ArrayList<>(phases.values());
  }

  private List<ActiveScope> activeScopes(
      List<IssueScopeCatalogService.ScopeGroup> groups,
      long projectId,
      IssueScopeDimension dimension) {
    if (groups.isEmpty()) {
      return List.of();
    }
    Map<String, IssueScopeCatalogService.ScopeGroup> groupByBusinessKey = new LinkedHashMap<>();
    groups.forEach(group -> groupByBusinessKey.put(
        group.businessKey().toLowerCase(Locale.ROOT), group));
    List<ActiveScope> activeScopes = new ArrayList<>();
    for (DashboardVersionScopeConfig.ScopeDefinition definition : configLoader.config().scopes()) {
      if (definition.projectId() != projectId
          || !IssueScopeDimension.parse(definition.dimension()).equals(dimension)) {
        continue;
      }
      IssueScopeCatalogService.ScopeGroup anchor = groupByBusinessKey.get(
          definition.anchorBusinessKey().toLowerCase(Locale.ROOT));
      if (anchor == null) {
        continue;
      }
      List<IssueScopeCatalogService.ScopeGroup> members = definition.memberBusinessKeys().stream()
          .map(key -> groupByBusinessKey.get(key.toLowerCase(Locale.ROOT)))
          .filter(member -> member != null)
          .filter(member -> member.projectId() == projectId)
          .sorted(Comparator.comparingInt(IssueScopeCatalogService.ScopeGroup::sortOrder)
              .thenComparingLong(IssueScopeCatalogService.ScopeGroup::id))
          .toList();
      if (members.isEmpty()) {
        continue;
      }
      int sortOrder = members.stream()
          .mapToInt(IssueScopeCatalogService.ScopeGroup::sortOrder)
          .min()
          .orElse(anchor.sortOrder());
      activeScopes.add(new ActiveScope(definition, anchor, members, sortOrder));
    }
    return List.copyOf(activeScopes);
  }

  private List<IssueScopeCatalogService.ScopeGroup> groups(
      long projectId, IssueScopeDimension dimension) {
    return catalogService.listEnabledGroups(projectId, dimension);
  }

  private VersionOption singleOption(IssueScopeCatalogService.ScopeGroup group) {
    return new VersionOption(
        group.id(), group.businessKey(), group.displayName(), group.sortOrder());
  }

  private List<String> concat(List<String> first, List<String> second) {
    List<String> values = new ArrayList<>(first.size() + second.size());
    values.addAll(first);
    values.addAll(second);
    return values;
  }

  private List<String> distinctTexts(List<String> values) {
    Set<String> seen = new LinkedHashSet<>();
    List<String> result = new ArrayList<>();
    for (String value : values) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized != null && seen.add(normalized.toLowerCase(Locale.ROOT))) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }

  private String describeMembers(List<IssueScopeCatalogService.ScopeGroup> members) {
    StringBuilder value = new StringBuilder();
    for (IssueScopeCatalogService.ScopeGroup member : members) {
      value.append(member.id()).append(':').append(member.businessKey()).append(';');
    }
    return value.toString();
  }

  private String describePhases(List<PhaseMember> phases) {
    StringBuilder value = new StringBuilder();
    for (PhaseMember phase : phases) {
      value.append(phase.id()).append(':').append(phase.sourceValue()).append(':')
          .append(phase.sortOrder()).append(';');
    }
    return value.toString();
  }

  private String fingerprint(String source) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", error);
    }
  }

  /** 看板候选：groupId 为 BI 使用的稳定原目录 ID，value 为稳定查询身份。 */
  public record VersionOption(long groupId, String businessKey, String displayName, int sortOrder) {
    /** 看板请求、下钻与导出使用的稳定身份；显示名只用于 label。 */
    public String value() {
      return businessKey;
    }
  }

  private record ActiveScope(
      DashboardVersionScopeConfig.ScopeDefinition definition,
      IssueScopeCatalogService.ScopeGroup anchor,
      List<IssueScopeCatalogService.ScopeGroup> members,
      int sortOrder) {}
}
