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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 备份执行编排：手动与定时共用同一条流水线——
 * 抢占带 token 的运行权 → 导出与恢复性校验 → 身份化产物暂存 → 条件提交 SUCCESS → 仅轮转已登记成功产物。
 * 租约撤销会取消外部操作；不能确认停止时保留运行权、进程身份与临时文件，交由恢复巡检继续处置。
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
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(20);

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
  private final Map<Long, BackupExecutionContext> executions = new ConcurrentHashMap<>();

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
  /** 停止心跳并向所有在途备份发送取消信号。 */
  public void shutdown() {
    executions.values().forEach(context -> context.requestStop("备份服务正在关闭"));
    runExecutor.shutdown();
    heartbeatExecutor.shutdownNow();
  }

  private BackupTriggerResponse submit(String triggerType) {
    BackupSettings settings = settingsRepository.load().orElse(BackupSettings.defaults());
    Instant now = clock.instant();
    Duration lease = Duration.ofSeconds(properties.getLeaseSeconds());
    long runId = runRepository.nextRunId();
    String executionToken = UUID.randomUUID().toString();
    if (!stateRepository.tryStartRun(runId, executionToken, now.plus(lease), now)) {
      return new BackupTriggerResponse(false, null, "已有备份正在运行，请等待其完成后再触发");
    }
    BackupExecutionContext context = new BackupExecutionContext(runId, executionToken, stateRepository, clock);
    try {
      runRepository.insertRunning(runId, executionToken, triggerType, settings.storageMode(), now);
      executions.put(runId, context);
      runExecutor.execute(() -> executeRun(context, settings, now));
      return new BackupTriggerResponse(true, runId, "备份已开始执行");
    } catch (RejectedExecutionException rejected) {
      settleQuietly(runId, executionToken, "备份执行器不可用");
      executions.remove(runId, context);
      throw new BizException("备份执行器不可用，请稍后重试");
    } catch (RuntimeException failure) {
      settleQuietly(runId, executionToken, describe(failure.getMessage()));
      executions.remove(runId, context);
      throw failure;
    }
  }

  /** 提交阶段失败的尽力结算：数据库不可用时保留状态交恢复巡检，且不掩盖原始失败。 */
  private void settleQuietly(long runId, String executionToken, String message) {
    try {
      stateRepository.settleOwnedRun(runId, executionToken, message, clock.instant());
    } catch (RuntimeException settlementFailure) {
      log.warn(
          "backup_submit_settlement_failed runId={} message={}",
          runId,
          describe(settlementFailure.getMessage()));
    }
  }

  private void executeRun(BackupExecutionContext context, BackupSettings settings, Instant startedAt) {
    context.beginWorker();
    long runId = context.runId();
    String executionToken = context.executionToken();
    StageTracker stage = new StageTracker(STAGE_PRECHECK);
    Path tmpFile = null;
    boolean successCommitted = false;
    String failureMessage = null;
    ScheduledFuture<?> heartbeat = startHeartbeat(context);
    try {
      String label = BackupFileSupport.sanitizeLabel(properties.getInstanceLabel());
      Path root = Path.of(properties.getRoot());
      Path stagingDir = root.resolve(label).resolve(BackupFileSupport.STAGING_DIR_NAME);
      Files.createDirectories(stagingDir);
      tmpFile = stagingDir.resolve("tmp-" + runId + "-" + executionToken + ".dump");

      context.requireActive();
      stage.advance(context, STAGE_PRECHECK);
      long requiredBytes = requiredFreeBytes();
      checkLocalFreeSpace(stagingDir, requiredBytes);
      if (settings.remoteMode()) {
        requireRemoteFreeSpace(context, settings, requiredBytes);
      }

      stage.advance(context, STAGE_DUMP);
      BackupProcessRunner.ProcessResult dumpResult =
          processRunner.run(
              commandFactory.dumpCommand(databaseTarget, tmpFile),
              Map.of("PGPASSWORD", databaseTarget.password()),
              dumpTimeout(),
              context);
      context.requireActive();
      if (dumpResult.exitCode() != 0) {
        throw new BizException(
            "pg_dump 导出失败（退出码 " + dumpResult.exitCode() + "）：" + describe(dumpResult.stderr()));
      }
      if (!Files.exists(tmpFile) || Files.size(tmpFile) == 0) {
        throw new BizException("pg_dump 未生成有效的备份文件");
      }

      stage.advance(context, STAGE_VERIFY);
      BackupProcessRunner.ProcessResult verifyResult =
          processRunner.run(commandFactory.verifyCommand(tmpFile), Map.of(), dumpTimeout(), context);
      context.requireActive();
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

      String fileName =
          BackupFileSupport.fileNameFor(
              label,
              LocalDateTime.ofInstant(clock.instant(), PLATFORM_ZONE),
              runId,
              executionToken);
      Path targetDir = settings.remoteMode()
          ? null
          : localTargetDir(root, label, settings.localSubdirectory());
      if (targetDir != null) {
        Files.createDirectories(targetDir);
      }
      String targetPath = settings.remoteMode()
          ? remoteTargetPath(settings.remoteDirectory(), fileName)
          : targetDir.resolve(fileName).toString();
      stage.advance(context, STAGE_STORE);
      context.requireActive();
      runRepository.recordCandidateArtifact(
          runId, executionToken, targetPath, fileName, clock.instant());
      if (settings.remoteMode()) {
        targetPath = storeRemote(context, settings, tmpFile, fileName, fileBytes, sha256);
      } else {
        Path finalFile = targetDir.resolve(fileName);
        context.requireActive();
        Files.move(tmpFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        tmpFile = null;
      }

      context.requireActive();
      runRepository.finishSuccess(
          runId,
          executionToken,
          targetPath,
          fileName,
          fileBytes,
          sha256,
          pgServerVersion,
          flywayVersion,
          startedAt,
          clock.instant());
      successCommitted = true;
      stage.mark(STAGE_RETENTION);
      try {
        boolean retentionComplete;
        if (settings.remoteMode()) {
          retentionComplete =
              rotateRemote(context, settings, label, settings.storageMode(), settings.retentionCopies());
        } else {
          retentionComplete =
              rotateLocal(context, targetDir, label, settings.storageMode(), settings.retentionCopies());
        }
        if (retentionComplete) {
          context.requireActive();
          runRepository.finishRetention(runId, executionToken, clock.instant());
        } else {
          log.warn("backup_retention_incomplete runId={} message=部分旧产物未能删除", runId);
        }
      } catch (RuntimeException retentionFailure) {
        // 产物已提交成功；轮转问题只保留在日志和 RETENTION 阶段，不反写成功终态。
        log.warn(
            "backup_retention_incomplete runId={} message={}",
            runId,
            describe(retentionFailure.getMessage()));
      }
      log.info(
          "backup_run_success runId={} mode={} file={} bytes={} durationMs={}",
          runId,
          settings.storageMode(),
          fileName,
          fileBytes,
          Duration.between(startedAt, clock.instant()).toMillis());
    } catch (IOException | RuntimeException failure) {
      if (!successCommitted) {
        failureMessage = describe(failure.getMessage());
        log.warn(
            "backup_run_failed runId={} stage={} message={}",
            runId,
            stage.current(),
            failureMessage);
      }
    } finally {
      heartbeat.cancel(false);
      context.requestStop(successCommitted ? "备份执行已完成" : "备份执行已结束");
      boolean quiescent = context.awaitExternalEffectsStopped(Duration.ZERO);
      if (tmpFile != null && quiescent) {
        try {
          Files.deleteIfExists(tmpFile);
        } catch (IOException cleanupFailure) {
          log.warn("backup_staging_cleanup_failed runId={} path={}", runId, tmpFile);
        }
      }
      boolean settled = false;
      if (quiescent) {
        try {
          settled = stateRepository.settleOwnedRun(runId, executionToken, failureMessage, clock.instant());
        } catch (RuntimeException settlementFailure) {
          // 结算未确认时保留执行上下文，让恢复巡检按 token 与进程身份继续收敛。
          log.warn(
              "backup_run_settlement_failed runId={} message={}",
              runId,
              describe(settlementFailure.getMessage()));
        }
      } else {
        log.error("backup_execution_stop_unconfirmed runId={} token={}", runId, executionToken);
      }
      context.endWorker();
      if (settled) {
        executions.remove(runId, context);
      }
    }
  }

  /**
   * 远程落位：指纹校验 → 认证 → 目录就绪 → 上传（临时名 + 远端改名）→ 大小与可选 SHA-256 校验 →
   * 删除本地临时副本（成品仅存远端一份）→ 远端轮转。
   */
  private String storeRemote(
      BackupExecutionContext context,
      BackupSettings settings,
      Path tmpFile,
      String fileName,
      long fileBytes,
      String sha256) {
    context.beginExternalOperation();
    BackupRemoteStorage storage = null;
    try {
      storage = openVerifiedStorage(context, settings);
      context.requireActive();
      storage.authenticate(decryptedPassword(settings));
      String remoteDirectory = settings.remoteDirectory();
      context.requireActive();
      storage.ensureDirectory(remoteDirectory);
      context.requireActive();
      storage.upload(tmpFile, remoteDirectory, fileName);
      context.requireActive();
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
      context.requireActive();
      return remoteTargetPath(remoteDirectory, fileName);
    } finally {
      try {
        if (storage != null) {
          storage.close();
          context.detachStorage(storage);
        }
      } finally {
        context.endExternalOperation();
      }
    }
  }

  /** 打开远程会话并完成主机指纹校验；指纹不一致时关闭会话并给出处置指引。 */
  private BackupRemoteStorage openVerifiedStorage(
      BackupExecutionContext context, BackupSettings settings) {
    if (!crypto.isConfigured()) {
      throw new BizException("服务端未配置备份主密钥 PLATFORM_BACKUP_SECRET_KEY，无法解密远程密码");
    }
    if (!settings.hasStoredRemotePassword()) {
      throw new BizException("远程密码尚未保存，无法执行远程备份");
    }
    BackupRemoteStorage storage =
        remoteStorageFactory.open(
            new BackupRemoteEndpoint(settings.remoteHost(), settings.remotePort(), settings.remoteUsername()));
    context.attachStorage(storage);
    String expected = settings.remoteHostKeyFingerprint();
    if (expected != null && !expected.isBlank() && !expected.equals(storage.hostKeyFingerprint())) {
      String actual = storage.hostKeyFingerprint();
      storage.close();
      context.detachStorage(storage);
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

  private boolean rotateLocal(
      BackupExecutionContext context, Path targetDir, String label, String storageMode, int retentionCopies) {
    Pattern pattern = BackupFileSupport.dumpFilePattern(label);
    List<String> names = BackupFileSupport.listMatchedFileNames(targetDir, pattern);
    Set<String> registered = new HashSet<>();
    Path absoluteTarget = targetDir.toAbsolutePath().normalize();
    for (BackupRunRepository.SuccessfulArtifact artifact : runRepository.successfulArtifacts(storageMode)) {
      Path registeredPath = Path.of(artifact.targetPath()).toAbsolutePath().normalize();
      if (absoluteTarget.equals(registeredPath.getParent())) {
        registered.add(artifact.fileName());
      }
    }
    names.removeIf(name -> !registered.contains(name));
    context.requireActive();
    return deleteBeyondRetention(
        context,
        names,
        pattern,
        retentionCopies,
        name -> Files.deleteIfExists(targetDir.resolve(name)));
  }

  private boolean rotateRemote(
      BackupExecutionContext context,
      BackupSettings settings,
      String label,
      String storageMode,
      int retentionCopies) {
    context.beginExternalOperation();
    BackupRemoteStorage storage = null;
    try {
      storage = openVerifiedStorage(context, settings);
      storage.authenticate(decryptedPassword(settings));
      String directory = settings.remoteDirectory();
      Pattern pattern = BackupFileSupport.dumpFilePattern(label);
      List<String> names = new ArrayList<>(storage.listFileNames(directory, pattern));
      String targetPrefix = directory.endsWith("/") ? directory : directory + "/";
      Set<String> registered = new HashSet<>();
      for (BackupRunRepository.SuccessfulArtifact artifact : runRepository.successfulArtifacts(storageMode)) {
        if (artifact.targetPath().equals(targetPrefix + artifact.fileName())) {
          registered.add(artifact.fileName());
        }
      }
      names.removeIf(name -> !registered.contains(name));
      context.requireActive();
      BackupRemoteStorage activeStorage = storage;
      return deleteBeyondRetention(
          context,
          names,
          pattern,
          retentionCopies,
          name -> activeStorage.deleteFile(directory, name));
    } finally {
      try {
        if (storage != null) {
          storage.close();
          context.detachStorage(storage);
        }
      } finally {
        context.endExternalOperation();
      }
    }
  }

  private boolean deleteBeyondRetention(
      BackupExecutionContext context,
      List<String> names,
      Pattern pattern,
      int retentionCopies,
      FileDeleter deleter) {
    boolean complete = true;
    for (String name : BackupFileSupport.namesBeyondRetention(names, pattern, retentionCopies)) {
      context.requireActive();
      try {
        deleter.delete(name);
      } catch (IOException | RuntimeException failure) {
        // 单个旧文件删除失败仅告警不中断：轮转失败不影响本次备份产物的有效性。
        log.warn("backup_retention_delete_failed file={} message={}", name, describe(failure.getMessage()));
        complete = false;
      }
    }
    return complete;
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

  private void requireRemoteFreeSpace(
      BackupExecutionContext context, BackupSettings settings, long requiredBytes) {
    context.beginExternalOperation();
    BackupRemoteStorage storage = null;
    try {
      storage = openVerifiedStorage(context, settings);
      context.requireActive();
      storage.authenticate(decryptedPassword(settings));
      storage.ensureDirectory(settings.remoteDirectory());
      long free = storage.freeSpaceBytes(settings.remoteDirectory());
      if (free < requiredBytes) {
        throw new BizException(
            "远程目录可用空间不足：需要约 " + BackupFileSupport.humanBytes(requiredBytes)
                + "，可用 " + BackupFileSupport.humanBytes(free));
      }
      context.requireActive();
    } finally {
      try {
        if (storage != null) {
          storage.close();
          context.detachStorage(storage);
        }
      } finally {
        context.endExternalOperation();
      }
    }
  }

  private Path localTargetDir(Path root, String label, String configuredSubdirectory) {
    String subdirectory = BackupFileSupport.resolveLocalSubdirectory(configuredSubdirectory);
    Path labelDir = root.resolve(label);
    return subdirectory == null ? labelDir : labelDir.resolve(subdirectory);
  }

  private String remoteTargetPath(String directory, String fileName) {
    String separator = directory.endsWith("/") ? "" : "/";
    return directory + separator + fileName;
  }

  private ScheduledFuture<?> startHeartbeat(BackupExecutionContext context) {
    long intervalMillis = Math.max(1000, properties.getLeaseSeconds() * 1000L / 3);
    return heartbeatExecutor.scheduleAtFixedRate(
        () -> {
          try {
            Instant now = clock.instant();
            stateRepository.heartbeat(
                context.runId(),
                context.executionToken(),
                now.plusSeconds(properties.getLeaseSeconds()),
                now);
          } catch (RuntimeException failure) {
            context.requestStop("备份心跳未能续租：" + describe(failure.getMessage()));
            log.warn(
                "backup_heartbeat_failed runId={} message={}",
                context.runId(),
                describe(failure.getMessage()));
          }
        },
        intervalMillis,
        intervalMillis,
        TimeUnit.MILLISECONDS);
  }

  /** 让运行中的失租任务停止并等待 worker、进程与远端操作全部退出。 */
  boolean stopExpiredExecution(BackupStateRepository.ExpiredExecution expired) {
    BackupExecutionContext context = executions.get(expired.runId());
    if (context != null) {
      if (!context.executionToken().equals(expired.executionToken())) {
        return false;
      }
      context.requestStop("备份租约过期，恢复器撤销执行身份");
      return context.awaitStopped(STOP_TIMEOUT);
    }
    if (expired.processId() == null) {
      return false;
    }
    return BackupExecutionContext.stopRecordedProcess(expired);
  }

  /** 删除已确认停止任务自己的暂存文件；其他运行与孤儿产物不参与清理。 */
  boolean cleanupExpiredStaging(BackupStateRepository.ExpiredExecution expired) {
    if (expired.executionToken() == null
        || !expired.executionToken().matches("[A-Fa-f0-9-]{36}")) {
      return false;
    }
    try {
      String label = BackupFileSupport.sanitizeLabel(properties.getInstanceLabel());
      Path stagingDir =
          Path.of(properties.getRoot()).resolve(label).resolve(BackupFileSupport.STAGING_DIR_NAME);
      return !Files.exists(stagingDir.resolve(
              "tmp-" + expired.runId() + "-" + expired.executionToken() + ".dump"))
          || Files.deleteIfExists(
              stagingDir.resolve("tmp-" + expired.runId() + "-" + expired.executionToken() + ".dump"));
    } catch (RuntimeException | IOException cleanupFailure) {
      log.warn(
          "backup_orphan_cleanup_failed runId={} message={}",
          expired.runId(),
          describe(cleanupFailure.getMessage()));
      return false;
    }
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

    private void advance(BackupExecutionContext context, String next) {
      context.requireActive();
      runRepository.updateStage(context.runId(), context.executionToken(), next, clock.instant());
      current = next;
    }

    private void mark(String next) {
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
