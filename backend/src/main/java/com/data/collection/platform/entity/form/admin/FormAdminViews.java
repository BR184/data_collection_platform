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

  /** 项目候选；projectId 以十进制字符串返回，避免前端 Number 精度损失。 */
  public record ProjectOption(
      String projectId,
      String name,
      String path,
      String namespacePath,
      String fullPath) {}

  /**
   * 项目候选分页。
   *
   * @param available 该来源是否已有本地目录投影；false 表示尚未完成目录同步
   * @param items 当前页候选或 projectIds 精确回显结果
   * @param nextCursor 下一页游标；无更多数据时为 null
   * @param missingProjectIds 精确回显中已不在目录里的项目 ID，按请求顺序
   */
  public record ProjectCandidatePage(
      boolean available,
      List<ProjectOption> items,
      String nextCursor,
      List<String> missingProjectIds) {}
}
