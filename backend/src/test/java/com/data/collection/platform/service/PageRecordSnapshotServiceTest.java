package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class PageRecordSnapshotServiceTest {

  @Test
  void reviewSourceVersionIncludesTheConfiguredReadMode() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("source-version");
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));

    String sourceVersion = service.reviewDataSourceVersion();

    assertThat(sourceVersion).startsWith("source-version|source-version|source-version|");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, atLeastOnce()).queryForObject(sqlCaptor.capture(), eq(String.class));
    List<String> sourceQueries = sqlCaptor.getAllValues();
    assertThat(sourceQueries)
        .anyMatch(query -> query.contains("review_data_read_mode") && query.contains("enabled"));
  }
}
