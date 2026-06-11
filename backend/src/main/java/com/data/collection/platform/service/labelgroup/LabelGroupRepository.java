package com.data.collection.platform.service.labelgroup;

import java.util.List;
import java.util.Optional;

public interface LabelGroupRepository {
  LabelGroupRecord createGroup(
      String name, String valueType, String groupType, String description, String username);

  void replaceMembers(Long groupId, List<LabelGroupMemberRecord> members);

  void replaceReferences(Long groupId, List<Long> childGroupIds);

  Optional<LabelGroupRecord> findById(Long groupId);

  List<LabelGroupRecord> list(String valueType, String keyword, Boolean enabled);

  boolean existsByName(String name, Long excludeId);

  void updateGroup(
      Long groupId,
      String name,
      String valueType,
      String groupType,
      String description,
      boolean enabled,
      String username);

  void deleteById(Long groupId);
}
