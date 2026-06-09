package com.data.collection.platform.controller;

import com.data.collection.platform.service.CreateSegmentRequest;
import com.data.collection.platform.service.CreateSegmentFilterPresetRequest;
import com.data.collection.platform.service.ScopeCompositionMode;
import com.data.collection.platform.service.SegmentComputeService;
import com.data.collection.platform.service.SegmentDefinition;
import com.data.collection.platform.service.SegmentDefinitionStatus;
import com.data.collection.platform.service.SegmentExecutionPlan;
import com.data.collection.platform.service.SegmentFilterPreset;
import com.data.collection.platform.service.SegmentFilterPresetApplyResult;
import com.data.collection.platform.service.SegmentFilterPresetService;
import com.data.collection.platform.service.SegmentMemberSource;
import com.data.collection.platform.service.SegmentType;
import com.data.collection.platform.service.SemanticScopePlan;
import com.data.collection.platform.service.SemanticTagGroupCatalog;
import com.data.collection.platform.service.SemanticTagGroupService;
import com.data.collection.platform.service.UpdateSegmentFilterPresetRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SegmentManagementController {
  private final SemanticTagGroupService semanticTagGroupService;
  private final SegmentComputeService segmentComputeService;
  private final SegmentFilterPresetService segmentFilterPresetService;

  public SegmentManagementController(
      SemanticTagGroupService semanticTagGroupService,
      SegmentComputeService segmentComputeService,
      SegmentFilterPresetService segmentFilterPresetService) {
    this.semanticTagGroupService = semanticTagGroupService;
    this.segmentComputeService = segmentComputeService;
    this.segmentFilterPresetService = segmentFilterPresetService;
  }

  @GetMapping("/api/semantic-tag-groups/static")
  public SemanticTagGroupCatalog listStaticSemanticGroups(
      @RequestParam(defaultValue = "issue") String entityType) {
    return semanticTagGroupService.listStaticGroups(entityType);
  }

  @GetMapping("/api/segments")
  public List<SegmentDefinitionResponse> listSegments() {
    return segmentComputeService.listDefinitions().stream()
        .map(SegmentDefinitionResponse::from)
        .toList();
  }

  @PostMapping("/api/segments")
  @ResponseStatus(HttpStatus.CREATED)
  public SegmentDefinitionResponse createSegment(@Valid @RequestBody CreateSegmentWebRequest request) {
    return SegmentDefinitionResponse.from(segmentComputeService.createDefinition(request.toService()));
  }

  @PatchMapping("/api/segments/{id}/disabled")
  public SegmentDefinitionResponse disableSegment(
      @PathVariable long id, @Valid @RequestBody DisableSegmentWebRequest request) {
    return SegmentDefinitionResponse.from(segmentComputeService.disable(id, request.operator()));
  }

  @GetMapping("/api/segment-filter-presets")
  public List<SegmentFilterPreset> listFilterPresets(
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) String scenarioKey,
      @RequestParam(required = false) String ownerUserId) {
    return segmentFilterPresetService.list(entityType, scenarioKey, ownerUserId);
  }

  @PostMapping("/api/segment-filter-presets")
  @ResponseStatus(HttpStatus.CREATED)
  public SegmentFilterPreset createFilterPreset(
      @Valid @RequestBody CreateSegmentFilterPresetWebRequest request) {
    return segmentFilterPresetService.create(request.toService());
  }

  @PostMapping("/api/segment-filter-presets/{id}/apply")
  public SegmentFilterPresetApplyResult applyFilterPreset(
      @PathVariable long id, @RequestParam(required = false) String currentTagSchemaHash) {
    return segmentFilterPresetService.apply(id, currentTagSchemaHash);
  }

  @PatchMapping("/api/segment-filter-presets/{id}")
  public SegmentFilterPreset updateFilterPreset(
      @PathVariable long id, @Valid @RequestBody UpdateSegmentFilterPresetWebRequest request) {
    return segmentFilterPresetService.update(id, request.toService());
  }

  @DeleteMapping("/api/segment-filter-presets/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteFilterPreset(@PathVariable long id) {
    segmentFilterPresetService.delete(id);
  }

  public record CreateSegmentWebRequest(
      @NotBlank String name,
      @NotNull SegmentType segmentType,
      @NotBlank String entityType,
      @NotBlank String scenarioKey,
      @NotEmpty List<@NotBlank String> scopeChain,
      @NotNull ScopeCompositionMode compositionMode,
      @NotBlank String ruleJson,
      @NotNull SegmentMemberSource memberSource,
      String tagSchemaHash,
      @NotBlank String createdBy) {
    CreateSegmentRequest toService() {
      return new CreateSegmentRequest(
          name,
          segmentType,
          entityType,
          scenarioKey,
          scopeChain,
          compositionMode,
          ruleJson,
          memberSource,
          tagSchemaHash,
          createdBy);
    }
  }

  public record DisableSegmentWebRequest(@NotBlank String operator) {}

  public record CreateSegmentFilterPresetWebRequest(
      @NotBlank String presetName,
      @NotBlank String ownerUserId,
      @NotBlank String visibility,
      @NotBlank String entityType,
      @NotBlank String scenarioKey,
      @NotBlank String scopeKey,
      @NotBlank String dslJson,
      @NotBlank String tagSchemaHash,
      String sourceDataWatermarkAtSave) {
    CreateSegmentFilterPresetRequest toService() {
      return new CreateSegmentFilterPresetRequest(
          presetName,
          ownerUserId,
          visibility,
          entityType,
          scenarioKey,
          scopeKey,
          dslJson,
          tagSchemaHash,
          sourceDataWatermarkAtSave);
    }
  }

  public record UpdateSegmentFilterPresetWebRequest(
      String presetName,
      String visibility,
      String dslJson,
      String tagSchemaHash,
      String sourceDataWatermarkAtSave) {
    UpdateSegmentFilterPresetRequest toService() {
      return new UpdateSegmentFilterPresetRequest(
          presetName,
          visibility,
          dslJson,
          tagSchemaHash,
          sourceDataWatermarkAtSave);
    }
  }

  public record SegmentDefinitionResponse(
      long id,
      String name,
      SegmentType segmentType,
      String entityType,
      String scenarioKey,
      SemanticScopePlan scopePlan,
      SegmentMemberSource memberSource,
      String tagSchemaHash,
      SegmentDefinitionStatus status,
      String createdBy,
      SegmentExecutionPlan executionPlan,
      int previewMemberCount) {
    static SegmentDefinitionResponse from(SegmentDefinition definition) {
      return new SegmentDefinitionResponse(
          definition.id(),
          definition.name(),
          definition.segmentType(),
          definition.entityType(),
          definition.scenarioKey(),
          definition.scopePlan(),
          definition.memberSource(),
          definition.tagSchemaHash(),
          definition.status(),
          definition.createdBy(),
          definition.executionPlan(),
          definition.previewMembers().size());
    }
  }
}
