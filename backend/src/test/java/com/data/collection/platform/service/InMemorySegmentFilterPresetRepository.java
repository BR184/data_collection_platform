package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class InMemorySegmentFilterPresetRepository implements SegmentFilterPresetRepository {
  private final AtomicLong nextId = new AtomicLong(1);
  private final Map<Long, SegmentFilterPreset> presets = new LinkedHashMap<>();

  @Override
  public synchronized SegmentFilterPreset save(SegmentFilterPreset preset) {
    long id = preset.id() > 0 ? preset.id() : nextId.getAndIncrement();
    LocalDateTime createdAt = preset.createdAt() == null ? LocalDateTime.now() : preset.createdAt();
    LocalDateTime updatedAt = preset.updatedAt() == null ? createdAt : preset.updatedAt();
    SegmentFilterPreset saved =
        new SegmentFilterPreset(
            id,
            preset.presetName(),
            preset.ownerUserId(),
            preset.visibility(),
            preset.entityType(),
            preset.scenarioKey(),
            preset.scopeKey(),
            preset.dslJson(),
            preset.dslHash(),
            preset.tagSchemaHash(),
            preset.sourceDataWatermarkAtSave(),
            preset.lastUsedAt(),
            createdAt,
            updatedAt);
    presets.put(id, saved);
    return saved;
  }

  @Override
  public synchronized Optional<SegmentFilterPreset> findById(long id) {
    return Optional.ofNullable(presets.get(id));
  }

  @Override
  public synchronized List<SegmentFilterPreset> findAll() {
    return List.copyOf(presets.values());
  }

  @Override
  public synchronized void delete(long id) {
    presets.remove(id);
  }
}
