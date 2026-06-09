package com.data.collection.platform.service;

import org.springframework.stereotype.Service;

@Service
public class SegmentSchemaCompatibilityChecker {
  public CompatibilityResult check(
      SegmentDefinition segment, String oldSchemaHash, String newSchemaHash) {
    if (oldSchemaHash == null || oldSchemaHash.equals(newSchemaHash)) {
      return CompatibilityResult.ok();
    }
    if (segment.segmentType() == SegmentType.STATIC) {
      return CompatibilityResult.ok();
    }
    return CompatibilityResult.incompatible("dynamic segment requires revalidation");
  }
}
