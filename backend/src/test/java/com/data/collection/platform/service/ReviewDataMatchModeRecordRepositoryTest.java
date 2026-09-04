package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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

  @Test
  void missingMatchModeRecordRaisesBusinessErrorInsteadOfDatabaseError() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.<Object>query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
    ReviewDataMatchModeRecordRepository repository =
        new ReviewDataMatchModeRecordRepository(jdbcTemplate);

    assertThatThrownBy(() -> repository.getRecordOrThrow(-404L))
        .isInstanceOf(BizException.class)
        .hasMessage("该评审记录已被重新同步，请刷新列表后重试");
  }
}
