package com.data.collection.platform.service.backup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.backup.BackupTriggerResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 备份执行编排：手动与定时共用同一条流水线——
 * 抢运行权 → 磁盘预检 → pg_dump 导出 → pg_restore --list 可恢复性校验 → 本地原子落位 / 远程上传校验 →
 * 按保留份数轮转 → 终态落库。任一环节失败即 FAILED 并清理临时文件，已有备份零影响，不自动重试。
 * 本地产物经挂载目录直落宿主机；远程产物上传校验通过后删除本地临时副本（成品仅存远端一份）。
 */
@Service
public class BackupOrchestrationService {
  private static final Logger log = LoggerFactory.getLogger(BackupOrchestrationService.class);
  static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Shanghai");
  static final String STAGE_PRECHECK = "PRECHECK";
  static final String STAGE_DUMP = "DUMP";
  static final String STAGE_VERIFY = "VERIFY";
  static final String STAGE_STORE = "STORE";
  static final String STAGE_RETENTION = "RETENTION";
  private static final String STAGING_TMP_PREFIX = "tmp-";
  private static final String STAGING_TMP_SUFFIX = ".dump";

  private final BackupSettingsRepository settingsRepository;
  private final BackupRunRepository runRepository;
  private final BackupStateRepository stateRepository;
  private final BackupConfigurationProperties properties;
  private final BackupCryptoSupport crypto;
  private final BackupCommandFactory commandFactory;
  private final BackupProcessRunner processRunner;
  private final BackupRemoteStorageFactory remoteStorageFactory;
  private final BackupDatabaseTarget databaseTarget;
  private final Clock clock;
  private final ExecutorService runExecutor;
  private final ScheduledExecutorService heartbeatExecutor;

  @Autowired
  public BackupOrchestrationService(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupStateRepository stateRepository,
      BackupConfigurationProperties properties,
      BackupCryptoSupport crypto,
      BackupCommandFactory commandFactory,
      BackupProcessRunner processRunner,
      BackupRemoteStorageFactory remoteStorageFactory,
      @Value("${spring.datasource.url}") String datasourceUrl,
      @Value("${spring.datasource.username}") String datasourceUsername,
      @Value("${spring.datasource.password}") String datasourcePassword) {
    this(
        settingsRepository,
        runRepository,
        stateRepository,
        properties,
        crypto,
        commandFactory,
        processRunner,
        remoteStorageFactory,
        BackupDatabaseTarget.from(datasourceUrl, datasourceUsername, datasourcePassword),
        Clock.systemUTC(),
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("backup-run-", 0).factory()),
        heartbeatExecutor());
  }

  BackupOrchestrationService(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupStateRepository stateRepository,
      BackupConfigurationProperties properties,
      BackupCryptoSupport crypto,
      BackupCommandFactory commandFactory,
      BackupProcessRunner processRunner,
      BackupRemoteStorageFactory remoteStorageFactory,
      BackupDatabaseTarget databaseTarget,
      Clock clock,
      ExecutorService runExecutor,
      ScheduledExecutorService heartbeatExecutor) {
    this.settingsRepository = settingsRepository;
    this.runRepository = runRepository;
    this.stateRepository = stateRepository;
    this.properties = properties;
    this.crypto = crypto;
    this.commandFactory = commandFactory;
    this.processRunner = processRunner;
    this.remoteStorageFactory = remoteStorageFactory;
    this.databaseTarget = databaseTarget;
    this.clock = clock;
    this.runExecutor = runExecutor;
    this.heartbeatExecutor = heartbeatExecutor;
  }

  /** 手动触发一次备份；已有运行在执行时返回 accepted=false 的业务级拒绝。 */
  public BackupTriggerResponse triggerManual() {
    return submit("MANUAL");
  }

  /** 定时调度触发入口，与手动触发共用流水线。 */
  BackupTriggerResponse triggerScheduled() {
    return submit("SCHEDULE");
  }

  @PreDestroy
  public void shutdown() {
    runExecutor.shutdown();
    heartbeatExecutor.shutdownNow();
  }

  private BackupTriggerResponse submit(String triggerType) {
    BackupSettings settings = settingsRepository.load().orElse(BackupSettings.defaults());
    Instant now = clock.instant();
    Duration lease = Duration.ofSeconds(properties.getLeaseSeconds());
    long runId = runRepository.nextRunId();
    if (!stateRepository.tryStartRun(runId, now.plus(lease), now)) {
      return new BackupTriggerResponse(false, null, "已有备份正在运行，请等待其完成后再触发");
    }
    try {
      runRepository.insertRunning(runId, triggerType, settings.storageMode(), now);
      runExecutor.execute(() -> executeRun(runId));
      return new BackupTriggerResponse(true, runId, "备份已开始执行");
    } catch (RejectedExecutionException rejected) {
      runRepository.finishFailure(runId, "备份执行器不可用", now, clock.instant());
      stateRepository.release(runId, clock.instant());
      throw new BizException("备份执行器不可用，请稍后重试");
    }
  }

  private void executeRun(long runId) {
    Instant startedAt = clock.instant();
    StageTracker stage = new StageTracker(STAGE_PRECHECK);
    Path tmpFile = null;
    ScheduledFuture<?> heartbeat = startHeartbeat(runId);
    try {
      BackupSettings settings = settingsRepository.load().orElse(BackupSettings.defaults());
      String label = BackupFileSupport.sanitizeLabel(properties.getInstanceLabel());
      Path root = Path.of(properties.getRoot());
      Path stagingDir = root.resolve(label).resolve(BackupFileSupport.STAGING_DIR_NAME);
      Files.createDirectories(stagingDir);
      removeStaleStagingFiles(stagingDir);
      tmpFile = stagingDir.resolve(STAGING_TMP_PREFIX + runId + STAGING_TMP_SUFFIX);

      stage.advance(runId, STAGE_PRECHECK);
      long requiredBytes = requiredFreeBytes();
      checkLocalFreeSpace(stagingDir, requiredBytes);
      if (settings.remoteMode()) {
        requireRemoteFreeSpace(settings, requiredBytes);
      }

      stage.advance(runId, STAGE_DUMP);
      BackupProcessRunner.ProcessResult dumpResult =
          processRunner.run(
              commandFactory.dumpCommand(databaseTarget, tmpFile),
              Map.of("PGPASSWORD", databaseTarget.password()),
              dumpTimeout());
      if (dumpResult.exitCode() != 0) {
        throw new BizException(
            "pg_dump 导出失败（退出码 " + dumpResult.exitCode() + "）：" + describe(dumpResult.stderr()));
      }
      if (!Files.exists(tmpFile) || Files.size(tmpFile) == 0) {
        throw new BizException("pg_dump 未生成有效的备份文件");
      }

      stage.advance(runId, STAGE_VERIFY);
      BackupProcessRunner.ProcessResult verifyResult =
          processRunner.run(commandFactory.verifyCommand(tmpFile), Map.of(), dumpTimeout());
      if (verifyResult.exitCode() != 0) {
        throw new BizException(
            "备份文件可恢复性校验未通过（文件可能被截断或损坏，pg_restore --list 退出码 "
                + verifyResult.exitCode()
                + "）："
                + describe(verifyResult.stderr()));
      }
      long fileBytes = Files.size(tmpFile);
      String sha256 = BackupFileSupport.sha256Hex(tmpFile);
      String pgServerVersion = runRepository.databaseServerVersion();
      String flywayVersion = runRepository.latestFlywayVersion();

      String fileName = BackupFileSupport.fileNameFor(label, LocalDateTime.now(PLATFORM_ZONE));
      String targetPath;
      if (settings.remoteMode()) {
        stage.advance(runId, STAGE_STORE);
        targetPath = storeRemote(runId, stage, settings, tmpFile, fileName, fileBytes, sha256, label);
      } else {
        stage.advance(runId, STAGE_STORE);
        Path targetDir = localTargetDir(root, label, settings.localSubdirectory());
        Files.createDirectories(targetDir);
        Path finalFile = targetDir.resolve(fileName);
        Files.move(tmpFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        tmpFile = null;
        stage.advance(runId, STAGE_RETENTION);
        rotateLocal(targetDir, label, settings.retentionCopies());
        targetPath = finalFile.toString();
      }

      runRepository.finishSuccess(
          runId,
          targetPath,
          fileName,
          fileBytes,
          sha256,
          pgServerVersion,
          flywayVersion,
          startedAt,
          clock.instant());
      log.info(
          "backup_run_success runId={} mode={} file={} bytes={} durationMs={}",
          runId,
          settings.storageMode(),
          fileName,
          fileBytes,
          Duration.between(startedAt, clock.instant()).toMillis());
    } catch (IOException failure) {
      fail(runId, stage.current(), "备份执行 IO 异常：" + describe(failure.getMessage()), startedAt);
    } catch (RuntimeException failure) {
      fail(runId, stage.current(), describe(failure.getMessage()), startedAt);
    } finally {
      heartbeat.cancel(false);
      if (tmpFile != null) {
        try {
          Files.deleteIfExists(tmpFile);
        } catch (IOException cleanupFailure) {
          log.warn("backup_staging_cleanup_failed runId={} path={}", runId, tmpFile);
        }
      }
      stateRepository.release(runId, clock.instant());
    }
  }

  /**
   * 远程落位：指纹校验 → 认证 → 目录就绪 → 上传（临时名 + 远端改名）→ 大小与可选 SHA-256 校验 →
   * 删除本地临时副本（成品仅存远端一份）→ 远端轮转。
   */
  private String storeRemote(
      long runId,
      StageTracker stage,
      BackupSettings settings,
      Path tmpFile,
      String fileName,
      long fileBytes,
      String sha256,
      String label) {
    try (BackupRemoteStorage storage = openVerifiedStorage(settings)) {
      storage.authenticate(decryptedPassword(settings));
      String remoteDirectory = settings.remoteDirectory();
      storage.ensureDirectory(remoteDirectory);
      storage.upload(tmpFile, remoteDirectory, fileName);
      long remoteBytes = storage.fileSize(remoteDirectory, fileName);
      if (remoteBytes != fileBytes) {
        throw new BizException(
            "远端文件大小不一致（本地 " + fileBytes + " 字节，远端 " + remoteBytes + " 字节），远端产物不可信");
      }
      storage
          .sha256(remoteDirectory, fileName)
          .filter(remoteSha256 -> !remoteSha256.equalsIgnoreCase(sha256))
          .ifPresent(
              remoteSha256 -> {
                throw new BizException("远端文件 SHA-256 与本地不一致，远端产物不可信");
              });
      stage.advance(runId, STAGE_RETENTION);
      rotateRemote(storage, remoteDirectory, label, settings.retentionCopies());
      return remoteDirectory + "/" + fileName;
    }
  }

  /** 打开远程会话并完成主机指纹校验；指纹不一致时关闭会话并给出处置指引。 */
  private BackupRemoteStorage openVerifiedStorage(BackupSettings settings) {
    if (!crypto.isConfigured()) {
      throw new BizException("服务端未配置备份主密钥 PLATFORM_BACKUP_SECRET_KEY，无法解密远程密码");
    }
    if (!settings.hasStoredRemotePassword()) {
      throw new BizException("远程密码尚未保存，无法执行远程备份");
    }
    BackupRemoteStorage storage =
        remoteStorageFactory.open(
            new BackupRemoteEndpoint(settings.remoteHost(), settings.remotePort(), settings.remoteUsername()));
    String expected = settings.remoteHostKeyFingerprint();
    if (expected != null && !expected.isBlank() && !expected.equals(storage.hostKeyFingerprint())) {
      String actual = storage.hostKeyFingerprint();
      storage.close();
      throw new BizException(
          "远程服务器主机密钥指纹与已保存配置不一致（疑似服务器变更或中间人）：已保存 "
              + expected
              + "，实际 "
              + actual
              + "。如确认服务器已变更，请在备份页重新测试连接并更新指纹后保存");
    }
    return storage;
  }

  private String decryptedPassword(BackupSettings settings) {
    return crypto.decrypt(settings.remotePasswordCipher());
  }

  private void rotateLocal(Path targetDir, String label, int retentionCopies) {
    Pattern pattern = BackupFileSupport.dumpFilePattern(label);
    List<String> names = BackupFileSupport.listMatchedFileNames(targetDir, pattern);
    deleteBeyondRetention(names, pattern, retentionCopies, name -> Files.deleteIfExists(targetDir.resolve(name)));
  }

  private void rotateRemote(BackupRemoteStorage storage, String directory, String label, int retentionCopies) {
    Pattern pattern = BackupFileSupport.dumpFilePattern(label);
    List<String> names = storage.listFileNames(directory, pattern);
    deleteBeyondRetention(names, pattern, retentionCopies, name -> storage.deleteFile(directory, name));
  }

  private void deleteBeyondRetention(
      List<String> names, Pattern pattern, int retentionCopies, FileDeleter deleter) {
    for (String name : BackupFileSupport.namesBeyondRetention(names, pattern, retentionCopies)) {
      try {
        deleter.delete(name);
      } catch (IOException | RuntimeException failure) {
        // 单个旧文件删除失败仅告警不中断：轮转失败不影响本次备份产物的有效性。
        log.warn("backup_retention_delete_failed file={} message={}", name, describe(failure.getMessage()));
      }
    }
  }

  private long requiredFreeBytes() {
    long lastBytes = runRepository.maxSuccessfulBytes().orElse(0);
    return Math.max(lastBytes * 2, BackupFileSupport.megabytesToBytes(properties.getHeadroomMb()));
  }

  private void checkLocalFreeSpace(Path directory, long requiredBytes) {
    try {
      long usable = Files.getFileStore(directory).getUsableSpace();
      if (usable < requiredBytes) {
        throw new BizException(
            "备份目标磁盘可用空间不足：需要约 " + BackupFileSupport.humanBytes(requiredBytes)
                + "，可用 " + BackupFileSupport.humanBytes(usable));
      }
    } catch (IOException failure) {
      throw new BizException("备份目标磁盘空间探测失败：" + describe(failure.getMessage()));
    }
  }

  private void requireRemoteFreeSpace(BackupSettings settings, long requiredBytes) {
    try (BackupRemoteStorage storage = openVerifiedStorage(settings)) {
      storage.authenticate(decryptedPassword(settings));
      storage.ensureDirectory(settings.remoteDirectory());
      long free = storage.freeSpaceBytes(settings.remoteDirectory());
      if (free < requiredBytes) {
        throw new BizException(
            "远程目录可用空间不足：需要约 " + BackupFileSupport.humanBytes(requiredBytes)
                + "，可用 " + BackupFileSupport.humanBytes(free));
      }
    }
  }

  private Path localTargetDir(Path root, String label, String configuredSubdirectory) {
    String subdirectory = BackupFileSupport.resolveLocalSubdirectory(configuredSubdirectory);
    Path labelDir = root.resolve(label);
    return subdirectory == null ? labelDir : labelDir.resolve(subdirectory);
  }

  private void removeStaleStagingFiles(Path stagingDir) throws IOException {
    // 单运行权保证同一时刻只有一个运行在写 staging；任何残留 tmp 文件都是历史中断的垃圾。
    try (var entries = Files.list(stagingDir)) {
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        if (name.startsWith(STAGING_TMP_PREFIX) && name.endsWith(STAGING_TMP_SUFFIX)) {
          Files.deleteIfExists(entry);
        }
      }
    }
  }

  private ScheduledFuture<?> startHeartbeat(long runId) {
    long intervalMillis = Math.max(1000, properties.getLeaseSeconds() * 1000L / 3);
    return heartbeatExecutor.scheduleAtFixedRate(
        () -> {
          try {
            stateRepository.heartbeat(
                runId, clock.instant().plusSeconds(properties.getLeaseSeconds()), clock.instant());
          } catch (RuntimeException failure) {
            log.warn("backup_heartbeat_failed runId={} message={}", runId, describe(failure.getMessage()));
          }
        },
        intervalMillis,
        intervalMillis,
        TimeUnit.MILLISECONDS);
  }

  private void fail(long runId, String stage, String message, Instant startedAt) {
    log.warn("backup_run_failed runId={} stage={} message={}", runId, stage, message);
    runRepository.finishFailure(runId, message, startedAt, clock.instant());
  }

  private Duration dumpTimeout() {
    return Duration.ofMinutes(properties.getDumpTimeoutMinutes());
  }

  private static String describe(String message) {
    return message == null || message.isBlank() ? "无详细错误信息" : message;
  }

  private static ScheduledExecutorService heartbeatExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        task -> {
          Thread thread = new Thread(task, "backup-heartbeat");
          thread.setDaemon(true);
          return thread;
        });
  }

  /** 阶段推进的可变追踪：写库并同步内存值，失败终态据此保留最后阶段。 */
  private final class StageTracker {
    private String current;

    private StageTracker(String initial) {
      this.current = initial;
    }

    private void advance(long runId, String next) {
      runRepository.updateStage(runId, next);
      current = next;
    }

    private String current() {
      return current;
    }
  }

  @FunctionalInterface
  private interface FileDeleter {
    void delete(String fileName) throws IOException;
  }
}
