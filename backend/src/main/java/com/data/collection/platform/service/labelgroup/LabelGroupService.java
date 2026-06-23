package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupChildResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupCreateRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleConfigRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupUpdateRequest;
import com.data.collection.platform.service.TextQuerySupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LabelGroupService {
  static final int MAX_MEMBER_COUNT = 200;
  static final String TYPE_STRING = "STRING";
  static final String TYPE_NUMBER = "NUMBER";
  static final String TYPE_DATE = "DATE";
  static final String TYPE_BOOLEAN = "BOOLEAN";
  static final String TYPE_STATIC = "STATIC";
  static final String TYPE_DYNAMIC = "DYNAMIC";
  static final String TYPE_COMPOSITE = "COMPOSITE";
  private static final String DEFAULT_USER = "system";

  private final LabelGroupRepository repository;
  private final LabelGroupDynamicRuleEvaluationService dynamicRuleEvaluationService;
  private final ObjectProvider<LabelValueQueryService> labelValueQueryServiceProvider;

  @Autowired
  public LabelGroupService(
      LabelGroupRepository repository,
      LabelGroupDynamicRuleEvaluationService dynamicRuleEvaluationService,
      ObjectProvider<LabelValueQueryService> labelValueQueryServiceProvider) {
    this.repository = repository;
    this.dynamicRuleEvaluationService = dynamicRuleEvaluationService;
    this.labelValueQueryServiceProvider = labelValueQueryServiceProvider;
  }

  LabelGroupService(
      LabelGroupRepository repository,
      LabelGroupDynamicRuleEvaluationService dynamicRuleEvaluationService) {
    this(repository, dynamicRuleEvaluationService, null);
  }

  @Transactional
  public LabelGroupResponse create(LabelGroupCreateRequest request) {
    String name = requireName(request.name());
    validateDuplicateName(name, null);
    String groupType = normalizeGroupType(request.groupType());
    String applicableScope = normalizeApplicableScope(request.applicableScope());
    String sourceFieldKey = normalizeSourceFieldKey(request.sourceFieldKey(), applicableScope);
    List<LabelGroupMemberRecord> members = normalizeMembers(request.members());
    List<LabelGroupRecord> childGroups = loadChildGroups(request.childGroupIds());
    LabelGroupDynamicRuleRecord dynamicRule = normalizeDynamicRule(null, request.dynamicRule());
    members = materializeDynamicMembers(groupType, members, dynamicRule);
    dynamicRule = markDynamicRuleComputed(groupType, dynamicRule);
    String valueType = inferGroupValueType(members, childGroups, dynamicRule);
    valueType = defaultSystemGroupValueType(name, groupType, valueType, members, childGroups, dynamicRule);
    validateGroupShape(null, name, groupType, valueType, applicableScope, sourceFieldKey, members, childGroups, dynamicRule);

    LabelGroupRecord group =
        repository.createGroup(
            name,
            valueType,
            groupType,
            applicableScope,
            sourceFieldKey,
            trimToNull(request.description()),
            DEFAULT_USER);
    repository.replaceMembers(group.id(), members);
    repository.replaceReferences(group.id(), childGroups.stream().map(LabelGroupRecord::id).toList());
    repository.replaceDynamicRule(group.id(), dynamicRule);
    return get(group.id());
  }

  @Transactional
  public LabelGroupResponse update(Long groupId, LabelGroupUpdateRequest request) {
    LabelGroupRecord existing = findExisting(groupId);
    boolean systemDefault = isSystemDefaultGroup(existing.name());
    String name = systemDefault ? existing.name() : requireName(request.name());
    validateDuplicateName(name, groupId);
    String groupType = systemDefault
        ? existing.groupType()
        : normalizeGroupType(request.groupType() == null ? existing.groupType() : request.groupType());
    String applicableScope = systemDefault
        ? existing.applicableScope()
        : normalizeApplicableScope(request.applicableScope() == null ? existing.applicableScope() : request.applicableScope());
    String sourceFieldKey = normalizeSourceFieldKey(
        systemDefault || request.sourceFieldKey() == null ? existing.sourceFieldKey() : request.sourceFieldKey(),
        applicableScope);
    List<LabelGroupMemberRecord> members = normalizeMembers(request.members());
    List<LabelGroupRecord> childGroups = loadChildGroups(request.childGroupIds());
    LabelGroupDynamicRuleRecord dynamicRule = normalizeDynamicRule(groupId, request.dynamicRule());
    members = materializeDynamicMembers(groupType, members, dynamicRule);
    dynamicRule = markDynamicRuleComputed(groupType, dynamicRule);
    String valueType = inferGroupValueType(members, childGroups, dynamicRule);
    valueType = defaultSystemGroupValueType(name, groupType, valueType, members, childGroups, dynamicRule);
    validateGroupShape(groupId, name, groupType, valueType, applicableScope, sourceFieldKey, members, childGroups, dynamicRule);
    boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();

    repository.updateGroup(
        groupId,
        name,
        valueType,
        groupType,
        applicableScope,
        sourceFieldKey,
        trimToNull(request.description()),
        enabled,
        DEFAULT_USER);
    repository.replaceMembers(groupId, members);
    repository.replaceReferences(groupId, childGroups.stream().map(LabelGroupRecord::id).toList());
    repository.replaceDynamicRule(groupId, dynamicRule);
    return get(groupId);
  }

  public List<LabelGroupResponse> list(String valueType, String keyword, Boolean enabled) {
    return repository.list(normalizeValueType(valueType), trimToNull(keyword), enabled).stream()
        .map(this::toResponse)
        .toList();
  }

  public LabelGroupResponse get(Long groupId) {
    return toResponse(findExisting(groupId));
  }

  Optional<ExpandedLabelGroup> expandSystemDefault(String groupName, String valueType) {
    String safeName = trimToNull(groupName);
    String safeValueType = normalizeValueType(valueType);
    if (safeName == null || safeValueType == null) {
      return Optional.empty();
    }
    return repository.list(safeValueType, null, true).stream()
        .filter(group -> safeName.equals(group.name()))
        .findFirst()
        .flatMap(group -> {
          try {
            ExpandedLabelGroup expanded = new ExpandedLabelGroup(
                group.id(), group.name(), group.valueType(), List.copyOf(expandValues(group, new LinkedHashSet<>())));
            return expanded.values().isEmpty() ? Optional.empty() : Optional.of(expanded);
          } catch (RuntimeException ignored) {
            return Optional.empty();
          }
        });
  }

  @Transactional
  public void delete(Long groupId) {
    LabelGroupRecord existing = findExisting(groupId);
    if (isSystemDefaultGroup(existing.name())) {
      throw new BizException("系统默认标签组不能删除，可编辑成员、备注或停用");
    }
    repository.deleteById(groupId);
  }

  LabelGroupRecord requireGroup(Long groupId) {
    return findExisting(groupId);
  }

  ExpandedLabelGroup expandValues(Long groupId) {
    LabelGroupRecord group = findExisting(groupId);
    LinkedHashSet<String> values = expandValues(group, new LinkedHashSet<>());
    if (values.isEmpty()) {
      throw new BizException("该标签组当前没有可用成员");
    }
    if (values.size() > MAX_MEMBER_COUNT) {
      throw new BizException("标签组展开后超过 200 个值，请拆分后保存");
    }
    return new ExpandedLabelGroup(group.id(), group.name(), group.valueType(), List.copyOf(values));
  }

  private LinkedHashSet<String> expandValues(LabelGroupRecord group, LinkedHashSet<Long> visiting) {
    if (!group.enabled()) {
      throw new BizException("标签组已禁用，不能应用筛选：" + group.name());
    }
    if (!visiting.add(group.id())) {
      throw new BizException("标签组引用存在循环：" + group.name());
    }
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (LabelGroupMemberRecord member : group.members()) {
      values.add(member.memberValue());
    }
    for (LabelGroupChildRecord child : group.childGroups()) {
      LabelGroupRecord childGroup = findExisting(child.childGroupId());
      if (group.valueType() != null
          && childGroup.valueType() != null
          && !group.valueType().equals(childGroup.valueType())) {
        throw new BizException("子标签组值类型不一致：" + childGroup.name());
      }
      values.addAll(expandValues(childGroup, visiting));
    }
    visiting.remove(group.id());
    return values;
  }

  private LabelGroupRecord findExisting(Long groupId) {
    return repository.findById(groupId).orElseThrow(() -> new BizException("标签组不存在：" + groupId));
  }

  private void validateDuplicateName(String name, Long excludeId) {
    if (repository.existsByName(name, excludeId)) {
      throw new BizException("已存在同名标签组：" + name);
    }
  }

  private List<LabelGroupMemberRecord> normalizeMembers(List<LabelGroupMemberRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      return List.of();
    }
    if (requests.size() > MAX_MEMBER_COUNT) {
      throw new BizException("标签组成员超过 200 个，请拆分后保存");
    }
    List<LabelGroupMemberRecord> members = new ArrayList<>();
    Set<String> values = new LinkedHashSet<>();
    int sortOrder = 0;
    for (LabelGroupMemberRequest request : requests) {
      String value = requireText(request.value(), "标签组成员值不能为空");
      if (value.length() > 500) {
        throw new BizException("标签组成员值不能超过 500 个字符");
      }
      if (!values.add(value)) {
        throw new BizException("标签组成员重复：" + value);
      }
      String label = trimToNull(request.label());
      members.add(new LabelGroupMemberRecord(null, null, value, label == null ? value : label, sortOrder++));
    }
    return members;
  }

  private List<LabelGroupRecord> loadChildGroups(List<Long> childGroupIds) {
    if (childGroupIds == null || childGroupIds.isEmpty()) {
      return List.of();
    }
    List<LabelGroupRecord> childGroups = new ArrayList<>();
    Set<Long> seen = new LinkedHashSet<>();
    for (Long childGroupId : childGroupIds) {
      if (childGroupId == null || !seen.add(childGroupId)) {
        continue;
      }
      childGroups.add(findExisting(childGroupId));
    }
    return childGroups;
  }

  private void validateGroupShape(
      Long groupId,
      String groupName,
      String groupType,
      String valueType,
      String applicableScope,
      String sourceFieldKey,
      List<LabelGroupMemberRecord> members,
      List<LabelGroupRecord> childGroups,
      LabelGroupDynamicRuleRecord dynamicRule) {
    if (members.isEmpty()
        && childGroups.isEmpty()
        && dynamicRule == null
        && !isEmptySystemDefaultStaticGroup(groupName, groupType, members, childGroups, dynamicRule)) {
      throw new BizException("标签组成员不能为空");
    }
    if (valueType == null) {
      throw new BizException("标签组值类型不能为空");
    }
    if (TYPE_DYNAMIC.equals(groupType) && dynamicRule == null) {
      throw new BizException("动态标签组必须配置动态规则");
    }
    if (!TYPE_DYNAMIC.equals(groupType) && dynamicRule != null) {
      throw new BizException("只有动态标签组可以配置动态规则");
    }
    validateApplicableScope(applicableScope, sourceFieldKey, valueType);
    if (TYPE_DYNAMIC.equals(groupType) && !childGroups.isEmpty()) {
      throw new BizException("动态标签组不能直接保存子标签组引用");
    }
    if (TYPE_DYNAMIC.equals(groupType) && members.isEmpty() && valueType == null) {
      throw new BizException("动态标签组需要保存最近一次规则计算出的成员值");
    }
    if (TYPE_COMPOSITE.equals(groupType) && members.size() > 0) {
      throw new BizException("组合标签组只能选择子标签组");
    }
    for (LabelGroupRecord childGroup : childGroups) {
      if (childGroup.valueType() == null) {
        throw new BizException("子标签组尚未定型：" + childGroup.name());
      }
      if (!valueType.equals(childGroup.valueType())) {
        throw new BizException("子标签组值类型不一致：" + childGroup.name());
      }
      if (TYPE_STATIC.equals(groupType) && !TYPE_STATIC.equals(childGroup.groupType())) {
        throw new BizException("静态标签组只能嵌套静态标签组：" + childGroup.name());
      }
      if (groupId != null && wouldCreateCycle(groupId, childGroup, new LinkedHashSet<>())) {
        throw new BizException("标签组引用存在循环：" + childGroup.name());
      }
    }
    int expandedCount = members.size();
    for (LabelGroupRecord childGroup : childGroups) {
      expandedCount += expandValues(childGroup, new LinkedHashSet<>()).size();
    }
    if (expandedCount > MAX_MEMBER_COUNT) {
      throw new BizException("标签组展开后超过 200 个值，请拆分后保存");
    }
  }

  private boolean wouldCreateCycle(Long parentGroupId, LabelGroupRecord candidate, LinkedHashSet<Long> visited) {
    if (parentGroupId.equals(candidate.id())) {
      return true;
    }
    if (!visited.add(candidate.id())) {
      return false;
    }
    for (LabelGroupChildRecord child : candidate.childGroups()) {
      if (parentGroupId.equals(child.childGroupId())) {
        return true;
      }
      if (wouldCreateCycle(parentGroupId, findExisting(child.childGroupId()), visited)) {
        return true;
      }
    }
    return false;
  }

  private String inferGroupValueType(
      List<LabelGroupMemberRecord> members,
      List<LabelGroupRecord> childGroups,
      LabelGroupDynamicRuleRecord dynamicRule) {
    String valueType = null;
    if (dynamicRule != null && dynamicRule.outputValueType() != null) {
      valueType = mergeValueType(valueType, dynamicRule.outputValueType(), "动态规则输出字段");
    }
    for (LabelGroupMemberRecord member : members) {
      valueType = mergeValueType(valueType, inferValueType(member.memberValue()), member.memberValue());
    }
    for (LabelGroupRecord childGroup : childGroups) {
      valueType = mergeValueType(valueType, childGroup.valueType(), childGroup.name());
    }
    return valueType;
  }

  private String defaultSystemGroupValueType(
      String groupName,
      String groupType,
      String valueType,
      List<LabelGroupMemberRecord> members,
      List<LabelGroupRecord> childGroups,
      LabelGroupDynamicRuleRecord dynamicRule) {
    if (valueType == null && isEmptySystemDefaultStaticGroup(groupName, groupType, members, childGroups, dynamicRule)) {
      return TYPE_STRING;
    }
    return valueType;
  }

  private boolean isEmptySystemDefaultStaticGroup(
      String groupName,
      String groupType,
      List<LabelGroupMemberRecord> members,
      List<LabelGroupRecord> childGroups,
      LabelGroupDynamicRuleRecord dynamicRule) {
    return TYPE_STATIC.equals(groupType)
        && SystemDefaultLabelGroupCatalog.isSystemDefaultGroupName(groupName)
        && members.isEmpty()
        && childGroups.isEmpty()
        && dynamicRule == null;
  }

  private LabelGroupDynamicRuleRecord normalizeDynamicRule(Long groupId, LabelGroupDynamicRuleRequest request) {
    if (request == null) {
      return null;
    }
    LabelGroupRuleConfigRequest ruleConfig = request.ruleConfig();
    if (ruleConfig == null) {
      throw new BizException("动态规则配置不能为空");
    }
    String outputValueType = dynamicRuleEvaluationService.outputValueType(ruleConfig);
    String ruleConfigJson = dynamicRuleEvaluationService.serializeRuleConfig(ruleConfig);
    return new LabelGroupDynamicRuleRecord(null, groupId, ruleConfigJson, outputValueType, null, null, null);
  }

  private List<LabelGroupMemberRecord> materializeDynamicMembers(
      String groupType,
      List<LabelGroupMemberRecord> members,
      LabelGroupDynamicRuleRecord dynamicRule) {
    if (!TYPE_DYNAMIC.equals(groupType) || dynamicRule == null || dynamicRuleEvaluationService == null) {
      return members;
    }
    return dynamicRuleEvaluationService.materializeMembers(
        dynamicRuleEvaluationService.parseRuleConfig(dynamicRule.ruleConfigJson()));
  }

  private LabelGroupDynamicRuleRecord markDynamicRuleComputed(
      String groupType,
      LabelGroupDynamicRuleRecord dynamicRule) {
    if (!TYPE_DYNAMIC.equals(groupType) || dynamicRule == null || dynamicRuleEvaluationService == null) {
      return dynamicRule;
    }
    return new LabelGroupDynamicRuleRecord(
        dynamicRule.id(),
        dynamicRule.groupId(),
        dynamicRule.ruleConfigJson(),
        dynamicRule.outputValueType(),
        "SUCCESS",
        null,
        OffsetDateTime.now());
  }

  private String mergeValueType(String current, String next, String value) {
    if (next == null || next.isBlank()) {
      throw new BizException("标签组值类型不能为空：" + value);
    }
    if (current == null) {
      return next;
    }
    if (!current.equals(next)) {
      throw new BizException("标签组成员值类型不一致：" + value);
    }
    return current;
  }

  private String inferValueType(String value) {
    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
      return TYPE_BOOLEAN;
    }
    if (isNumber(value)) {
      return TYPE_NUMBER;
    }
    if (isDate(value)) {
      return TYPE_DATE;
    }
    return TYPE_STRING;
  }

  private boolean isNumber(String value) {
    try {
      new BigDecimal(value);
      return true;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private boolean isDate(String value) {
    try {
      LocalDate.parse(value);
      return true;
    } catch (DateTimeParseException ignored) {
      try {
        OffsetDateTime.parse(value);
        return true;
      } catch (DateTimeParseException ignoredAgain) {
        return false;
      }
    }
  }

  private LabelGroupResponse toResponse(LabelGroupRecord group) {
    ExpandedLabelGroup expanded = null;
    try {
      expanded = new ExpandedLabelGroup(
          group.id(), group.name(), group.valueType(), List.copyOf(expandValues(group, new LinkedHashSet<>())));
    } catch (RuntimeException ignored) {
      // List pages should still render the group metadata; expansion APIs surface the error explicitly.
    }
    List<LabelGroupMemberResponse> members =
        group.members().stream()
            .map(member -> new LabelGroupMemberResponse(
                member.id(), member.memberValue(), member.displayName(), true, member.sortOrder()))
            .toList();
    List<LabelGroupChildResponse> childGroups =
        group.childGroups().stream()
            .map(child -> new LabelGroupChildResponse(
                child.childGroupId(),
                child.childName(),
                child.childGroupType(),
                child.childValueType(),
                child.childEnabled()))
            .toList();
    LabelGroupDynamicRuleResponse dynamicRule =
        group.dynamicRule() == null
            ? null
            : new LabelGroupDynamicRuleResponse(
                dynamicRuleEvaluationService.parseRuleConfig(group.dynamicRule().ruleConfigJson()),
                group.dynamicRule().outputValueType(),
                group.dynamicRule().lastStatus(),
                group.dynamicRule().lastError(),
                group.dynamicRule().lastComputedAt());
    List<LabelGroupMemberResponse> expandedPreview =
        expanded == null
            ? List.of()
            : expanded.values().stream()
                .map(value -> new LabelGroupMemberResponse(null, value, value, true, 0))
                .toList();
    return new LabelGroupResponse(
        group.id(),
        group.name(),
        group.valueType(),
        group.groupType(),
        group.applicableScope(),
        group.sourceFieldKey(),
        group.description(),
        group.enabled(),
        expanded == null ? members.size() : expanded.values().size(),
        members,
        childGroups,
        dynamicRule,
        expandedPreview,
        isSystemDefaultGroup(group.name()),
        group.createdBy(),
        group.createdAt(),
        group.updatedBy(),
        group.updatedAt());
  }

  private boolean isSystemDefaultGroup(String name) {
    return SystemDefaultLabelGroupCatalog.isSystemDefaultGroupName(name);
  }

  private String requireName(String value) {
    String name = requireText(value, "标签组名称不能为空");
    if (name.length() > 100) {
      throw new BizException("标签组名称不能超过 100 个字符");
    }
    return name;
  }

  private String normalizeGroupType(String value) {
    String groupType = trimToNull(value);
    if (groupType == null) {
      return TYPE_STATIC;
    }
    groupType = groupType.toUpperCase(Locale.ROOT);
    if (!Set.of(TYPE_STATIC, TYPE_DYNAMIC, TYPE_COMPOSITE).contains(groupType)) {
      throw new BizException("标签组类型不支持：" + value);
    }
    return groupType;
  }

  private String normalizeValueType(String value) {
    String valueType = trimToNull(value);
    return valueType == null ? null : valueType.toUpperCase(Locale.ROOT);
  }

  private String normalizeApplicableScope(String value) {
    String scope = trimToNull(value);
    if (scope == null) {
      return "SAME_TYPE";
    }
    scope = scope.toUpperCase(Locale.ROOT);
    if (!Set.of("SAME_TYPE", "SAME_FIELD").contains(scope)) {
      throw new BizException("标签组适用范围不支持：" + value);
    }
    return scope;
  }

  private String normalizeSourceFieldKey(String value, String applicableScope) {
    String sourceFieldKey = trimToNull(value);
    if ("SAME_FIELD".equals(applicableScope)) {
      if (sourceFieldKey == null) {
        throw new BizException("SAME_FIELD 适用范围必须指定来源字段");
      }
      return normalizeSameFieldKey(sourceFieldKey);
    }
    return sourceFieldKey;
  }

  private void validateApplicableScope(String applicableScope, String sourceFieldKey, String valueType) {
    if (valueType == null) {
      return;
    }
    if ("SAME_FIELD".equals(applicableScope) && trimToNull(sourceFieldKey) == null) {
      throw new BizException("SAME_FIELD 适用范围必须指定来源字段");
    }
  }

  private String normalizeSameFieldKey(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    return switch (normalized) {
      case "模块", "模块名", "模块名称", "module", "moduleName", "moduleNames" -> "moduleName";
      case "评审负责人", "reviewOwner" -> "reviewOwner";
      case "评审专家", "reviewExpert" -> "reviewExpert";
      case "项目", "project", "projectName" -> "projectName";
      case "客户问题处理人", "customer_assignee", "issue_assignee", "assigneeName" -> "assigneeName";
      default -> normalized;
    };
  }

  private String requireText(String value, String message) {
    String text = trimToNull(value);
    if (text == null) {
      throw new BizException(message);
    }
    return text;
  }

  private String trimToNull(String value) {
    return TextQuerySupport.trimToNull(value);
  }

  record ExpandedLabelGroup(Long groupId, String groupName, String valueType, List<String> values) {}
}
