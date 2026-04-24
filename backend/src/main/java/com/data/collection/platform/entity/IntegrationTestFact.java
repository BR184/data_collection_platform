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
@TableName("integration_test_fact")
public class IntegrationTestFact {
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

  @TableField("issuable_reference")
  private String issuableReference;

  private String title;

  @TableField("issue_state")
  private String issueState;

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

  @TableField("note_id")
  private Long noteId;

  @TableField("note_created_at_source")
  private LocalDateTime noteCreatedAtSource;

  @TableField("note_updated_at_source")
  private LocalDateTime noteUpdatedAtSource;

  @TableField("module_name")
  private String moduleName;

  @TableField("function_name")
  private String functionName;

  private String executor;

  @TableField("testing_phase")
  private String testingPhase;

  @TableField("execute_case")
  private Integer executeCase;

  @TableField("pass_case")
  private Integer passCase;

  @TableField("not_pass_case")
  private Integer notPassCase;

  @TableField("not_pass_case_now")
  private Integer notPassCaseNow;

  @TableField("problem_case")
  private Integer problemCase;

  @TableField("exception_count")
  private Integer exceptionCount;

  @TableField("pass_rate")
  private BigDecimal passRate;

  private Boolean legal;

  @TableField("parse_status")
  private String parseStatus;

  @TableField("validation_reason")
  private String validationReason;

  @TableField("label_names")
  private String labelNames;

  @TableField("function_labels")
  private String functionLabels;

  private Boolean deleted;

  @TableField("fact_refreshed_at")
  private LocalDateTime factRefreshedAt;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
