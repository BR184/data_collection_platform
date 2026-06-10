package com.data.collection.platform.service.labelgroup;

record LabelGroupMemberRecord(
    Long id,
    Long groupId,
    String dimensionKey,
    String memberValue,
    String displayName,
    int sortOrder) {}
