package com.data.collection.platform.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 系统测试领域对统一议题范围目录的类型安全适配器。 */
@Service
public class SystemTestPhaseCatalogService {
  public static final long LEGACY_CROWN_CAD_PROJECT_ID = IssueScopeCatalogService.CROWN_CAD_PROJECT_ID;

  private static final Pattern TURN_LABEL_PATTERN =
      Pattern.compile("(第[一二三四五六七八九十0-9]+轮系统测试|回归测试|系统测试)");
  private static final List<String> SYSTEM_TEST_TOKENS = List.of("系统测试", "回归测试");

  private final IssueScopeCatalogService issueScopeCatalogService;

  public SystemTestPhaseCatalogService(IssueScopeCatalogService issueScopeCatalogService) {
    this.issueScopeCatalogService = issueScopeCatalogService;
  }

  /** 返回项目下启用的系统测试版本及其精确测试轮次。 */
  public List<PhaseGroup> listGroups(Long projectId) {
    if (projectId == null) {
      return List.of();
    }
    return issueScopeCatalogService
        .listEnabledGroups(projectId, IssueScopeDimension.TESTING_PHASE)
        .stream()
        .map(
            group ->
                new PhaseGroup(
                    group.projectId(),
                    group.id(),
                    group.businessKey(),
                    group.members().stream()
                        .map(IssueScopeCatalogService.ScopeMember::sourceValue)
                        .toList(),
                    0L))
        .toList();
  }

  /** 返回项目下按管理员顺序排列的启用版本业务键。 */
  public List<String> listParentNames(Long projectId) {
    if (projectId == null) {
      return List.of();
    }
    return issueScopeCatalogService.listEnabledBusinessKeys(
        projectId, IssueScopeDimension.TESTING_PHASE);
  }

  /** 返回当前事实发布实际影响的系统测试版本业务键。 */
  public List<String> listParentNames(
      Long projectId, com.data.collection.platform.entity.FactPublicationContext context) {
    if (projectId == null || context == null) {
      return List.of();
    }
    return issueScopeCatalogService
        .listEnabledGroups(projectId, IssueScopeDimension.TESTING_PHASE)
        .stream()
        .filter(
            group ->
                context.covers(
                    com.data.collection.platform.entity.FactType.ISSUE,
                    com.data.collection.platform.entity.ProjectionScopeType.ISSUE_SCOPE_GROUP,
                    FactProjectionScopeKeyCodec.issueScopeGroup(
                        group.projectId(), group.dimension(), group.id())))
        .map(IssueScopeCatalogService.ScopeGroup::businessKey)
        .toList();
  }

  /** 返回项目下所有启用的精确测试阶段值。 */
  public List<String> listTestingPhases(Long projectId) {
    return listGroups(projectId).stream().flatMap(group -> group.testingPhases().stream()).toList();
  }

  /** 将版本业务键展开为精确测试阶段值。 */
  public List<String> listTestingPhasesByParent(Long projectId, String parentName) {
    if (projectId == null || TextQuerySupport.trimToNull(parentName) == null) {
      return List.of();
    }
    return issueScopeCatalogService
        .findEnabledGroup(projectId, IssueScopeDimension.TESTING_PHASE, parentName)
        .stream()
        .flatMap(group -> group.members().stream())
        .map(IssueScopeCatalogService.ScopeMember::sourceValue)
        .toList();
  }

  /** 判断值是否为目录中启用的精确测试阶段。 */
  public boolean isConfiguredTestingPhase(Long projectId, String testingPhase) {
    String normalized = TextQuerySupport.trimToNull(testingPhase);
    return projectId != null
        && normalized != null
        && listTestingPhases(projectId).stream()
            .anyMatch(phase -> phase.equalsIgnoreCase(normalized));
  }

  /** 从具体轮次文本中提取父级版本，仅用于事实解析。 */
  public String parentName(String phaseLabel) {
    String normalized = TextQuerySupport.trimToNull(phaseLabel);
    if (normalized == null) {
      return "";
    }
    Matcher matcher = TURN_LABEL_PATTERN.matcher(normalized);
    if (matcher.find()) {
      String parent = TextQuerySupport.trimToNull(normalized.replace(matcher.group(1), ""));
      if (parent != null) {
        return parent;
      }
    }
    return normalized;
  }

  /** 判断文本是否包含系统测试或回归测试语义。 */
  public boolean isSystemTestPhase(String value) {
    return StringUtils.hasText(value) && IssueRuleSupport.containsToken(value, SYSTEM_TEST_TOKENS);
  }

  public record PhaseGroup(
      Long projectId, long groupId, String name, List<String> testingPhases, long issueCount) {}
}
