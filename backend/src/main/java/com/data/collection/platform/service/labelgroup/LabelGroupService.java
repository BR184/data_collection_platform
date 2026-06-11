package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupChildResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupCreateRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupResponse;
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
import java.util.Set;
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

  public LabelGroupService(LabelGroupRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public LabelGroupResponse create(LabelGroupCreateRequest request) {
    String name = requireName(request.name());
    validateDuplicateName(name, null);
    String groupType = normalizeGroupType(request.groupType());
    List<LabelGroupMemberRecord> members = normalizeMembers(request.members());
    List<LabelGroupRecord> childGroups = loadChildGroups(request.childGroupIds());
    String valueType = inferGroupValueType(members, childGroups);
    validateGroupShape(null, groupType, valueType, members, childGroups);

    LabelGroupRecord group =
        repository.createGroup(name, valueType, groupType, trimToNull(request.description()), DEFAULT_USER);
    repository.replaceMembers(group.id(), members);
    repository.replaceReferences(group.id(), childGroups.stream().map(LabelGroupRecord::id).toList());
    return get(group.id());
  }

  @Transactional
  public LabelGroupResponse update(Long groupId, LabelGroupUpdateRequest request) {
    LabelGroupRecord existing = findExisting(groupId);
    String name = requireName(request.name());
    validateDuplicateName(name, groupId);
    String groupType = normalizeGroupType(request.groupType() == null ? existing.groupType() : request.groupType());
    List<LabelGroupMemberRecord> members = normalizeMembers(request.members());
    List<LabelGroupRecord> childGroups = loadChildGroups(request.childGroupIds());
    String valueType = inferGroupValueType(members, childGroups);
    validateGroupShape(groupId, groupType, valueType, members, childGroups);
    boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();

    repository.updateGroup(
        groupId,
        name,
        valueType,
        groupType,
        trimToNull(request.description()),
        enabled,
        DEFAULT_USER);
    repository.replaceMembers(groupId, members);
    repository.replaceReferences(groupId, childGroups.stream().map(LabelGroupRecord::id).toList());
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

  @Transactional
  public void delete(Long groupId) {
    findExisting(groupId);
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
      String groupType,
      String valueType,
      List<LabelGroupMemberRecord> members,
      List<LabelGroupRecord> childGroups) {
    if (members.isEmpty() && childGroups.isEmpty()) {
      throw new BizException("标签组成员不能为空");
    }
    if (valueType == null) {
      throw new BizException("标签组值类型不能为空");
    }
    if (TYPE_DYNAMIC.equals(groupType) && !childGroups.isEmpty()) {
      throw new BizException("动态标签组不能直接保存子标签组引用");
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

  private String inferGroupValueType(List<LabelGroupMemberRecord> members, List<LabelGroupRecord> childGroups) {
    String valueType = null;
    for (LabelGroupMemberRecord member : members) {
      valueType = mergeValueType(valueType, inferValueType(member.memberValue()), member.memberValue());
    }
    for (LabelGroupRecord childGroup : childGroups) {
      valueType = mergeValueType(valueType, childGroup.valueType(), childGroup.name());
    }
    return valueType;
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
        group.description(),
        group.enabled(),
        expanded == null ? members.size() : expanded.values().size(),
        members,
        childGroups,
        expandedPreview,
        group.createdBy(),
        group.createdAt(),
        group.updatedBy(),
        group.updatedAt());
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
