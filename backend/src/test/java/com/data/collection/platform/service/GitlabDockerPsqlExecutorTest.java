package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabDockerPsqlExecutorTest {
  @Test
  void shouldBuildDockerExecCommandForGitlabPsql() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setDockerCommand("podman");
    GitlabDockerPsqlExecutor executor = newExecutor(properties);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setDockerContainerName("gitlab-postgres");
    config.setDbName("gitlabhq_production");

    List<String> command = executor.buildDockerCommand(config, "select 1");

    assertThat(command)
        .containsExactly(
            "podman",
            "exec",
            "gitlab-postgres",
            "bash",
            "-lc",
            """
            gitlab-psql -d "gitlabhq_production" -At <<'SQL'
            select 1;
            SQL
            """);
  }

  @Test
  void shouldRejectDockerModeWithoutContainerNameBeforeStartingProcess() {
    GitlabDockerPsqlExecutor executor = newExecutor(new GitlabMirrorProperties());
    GitlabSyncConfig config = new GitlabSyncConfig();

    assertThatThrownBy(() -> executor.execute(config, "select 1"))
        .isInstanceOf(BizException.class)
        .hasMessage("Docker mode requires a container name");
  }

  @Test
  void shouldParseJsonRowsAndRejectErrorLinesFromPsqlOutput() {
    GitlabDockerPsqlExecutor executor = newExecutor(new GitlabMirrorProperties());

    var parse = executor.parseRowsTestBridge();

    List<java.util.Map<String, Object>> rows =
        parse.apply(List.of("{\"id\": 101}", "{\"id\": 202}"));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("id", 101);

    assertThatThrownBy(() -> parse.apply(List.of("ERROR: relation \"x\" does not exist")))
        .isInstanceOf(BizException.class)
        .hasMessage("ERROR: relation \"x\" does not exist");
  }

  private GitlabDockerPsqlExecutor newExecutor(GitlabMirrorProperties properties) {
    return new GitlabDockerPsqlExecutor(
        properties,
        new GitlabSourceConnectionSettings(properties),
        new GitlabSourceQueryRetryPolicy(properties),
        new ObjectMapper());
  }
}
