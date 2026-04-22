package com.data.collection.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName("issue_fact")
public class IssueFact {
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

  @TableField("issue_id")
  private Long issueId;

  @TableField("issue_iid")
  private Long issueIid;

  private String title;

  @TableField("issue_state")
  private String issueState;

  @TableField("issue_type")
  private String issueType;

  @TableField("milestone_title")
  private String milestoneTitle;

  @TableField("author_name")
  private String authorName;

  @TableField("assignee_name")
  private String assigneeName;

  @TableField("created_at_source")
  private LocalDateTime createdAtSource;

  @TableField("updated_at_source")
  private LocalDateTime updatedAtSource;

  @TableField("ods_updated_at")
  private LocalDateTime odsUpdatedAt;

  @TableField("closed_at_source")
  private LocalDateTime closedAtSource;

  @TableField("module_name")
  private String moduleName;

  @TableField("primary_module_name")
  private String primaryModuleName;

  @TableField("module_names")
  private String moduleNames;

  @TableField("function_name")
  private String functionName;

  @TableField("testing_phase")
  private String testingPhase;

  @TableField("severity_level")
  private String severityLevel;

  @TableField("severity_alias")
  private String severityAlias;

  @TableField("priority_level")
  private String priorityLevel;

  private String urgency;

  @TableField("bug_status")
  private String bugStatus;

  private String category;

  @TableField("reason_category")
  private String reasonCategory;

  @TableField("system_test_label")
  private String systemTestLabel;

  @TableField("label_names")
  private String labelNames;

  @TableField("is_excluded")
  private Boolean excluded;

  @TableField("exclusion_reason")
  private String exclusionReason;

  @TableField("is_fixed")
  private Boolean fixed;

  @TableField("delay_issue")
  private Boolean delayIssue;

  @TableField("delay_reason")
  private String delayReason;

  @TableField("delay_cause")
  private String delayCause;

  @TableField("is_regression")
  private Boolean regression;

  @TableField("is_crash")
  private Boolean crash;

  @TableField("is_level1_other")
  private Boolean level1Other;

  @TableField("is_illegal")
  private Boolean illegal;

  @TableField("illegal_reason")
  private String illegalReason;

  @TableField("has_response")
  private Boolean hasResponse;

  @TableField("response_overdue")
  private Boolean responseOverdue;

  @TableField("is_response_delayed")
  private Boolean responseDelayed;

  @TableField("resolve_sla_days")
  private Integer resolveSlaDays;

  @TableField("resolve_deadline_at")
  private LocalDateTime resolveDeadlineAt;

  @TableField("is_resolve_delayed")
  private Boolean resolveDelayed;

  @TableField("is_legacy")
  private Boolean legacy;

  private Boolean deleted;

  @TableField("fact_refreshed_at")
  private LocalDateTime factRefreshedAt;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
