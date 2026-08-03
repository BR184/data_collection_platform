package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactType;
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

  @Test
  void reviewSourceVersionRetainsDynamicComponentsBeyondLegacyColumnLimit() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    FactProjectionVersionService projectionVersionService = mock(FactProjectionVersionService.class);
    String component = "source-version-component-".repeat(12);
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(component);
    when(projectionVersionService.globalSourceVersion(anyString(), any(FactType.class)))
        .thenReturn(component);
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            mock(JsonUtils.class),
            projectionVersionService,
            mock(IssueProjectionScopeResolver.class));

    String sourceVersion = service.reviewDataSourceVersion();

    assertThat(sourceVersion).hasSizeGreaterThan(256);
  }

  @Test
  void savePassesTheCompleteLongSourceVersionToSnapshotPersistence() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    when(jsonUtils.toJson(any())).thenReturn("{}");
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            jsonUtils,
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));
    String sourceVersion = "source-version-component-".repeat(12);
    PageRecordSnapshotService.SnapshotRequest request =
        new PageRecordSnapshotService.SnapshotRequest(
            "review-data", "LIST", "all", "review-v1", sourceVersion, java.util.Map.of());

    service.save(request, java.util.Map.of());

    ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(anyString(), argumentsCaptor.capture());
    Object[] arguments = argumentsCaptor.getValue();
    assertThat(arguments).hasSize(8);
    assertThat(arguments[4]).isEqualTo(sourceVersion);
  }
}
