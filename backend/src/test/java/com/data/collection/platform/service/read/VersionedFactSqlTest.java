package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.PublishedReadContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VersionedFactSqlTest {
  private final UUID epoch = UUID.randomUUID();
  private final FactReadSelection selection = new FactReadSelection(List.of(new PublishedReadContext(
      "alpha", PublicationFactTable.ISSUE, epoch, 12, UUID.randomUUID(), Instant.EPOCH)), java.time.LocalDate.of(2026, 10, 13));

  @Test
  void test_existing_business_parameters_follow_explicit_version_parameters() {
    VersionedFactSql.Query query = VersionedFactSql.bind(selection,
        "select * from issue_fact where project_id = ?", List.of(325L));
    assertThat(query.arguments()).containsExactly("alpha", epoch, 12L, 12L, 325L);
    assertThat(query.sql()).contains("issue_fact_customer_members as", "parent.id = member.issue_fact_revision_id");
  }

  @Test
  void test_existing_with_query_remains_one_version_limited_query() {
    VersionedFactSql.Query query = VersionedFactSql.bind(selection,
        "with filtered as (select * from issue_fact) select count(*) from filtered", List.of());
    assertThat(query.sql()).contains(", filtered as").doesNotContain(" with filtered");
  }

  @Test
  void test_missing_fact_domain_is_rejected_instead_of_reading_current_rows() {
    assertThatThrownBy(() -> VersionedFactSql.bind(selection,
        "select * from merge_request_fact", List.of())).hasMessageContaining("未绑定所需版本");
  }
}
