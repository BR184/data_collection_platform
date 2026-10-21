package com.data.collection.platform.service.labelgroup;

import java.util.List;
import java.util.Optional;

public interface LabelGroupRepository {
  /** 读取发布时的冻结定义；返回对象禁止写入当前标签目录。 */
  LabelGroupRepository forRead(com.data.collection.platform.entity.read.ReadSelection selection);
  LabelGroupRecord createGroup(
      String name,
      String valueType,
      String groupType,
      String applicableScope,
      String sourceFieldKey,
      String description,
      String username);

  void replaceMembers(Long groupId, List<LabelGroupMemberRecord> members);

  void replaceReferences(Long groupId, List<Long> childGroupIds);

  void replaceDynamicRule(Long groupId, LabelGroupDynamicRuleRecord rule);

  Optional<LabelGroupRecord> findById(Long groupId);

  List<LabelGroupRecord> list(String valueType, String keyword, Boolean enabled);

  boolean existsByName(String name, Long excludeId);

  void updateGroup(
      Long groupId,
      String name,
      String valueType,
      String groupType,
      String applicableScope,
      String sourceFieldKey,
      String description,
      boolean enabled,
      String username);

  void deleteById(Long groupId);
}
