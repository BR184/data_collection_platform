package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseBrowserFormTableDefinitionsTest {

  @Test
  void shouldRegisterAllFormBaseTablesAsReadOnlyDefinitions() {
    List<String> tables =
        List.of(
            "form_template_definitions",
            "form_template_scopes",
            "form_template_versions",
            "form_template_scope_drafts",
            "form_template_preview_evidence",
            "form_record_audit_logs",
            "form_template_migration_logs",
            "form_template_publications",
            "form_template_publication_attempts",
            "form_authority_cutover_manifests",
            "form_authority_cutover_items",
            "form_resource_authorities",
            "form_change_heads");

    for (String table : tables) {
      DatabaseBrowserTableDefinition definition = DatabaseBrowserTableCatalog.findDefinition(table);
      assertThat(definition).as(table).isNotNull();
      assertThat(definition.columns()).as(table).isNotEmpty();
      assertThat(definition.label()).as(table).isNotBlank();
    }
  }
}
