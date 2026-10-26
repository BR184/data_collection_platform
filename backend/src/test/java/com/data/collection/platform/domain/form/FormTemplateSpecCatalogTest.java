package com.data.collection.platform.domain.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FormTemplateSpecCatalogTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final FormTemplateSpecCatalog catalog = new FormTemplateSpecCatalog(objectMapper);
  private final FormVersionSchemaService schemaService =
      new FormVersionSchemaService(objectMapper);

  @Test
  void shouldLoadThreeApprovedTemplateSpecs() {
    assertThat(catalog.all()).hasSize(3);
    assertThat(catalog.require("code_review").fields()).hasSize(8);
    assertThat(catalog.require("issue_response").fields()).hasSize(7);
    assertThat(catalog.require("issue_reply").fields()).hasSize(13);
  }

  @Test
  void shouldFreezeDefectCauseDictionaryAtApprovedHash() {
    FormDictionarySpec dictionary =
        catalog.require("issue_reply").dictionary("DEFECT_CAUSE").orElseThrow();

    assertThat(dictionary.items()).hasSize(24);
    assertThat(dictionary.canonicalSha256())
        .isEqualTo("efbc6c2ef07c5e7e229c1a2e0fffd7411b6c01097e16a70d2abeb8103535ce6c");
    assertThat(FormHashSupport.canonicalDictionaryHash(objectMapper, dictionary))
        .isEqualTo(dictionary.canonicalSha256());
  }

  @Test
  void shouldGenerateCodeReviewLegacySchemaWithExemptedRequiredColumns() {
    FormTemplateSpec spec = catalog.require("code_review");
    FormVersionSchema schema = schemaService.generate(1L, 2L, spec, true);
    String ddl = String.join("\n", schema.statements());

    assertThat(schema.instanceTable()).isEqualTo("form_s1_v2_instances");
    assertThat(schema.recordTable()).isEqualTo("form_s1_v2_records");
    assertThat(ddl)
        .contains("legacy_incomplete boolean not null default false")
        .contains("legacy_missing_fields text[] not null default '{}'")
        .contains("reviewer_name varchar(128)")
        .contains(
            "check (deleted or legacy_incomplete or (reviewer_user_id is not null and btrim(reviewer_user_id) <> ''))")
        .contains("where deleted = false");
    assertThat(schema.ddlHash()).hasSize(64);
  }

  @Test
  void shouldGenerateIssueReplyConditionConstraints() {
    FormTemplateSpec spec = catalog.require("issue_reply");
    FormVersionSchema schema = schemaService.generate(7L, 8L, spec, false);
    String ddl = String.join("\n", schema.statements());

    assertThat(ddl)
        .contains("cause_keys text[] not null")
        .contains(
            "check (cardinality(cause_keys) >= 1 and cardinality(cause_keys) <= 24)")
        .contains("check (not ('NONE' = any(affected_kinds) and cardinality(affected_kinds) > 1))")
        .contains("check (not ('KNOWN' = any(affected_kinds)) or (known_affected_functions is not null")
        .contains("check (('KNOWN' = any(affected_kinds)) or known_affected_functions is null)")
        .contains("relation_table_updated varchar(16) not null");
  }

  @Test
  void shouldGenerateIssueResponseSnapshotConstraint() {
    FormTemplateSpec spec = catalog.require("issue_response");
    FormVersionSchema schema = schemaService.generate(3L, 4L, spec, false);
    String ddl = String.join("\n", schema.statements());

    assertThat(ddl)
        .contains("owner_confirmation_applicable boolean not null")
        .contains("check (owner_confirmation_applicable is not null and ((owner_confirmation_applicable and")
        .contains("planned_resolution_date date not null");
  }

  @Test
  void shouldKeepDdlHashStableForSameInput() {
    FormTemplateSpec spec = catalog.require("code_review");

    assertThat(schemaService.generate(9L, 10L, spec, true).ddlHash())
        .isEqualTo(schemaService.generate(9L, 10L, spec, true).ddlHash())
        .isNotEqualTo(schemaService.generate(9L, 11L, spec, true).ddlHash());
  }
}
