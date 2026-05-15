package com.data.collection.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NoLegacySyncModelTest {
  private static final Path RUNTIME_SOURCE_ROOT = Path.of("src/main/java");
  private static final List<String> FORBIDDEN_SYMBOLS =
      List.of(
          "GitlabSyncTask",
          "GitlabSyncLog",
          "GitlabSyncJob",
          "GitlabSyncJobType",
          "GitlabTableSync",
          "GitlabSyncTaskService",
          "GitlabSyncLogService",
          "GitlabSyncJobMapper",
          "gitlab_sync_tasks",
          "gitlab_sync_logs",
          "gitlab_sync_jobs",
          "gitlab_table_sync_tasks",
          "gitlab_table_sync_states");

  @Test
  void runtimeCodeMustNotReferenceLegacySyncModels() throws IOException {
    assertThat(Files.isDirectory(RUNTIME_SOURCE_ROOT)).isTrue();

    try (Stream<Path> sourceFiles = Files.walk(RUNTIME_SOURCE_ROOT)) {
      List<String> violations =
          sourceFiles
              .filter(path -> path.toString().endsWith(".java"))
              .flatMap(NoLegacySyncModelTest::violationsIn)
              .toList();

      assertThat(violations)
          .as("legacy sync model references under %s", RUNTIME_SOURCE_ROOT)
          .isEmpty();
    }
  }

  private static Stream<String> violationsIn(Path sourceFile) {
    try {
      String content = Files.readString(sourceFile, StandardCharsets.UTF_8);
      return FORBIDDEN_SYMBOLS.stream()
          .filter(content::contains)
          .map(symbol -> sourceFile + " contains " + symbol);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to read " + sourceFile, error);
    }
  }
}
