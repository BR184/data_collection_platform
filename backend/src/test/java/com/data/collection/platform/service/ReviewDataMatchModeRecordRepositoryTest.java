package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ReviewDataMatchModeRecordRepositoryTest {

  @Test
  void changedSnapshotIdUpdatesProblemLinkByStableLegacyId() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0, 1);
    ReviewDataMatchModeRecordRepository repository =
        new ReviewDataMatchModeRecordRepository(jdbcTemplate);

    repository.linkMaterializedProblem(-91L, "legacy-problem-7", 22L, 33L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(sqlCaptor.capture(), any(Object[].class));
    List<String> sqlStatements = sqlCaptor.getAllValues();
    assertThat(sqlStatements.get(0))
        .contains("or match_mode_problem_legacy_id = ?");
    assertThat(sqlStatements.get(1))
        .contains("on conflict (match_mode_problem_legacy_id)");
  }
}
