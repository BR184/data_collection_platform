package com.data.collection.platform.bi.domain.source;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 单次编码页面请求冻结后的合并请求、提交和代码走查来源快照。 */
public record BiCodingSource(
    String sourceVersion,
    String snapshotId,
    Granularity granularity,
    List<MergeRequestRecord> mergeRequests,
    List<CommitRecord> commits,
    List<CodeReviewRecord> codeReviews,
    boolean mergeRequestIdsAvailable,
    boolean codeScaleAvailable,
    boolean commitDetailsAvailable,
    boolean reviewMetricsAvailable,
    boolean scanDataAvailable,
    boolean commentRateDataAvailable) {
  public BiCodingSource {
    granularity = granularity == null ? Granularity.DAY : granularity;
    mergeRequests = copy(mergeRequests);
    commits = copy(commits);
    codeReviews = copy(codeReviews);
  }

  private static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  /** 编码趋势支持的确定性时间桶。 */
  public enum Granularity {
    DAY,
    WEEK
  }

  /** 合并请求在当前冻结读源中的稳定身份。 */
  public sealed interface MergeRequestIdentity
      permits CompatibilityMergeRequestIdentity, FormalMergeRequestIdentity {}

  /** 兼容态以来源实例和 MR IID 共同标识合并请求。 */
  public record CompatibilityMergeRequestIdentity(
      String sourceInstance,
      long mergeRequestIid) implements MergeRequestIdentity {
    public CompatibilityMergeRequestIdentity {
      sourceInstance = Objects.requireNonNull(sourceInstance, "sourceInstance")
          .trim().toLowerCase(Locale.ROOT);
      if (sourceInstance.isEmpty() || mergeRequestIid <= 0) {
        throw new IllegalArgumentException("兼容态合并请求身份必须包含来源实例和正数 IID");
      }
    }
  }

  /** 正式态以项目 ID 和 MR ID 共同标识合并请求。 */
  public record FormalMergeRequestIdentity(
      long projectId,
      long mergeRequestId) implements MergeRequestIdentity {
    public FormalMergeRequestIdentity {
      if (projectId <= 0 || mergeRequestId <= 0) {
        throw new IllegalArgumentException("正式态合并请求身份必须包含正数项目 ID 和 MR ID");
      }
    }
  }

  /** 去重代码规模、合并请求和贡献维度所需的合并请求事实。 */
  public record MergeRequestRecord(
      MergeRequestIdentity mergeRequestIdentity,
      LocalDate mergedOn,
      String repositoryId,
      String repositoryName,
      BiSourceDimension author,
      BiSourceDimension module,
      Long addedLines) {}

  /** 提交频次所需的稳定提交事实。 */
  public record CommitRecord(String commitId, LocalDate committedOn) {}

  /** 单次人工走查、静态扫描和注释率所需的基础事实。 */
  public record CodeReviewRecord(
      long codeReviewId,
      MergeRequestIdentity mergeRequestIdentity,
      LocalDate reviewedOn,
      BiSourceDimension module,
      Long reviewedLines,
      BigDecimal reviewDurationMinutes,
      Long effectiveProblemCount,
      Long codeSpecificationCount,
      Long codeLogicSpecificationCount,
      Long performanceSpecificationCount,
      Long designSpecificationCount,
      Long otherSpecificationCount,
      String scanStatus,
      Long scanBugCount,
      BigDecimal commentRate,
      String commentRateSource) {}
}
