package com.data.collection.platform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SemanticTagGroupService {
  private final List<SemanticTagGroupDefinition> staticIssueGroups;
  private final String staticIssueSchemaHash;

  public SemanticTagGroupService() {
    this(staticIssueGroups());
  }

  SemanticTagGroupService(List<SemanticTagGroupDefinition> staticIssueGroups) {
    this.staticIssueGroups = List.copyOf(staticIssueGroups);
    this.staticIssueSchemaHash = sha256(this.staticIssueGroups.toString());
  }

  public static SemanticTagGroupService withDefaults() {
    return new SemanticTagGroupService(staticIssueGroups());
  }

  public SemanticTagGroupCatalog listStaticGroups(String entityType) {
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
                value("LEVEL1_MISSING_OWNER_SIGN", "Level 1 missing owner sign", 70))));
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
