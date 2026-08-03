package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.mapper.MergeRequestFactMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class MergeRequestFactPersistenceServiceTest {

  @Test
  void test_empty_merge_request_source_deletes_stale_target_fact() {
    MergeRequestFactMapper factMapper = mock(MergeRequestFactMapper.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    MergeRequestFactPersistenceService service =
        new MergeRequestFactPersistenceService(factMapper, jdbcTemplate);

    service.replaceRootFacts(
        "GITLAB",
        "default",
        List.of(202L),
        List.of());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .contains("delete from merge_request_fact")
        .contains("merge_request_id in (?)");
    assertThat(argsCaptor.getValue()).containsExactly("GITLAB", "default", 202L);
    org.mockito.Mockito.verifyNoInteractions(factMapper);
  }

  @Test
  void test_target_replacement_writes_current_merge_request_after_delete() {
    MergeRequestFactMapper factMapper = mock(MergeRequestFactMapper.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    MergeRequestFactPersistenceService service =
        new MergeRequestFactPersistenceService(factMapper, jdbcTemplate);
    MergeRequestFact currentFact = mock(MergeRequestFact.class);

    service.replaceRootFacts(
        "GITLAB",
        "default",
        List.of(202L),
        List.of(currentFact));

    InOrder ordered = inOrder(jdbcTemplate, factMapper);
    ordered.verify(jdbcTemplate)
        .update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    ordered.verify(factMapper).batchUpsert(List.of(currentFact));
  }

  @Test
  void test_empty_full_snapshot_clears_merge_request_facts() {
    MergeRequestFactMapper factMapper = mock(MergeRequestFactMapper.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    MergeRequestFactPersistenceService service =
        new MergeRequestFactPersistenceService(factMapper, jdbcTemplate);

    service.replaceAllFacts("GITLAB", "default", List.of());

    verify(jdbcTemplate).update(
        org.mockito.ArgumentMatchers.contains("delete from merge_request_fact"),
        org.mockito.ArgumentMatchers.eq("GITLAB"),
        org.mockito.ArgumentMatchers.eq("default"));
    org.mockito.Mockito.verifyNoInteractions(factMapper);
  }
}
