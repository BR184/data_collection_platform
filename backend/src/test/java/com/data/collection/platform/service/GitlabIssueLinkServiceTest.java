package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import org.junit.jupiter.api.Test;
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
    assertThat(service.issueUrl(1001L, 25695)).isEqualTo("http://gitlab.example.com/group/project/-/issues/25695");
    verify(jdbcTemplate).queryForObject(any(String.class), eq(String.class), eq(1001L));
  }

  @Test
  void shouldAvoidBrokenGlobalIssueUrlWhenProjectPathIsMissing() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(1001L)))
        .thenThrow(new EmptyResultDataAccessException(1));
    GitlabIssueLinkService service = new GitlabIssueLinkService(jdbcTemplate, properties());

    assertThat(service.issueUrl(1001L, 25694)).isNull();
  }

  private GitlabMirrorProperties properties() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setWebBaseUrl("http://gitlab.example.com/");
    return properties;
  }
}
