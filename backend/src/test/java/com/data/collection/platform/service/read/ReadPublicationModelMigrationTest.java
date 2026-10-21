package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReadPublicationModelMigrationTest {

  @Test
  void shouldDefineReadPublicationMetadataTables() throws IOException {
    String migration = readMigration("V20261012_01__read_publication_model.sql");

    assertThat(migration)
        .contains("create table read_definition_snapshots")
        .contains("create table read_publication_domains")
        .contains("create table read_publications")
        .contains("create table read_publication_fact_roots")
        .contains("create table read_publication_scope_generations")
        .contains("create table read_view_contexts")
        .contains("create table read_view_publications");
  }

  @Test
  void shouldDefinePublicationLifecycleConstraints() throws IOException {
    String migration = readMigration("V20261012_01__read_publication_model.sql");

    assertThat(migration)
        .contains("'building', 'projecting', 'ready_to_publish', 'published', 'aborted', 'retiring', 'retired'")
        .contains("uk_read_publications_domain_sequence")
        .contains("where sequence_no > 0")
        .contains("uq_read_publications_active_candidate")
        .contains("where state in ('building', 'projecting', 'ready_to_publish')")
        .contains("unique (producer_kind, source_instance, fact_type, table_key)")
        .contains("fk_read_publication_domains_current");
  }

  @Test
  void shouldAddFactRevisionColumnsAndOpenRevisionGuard() throws IOException {
    String migration = readMigration("V20261012_01__read_publication_model.sql");

    assertThat(migration)
        .contains("add column if not exists publication_epoch_id uuid")
        .contains("add column if not exists created_by_publication_id uuid")
        .contains("add column if not exists visible_from bigint")
        .contains("add column if not exists visible_to bigint")
        .contains("uq_issue_fact_open_published_revision")
        .contains("uq_merge_request_fact_open_published_revision")
        .contains("uq_integration_test_fact_open_published_revision")
        .contains("where visible_from is not null and visible_to is null")
        .contains("check (visible_to is null or (visible_from is not null and visible_to > visible_from))");
  }

  @Test
  void shouldBindRelationTablesToParentRevision() throws IOException {
    String migration = readMigration("V20261012_01__read_publication_model.sql");

    assertThat(migration)
        .contains("add column if not exists issue_fact_revision_id bigint")
        .contains("add column if not exists merge_request_revision_id bigint")
        .contains("fk_issue_fact_customer_members_revision")
        .contains("references issue_fact(id)")
        .contains("fk_merge_request_commit_fact_revision")
        .contains("references merge_request_fact(id)");
  }

  @Test
  void shouldKeyRootManifestByFullBusinessIdentity() throws IOException {
    String migration = readMigration("V20261012_01__read_publication_model.sql");

    assertThat(migration)
        .contains(
            "primary key (publication_id, source_system, source_instance, fact_type, project_id, root_id)")
        .contains("project_id bigint not null")
        .contains("check (project_id >= 0)");
  }

  @Test
  void shouldOnlyAddStructureWithoutDestructiveStatements() throws IOException {
    String migration = readMigration("V20261012_01__read_publication_model.sql");

    assertThat(migration)
        .doesNotContain("drop table")
        .doesNotContain("drop column")
        .doesNotContain("rename to")
        .doesNotContain("rename column")
        .doesNotContain("delete from issue_fact")
        .doesNotContain("truncate");
  }

  private String readMigration(String fileName) throws IOException {
    return Files.readString(
            Path.of("src", "main", "resources", "db", "migration", fileName), StandardCharsets.UTF_8)
        .toLowerCase();
  }
}
