package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class GitlabResourceLinkServiceTest {
  private JdbcTemplate jdbcFixture() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(any(String.class), eq(Boolean.class), any(String.class), any(String.class))).thenReturn(true);
    return jdbc;
  }

  @Test
  void test_optional_missing_project_metadata_does_not_poison_reads_or_cache_a_permanent_miss() {
    JdbcTemplate jdbc = jdbcFixture();
    when(jdbc.queryForObject(any(String.class), eq(Boolean.class), any(String.class), any(String.class)))
        .thenReturn(false, true);
    when(jdbc.queryForObject(any(String.class), eq(String.class), eq(1001L))).thenReturn("group/project");
    GitlabResourceLinkService service = new GitlabResourceLinkService(jdbc, properties());
    assertThat(service.issueUrl(1001L, 21)).isNull();
    assertThat(service.issueUrl(1001L, 21)).isEqualTo("http://gitlab.example.com/group/project/-/issues/21");
  }

  @Test
  void shouldBuildIssueUrlWithProjectPath() {
    JdbcTemplate jdbcTemplate = jdbcFixture();
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenReturn("group/project");
    GitlabResourceLinkService service = new GitlabResourceLinkService(jdbcTemplate, properties());

    assertThat(service.issueUrl(1001L, 25694)).isEqualTo("http://gitlab.example.com/group/project/-/issues/25694");
    assertThat(service.mergeRequestUrl(1001L, 18)).isEqualTo("http://gitlab.example.com/group/project/-/merge_requests/18");
    assertThat(service.issueUrl(1001L, 25695)).isEqualTo("http://gitlab.example.com/group/project/-/issues/25695");
    verify(jdbcTemplate).queryForObject(any(String.class), eq(String.class), eq(1001L));
  }

  @Test
  void shouldUseRecursiveNamespacePathLookupForNestedProjects() {
    JdbcTemplate jdbcTemplate = jdbcFixture();
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenReturn("parent/subgroup/project");
    GitlabResourceLinkService service = new GitlabResourceLinkService(jdbcTemplate, propertiesWithoutScheme());

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
    JdbcTemplate jdbcTemplate = jdbcFixture();
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenThrow(new EmptyResultDataAccessException(1));
    GitlabResourceLinkService service = new GitlabResourceLinkService(jdbcTemplate, properties());

    assertThat(service.issueUrl(1001L, 25694)).isNull();
  }

  @Test
  void shouldUseSingleMirrorTablesForRequestedSourceInstance() {
    JdbcTemplate jdbcTemplate = jdbcFixture();
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenReturn("group/project");
    GitlabResourceLinkService service = new GitlabResourceLinkService(jdbcTemplate, properties());

    assertThat(service.issueUrl("cc", 1001L, 25694))
        .isEqualTo("http://gitlab.example.com/group/project/-/issues/25694");

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(String.class), eq(1001L));
    assertThat(sqlCaptor.getValue())
        .contains("\"ods_gitlab_projects\"")
        .contains("\"ods_gitlab_namespaces\"")
        .doesNotContain("_cc_projects");
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
