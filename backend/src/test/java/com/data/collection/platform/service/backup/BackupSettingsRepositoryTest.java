package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class BackupSettingsRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-09-10T02:00:00Z");

  @Autowired private BackupSettingsRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from backup_settings");
  }

  @Test
  void test_load_withoutRow_returnsEmpty() {
    assertThat(repository.load()).isEqualTo(Optional.empty());
  }

  @Test
  void test_insert_thenUpdate_roundTripsAllFieldsAndBumpsVersion() {
    BackupSettings first = settings(0);
    BackupSettings inserted = repository.insert(first, NOW);
    assertThat(inserted.version()).isEqualTo(1);

    BackupSettings changed = new BackupSettings(
        true, LocalTime.of(2, 30), 7, "REMOTE", "sub/dir", "backup-host", 2200, "oper",
        "v1:cipher", "/backups", "SHA256:fp", inserted.version(), "editor", NOW);
    BackupSettings updated = repository.update(changed, inserted.version(), NOW);

    assertThat(updated.version()).isEqualTo(2);
    assertThat(repository.load()).hasValueSatisfying(loaded -> {
      assertThat(loaded.enabled()).isTrue();
      assertThat(loaded.scheduleTime()).isEqualTo(LocalTime.of(2, 30));
      assertThat(loaded.retentionCopies()).isEqualTo(7);
      assertThat(loaded.storageMode()).isEqualTo("REMOTE");
      assertThat(loaded.localSubdirectory()).isEqualTo("sub/dir");
      assertThat(loaded.remoteHost()).isEqualTo("backup-host");
      assertThat(loaded.remotePort()).isEqualTo(2200);
      assertThat(loaded.remoteUsername()).isEqualTo("oper");
      assertThat(loaded.remotePasswordCipher()).isEqualTo("v1:cipher");
      assertThat(loaded.remoteDirectory()).isEqualTo("/backups");
      assertThat(loaded.remoteHostKeyFingerprint()).isEqualTo("SHA256:fp");
      assertThat(loaded.updatedBy()).isEqualTo("editor");
    });
  }

  @Test
  void test_update_withStaleVersion_rejectedAsConflict() {
    repository.insert(settings(0), NOW);

    BackupSettings stale = settings(99);
    assertThatThrownBy(() -> repository.update(stale, 99, NOW))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已被他人修改");
    assertThat(repository.load()).hasValueSatisfying(loaded -> assertThat(loaded.version()).isEqualTo(1));
  }

  private BackupSettings settings(long version) {
    return new BackupSettings(
        false, LocalTime.of(3, 0), 14, "LOCAL", null, null, 22, null, null, null, null,
        version, null, null);
  }
}
