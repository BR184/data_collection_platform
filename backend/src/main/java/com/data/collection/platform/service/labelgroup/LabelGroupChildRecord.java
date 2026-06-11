package com.data.collection.platform.service.labelgroup;

record LabelGroupChildRecord(
    Long id,
    Long parentGroupId,
    Long childGroupId,
    String childName,
    String childGroupType,
    String childValueType,
    boolean childEnabled,
    int sortOrder) {}
