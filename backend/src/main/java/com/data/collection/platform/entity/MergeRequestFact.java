package com.data.collection.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName("merge_request_fact")
public class MergeRequestFact {
  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("source_system")
  private String sourceSystem;

  @TableField("source_instance")
  private String sourceInstance;

  @TableField("ingest_channel")
  private String ingestChannel;

  @TableField("source_summary")
  private String sourceSummary;

  @TableField("raw_payload")
  private String rawPayload;

  @TableField("project_id")
  private Long projectId;

  @TableField("project_name")
  private String projectName;

  @TableField("repository_name")
  private String repositoryName;

  @TableField("merge_request_id")
  private Long mergeRequestId;

  @TableField("merge_request_iid")
  private Long mergeRequestIid;

  private String title;

  @TableField("merge_request_state")
  private String mergeRequestState;

  @TableField("target_branch")
  private String targetBranch;

  @TableField("source_branch")
  private String sourceBranch;

  @TableField("author_name")
  private String authorName;

  @TableField("merge_user_name")
  private String mergeUserName;

  @TableField("owner_name")
  private String ownerName;

  @TableField("reviewer_names")
  private String reviewerNames;

  @TableField("assignee_names")
  private String assigneeNames;

  @TableField("module_name")
  private String moduleName;

  @TableField("label_names")
  private String labelNames;

  @TableField("created_at_source")
  private LocalDateTime createdAtSource;

  @TableField("updated_at_source")
  private LocalDateTime updatedAtSource;

  @TableField("ods_updated_at")
  private LocalDateTime odsUpdatedAt;

  @TableField("merged_at_source")
  private LocalDateTime mergedAtSource;

  @TableField("review_status")
  private String reviewStatus;

  @TableField("review_duration_minutes")
  private Integer reviewDurationMinutes;

  @TableField("comment_rate")
  private BigDecimal commentRate;

  @TableField("comment_rate_source")
  private String commentRateSource;

  @TableField("defect_count")
  private Integer defectCount;

  @TableField("defect_count_source")
  private String defectCountSource;

  @TableField("scan_status")
  private String scanStatus;

  @TableField("scan_bug_count")
  private Integer scanBugCount;

  @TableField("annotation_rate_result")
  private String annotationRateResult;

  @TableField("bug_count_result")
  private String bugCountResult;

  @TableField("added_lines")
  private Integer addedLines;

  private Boolean deleted;

  @TableField("fact_refreshed_at")
  private LocalDateTime factRefreshedAt;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
