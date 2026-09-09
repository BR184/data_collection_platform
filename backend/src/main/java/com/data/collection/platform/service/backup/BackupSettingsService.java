package com.data.collection.platform.service.backup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.backup.BackupConnectionTestRequest;
import com.data.collection.platform.entity.backup.BackupConnectionTestResponse;
import com.data.collection.platform.entity.backup.BackupSettingsResponse;
import com.data.collection.platform.entity.backup.BackupSettingsSaveRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 备份配置的读取、保存与远程连接测试。
 * 保存语义：远程密码留空 = 保留已存密文；填新值 = 覆盖加密；密码明文只在加解密瞬间存在于内存。
 * 测试连接为纯只读三查（SSH 登录与指纹、目录写探针、磁盘余量），绝不触发备份、不改任何配置。
 */
@Service
public class BackupSettingsService {
  private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter SCHEDULE_TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final BackupSettingsRepository settingsRepository;
  private final BackupRunRepository runRepository;
  private final BackupConfigurationProperties properties;
  private final BackupCryptoSupport crypto;
  private final BackupRemoteStorageFactory remoteStorageFactory;
  private final Clock clock;

  @Autowired
  public BackupSettingsService(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupConfigurationProperties properties,
      BackupCryptoSupport crypto,
      BackupRemoteStorageFactory remoteStorageFactory) {
    this(settingsRepository, runRepository, properties, crypto, remoteStorageFactory, Clock.systemUTC());
  }

  BackupSettingsService(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupConfigurationProperties properties,
      BackupCryptoSupport crypto,
      BackupRemoteStorageFactory remoteStorageFactory,
      Clock clock) {
    this.settingsRepository = settingsRepository;
    this.runRepository = runRepository;
    this.properties = properties;
    this.crypto = crypto;
    this.remoteStorageFactory = remoteStorageFactory;
    this.clock = clock;
  }

  /** 读取配置视图；行未保存时呈现与建表默认值一致的默认配置（版本 0）。 */
  public BackupSettingsResponse settingsView() {
    BackupSettings settings = settingsRepository.load().orElse(BackupSettings.defaults());
    return toResponse(settings);
  }

  /** 保存配置：整体替换 + 乐观锁；REMOTE 模式校验必填项与主密钥可用；返回保存后的视图。 */
  public BackupSettingsResponse save(BackupSettingsSaveRequest request, String actor) {
    if (request == null) {
      throw new BizException("备份配置不能为空");
    }
    BackupSettings current = settingsRepository.load().orElse(BackupSettings.defaults());
    long expectedVersion = request.version() == null ? 0 : request.version();
    if (expectedVersion != current.version()) {
      throw new BizException(
          ResultCode.CONFLICT,
          "备份配置已被他人修改（版本 " + expectedVersion + " 已失效），请刷新后重试");
    }

    LocalTime scheduleTime = parseScheduleTime(request.scheduleTime());
    int retentionCopies = request.retentionCopies() == null ? current.retentionCopies() : request.retentionCopies();
    if (retentionCopies < 1 || retentionCopies > 365) {
      throw new BizException("保留份数必须在 1 至 365 之间");
    }
    String storageMode = request.storageMode() == null ? BackupSettings.STORAGE_MODE_LOCAL : request.storageMode();
    if (!BackupSettings.STORAGE_MODE_LOCAL.equals(storageMode)
        && !BackupSettings.STORAGE_MODE_REMOTE.equals(storageMode)) {
      throw new BizException("存储模式仅支持 LOCAL（部署服务器本地）或 REMOTE（远程备份服务器）");
    }
    String localSubdirectory = BackupFileSupport.resolveLocalSubdirectory(request.localSubdirectory());
    String remoteHost = trimToNull(request.remoteHost());
    int remotePort = request.remotePort() == null ? 22 : request.remotePort();
    if (remotePort < 1 || remotePort > 65535) {
      throw new BizException("远程端口必须在 1 至 65535 之间");
    }
    String remoteUsername = trimToNull(request.remoteUsername());
    String remoteDirectory = trimToNull(request.remoteDirectory());
    String fingerprint = normalizeFingerprint(request.remoteHostKeyFingerprint());

    if (BackupSettings.STORAGE_MODE_REMOTE.equals(storageMode)) {
      requireText(remoteHost, "远程服务器地址");
      requireText(remoteUsername, "远程服务器用户名");
      requireText(remoteDirectory, "远程备份目录");
    }

    String passwordCipher = resolvePasswordCipher(request.remotePassword(), current);
    if (BackupSettings.STORAGE_MODE_REMOTE.equals(storageMode)
        && passwordCipher == null
        && !current.hasStoredRemotePassword()) {
      throw new BizException("首次配置远程备份必须录入远程密码");
    }

    BackupSettings next =
        new BackupSettings(
            request.enabled(),
            scheduleTime,
            retentionCopies,
            storageMode,
            localSubdirectory,
            remoteHost,
            remotePort,
            remoteUsername,
            passwordCipher != null ? passwordCipher : current.remotePasswordCipher(),
            remoteDirectory,
            fingerprint,
            current.version(),
            actor,
            current.updatedAt());
    Instant now = clock.instant();
    BackupSettings saved =
        current.version() == 0
            ? settingsRepository.insert(next, now)
            : settingsRepository.update(next, current.version(), now);
    return toResponse(saved);
  }

  /** 远程连接测试：SSH 登录与指纹校验、目录写探针、磁盘余量三查，逐项返回结果。 */
  public BackupConnectionTestResponse testConnection(BackupConnectionTestRequest request) {
    if (request == null) {
      throw new BizException("测试连接请求不能为空");
    }
    String host = trimToNull(request.remoteHost());
    String username = trimToNull(request.remoteUsername());
    String directory = trimToNull(request.remoteDirectory());
    if (host == null || username == null || directory == null) {
      throw new BizException("测试连接前请先填写远程服务器地址、用户名与备份目录");
    }
    int port = request.remotePort() == null ? 22 : request.remotePort();
    String password = resolveTestPassword(request.remotePassword());

    List<BackupConnectionTestResponse.CheckResult> checks = new ArrayList<>();
    try (BackupRemoteStorage storage = remoteStorageFactory.open(new BackupRemoteEndpoint(host, port, username))) {
      String actual = storage.hostKeyFingerprint();
      String expected = trimToNull(request.remoteHostKeyFingerprint());
      if (expected != null && !expected.equals(actual)) {
        checks.add(
            new BackupConnectionTestResponse.CheckResult(
                "ssh-auth",
                false,
                "主机密钥指纹与已保存不一致（疑似服务器变更或中间人）：已保存 "
                    + expected
                    + "，实际 "
                    + actual
                    + "。如确认服务器已变更，请更新指纹为实际值后重新测试并保存"));
        return new BackupConnectionTestResponse(false, checks, actual);
      }
      try {
        storage.authenticate(password);
        checks.add(
            new BackupConnectionTestResponse.CheckResult(
                "ssh-auth", true, "SSH 登录成功，主机密钥指纹 " + actual));
      } catch (BackupRemoteException failure) {
        checks.add(new BackupConnectionTestResponse.CheckResult("ssh-auth", false, failure.getMessage()));
        return new BackupConnectionTestResponse(false, checks, actual);
      }
      try {
        storage.ensureDirectory(directory);
        storage.writeProbeAndDelete(directory);
        checks.add(
            new BackupConnectionTestResponse.CheckResult("directory-write", true, "备份目录就绪且可写：" + directory));
      } catch (BackupRemoteException failure) {
        checks.add(new BackupConnectionTestResponse.CheckResult("directory-write", false, failure.getMessage()));
        return new BackupConnectionTestResponse(false, checks, actual);
      }
      try {
        long free = storage.freeSpaceBytes(directory);
        long required =
            Math.max(
                runRepository.maxSuccessfulBytes().orElse(0) * 2,
                BackupFileSupport.megabytesToBytes(properties.getHeadroomMb()));
        if (free < required) {
          checks.add(
              new BackupConnectionTestResponse.CheckResult(
                  "disk-space",
                  false,
                  "远程磁盘可用空间不足：需要约 "
                      + BackupFileSupport.humanBytes(required)
                      + "，可用 "
                      + BackupFileSupport.humanBytes(free)));
        } else {
          checks.add(
              new BackupConnectionTestResponse.CheckResult(
                  "disk-space", true, "远程磁盘可用空间 " + BackupFileSupport.humanBytes(free)));
        }
      } catch (BackupRemoteException failure) {
        checks.add(new BackupConnectionTestResponse.CheckResult("disk-space", false, failure.getMessage()));
      }
      boolean ok = checks.stream().allMatch(BackupConnectionTestResponse.CheckResult::passed);
      return new BackupConnectionTestResponse(ok, checks, actual);
    } catch (BackupRemoteException failure) {
      checks.add(new BackupConnectionTestResponse.CheckResult("ssh-auth", false, failure.getMessage()));
      return new BackupConnectionTestResponse(false, checks, null);
    }
  }

  private String resolvePasswordCipher(String submittedPassword, BackupSettings current) {
    if (submittedPassword == null || submittedPassword.isBlank()) {
      return null;
    }
    return crypto.encrypt(submittedPassword);
  }

  private String resolveTestPassword(String submittedPassword) {
    if (submittedPassword != null && !submittedPassword.isBlank()) {
      return submittedPassword;
    }
    BackupSettings current = settingsRepository.load().orElse(null);
    if (current != null && current.hasStoredRemotePassword()) {
      return crypto.decrypt(current.remotePasswordCipher());
    }
    throw new BizException("请先录入远程密码再测试连接");
  }

  private BackupSettingsResponse toResponse(BackupSettings settings) {
    String label = BackupFileSupport.sanitizeLabel(properties.getInstanceLabel());
    String localRootEffective =
        properties.getRoot() + "/" + label + (settings.localSubdirectory() == null ? "" : "/" + settings.localSubdirectory());
    return new BackupSettingsResponse(
        settings.enabled(),
        settings.scheduleTime().format(SCHEDULE_TIME),
        settings.retentionCopies(),
        settings.storageMode(),
        settings.localSubdirectory(),
        settings.remoteHost(),
        settings.remotePort(),
        settings.remoteUsername(),
        settings.hasStoredRemotePassword(),
        settings.remoteDirectory(),
        settings.remoteHostKeyFingerprint(),
        settings.version(),
        settings.updatedAt() == null ? null : DISPLAY_TIME.format(settings.updatedAt().atZone(BackupOrchestrationService.PLATFORM_ZONE)),
        localRootEffective,
        crypto.isConfigured(),
        label);
  }

  private LocalTime parseScheduleTime(String value) {
    if (value == null || value.isBlank()) {
      throw new BizException("备份时刻不能为空");
    }
    try {
      return LocalTime.parse(value.trim());
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new BizException("备份时刻必须使用 HH:mm 或 HH:mm:ss 格式");
    }
  }

  private String normalizeFingerprint(String value) {
    String trimmed = trimToNull(value);
    if (trimmed != null && trimmed.length() > 128) {
      throw new BizException("主机密钥指纹长度非法");
    }
    return trimmed;
  }

  private void requireText(String value, String label) {
    if (value == null) {
      throw new BizException(label + "不能为空");
    }
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
