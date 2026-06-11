package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupCreateRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupUpdateRequest;
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
  private LabelGroupService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryLabelGroupRepository();
    service = new LabelGroupService(repository);
  }

  @Test
  void shouldCreateStaticStringGroupFromPlainValues() {
    LabelGroupResponse response =
        service.create(
            new LabelGroupCreateRequest(
                "核心人员",
                "STATIC",
                "常用人员字符串集合",
                List.of(member("张三"), member("李四")),
                List.of()));

    assertThat(response.id()).isEqualTo(1L);
    assertThat(response.valueType()).isEqualTo("STRING");
    assertThat(response.groupType()).isEqualTo("STATIC");
    assertThat(response.memberCount()).isEqualTo(2);
    assertThat(response.members()).extracting(member -> member.value()).containsExactly("张三", "李四");
    assertThat(response.expandedPreview()).extracting(member -> member.value()).containsExactly("张三", "李四");
  }

  @Test
  void shouldRejectDuplicateNameGlobally() {
    service.create(request("核心人员", "STATIC", List.of(member("张三")), List.of()));

    assertThatThrownBy(
            () -> service.create(request("核心人员", "STATIC", List.of(member("李四")), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已存在同名标签组");
  }

  @Test
  void shouldRejectMixedValueTypes() {
    assertThatThrownBy(
            () -> service.create(request("混合组", "STATIC", List.of(member("张三"), member("2026-06-10")), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("标签组成员值类型不一致");
  }

  @Test
  void shouldInferNumberValueType() {
    LabelGroupResponse response =
        service.create(request("数字组", "STATIC", List.of(member("1"), member("2.5")), List.of()));

    assertThat(response.valueType()).isEqualTo("NUMBER");
  }

  @Test
  void shouldAllowStaticGroupToReferenceStaticGroupWithSameValueType() {
    LabelGroupResponse child =
        service.create(request("基础人员", "STATIC", List.of(member("张三"), member("李四")), List.of()));

    LabelGroupResponse parent =
        service.create(request("核心人员", "STATIC", List.of(member("王五")), List.of(child.id())));

    assertThat(parent.valueType()).isEqualTo("STRING");
    assertThat(parent.childGroups()).singleElement().satisfies(group -> {
      assertThat(group.id()).isEqualTo(child.id());
      assertThat(group.groupType()).isEqualTo("STATIC");
    });
    assertThat(parent.expandedPreview())
        .extracting(member -> member.value())
        .containsExactly("王五", "张三", "李四");
  }

  @Test
  void shouldRejectStaticGroupReferencingDynamicChild() {
    LabelGroupResponse dynamic =
        service.create(request("动态人员", "DYNAMIC", List.of(member("张三")), List.of()));

    assertThatThrownBy(
            () -> service.create(request("静态父组", "STATIC", List.of(member("李四")), List.of(dynamic.id()))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("静态标签组只能嵌套静态标签组");
  }

  @Test
  void shouldCreateCompositeGroupFromChildrenWithUnionDedupe() {
    LabelGroupResponse staticChild =
        service.create(request("静态人员", "STATIC", List.of(member("张三"), member("李四")), List.of()));
    LabelGroupResponse dynamicChild =
        service.create(request("动态人员", "DYNAMIC", List.of(member("李四"), member("王五")), List.of()));

    LabelGroupResponse composite =
        service.create(request("重点关注人员", "COMPOSITE", List.of(), List.of(staticChild.id(), dynamicChild.id())));

    assertThat(composite.valueType()).isEqualTo("STRING");
    assertThat(composite.groupType()).isEqualTo("COMPOSITE");
    assertThat(composite.expandedPreview())
        .extracting(member -> member.value())
        .containsExactly("张三", "李四", "王五");
  }

  @Test
  void shouldRejectCompositeWithDirectMembers() {
    assertThatThrownBy(
            () -> service.create(request("错误组合", "COMPOSITE", List.of(member("张三")), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("组合标签组只能选择子标签组");
  }

  @Test
  void shouldRejectReferenceCycleOnUpdate() {
    LabelGroupResponse groupA =
        service.create(request("A", "STATIC", List.of(member("张三")), List.of()));
    LabelGroupResponse groupB =
        service.create(request("B", "STATIC", List.of(member("李四")), List.of(groupA.id())));

    assertThatThrownBy(
            () ->
                service.update(
                    groupA.id(),
                    new LabelGroupUpdateRequest(
                        "A",
                        "STATIC",
                        null,
                        true,
                        List.of(member("张三")),
                        List.of(groupB.id()))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("标签组引用存在循环");
  }

  @Test
  void shouldListByValueType() {
    service.create(request("人员", "STATIC", List.of(member("张三")), List.of()));
    service.create(request("日期", "STATIC", List.of(member("2026-06-10")), List.of()));

    List<LabelGroupResponse> groups = service.list("STRING", null, true);

    assertThat(groups).extracting(LabelGroupResponse::name).containsExactly("人员");
  }

  @Test
  void shouldRejectMoreThanTwoHundredMembers() {
    List<LabelGroupMemberRequest> members = new ArrayList<>();
    for (int index = 0; index < 201; index++) {
      members.add(member("成员" + index));
    }

    assertThatThrownBy(() -> service.create(request("大组", "STATIC", members, List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("超过 200 个");
  }

  @Test
  void shouldDeleteGroupAndMembers() {
    LabelGroupResponse created =
        service.create(request("核心人员", "STATIC", List.of(member("张三")), List.of()));

    service.delete(created.id());

    assertThat(repository.findById(created.id())).isEmpty();
  }

  private LabelGroupCreateRequest request(
      String name, String groupType, List<LabelGroupMemberRequest> members, List<Long> childGroupIds) {
    return new LabelGroupCreateRequest(name, groupType, null, members, childGroupIds);
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
        String name, String valueType, String groupType, String description, String username) {
      Long id = nextId++;
      LabelGroupRecord group =
          new LabelGroupRecord(
              id,
              name,
              valueType,
              groupType,
              description,
              true,
              username,
              OffsetDateTime.now(),
              username,
              OffsetDateTime.now(),
              List.of(),
              List.of());
      groups.put(id, group);
      return group;
    }

    @Override
    public void replaceMembers(Long groupId, List<LabelGroupMemberRecord> members) {
      LabelGroupRecord group = groups.get(groupId);
      List<LabelGroupMemberRecord> saved =
          members.stream()
              .map(
                  member ->
                      new LabelGroupMemberRecord(
                          nextMemberId++,
                          groupId,
                          member.memberValue(),
                          member.displayName(),
                          member.sortOrder()))
              .toList();
      groups.put(groupId, withMembers(group, saved));
    }

    @Override
    public void replaceReferences(Long groupId, List<Long> childGroupIds) {
      LabelGroupRecord group = groups.get(groupId);
      List<LabelGroupChildRecord> references =
          childGroupIds.stream()
              .map(childGroupId -> toChildRecord(groupId, groups.get(childGroupId)))
              .toList();
      groups.put(groupId, withReferences(group, references));
    }

    @Override
    public Optional<LabelGroupRecord> findById(Long groupId) {
      return Optional.ofNullable(groups.get(groupId));
    }

    @Override
    public List<LabelGroupRecord> list(String valueType, String keyword, Boolean enabled) {
      return groups.values().stream()
          .filter(group -> valueType == null || valueType.equals(group.valueType()))
          .filter(group -> keyword == null || group.name().contains(keyword))
          .filter(group -> enabled == null || enabled == group.enabled())
          .toList();
    }

    @Override
    public boolean existsByName(String name, Long excludeId) {
      return groups.values().stream()
          .anyMatch(group -> group.name().equals(name) && (excludeId == null || !group.id().equals(excludeId)));
    }

    @Override
    public void updateGroup(
        Long groupId,
        String name,
        String valueType,
        String groupType,
        String description,
        boolean enabled,
        String username) {
      LabelGroupRecord group = groups.get(groupId);
      groups.put(
          groupId,
          new LabelGroupRecord(
              group.id(),
              name,
              valueType,
              groupType,
              description,
              enabled,
              group.createdBy(),
              group.createdAt(),
              username,
              OffsetDateTime.now(),
              group.members(),
              group.childGroups()));
    }

    @Override
    public void deleteById(Long groupId) {
      groups.remove(groupId);
    }

    private LabelGroupChildRecord toChildRecord(Long parentGroupId, LabelGroupRecord child) {
      return new LabelGroupChildRecord(
          null,
          parentGroupId,
          child.id(),
          child.name(),
          child.groupType(),
          child.valueType(),
          child.enabled(),
          0);
    }

    private LabelGroupRecord withMembers(
        LabelGroupRecord group, List<LabelGroupMemberRecord> members) {
      return new LabelGroupRecord(
          group.id(),
          group.name(),
          group.valueType(),
          group.groupType(),
          group.description(),
          group.enabled(),
          group.createdBy(),
          group.createdAt(),
          group.updatedBy(),
          group.updatedAt(),
          members,
          group.childGroups());
    }

    private LabelGroupRecord withReferences(
        LabelGroupRecord group, List<LabelGroupChildRecord> childGroups) {
      return new LabelGroupRecord(
          group.id(),
          group.name(),
          group.valueType(),
          group.groupType(),
          group.description(),
          group.enabled(),
          group.createdBy(),
          group.createdAt(),
          group.updatedBy(),
          group.updatedAt(),
          group.members(),
          childGroups);
    }
  }
}
