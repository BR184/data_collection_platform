package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupCreateRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupUpdateRequest;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LabelGroupService {
  static final int MAX_MEMBER_COUNT = 200;
  private static final String STATIC_GROUP = "STATIC";
  private static final String DEFAULT_USER = "system";
  private static final Map<String, Set<String>> ENUM_CANONICAL_VALUES =
      Map.of(
          "severity_level", Set.of("一级缺陷", "二级缺陷", "三级缺陷"),
          "priority_level", Set.of("P1", "P2", "P3"),
          "closure_status", Set.of("需求如此"));

  private final LabelGroupRepository repository;
  private final LabelDimensionCatalogService dimensionCatalogService;
  private final LabelValueQueryService labelValueQueryService;

  public LabelGroupService(
      LabelGroupRepository repository,
      LabelDimensionCatalogService dimensionCatalogService,
      LabelValueQueryService labelValueQueryService) {
    this.repository = repository;
    this.dimensionCatalogService = dimensionCatalogService;
    this.labelValueQueryService = labelValueQueryService;
  }

  @Transactional
  public LabelGroupResponse create(LabelGroupCreateRequest request) {
    String dimensionKey = requireText(request.dimensionKey(), "标签维度不能为空");
    LabelDimensionDefinition dimension = dimensionCatalogService.getDimension(dimensionKey);
    if (!dimension.staticSupported()) {
      throw new BizException("当前标签维度暂不支持静态标签组：" + dimension.name());
    }
    String name = requireName(request.name());
    validateDuplicateName(dimensionKey, name, null);
    List<LabelGroupMemberRecord> members = normalizeMembers(dimension, request.members());

    LabelGroupRecord group =
        repository.createGroup(name, dimensionKey, STATIC_GROUP, trimToNull(request.description()), DEFAULT_USER);
    repository.replaceMembers(group.id(), dimensionKey, members);
    return get(group.id());
  }

  @Transactional
  public LabelGroupResponse update(Long groupId, LabelGroupUpdateRequest request) {
    LabelGroupRecord existing = findExisting(groupId);
    if (request.dimensionKey() != null && !request.dimensionKey().isBlank()
        && !existing.dimensionKey().equals(request.dimensionKey())) {
      throw new BizException("标签组保存后不能修改标签维度，请新建标签组");
    }
    LabelDimensionDefinition dimension = dimensionCatalogService.getDimension(existing.dimensionKey());
    String name = requireName(request.name());
    validateDuplicateName(existing.dimensionKey(), name, groupId);
    List<LabelGroupMemberRecord> members = normalizeMembers(dimension, request.members());
    boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();

    repository.updateGroup(groupId, name, trimToNull(request.description()), enabled, DEFAULT_USER);
    repository.replaceMembers(groupId, existing.dimensionKey(), members);
    return get(groupId);
  }

  public List<LabelGroupResponse> list(String dimensionKey, String keyword, Boolean enabled) {
    if (dimensionKey != null && !dimensionKey.isBlank()) {
      dimensionCatalogService.getDimension(dimensionKey);
    }
    return repository.list(trimToNull(dimensionKey), trimToNull(keyword), enabled).stream()
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

  private LabelGroupRecord findExisting(Long groupId) {
    return repository.findById(groupId).orElseThrow(() -> new BizException("标签组不存在：" + groupId));
  }

  private void validateDuplicateName(String dimensionKey, String name, Long excludeId) {
    if (repository.existsByDimensionAndName(dimensionKey, name, excludeId)) {
      throw new BizException("同一标签维度下已存在同名标签组：" + name);
    }
  }

  private List<LabelGroupMemberRecord> normalizeMembers(
      LabelDimensionDefinition dimension, List<LabelGroupMemberRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      throw new BizException("标签组成员不能为空");
    }
    if (requests.size() > MAX_MEMBER_COUNT) {
      throw new BizException("标签组成员超过 200 个，请拆分后保存");
    }

    List<LabelGroupMemberRecord> members = new ArrayList<>();
    Set<String> values = new LinkedHashSet<>();
    int sortOrder = 0;
    for (LabelGroupMemberRequest request : requests) {
      String value = requireText(request.value(), "标签组成员值不能为空");
      String label = trimToNull(request.label());
      if (label == null) {
        label = value;
      }
      validateMemberValue(dimension, value);
      if (!values.add(value)) {
        throw new BizException("标签组成员重复：" + value);
      }
      members.add(new LabelGroupMemberRecord(
          null, null, dimension.key(), value, label, sortOrder++));
    }
    return members;
  }

  private void validateMemberValue(LabelDimensionDefinition dimension, String value) {
    switch (dimension.valueKind()) {
      case STRING_LITERAL -> {
        if (value.length() > 255) {
          throw new BizException("标签组成员格式不符合当前维度要求");
        }
      }
      case BRANCH_NAME -> {
        if (value.length() > 255 || value.contains(" ")) {
          throw new BizException("标签组成员格式不符合当前维度要求");
        }
      }
      case GITLAB_USER_ID -> {
        if (!value.matches("\\d{1,20}")) {
          throw new BizException("标签组成员格式不符合当前维度要求");
        }
      }
      case ENUM_KEY -> validateEnumValue(dimension.key(), value);
    }
  }

  private void validateEnumValue(String dimensionKey, String value) {
    Set<String> allowed = ENUM_CANONICAL_VALUES.get(dimensionKey);
    if (allowed == null || !allowed.contains(value)) {
      if ("closure_status".equals(dimensionKey) && "设计如此".equals(value)) {
        throw new BizException("客户问题闭环状态请保存规范值“需求如此”，不要保存“设计如此”");
      }
      throw new BizException("标签组成员格式不符合当前维度要求");
    }
  }

  private LabelGroupResponse toResponse(LabelGroupRecord group) {
    LabelDimensionDefinition dimension = dimensionCatalogService.getDimension(group.dimensionKey());
    Set<String> currentValues = loadCurrentValues(group.dimensionKey());
    List<LabelGroupMemberResponse> members =
        group.members().stream()
            .map(member -> new LabelGroupMemberResponse(
                member.id(),
                member.memberValue(),
                member.displayName(),
                currentValues.isEmpty() || currentValues.contains(member.memberValue()),
                member.sortOrder()))
            .toList();
    return new LabelGroupResponse(
        group.id(),
        group.name(),
        group.dimensionKey(),
        dimension.name(),
        group.groupType(),
        group.description(),
        group.enabled(),
        members.size(),
        members,
        group.createdBy(),
        group.createdAt(),
        group.updatedBy(),
        group.updatedAt());
  }

  private Set<String> loadCurrentValues(String dimensionKey) {
    try {
      LabelValuePageResponse values =
          labelValueQueryService.listValues(dimensionKey, null, null, null, 1, MAX_MEMBER_COUNT);
      return values.items().stream()
          .map(item -> item.value())
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    } catch (RuntimeException ignored) {
      return Set.of();
    }
  }

  private String requireName(String value) {
    String name = requireText(value, "标签组名称不能为空");
    if (name.length() > 100) {
      throw new BizException("标签组名称不能超过 100 个字符");
    }
    return name;
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
}
