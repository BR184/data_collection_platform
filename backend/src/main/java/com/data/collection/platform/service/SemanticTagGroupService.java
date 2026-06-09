package com.data.collection.platform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SemanticTagGroupService {
  private final SemanticTagGroupRepository repository;
  private final List<SemanticTagGroupDefinition> staticIssueGroups;
  private final String staticIssueSchemaHash;

  public SemanticTagGroupService() {
    this(SemanticTagGroupRepository.empty(), staticIssueGroups());
  }

  @Autowired
  public SemanticTagGroupService(SemanticTagGroupRepository repository) {
    this(repository, staticIssueGroups());
  }

  SemanticTagGroupService(List<SemanticTagGroupDefinition> staticIssueGroups) {
    this(SemanticTagGroupRepository.empty(), staticIssueGroups);
  }

  SemanticTagGroupService(
      SemanticTagGroupRepository repository,
      List<SemanticTagGroupDefinition> staticIssueGroups) {
    this.repository = repository;
    this.staticIssueGroups = List.copyOf(staticIssueGroups);
    this.staticIssueSchemaHash = sha256(this.staticIssueGroups.toString());
  }

  public static SemanticTagGroupService withDefaults() {
    return new SemanticTagGroupService(staticIssueGroups());
  }

  public SemanticTagGroupCatalog listStaticGroups(String entityType) {
    List<SemanticTagGroupDefinition> databaseGroups = repository.listEnabledGroups(entityType);
    if (!databaseGroups.isEmpty()) {
      return new SemanticTagGroupCatalog(entityType, sha256(databaseGroups.toString()), databaseGroups);
    }
    if (!"issue".equals(entityType)) {
      return new SemanticTagGroupCatalog(entityType, sha256(entityType + ":empty"), List.of());
    }
    return new SemanticTagGroupCatalog(entityType, staticIssueSchemaHash, staticIssueGroups);
  }

  private static List<SemanticTagGroupDefinition> staticIssueGroups() {
    return List.of(
        group(
            "issue",
            "severity_level",
            "Severity level",
            "issue_severity_policy",
            List.of(
                value("LEVEL1", "Level 1 defect", 10),
                value("LEVEL2", "Level 2 defect", 20),
                value("LEVEL3", "Level 3 defect", 30),
                value("SUGGESTION", "Suggestion", 40))),
        group(
            "issue",
            "urgency",
            "Urgency",
            "customer_issue_urgency_policy",
            List.of(value("P1", "P1", 10), value("P2", "P2", 20), value("P3", "P3", 30))),
        group(
            "issue",
            "system_test_exclusion_type",
            "System test exclusion type",
            "system_test_exclusion_policy",
            List.of(
                value("FUNCTION_BLOCKED", "Function blocked", 10),
                value("REJECTED", "Rejected", 20),
                value("SUGGESTION", "Suggestion", 30),
                value("CLOSED_REJECTION", "Closed with rejection", 40),
                value("CLOSED_REQUIREMENT_AS_IS", "Closed as requirement", 50))),
        group(
            "issue",
            "delay_cause",
            "Delay cause",
            "system_test_delay_cause_policy",
            List.of(
                value("TECHNICAL_BLOCKER", "Technical blocker", 10),
                value("SOLUTION_BLOCKER", "Solution blocker", 20),
                value("RESOURCE_BLOCKER", "Resource blocker", 30),
                value("DATA_ANOMALY", "Data anomaly", 40),
                value("ALGORITHM_ISSUE", "Algorithm issue", 50),
                value("MECHANISM_ISSUE", "Mechanism issue", 60),
                value("COMPUTATION_EFFICIENCY", "Computation efficiency", 70))),
        group(
            "issue",
            "customer_issue_closure_status",
            "Customer issue closure status",
            "customer_issue_closure_policy",
            List.of(
                value("FIXED_DONE", "Fixed or done", 10),
                value("DELAY_REQUESTED", "Delay requested", 20),
                value("DATA_ANOMALY", "Data anomaly", 30),
                value("REQUIREMENT_AS_IS", "Requirement as-is", 40),
                value("DESIGN_AS_IS", "Design as-is", 50),
                value("NOT_REPRODUCED", "Not reproduced", 60))),
        group(
            "issue",
            "illegal_type",
            "Illegal data type",
            "system_test_illegal_type_policy",
            List.of(
                value("MISSING_SEVERITY", "Missing severity", 10),
                value("MISSING_MODULE", "Missing module", 20),
                value("MISSING_REQUIRED_REPLY", "Missing required reply", 30),
                value("NON_UNIQUE_DEFECT_REASON", "Non-unique defect reason", 40),
                value("MISSING_DEFECT_INVESTIGATION_TEMPLATE", "Missing investigation", 50),
                value("INVALID_PLAN_RESOLVE_TIME", "Invalid planned resolve time", 60),
                value("LEVEL1_MISSING_OWNER_SIGN", "Level 1 missing owner sign", 70))),
        group(
            "issue",
            "defect_reason_standard",
            "Defect reason standard",
            "defect_reason_policy",
            List.of(
                value("NEW_UNDERSTANDING_DEVIATION", "New understanding deviation", 10),
                value("NEW_REQUIREMENT", "New requirement", 20),
                value("CODING_BUSINESS_LOGIC_ERROR", "Coding business logic error", 30),
                value("BUILD_PACKAGE_DEPLOYMENT_ISSUE", "Build/package/deployment issue", 40),
                value("MECHANISM_UNSUPPORTED", "Mechanism unsupported", 50))),
        singleSelectionGroup(
            "issue",
            "ratio_empty_value_policy",
            "Ratio empty value policy",
            "ratio_display_policy",
            List.of(
                value("DISPLAY_SLASH", "Display slash", 10),
                value("DISPLAY_ZERO", "Display zero", 20))));
  }

  private static SemanticTagGroupDefinition group(
      String domain,
      String groupKey,
      String label,
      String rulePolicyKey,
      List<SemanticTagValueDefinition> values) {
    return new SemanticTagGroupDefinition(
        domain,
        groupKey,
        label,
        "STATIC",
        rulePolicyKey,
        "MULTIPLE",
        "EXACT",
        true,
        values.getFirst().sortOrder(),
        values);
  }

  private static SemanticTagGroupDefinition singleSelectionGroup(
      String domain,
      String groupKey,
      String label,
      String rulePolicyKey,
      List<SemanticTagValueDefinition> values) {
    return new SemanticTagGroupDefinition(
        domain,
        groupKey,
        label,
        "STATIC",
        rulePolicyKey,
        "SINGLE",
        "EXACT",
        true,
        values.getFirst().sortOrder(),
        values);
  }

  private static SemanticTagValueDefinition value(String valueKey, String label, int sortOrder) {
    return new SemanticTagValueDefinition(valueKey, label, "STRING", valueKey, true, sortOrder);
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
