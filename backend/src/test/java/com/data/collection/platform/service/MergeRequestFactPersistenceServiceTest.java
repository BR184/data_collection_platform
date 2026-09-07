package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.entity.MergeRequestCommitFact;
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
        List.of(),
        List.of());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(sqlCaptor.capture(), argsCaptor.capture());
    assertThat(sqlCaptor.getAllValues())
        .anyMatch(sql -> sql.contains("delete from merge_request_fact"))
        .anyMatch(sql -> sql.contains("delete from merge_request_commit_fact"))
        .allMatch(sql -> sql.contains("merge_request_id in (?)"));
    assertThat(argsCaptor.getAllValues())
        .allSatisfy(args -> assertThat(args).containsExactly("GITLAB", "default", 202L));
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
        List.of(currentFact),
        List.of());

    InOrder ordered = inOrder(jdbcTemplate, factMapper);
    ordered.verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    ordered.verify(factMapper).batchUpsert(List.of(currentFact));
  }

  @Test
  void test_empty_full_snapshot_clears_merge_request_facts() {
    MergeRequestFactMapper factMapper = mock(MergeRequestFactMapper.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    MergeRequestFactPersistenceService service =
        new MergeRequestFactPersistenceService(factMapper, jdbcTemplate);

    service.deleteFactsNotInSnapshot("GITLAB", "default", List.of(), List.of());

    verify(jdbcTemplate).update(
        org.mockito.ArgumentMatchers.contains("delete from merge_request_fact"),
        org.mockito.ArgumentMatchers.eq("GITLAB"),
        org.mockito.ArgumentMatchers.eq("default"));
    verify(jdbcTemplate).update(
        org.mockito.ArgumentMatchers.contains("delete from merge_request_commit_fact"),
        org.mockito.ArgumentMatchers.eq("GITLAB"),
        org.mockito.ArgumentMatchers.eq("default"));
    org.mockito.Mockito.verifyNoInteractions(factMapper);
  }

  @Test
  void test_full_snapshot_cleanup_stages_identities_then_deletes_outside_rows() {
    MergeRequestFactMapper factMapper = mock(MergeRequestFactMapper.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    MergeRequestFactPersistenceService service =
        new MergeRequestFactPersistenceService(factMapper, jdbcTemplate);
    MergeRequestFact keptFact = mock(MergeRequestFact.class);
    org.mockito.Mockito.when(keptFact.getProjectId()).thenReturn(9L);
    org.mockito.Mockito.when(keptFact.getMergeRequestId()).thenReturn(202L);
    MergeRequestCommitFact keptCommit =
        new MergeRequestCommitFact(
            "GITLAB", "default", 9L, 202L, 5L, "abc123", java.time.LocalDateTime.now());

    service.deleteFactsNotInSnapshot("GITLAB", "default", List.of(keptFact), List.of(keptCommit));

    verify(jdbcTemplate)
        .execute(org.mockito.ArgumentMatchers.contains("create temp table merge_request_snapshot_ids"));
    verify(jdbcTemplate)
        .execute(org.mockito.ArgumentMatchers.contains("create temp table merge_request_snapshot_commit_ids"));
    @SuppressWarnings({"unchecked", "rawtypes"})
    ArgumentCaptor<List<Object[]>> batchCaptor = ArgumentCaptor.forClass((Class) List.class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .batchUpdate(org.mockito.ArgumentMatchers.anyString(), batchCaptor.capture());
    assertThat(batchCaptor.getAllValues().get(0)).hasSize(1);
    assertThat(batchCaptor.getAllValues().get(0).get(0)).containsExactly(9L, 202L);
    assertThat(batchCaptor.getAllValues().get(1)).hasSize(1);
    assertThat(batchCaptor.getAllValues().get(1).get(0)).containsExactly(9L, 202L, "abc123");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(sqlCaptor.capture(), argsCaptor.capture());
    assertThat(sqlCaptor.getAllValues())
        .anyMatch(sql -> sql.contains("delete from merge_request_fact") && sql.contains("not exists"))
        .anyMatch(sql ->
            sql.contains("delete from merge_request_commit_fact") && sql.contains("not exists"));
    assertThat(argsCaptor.getAllValues())
        .allSatisfy(args -> assertThat(args).containsExactly("GITLAB", "default"));
  }
}
