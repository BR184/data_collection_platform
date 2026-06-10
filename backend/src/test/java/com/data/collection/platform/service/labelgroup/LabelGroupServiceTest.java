package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupCreateRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupUpdateRequest;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValueResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LabelGroupServiceTest {
  private InMemoryLabelGroupRepository repository;
  private LabelValueQueryService labelValueQueryService;
  private LabelGroupService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryLabelGroupRepository();
    labelValueQueryService = mock(LabelValueQueryService.class);
    service =
        new LabelGroupService(
            repository, new LabelDimensionCatalogService(), labelValueQueryService);
  }

  @Test
  void shouldCreateModuleGroup() {
    mockCurrentValues("module", "草图", "工程图");

    LabelGroupResponse response =
        service.create(
            new LabelGroupCreateRequest(
                "常用模块",
                "module",
                "常用模块集合",
                List.of(member("草图"), member("工程图"))));

    assertThat(response.id()).isEqualTo(1L);
    assertThat(response.name()).isEqualTo("常用模块");
    assertThat(response.dimensionKey()).isEqualTo("module");
    assertThat(response.dimensionName()).isEqualTo("模块");
    assertThat(response.memberCount()).isEqualTo(2);
    assertThat(response.members()).extracting(member -> member.value()).containsExactly("草图", "工程图");
  }

  @Test
  void shouldRejectDuplicateGroupNameWithinSameDimension() {
    mockCurrentValues("module", "草图");
    service.create(new LabelGroupCreateRequest("常用模块", "module", null, List.of(member("草图"))));

    assertThatThrownBy(
            () ->
                service.create(
                    new LabelGroupCreateRequest("常用模块", "module", null, List.of(member("工程图")))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("同一标签维度下已存在同名标签组");
  }

  @Test
  void shouldRejectUnknownDimension() {
    assertThatThrownBy(
            () ->
                service.create(
                    new LabelGroupCreateRequest("未知", "missing", null, List.of(member("草图")))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("标签维度不存在");
  }

  @Test
  void shouldRejectEmptyMembers() {
    assertThatThrownBy(
            () -> service.create(new LabelGroupCreateRequest("空组", "module", null, List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("标签组成员不能为空");
  }

  @Test
  void shouldRejectInvalidEnumMember() {
    assertThatThrownBy(
            () ->
                service.create(
                    new LabelGroupCreateRequest(
                        "错误优先级", "priority_level", null, List.of(member("P4")))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("标签组成员格式不符合当前维度要求");
  }

  @Test
  void shouldRejectClosureStatusEquivalentValueOnSave() {
    assertThatThrownBy(
            () ->
                service.create(
                    new LabelGroupCreateRequest(
                        "历史闭环", "closure_status", null, List.of(member("设计如此")))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("请保存规范值“需求如此”");
  }

  @Test
  void shouldAllowMissingCurrentCandidateAndMarkUnavailable() {
    mockCurrentValues("module", "草图");

    LabelGroupResponse response =
        service.create(
            new LabelGroupCreateRequest(
                "历史模块", "module", null, List.of(member("历史模块"))));

    assertThat(response.members()).singleElement().satisfies(member -> {
      assertThat(member.value()).isEqualTo("历史模块");
      assertThat(member.currentAvailable()).isFalse();
    });
  }

  @Test
  void shouldRejectDimensionChangeOnUpdate() {
    mockCurrentValues("module", "草图");
    LabelGroupResponse created =
        service.create(new LabelGroupCreateRequest("常用模块", "module", null, List.of(member("草图"))));

    assertThatThrownBy(
            () ->
                service.update(
                    created.id(),
                    new LabelGroupUpdateRequest(
                        "常用模块", "project", null, true, List.of(member("CrownCAD")))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不能修改标签维度");
  }

  @Test
  void shouldDeleteGroupAndMembers() {
    mockCurrentValues("module", "草图");
    LabelGroupResponse created =
        service.create(new LabelGroupCreateRequest("常用模块", "module", null, List.of(member("草图"))));

    service.delete(created.id());

    assertThat(repository.findById(created.id())).isEmpty();
  }

  @Test
  void shouldListMemberCountAndChineseDimensionName() {
    mockCurrentValues("module", "草图");
    service.create(new LabelGroupCreateRequest("常用模块", "module", null, List.of(member("草图"))));

    List<LabelGroupResponse> groups = service.list("module", "常用", true);

    assertThat(groups).singleElement().satisfies(group -> {
      assertThat(group.dimensionName()).isEqualTo("模块");
      assertThat(group.memberCount()).isEqualTo(1);
    });
  }

  @Test
  void shouldRejectMoreThanTwoHundredMembers() {
    List<LabelGroupMemberRequest> members = new ArrayList<>();
    for (int index = 0; index < 201; index++) {
      members.add(member("模块" + index));
    }

    assertThatThrownBy(
            () -> service.create(new LabelGroupCreateRequest("大组", "module", null, members)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("超过 200 个");
  }

  private void mockCurrentValues(String dimensionKey, String... values) {
    when(labelValueQueryService.listValues(eq(dimensionKey), isNull(), isNull(), isNull(), eq(1), anyInt()))
        .thenReturn(
            new LabelValuePageResponse(
                java.util.Arrays.stream(values)
                    .map(value -> new LabelValueResponse(value, value, LabelValueKind.STRING_LITERAL, "FACT", 0L))
                    .toList(),
                values.length,
                1,
                200));
  }

  private LabelGroupMemberRequest member(String value) {
    return new LabelGroupMemberRequest(value, value);
  }

  private static final class InMemoryLabelGroupRepository implements LabelGroupRepository {
    private final Map<Long, LabelGroupRecord> groups = new LinkedHashMap<>();
    private long nextId = 1L;
    private long nextMemberId = 1L;

    @Override
    public LabelGroupRecord createGroup(
        String name, String dimensionKey, String groupType, String description, String username) {
      Long id = nextId++;
      LabelGroupRecord group =
          new LabelGroupRecord(
              id,
              name,
              dimensionKey,
              groupType,
              description,
              true,
              username,
              OffsetDateTime.now(),
              username,
              OffsetDateTime.now(),
              List.of());
      groups.put(id, group);
      return group;
    }

    @Override
    public void replaceMembers(
        Long groupId, String dimensionKey, List<LabelGroupMemberRecord> members) {
      LabelGroupRecord group = groups.get(groupId);
      List<LabelGroupMemberRecord> saved =
          members.stream()
              .map(member -> new LabelGroupMemberRecord(
                  nextMemberId++,
                  groupId,
                  dimensionKey,
                  member.memberValue(),
                  member.displayName(),
                  member.sortOrder()))
              .toList();
      groups.put(groupId, withMembers(group, saved));
    }

    @Override
    public Optional<LabelGroupRecord> findById(Long groupId) {
      return Optional.ofNullable(groups.get(groupId));
    }

    @Override
    public List<LabelGroupRecord> list(String dimensionKey, String keyword, Boolean enabled) {
      return groups.values().stream()
          .filter(group -> dimensionKey == null || dimensionKey.equals(group.dimensionKey()))
          .filter(group -> keyword == null || group.name().contains(keyword))
          .filter(group -> enabled == null || enabled == group.enabled())
          .toList();
    }

    @Override
    public boolean existsByDimensionAndName(String dimensionKey, String name, Long excludeId) {
      return groups.values().stream()
          .anyMatch(group -> group.dimensionKey().equals(dimensionKey)
              && group.name().equals(name)
              && (excludeId == null || !group.id().equals(excludeId)));
    }

    @Override
    public void updateGroup(
        Long groupId, String name, String description, boolean enabled, String username) {
      LabelGroupRecord group = groups.get(groupId);
      groups.put(
          groupId,
          new LabelGroupRecord(
              group.id(),
              name,
              group.dimensionKey(),
              group.groupType(),
              description,
              enabled,
              group.createdBy(),
              group.createdAt(),
              username,
              OffsetDateTime.now(),
              group.members()));
    }

    @Override
    public void deleteById(Long groupId) {
      groups.remove(groupId);
    }

    private LabelGroupRecord withMembers(
        LabelGroupRecord group, List<LabelGroupMemberRecord> members) {
      return new LabelGroupRecord(
          group.id(),
          group.name(),
          group.dimensionKey(),
          group.groupType(),
          group.description(),
          group.enabled(),
          group.createdBy(),
          group.createdAt(),
          group.updatedBy(),
          group.updatedAt(),
          members);
    }
  }
}
