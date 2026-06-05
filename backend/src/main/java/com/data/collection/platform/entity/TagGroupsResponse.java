package com.data.collection.platform.entity;

import java.util.List;

public record TagGroupsResponse(
    String domain,
    String schemaHash,
    List<TagGroupResponse> groups) {
}
