package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessBackupProcessRunnerTest {
  private final ProcessBackupProcessRunner runner = new ProcessBackupProcessRunner();

  @TempDir Path tempDir;

  @Test
  void test_run_successCommand_returnsExitCodeZeroAndCapturesStderr() {
    List<String> command = stubCommand();

    BackupProcessRunner.ProcessResult result =
        runner.run(command, Map.of("STUB_EXIT", "0"), Duration.ofSeconds(30), context(mockActiveState()));

    assertThat(result.exitCode()).isZero();
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void test_run_failingCommand_returnsExitCodeAndStderrContent() {
    List<String> command = stubCommand();

    BackupProcessRunner.ProcessResult result =
        runner.run(command, Map.of("STUB_EXIT", "3"), Duration.ofSeconds(30), context(mockActiveState()));

    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.stderr()).contains("pg_dump: error details");
  }

  @Test
  void test_run_injectsEnvironmentVariablesIntoProcess() throws IOException {
    Path output = tempDir.resolve("env-capture.txt");
    List<String> command = stubCommand();

    runner.run(
        command,
        Map.of("PGPASSWORD", "s3cret", "STUB_OUTPUT", output.toString(), "STUB_EXIT", "0"),
        Duration.ofSeconds(30),
        context(mockActiveState()));

    assertThat(Files.readString(output, StandardCharsets.UTF_8).strip()).isEqualTo("s3cret");
  }

  @Test
  void test_run_hangingProcess_timesOutAndDestroys() {
    List<String> command = stubCommand();

    assertThatThrownBy(() -> runner.run(
            command, Map.of("STUB_SLEEP", "1"), Duration.ofMillis(500), context(mockActiveState())))
        .isInstanceOf(BackupProcessException.class)
        .hasMessageContaining("超过");
  }

  @Test
  void test_run_whenExecutionIdentityIsRevoked_terminatesRegisteredProcess() throws Exception {
    BackupStateRepository stateRepository = mockActiveState();
    CountDownLatch processRegistered = new CountDownLatch(1);
    doAnswer(invocation -> {
      processRegistered.countDown();
      return true;
    }).when(stateRepository).recordProcessIdentity(anyLong(), anyString(), anyLong(), any(), any());
    BackupExecutionContext context = context(stateRepository);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var result = executor.submit(() ->
          runner.run(stubCommand(), Map.of("STUB_SLEEP", "1"), Duration.ofSeconds(20), context));
      assertThat(processRegistered.await(5, TimeUnit.SECONDS)).isTrue();

      context.requestStop("test lease revoked");

      assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(BackupExecutionStoppedException.class);
      assertThat(context.awaitExternalEffectsStopped(Duration.ZERO)).isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void test_run_missingExecutable_throwsWithGuidance() {
    assertThatThrownBy(
            () ->
            runner.run(
                    List.of("definitely-not-a-real-binary-xyz"),
                    Map.of(),
                    Duration.ofSeconds(5),
                    context(mockActiveState())))
        .isInstanceOf(BackupProcessException.class)
        .hasMessageContaining("命令启动失败");
  }

  private BackupStateRepository mockActiveState() {
    BackupStateRepository repository = mock(BackupStateRepository.class);
    when(repository.isOwnedActive(anyLong(), anyString(), any())).thenReturn(true);
    when(repository.recordProcessIdentity(anyLong(), anyString(), anyLong(), any(), any())).thenReturn(true);
    return repository;
  }

  private BackupExecutionContext context(BackupStateRepository repository) {
    return new BackupExecutionContext(7L, UUID.randomUUID().toString(), repository, Clock.systemUTC());
  }

  private List<String> stubCommand() {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    try {
      if (windows) {
        Path script = tempDir.resolve("stub.cmd");
        Files.writeString(
            script,
            "@echo off\r\n"
                + "if defined STUB_OUTPUT (echo %PGPASSWORD%)> \"%STUB_OUTPUT%\"\r\n"
                + "if \"%STUB_EXIT%\"==\"3\" (\r\n"
                + "  echo pg_dump: error details 1>&2\r\n"
                + "  exit /b 3\r\n"
                + ")\r\n"
                + "if defined STUB_SLEEP (\r\n"
                + "  ping -n 6 127.0.0.1 >nul\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "exit /b %STUB_EXIT%\r\n",
            StandardCharsets.UTF_8);
        return List.of("cmd.exe", "/c", script.toString());
      }
      Path script = tempDir.resolve("stub.sh");
      Files.writeString(
          script,
          "#!/bin/sh\n"
              + "if [ -n \"$STUB_OUTPUT\" ]; then printf '%s' \"$PGPASSWORD\" > \"$STUB_OUTPUT\"; fi\n"
              + "if [ \"$STUB_EXIT\" = \"3\" ]; then echo \"pg_dump: error details\" >&2; exit 3; fi\n"
              + "if [ -n \"$STUB_SLEEP\" ]; then sleep 5; fi\n"
              + "exit \"$STUB_EXIT\"\n",
          StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
      return List.of(script.toString());
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
