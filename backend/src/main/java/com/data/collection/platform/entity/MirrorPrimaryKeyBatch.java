package com.data.collection.platform.entity;

import java.util.List;
import java.util.Map;

public record MirrorPrimaryKeyBatch(
    List<Map<String, Object>> keys,
    String nextCursor) {
}
