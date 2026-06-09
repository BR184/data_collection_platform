package com.data.collection.platform.service;

import java.util.List;
import java.util.Optional;

public interface SegmentFilterPresetRepository {
  SegmentFilterPreset save(SegmentFilterPreset preset);

  Optional<SegmentFilterPreset> findById(long id);

  List<SegmentFilterPreset> findAll();

  void delete(long id);
}
