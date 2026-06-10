package com.data.collection.platform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SegmentFilterPresetService {
  private final SegmentFilterPresetRepository repository;

  public SegmentFilterPresetService(SegmentFilterPresetRepository repository) {
    this.repository = repository;
  }

  public synchronized SegmentFilterPreset create(CreateSegmentFilterPresetRequest request) {
    LocalDateTime now = LocalDateTime.now();
    SegmentFilterPreset preset =
        new SegmentFilterPreset(
            0,
            request.presetName().trim(),
            request.ownerUserId().trim(),
            normalizeVisibility(request.visibility()),
            request.entityType().trim(),
            request.scenarioKey().trim(),
            request.scopeKey().trim(),
            request.dslJson(),
            sha256(request.dslJson()),
            request.tagSchemaHash(),
            request.sourceDataWatermarkAtSave(),
            null,
            now,
            now);
    return repository.save(preset);
  }

  public synchronized List<SegmentFilterPreset> list(
      String entityType, String scenarioKey, String ownerUserId) {
    return repository.findAll().stream()
        .filter(preset -> entityType == null || entityType.isBlank() || preset.entityType().equals(entityType))
        .filter(preset -> scenarioKey == null || scenarioKey.isBlank() || preset.scenarioKey().equals(scenarioKey))
        .filter(preset -> canRead(preset, ownerUserId))
        .sorted(Comparator.comparing(SegmentFilterPreset::updatedAt).reversed())
        .toList();
  }

  public synchronized SegmentFilterPresetApplyResult apply(long id, String currentTagSchemaHash) {
    SegmentFilterPreset preset = get(id);
    SegmentFilterPreset touched =
        new SegmentFilterPreset(
            preset.id(),
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
            LocalDateTime.now(),
            preset.createdAt(),
            preset.updatedAt());
    SegmentFilterPreset saved = repository.save(touched);
    boolean compatible =
        currentTagSchemaHash == null
            || currentTagSchemaHash.isBlank()
            || currentTagSchemaHash.equals(preset.tagSchemaHash());
    return new SegmentFilterPresetApplyResult(
        saved,
        compatible,
        compatible ? "SCHEMA_COMPATIBLE" : "SCHEMA_REVIEW_REQUIRED");
  }

  public synchronized SegmentFilterPreset update(long id, UpdateSegmentFilterPresetRequest request) {
    SegmentFilterPreset current = get(id);
    SegmentFilterPreset updated =
        new SegmentFilterPreset(
            current.id(),
            normalizeOptionalText(request.presetName(), current.presetName()),
            current.ownerUserId(),
            normalizeOptionalVisibility(request.visibility(), current.visibility()),
            normalizeOptionalText(request.entityType(), current.entityType()),
            normalizeOptionalText(request.scenarioKey(), current.scenarioKey()),
            normalizeOptionalText(request.scopeKey(), current.scopeKey()),
            normalizeOptionalText(request.dslJson(), current.dslJson()),
            sha256(normalizeOptionalText(request.dslJson(), current.dslJson())),
            normalizeOptionalText(request.tagSchemaHash(), current.tagSchemaHash()),
            normalizeOptionalText(
                request.sourceDataWatermarkAtSave(), current.sourceDataWatermarkAtSave()),
            current.lastUsedAt(),
            current.createdAt(),
            LocalDateTime.now());
    return repository.save(updated);
  }

  public synchronized void delete(long id) {
    get(id);
    repository.delete(id);
  }

  private SegmentFilterPreset get(long id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown segment filter preset: " + id));
  }

  private boolean canRead(SegmentFilterPreset preset, String ownerUserId) {
    if (!"PRIVATE".equals(preset.visibility())) {
      return true;
    }
    return ownerUserId != null && preset.ownerUserId().equals(ownerUserId);
  }

  private String normalizeVisibility(String visibility) {
    if (visibility == null || visibility.isBlank()) {
      return "PRIVATE";
    }
    String normalized = visibility.trim().toUpperCase();
    return switch (normalized) {
      case "TEAM", "PUBLIC" -> normalized;
      default -> "PRIVATE";
    };
  }

  private String normalizeOptionalVisibility(String visibility, String fallback) {
    return visibility == null || visibility.isBlank() ? fallback : normalizeVisibility(visibility);
  }

  private String normalizeOptionalText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }
}
