package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabDockerPsqlExecutorTest {
  @Test
  void shouldBuildDockerExecCommandForGitlabPsql() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setDockerCommand("podman");
    GitlabSourceConnectionSettings settings = new GitlabSourceConnectionSettings(properties);
    GitlabDockerPsqlExecutor executor = new GitlabDockerPsqlExecutor(properties, settings);
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
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    GitlabSourceConnectionSettings settings = new GitlabSourceConnectionSettings(properties);
    GitlabDockerPsqlExecutor executor = new GitlabDockerPsqlExecutor(properties, settings);
    GitlabSyncConfig config = new GitlabSyncConfig();

    assertThatThrownBy(() -> executor.execute(config, "select 1"))
        .isInstanceOf(BizException.class)
        .hasMessage("Docker mode requires a container name");
  }
}
