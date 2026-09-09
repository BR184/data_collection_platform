package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.backup.BackupConnectionTestRequest;
import com.data.collection.platform.entity.backup.BackupConnectionTestResponse;
import com.data.collection.platform.entity.backup.BackupSettingsResponse;
import com.data.collection.platform.entity.backup.BackupSettingsSaveRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackupSettingsServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-10T02:00:00Z");
  private static final String SECRET_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private BackupSettingsRepository settingsRepository;
  private BackupRunRepository runRepository;
  private final TestBackupRemoteStorage storage = new TestBackupRemoteStorage();
  private BackupConfigurationProperties properties;
  private BackupSettingsService service;

  @BeforeEach
  void setUp() {
    settingsRepository = mock(BackupSettingsRepository.class);
    runRepository = mock(BackupRunRepository.class);
    properties = new BackupConfigurationProperties();
    properties.setSecretKey(SECRET_KEY);
    service = new BackupSettingsService(
        settingsRepository,
        runRepository,
        properties,
        new BackupCryptoSupport(properties),
        endpoint -> storage,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void test_settingsView_withoutSavedRow_returnsDeployDefaults() {
    when(settingsRepository.load()).thenReturn(Optional.empty());

    BackupSettingsResponse view = service.settingsView();

    assertThat(view.enabled()).isFalse();
    assertThat(view.scheduleTime()).isEqualTo("03:00");
    assertThat(view.retentionCopies()).isEqualTo(14);
    assertThat(view.storageMode()).isEqualTo("LOCAL");
    assertThat(view.version()).isZero();
    assertThat(view.hasRemotePassword()).isFalse();
    assertThat(view.secretKeyConfigured()).isTrue();
    assertThat(view.instanceLabel()).isEqualTo("default");
  }

  @Test
  void test_save_firstSave_insertsWithVersionOne() {
    when(settingsRepository.load()).thenReturn(Optional.empty());
    when(settingsRepository.insert(any(), any())).thenReturn(savedSettings(1));

    BackupSettingsResponse response = service.save(localRequest(0L, ""), "admin");

    verify(settingsRepository).insert(any(), any());
    verify(settingsRepository, never()).update(any(), anyLong(), any());
    assertThat(response.version()).isEqualTo(1);
    assertThat(response.storageMode()).isEqualTo("LOCAL");
  }

  @Test
  void test_save_blankPassword_keepsStoredCipherAndNeverReencrypts() {
    BackupSettings current = savedSettings(3);
    when(settingsRepository.load()).thenReturn(Optional.of(current));
    when(settingsRepository.update(any(), anyLong(), any()))
        .thenAnswer(invocation -> savedSettings(current.version() + 1));

    BackupSettingsResponse response = service.save(localRequest(3L, ""), "admin");

    verify(settingsRepository)
        .update(
            argThat(settings -> "v1:existing:cipher".equals(settings.remotePasswordCipher())),
            eq(3L),
            any());
    assertThat(response.hasRemotePassword()).isTrue();
  }

  @Test
  void test_save_newPassword_encryptsAsVersionedCiphertext() {
    BackupSettings current = savedSettings(3);
    when(settingsRepository.load()).thenReturn(Optional.of(current));
    when(settingsRepository.update(any(), anyLong(), any())).thenReturn(savedSettings(4));

    service.save(localRequest(3L, "brand-new-password"), "admin");

    verify(settingsRepository)
        .update(
            argThat(
                settings ->
                    settings.remotePasswordCipher() != null
                        && settings.remotePasswordCipher().startsWith("v1:")),
            eq(3L),
            any());
  }

  @Test
  void test_save_versionConflict_rejectedWithConflictSemantics() {
    when(settingsRepository.load()).thenReturn(Optional.of(savedSettings(2)));

    assertThatThrownBy(() -> service.save(localRequest(9L, ""), "admin"))
        .isInstanceOf(BizException.class)
        .extracting("resultCode")
        .isEqualTo(ResultCode.CONFLICT);
  }

  @Test
  void test_save_remoteMode_missingRequiredFields_rejected() {
    when(settingsRepository.load()).thenReturn(Optional.empty());
    BackupSettingsSaveRequest request =
        new BackupSettingsSaveRequest(true, "03:00", 14, "REMOTE", null, null, 22, null, "pw", null, null, 0L);

    assertThatThrownBy(() -> service.save(request, "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("远程服务器地址");
  }

  @Test
  void test_save_remoteMode_withoutStoredOrSubmittedPassword_rejected() {
    when(settingsRepository.load()).thenReturn(Optional.empty());
    BackupSettingsSaveRequest request =
        new BackupSettingsSaveRequest(true, "03:00", 14, "REMOTE", null, "backup-host", 22, "oper", "", "/backups", null, 0L);

    assertThatThrownBy(() -> service.save(request, "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("远程密码");
  }

  @Test
  void test_save_invalidRetentionAndSubdirectory_rejected() {
    when(settingsRepository.load()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.save(localRequest(0L, "", 400), "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("保留份数");
    assertThatThrownBy(
            () ->
                service.save(
                    new BackupSettingsSaveRequest(
                        false, "03:00", 14, "LOCAL", "../escape", null, 22, null, "", null, null, 0L),
                    "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("本地子目录");
  }

  @Test
  void test_testConnection_fingerprintMismatch_shortCircuitsBeforeAuthentication() {
    storage.fingerprint = "SHA256:actual";
    BackupConnectionTestRequest request =
        new BackupConnectionTestRequest("backup-host", 22, "oper", "pw", "/backups", "SHA256:expected");

    BackupConnectionTestResponse response = service.testConnection(request);

    assertThat(response.ok()).isFalse();
    assertThat(response.actualFingerprint()).isEqualTo("SHA256:actual");
    assertThat(response.checks()).singleElement().satisfies(check -> {
      assertThat(check.passed()).isFalse();
      assertThat(check.message()).contains("指纹与已保存不一致");
    });
    assertThat(storage.operations).doesNotContain("authenticate");
    assertThat(storage.operations).contains("close");
  }

  @Test
  void test_testConnection_allChecksPass_reportsSuccessWithFingerprint() {
    when(runRepository.maxSuccessfulBytes()).thenReturn(OptionalLong.empty());
    storage.freeSpaceBytes = 20L * 1024 * 1024 * 1024;
    BackupConnectionTestRequest request =
        new BackupConnectionTestRequest("backup-host", 22, "oper", "pw", "/backups", null);

    BackupConnectionTestResponse response = service.testConnection(request);

    assertThat(response.ok()).isTrue();
    assertThat(response.actualFingerprint()).isEqualTo("SHA256:test-fingerprint");
    assertThat(response.checks()).hasSize(3);
    assertThat(response.checks()).allSatisfy(check -> assertThat(check.passed()).isTrue());
    assertThat(storage.authenticatedPassword).isEqualTo("pw");
  }

  @Test
  void test_testConnection_insufficientRemoteDisk_reportsFailedCheck() {
    when(runRepository.maxSuccessfulBytes()).thenReturn(OptionalLong.of(8L * 1024 * 1024 * 1024));
    storage.freeSpaceBytes = 1L * 1024 * 1024 * 1024;
    BackupConnectionTestRequest request =
        new BackupConnectionTestRequest("backup-host", 22, "oper", "pw", "/backups", null);

    BackupConnectionTestResponse response = service.testConnection(request);

    assertThat(response.ok()).isFalse();
    assertThat(response.checks()).extracting("name").containsExactly("ssh-auth", "directory-write", "disk-space");
    assertThat(response.checks().get(2).passed()).isFalse();
    assertThat(response.checks().get(2).message()).contains("可用空间不足");
  }

  @Test
  void test_testConnection_blankPasswordWithoutStoredSecret_rejected() {
    when(settingsRepository.load()).thenReturn(Optional.empty());
    BackupConnectionTestRequest request =
        new BackupConnectionTestRequest("backup-host", 22, "oper", "", "/backups", null);

    assertThatThrownBy(() -> service.testConnection(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("远程密码");
  }

  @Test
  void test_testConnection_connectFailure_reportsSshCheckFailure() {
    BackupRemoteStorageFactory failingFactory =
        endpoint -> {
          throw new BackupRemoteException("无法连接远程备份服务器 backup-host:22：connect timed out");
        };
    service = new BackupSettingsService(
        settingsRepository,
        runRepository,
        properties,
        new BackupCryptoSupport(properties),
        failingFactory,
        Clock.fixed(NOW, ZoneOffset.UTC));

    BackupConnectionTestResponse response =
        service.testConnection(
            new BackupConnectionTestRequest("backup-host", 22, "oper", "pw", "/backups", null));

    assertThat(response.ok()).isFalse();
    assertThat(response.actualFingerprint()).isNull();
    assertThat(response.checks().get(0).passed()).isFalse();
    assertThat(response.checks().get(0).message()).contains("无法连接");
  }

  private BackupSettingsSaveRequest localRequest(Long version, String password) {
    return localRequest(version, password, 14);
  }

  private BackupSettingsSaveRequest localRequest(Long version, String password, int retention) {
    return new BackupSettingsSaveRequest(
        false, "03:00", retention, "LOCAL", null, "backup-host", 22, "oper", password,
        "/backups", "SHA256:test-fingerprint", version);
  }

  private BackupSettings savedSettings(long version) {
    return new BackupSettings(
        false,
        LocalTime.of(3, 0),
        14,
        "LOCAL",
        null,
        "backup-host",
        22,
        "oper",
        "v1:existing:cipher",
        "/backups",
        "SHA256:test-fingerprint",
        version,
        "admin",
        NOW);
  }
}
