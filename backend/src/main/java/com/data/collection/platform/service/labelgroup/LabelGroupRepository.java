package com.data.collection.platform.service.labelgroup;

import java.util.List;
import java.util.Optional;

public interface LabelGroupRepository {
  LabelGroupRecord createGroup(
      String name, String dimensionKey, String groupType, String description, String username);

  void replaceMembers(Long groupId, String dimensionKey, List<LabelGroupMemberRecord> members);

  Optional<LabelGroupRecord> findById(Long groupId);

  List<LabelGroupRecord> list(String dimensionKey, String keyword, Boolean enabled);

  boolean existsByDimensionAndName(String dimensionKey, String name, Long excludeId);

  void updateGroup(
      Long groupId, String name, String description, boolean enabled, String username);

  void deleteById(Long groupId);
}
