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

  /**
   * 表单结构版本。
   *
   * @param recordSourceRole AUTHORITATIVE/LEGACY_SNAPSHOT/TARGET
   * @param exportable 服务端计算的导出资格；仅权威已发布/已退役且范围项目完整时为 true
   * @param sourceConfigId 该版本所属范围的代码平台来源
   * @param sourceInstance 来源实例
   * @param canonicalGitlabBaseUrl 规范化代码平台地址
   * @param projectIds 该版本可读项目集合，来自服务端范围元数据
   */
  public record VersionView(
      long id,
      String versionLabel,
      String storageKind,
      String versionStatus,
      String instanceTable,
      String recordTable,
      LocalDateTime publishedAt,
      String recordSourceRole,
      boolean exportable,
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      List<Long> projectIds) {}

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
   * @param available 当前是否可读取候选；最近刷新失败但有最后成功快照时仍为 true
   * @param status 目录状态：UNSYNCED/READY/EMPTY/FAILED/UNAVAILABLE
   * @param message 状态说明；失败时为最近错误，成功时为 null
   * @param items 当前页候选或 projectIds 精确回显结果
   * @param nextCursor 下一页游标；无更多数据时为 null
   * @param missingProjectIds 精确回显中已不在目录里的项目 ID，按请求顺序
   */
  public record ProjectCandidatePage(
      boolean available,
      String status,
      String message,
      List<ProjectOption> items,
      String nextCursor,
      List<String> missingProjectIds) {}
}
