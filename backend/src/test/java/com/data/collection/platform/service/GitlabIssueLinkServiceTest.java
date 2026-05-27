package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class GitlabIssueLinkServiceTest {

  @Test
  void shouldBuildIssueUrlWithProjectPath() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenReturn("group/project");
    GitlabIssueLinkService service = new GitlabIssueLinkService(jdbcTemplate, properties());

    assertThat(service.issueUrl(1001L, 25694)).isEqualTo("http://gitlab.example.com/group/project/-/issues/25694");
    assertThat(service.mergeRequestUrl(1001L, 18)).isEqualTo("http://gitlab.example.com/group/project/-/merge_requests/18");
    assertThat(service.issueUrl(1001L, 25695)).isEqualTo("http://gitlab.example.com/group/project/-/issues/25695");
    verify(jdbcTemplate).queryForObject(any(String.class), eq(String.class), eq(1001L));
  }

  @Test
  void shouldUseRecursiveNamespacePathLookupForNestedProjects() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenReturn("parent/subgroup/project");
    GitlabIssueLinkService service = new GitlabIssueLinkService(jdbcTemplate, propertiesWithoutScheme());

    assertThat(service.issueUrl(1001L, 1392)).isEqualTo("http://gitlab.example.com/parent/subgroup/project/-/issues/1392");

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(String.class), eq(1001L));
    assertThat(sqlCaptor.getValue())
        .contains("with recursive")
        .contains("namespace_chain")
        .contains("parent_id")
        .contains("string_agg(namespace_path, '/' order by depth desc)");
  }

  @Test
  void shouldAvoidBrokenGlobalIssueUrlWhenProjectPathIsMissing() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenThrow(new EmptyResultDataAccessException(1));
    GitlabIssueLinkService service = new GitlabIssueLinkService(jdbcTemplate, properties());

    assertThat(service.issueUrl(1001L, 25694)).isNull();
  }

  @Test
  void shouldBuildIssueUrlFromSourceScopedMirrorTables() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenThrow(new EmptyResultDataAccessException(1))
        .thenReturn("source-group/source-project");
    when(jdbcTemplate.queryForList(any(String.class), eq(String.class)))
        .thenReturn(List.of("ods_gitlab_cc_projects"));
    GitlabIssueLinkService service = new GitlabIssueLinkService(jdbcTemplate, properties());

    assertThat(service.issueUrl(1001L, 25694))
        .isEqualTo("http://gitlab.example.com/source-group/source-project/-/issues/25694");

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2)).queryForObject(sqlCaptor.capture(), eq(String.class), eq(1001L));
    assertThat(sqlCaptor.getAllValues().get(1))
        .contains("\"ods_gitlab_cc_projects\"")
        .contains("\"ods_gitlab_cc_namespaces\"");
  }

  private GitlabMirrorProperties properties() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setWebBaseUrl("http://gitlab.example.com/");
    return properties;
  }

  private GitlabMirrorProperties propertiesWithoutScheme() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setWebBaseUrl("gitlab.example.com/");
    return properties;
  }
}
