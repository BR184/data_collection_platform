package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GitlabDockerPsqlExecutor {
  private final GitlabMirrorProperties properties;
  private final GitlabSourceConnectionSettings connectionSettings;

  GitlabDockerPsqlExecutor(
      GitlabMirrorProperties properties, GitlabSourceConnectionSettings connectionSettings) {
    this.properties = properties;
    this.connectionSettings = connectionSettings;
  }

  List<String> execute(GitlabSyncConfig config, String sql) {
    String containerName = config.getDockerContainerName();
    if (containerName == null || containerName.isBlank()) {
      throw new BizException("Docker mode requires a container name");
    }

    try {
      ProcessBuilder builder = new ProcessBuilder(buildDockerCommand(config, sql));
      builder.redirectErrorStream(true);
      Process process = builder.start();
      ExecutorService outputReader = Executors.newSingleThreadExecutor();
      List<String> lines;
      try {
        Future<List<String>> outputFuture = outputReader.submit(() -> readProcessOutput(process));
        boolean finished =
            process.waitFor(connectionSettings.resolveExternalQueryTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
          process.destroyForcibly();
          throw new BizException("Docker GitLab PostgreSQL command timed out after "
              + connectionSettings.resolveExternalQueryTimeoutSeconds() + " seconds");
        }
        try {
          lines = outputFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          throw new BizException("Docker GitLab PostgreSQL command output read timed out");
        }
      } finally {
        outputReader.shutdownNow();
      }
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        throw new BizException("Docker GitLab PostgreSQL command failed: " + String.join(System.lineSeparator(), lines));
      }
      return lines;
    } catch (BizException e) {
      try (SyncRunLogContext.Scope action = SyncRunLogContext.action("Data_Fetching")) {
        log.error("Docker GitLab PostgreSQL command failed", e);
      }
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      try (SyncRunLogContext.Scope action = SyncRunLogContext.action("Data_Fetching")) {
        log.error("Docker GitLab PostgreSQL command interrupted", e);
      }
      throw new BizException("Docker GitLab PostgreSQL command interrupted");
    } catch (Exception e) {
      try (SyncRunLogContext.Scope action = SyncRunLogContext.action("Data_Fetching")) {
        log.error("Docker GitLab PostgreSQL command failed", e);
      }
      throw new BizException("Docker GitLab PostgreSQL command failed: " + e.getMessage());
    }
  }

  List<String> buildDockerCommand(GitlabSyncConfig config, String sql) {
    return List.of(
        properties.getDockerCommand(),
        "exec",
        config.getDockerContainerName(),
        "bash",
        "-lc",
        connectionSettings.buildDockerPsqlScript(config, sql));
  }

  private List<String> readProcessOutput(Process process) throws Exception {
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    return lines;
  }
}
