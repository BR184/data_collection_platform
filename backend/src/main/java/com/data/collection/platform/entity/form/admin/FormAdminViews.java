package com.data.collection.platform.entity.form.admin;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/** 表单管理端只读视图；字段名与前端管理页直接对应。 */
public final class FormAdminViews {

  private FormAdminViews() {}

  public record TemplateSummary(
      String templateKey,
      String displayName,
      String status,
      List<ScopeSummary> scopes) {}

  public record ScopeSummary(
      long id,
      String templateKey,
      String scopeKey,
      String status,
      String resourceType,
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      List<Long> projectIds,
      Long versionProjectId,
      String bindingHash,
      long bindingRevision,
      Long currentVersionId,
      String currentVersionLabel,
      String currentVersionStatus,
      Long draftRevision,
      boolean writeFenced,
      long activePublications,
      LocalDateTime updatedAt) {}

  public record ScopeDetail(
      ScopeSummary scope,
      DraftView draft,
      List<VersionView> versions,
      PreviewView preview,
      PublicationView latestPublication,
      long recordCount,
      long instanceCount) {}

  public record BindingView(
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      List<Long> projectIds,
      Long versionProjectId,
      String bindingHash,
      boolean bound) {}

  public record DraftView(
      long revision,
      JsonNode fieldSpec,
      BindingView binding,
      String versionLabel,
      JsonNode versionEvidence,
      JsonNode migrationSpec,
      String updatedBy,
      LocalDateTime updatedAt) {}

  public record VersionView(
      long id,
      String versionLabel,
      String storageKind,
      String versionStatus,
      String instanceTable,
      String recordTable,
      LocalDateTime publishedAt) {}

  public record PreviewView(
      long id,
      long draftRevision,
      long targetVersionId,
      String previewHash,
      JsonNode diff,
      JsonNode ddlSummary,
      String ddlHash,
      JsonNode impact,
      List<String> blockingErrors,
      LocalDateTime createdAt) {}

  public record PublicationView(
      long id,
      long scopeId,
      String requestKey,
      String publicationKind,
      String stage,
      int attemptNo,
      long recoveryCycle,
      Long expectedCurrentVersionId,
      Long fromVersionId,
      long toVersionId,
      String lastError,
      JsonNode selectionCounts,
      JsonNode verificationSummary,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime publishedAt) {}

  public record BindingOption(
      long sourceConfigId,
      String name,
      String sourceInstance,
      String canonicalGitlabBaseUrl) {}

  public record MilestoneView(long id, String title) {}

  public record MilestoneAvailability(boolean available, List<MilestoneView> items) {}
}
