package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.backup.BackupTriggerResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

class BackupOrchestrationServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-10T02:00:00Z");
  private static final byte[] DUMP_BYTES = "qaflex-dump-content-0123456789".getBytes(StandardCharsets.UTF_8);
  private static final String LABEL = "testinst";
  private static final String FINGERPRINT = "SHA256:test-fingerprint";

  @TempDir Path tempRoot;

  private BackupSettingsRepository settingsRepository;
  private BackupRunRepository runRepository;
  private BackupStateRepository stateRepository;
  private BackupConfigurationProperties properties;
  private final TestBackupRemoteStorage storage = new TestBackupRemoteStorage();
  private ExecutorService runExecutor;
  private ScheduledExecutorService heartbeatExecutor;
  private ScheduledFuture<?> heartbeatFuture;
  private BackupOrchestrationService service;

  @BeforeEach
  void setUp() throws Exception {
    settingsRepository = mock(BackupSettingsRepository.class);
    runRepository = mock(BackupRunRepository.class);
    stateRepository = mock(BackupStateRepository.class);
    properties = new BackupConfigurationProperties();
    properties.setRoot(tempRoot.toString());
    properties.setInstanceLabel(LABEL);
    properties.setSecretKey(Base64.getEncoder().encodeToString(new byte[32]));

    runExecutor = mock(ExecutorService.class);
    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(runExecutor).execute(any());
    heartbeatExecutor = mock(ScheduledExecutorService.class);
    heartbeatFuture = mock(ScheduledFuture.class);
    when(heartbeatExecutor.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
        .thenReturn((ScheduledFuture) heartbeatFuture);

    when(runRepository.nextRunId()).thenReturn(42L);
    when(runRepository.databaseServerVersion()).thenReturn("16.4");
    when(runRepository.latestFlywayVersion()).thenReturn("20260903.01");
    when(runRepository.maxSuccessfulBytes()).thenReturn(OptionalLong.empty());
    when(stateRepository.tryStartRun(anyLong(), any(), any())).thenReturn(true);

    service = new BackupOrchestrationService(
        settingsRepository,
        runRepository,
        stateRepository,
        properties,
        new BackupCryptoSupport(properties),
        new BackupCommandFactory(properties),
        processRunnerThatWritesDump(0, ""),
        endpoint -> storage,
        BackupDatabaseTarget.from("jdbc:postgresql://pg:5432/qaflex", "qaflex", "real-password"),
        Clock.fixed(NOW, ZoneOffset.UTC),
        runExecutor,
        heartbeatExecutor);
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  @Test
  void test_triggerManual_whenRunAlreadyActive_rejectedWithoutNewRun() {
    when(stateRepository.tryStartRun(anyLong(), any(), any())).thenReturn(false);

    BackupTriggerResponse response = service.triggerManual();

    assertThat(response.accepted()).isFalse();
    assertThat(response.runId()).isNull();
    assertThat(response.message()).contains("已有备份正在运行");
    verify(runRepository, never()).insertRunning(anyLong(), any(), any(), any());
  }

  @Test
  void test_triggerManual_localMode_completesPipelineWithRetentionAndCleanup() throws Exception {
    when(settingsRepository.load()).thenReturn(Optional.of(localSettings(2)));
    Path targetDir = tempRoot.resolve(LABEL);
    Files.createDirectories(targetDir);
    Files.writeString(targetDir.resolve("qaflex_" + LABEL + "_20260901-030000.dump"), "old-1");
    Files.writeString(targetDir.resolve("qaflex_" + LABEL + "_20260902-030000.dump"), "old-2");
    Files.writeString(targetDir.resolve("qaflex_other_20260901-030000.dump"), "foreign");
    Files.writeString(targetDir.resolve("notes.txt"), "unrelated");

    BackupTriggerResponse response = service.triggerManual();

    assertThat(response.accepted()).isTrue();
    assertThat(response.runId()).isEqualTo(42L);
    verify(stateRepository).tryStartRun(eq(42L), any(), any());
    verify(runRepository).insertRunning(eq(42L), eq("MANUAL"), eq("LOCAL"), any());
    verify(runRepository).updateStage(42L, BackupOrchestrationService.STAGE_PRECHECK);
    verify(runRepository).updateStage(42L, BackupOrchestrationService.STAGE_DUMP);
    verify(runRepository).updateStage(42L, BackupOrchestrationService.STAGE_VERIFY);
    verify(runRepository).updateStage(42L, BackupOrchestrationService.STAGE_STORE);
    verify(runRepository).updateStage(42L, BackupOrchestrationService.STAGE_RETENTION);

    ArgumentCaptor<String> targetPath = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishSuccess(
        eq(42L), targetPath.capture(), any(), eq((long) DUMP_BYTES.length), any(), eq("16.4"),
        eq("20260903.01"), any(), any());
    assertThat(Path.of(targetPath.getValue())).exists();
    assertThat(Path.of(targetPath.getValue()).getParent()).isEqualTo(targetDir);

    // 保留 2 份：新产物 + 最新旧文件；最旧删除；无关文件与本实例模式之外绝不触碰。
    List<String> remaining = BackupFileSupport.listMatchedFileNames(
        targetDir, BackupFileSupport.dumpFilePattern(LABEL));
    assertThat(remaining).hasSize(2);
    assertThat(remaining).anySatisfy(name -> assertThat(name).endsWith("_20260902-030000.dump"));
    assertThat(targetDir.resolve("qaflex_other_20260901-030000.dump")).exists();
    assertThat(targetDir.resolve("notes.txt")).exists();
    // staging 中临时文件已随原子改名消失。
    assertThat(stagingFiles()).isEmpty();
    verify(stateRepository).release(eq(42L), any());
    verify(heartbeatFuture).cancel(false);
  }

  private List<String> stagingFiles() throws IOException {
    Path staging = tempRoot.resolve(LABEL + "/.staging");
    if (!Files.isDirectory(staging)) {
      return List.of();
    }
    try (var entries = Files.list(staging)) {
      return entries.map(entry -> entry.getFileName().toString()).toList();
    }
  }

  @Test
  void test_triggerManual_whenDumpFails_marksFailedCleansStagingAndReleases() throws Exception {
    when(settingsRepository.load()).thenReturn(Optional.of(localSettings(2)));
    service = new BackupOrchestrationService(
        settingsRepository,
        runRepository,
        stateRepository,
        properties,
        new BackupCryptoSupport(properties),
        new BackupCommandFactory(properties),
        processRunnerThatWritesDump(3, "pg_dump: error: connection refused"),
        endpoint -> storage,
        BackupDatabaseTarget.from("jdbc:postgresql://pg:5432/qaflex", "qaflex", "real-password"),
        Clock.fixed(NOW, ZoneOffset.UTC),
        runExecutor,
        heartbeatExecutor);

    service.triggerManual();

    verify(runRepository, never()).finishSuccess(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishFailure(eq(42L), message.capture(), any(), any());
    assertThat(message.getValue()).contains("pg_dump").contains("connection refused");
    assertThat(stagingFiles()).isEmpty();
    verify(stateRepository).release(eq(42L), any());
  }

  @Test
  void test_triggerManual_whenVerifyFails_marksFailedWithVerificationReason() {
    when(settingsRepository.load()).thenReturn(Optional.of(localSettings(2)));
    service = new BackupOrchestrationService(
        settingsRepository,
        runRepository,
        stateRepository,
        properties,
        new BackupCryptoSupport(properties),
        new BackupCommandFactory(properties),
        processRunnerThatWritesDump(1, "pg_restore: unsupported"),
        endpoint -> storage,
        BackupDatabaseTarget.from("jdbc:postgresql://pg:5432/qaflex", "qaflex", "real-password"),
        Clock.fixed(NOW, ZoneOffset.UTC),
        runExecutor,
        heartbeatExecutor);

    service.triggerManual();

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishFailure(eq(42L), message.capture(), any(), any());
    assertThat(message.getValue()).contains("可恢复性校验未通过").contains("pg_restore: unsupported");
  }

  @Test
  void test_triggerManual_localMode_insufficientDiskSpace_failsInPrecheck() {
    when(settingsRepository.load()).thenReturn(Optional.of(localSettings(2)));
    when(runRepository.maxSuccessfulBytes()).thenReturn(OptionalLong.of(Long.MAX_VALUE / 2));

    service.triggerManual();

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishFailure(eq(42L), message.capture(), any(), any());
    assertThat(message.getValue()).contains("可用空间不足");
    verify(runRepository, never()).updateStage(42L, BackupOrchestrationService.STAGE_DUMP);
  }

  @Test
  void test_triggerManual_remoteMode_uploadsVerifiedCopiesAndDropsLocalStaging() throws Exception {
    when(settingsRepository.load()).thenReturn(Optional.of(remoteSettings(2)));
    storage.sha256 = Optional.of(expectedSha256());
    storage.fileSizeOverride = (long) DUMP_BYTES.length;

    service.triggerManual();

    assertThat(storage.operations).contains("authenticate", "ensureDirectory:/remote/backups");
    assertThat(storage.authenticatedPassword).isEqualTo("real-password");
    assertThat(storage.operations.stream().filter(op -> op.startsWith("upload:")))
        .singleElement()
        .satisfies(op -> assertThat(op).startsWith("upload:/remote/backups/").endsWith(".dump"));
    ArgumentCaptor<String> targetPath = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishSuccess(
        eq(42L), targetPath.capture(), any(), eq((long) DUMP_BYTES.length), any(), any(), any(), any(), any());
    assertThat(targetPath.getValue()).startsWith("/remote/backups/");
    // 成品仅存远端一份：本地 staging 临时文件已删除。
    assertThat(stagingFiles()).isEmpty();
    verify(runRepository).updateStage(42L, BackupOrchestrationService.STAGE_RETENTION);
  }

  @Test
  void test_triggerManual_remoteMode_fingerprintMismatch_failsBeforeUpload() {
    when(settingsRepository.load()).thenReturn(Optional.of(remoteSettings(2)));
    storage.fingerprint = "SHA256:changed-key";

    service.triggerManual();

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishFailure(eq(42L), message.capture(), any(), any());
    assertThat(message.getValue()).contains("指纹与已保存配置不一致");
    assertThat(storage.operations).doesNotContain("authenticate");
    verify(runRepository, never()).finishSuccess(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void test_triggerManual_remoteMode_remoteShaMismatch_failsAfterUpload() throws Exception {
    when(settingsRepository.load()).thenReturn(Optional.of(remoteSettings(2)));
    storage.sha256 = Optional.of("not-the-real-sha");
    storage.fileSizeOverride = (long) DUMP_BYTES.length;

    service.triggerManual();

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishFailure(eq(42L), message.capture(), any(), any());
    assertThat(message.getValue()).contains("SHA-256");
    assertThat(storage.operations).noneMatch(op -> op.startsWith("delete:"));
  }

  @Test
  void test_triggerManual_remoteMode_insufficientRemoteSpace_failsInPrecheck() {
    when(settingsRepository.load()).thenReturn(Optional.of(remoteSettings(2)));
    storage.freeSpaceBytes = 1024;

    service.triggerManual();

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishFailure(eq(42L), message.capture(), any(), any());
    assertThat(message.getValue()).contains("远程目录可用空间不足");
    assertThat(storage.operations).noneMatch(op -> op.startsWith("upload:"));
  }

  @Test
  void test_triggerManual_whenExecutorRejected_failsRunAndReleases() {
    when(settingsRepository.load()).thenReturn(Optional.of(localSettings(2)));
    ExecutorService rejectingExecutor = mock(ExecutorService.class);
    org.mockito.Mockito.doThrow(new RejectedExecutionException("shutting down"))
        .when(rejectingExecutor)
        .execute(any());
    service = new BackupOrchestrationService(
        settingsRepository,
        runRepository,
        stateRepository,
        properties,
        new BackupCryptoSupport(properties),
        new BackupCommandFactory(properties),
        processRunnerThatWritesDump(0, ""),
        endpoint -> storage,
        BackupDatabaseTarget.from("jdbc:postgresql://pg:5432/qaflex", "qaflex", "real-password"),
        Clock.fixed(NOW, ZoneOffset.UTC),
        rejectingExecutor,
        heartbeatExecutor);

    assertThatThrownBy(() -> service.triggerManual()).isInstanceOf(BizException.class);

    verify(runRepository).finishFailure(eq(42L), any(), any(), any());
    verify(stateRepository).release(eq(42L), any());
  }

  @Test
  void test_triggerManual_remoteMode_withoutStoredPassword_failsWithExplicitReason() {
    when(settingsRepository.load()).thenReturn(Optional.of(remoteSettingsWithoutPassword(2)));

    service.triggerManual();

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(runRepository).finishFailure(eq(42L), message.capture(), any(), any());
    assertThat(message.getValue()).contains("远程密码尚未保存");
  }

  private BackupProcessRunner processRunnerThatWritesDump(int verifyExitCode, String verifyStderr) {
    BackupProcessRunner runner = mock(BackupProcessRunner.class);
    when(runner.run(any(), any(), any()))
        .thenAnswer((Answer<BackupProcessRunner.ProcessResult>) invocation -> {
          List<String> command = invocation.getArgument(0);
          if (command.get(0).equals("pg_dump")) {
            String fileArg =
                command.stream().filter(arg -> arg.startsWith("--file=")).findFirst().orElseThrow();
            Path dumpFile = Path.of(fileArg.substring("--file=".length()));
            Files.write(dumpFile, DUMP_BYTES);
            return new BackupProcessRunner.ProcessResult(0, "");
          }
          return new BackupProcessRunner.ProcessResult(verifyExitCode, verifyStderr);
        });
    return runner;
  }

  private String expectedSha256() throws IOException {
    Path probe = tempRoot.resolve("sha-probe.bin");
    Files.write(probe, DUMP_BYTES);
    String sha = BackupFileSupport.sha256Hex(probe);
    Files.deleteIfExists(probe);
    return sha;
  }

  private BackupSettings localSettings(int retention) {
    return new BackupSettings(
        true, LocalTime.of(3, 0), retention, "LOCAL", null, "backup-host", 22, "oper",
        "v1:cipher", "/remote/backups", FINGERPRINT, 1, "admin", NOW);
  }

  private BackupSettings remoteSettings(int retention) {
    return new BackupSettings(
        true, LocalTime.of(3, 0), retention, "REMOTE", null, "backup-host", 22, "oper",
        new BackupCryptoSupport(properties).encrypt("real-password"), "/remote/backups",
        FINGERPRINT, 1, "admin", NOW);
  }

  private BackupSettings remoteSettingsWithoutPassword(int retention) {
    return new BackupSettings(
        true, LocalTime.of(3, 0), retention, "REMOTE", null, "backup-host", 22, "oper",
        null, "/remote/backups", FINGERPRINT, 1, "admin", NOW);
  }
}
