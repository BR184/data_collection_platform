package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupCommandContractTest {
  @TempDir Path tempDir;

  @Test
  void test_from_parsesHostPortDatabaseFromJdbcUrl() {
    BackupDatabaseTarget target =
        BackupDatabaseTarget.from("jdbc:postgresql://postgres:5432/qaflex?stringtype=unspecified", "qaflex", "pw");

    assertThat(target.host()).isEqualTo("postgres");
    assertThat(target.port()).isEqualTo(5432);
    assertThat(target.database()).isEqualTo("qaflex");
    assertThat(target.username()).isEqualTo("qaflex");
    assertThat(target.password()).isEqualTo("pw");
  }

  @Test
  void test_from_defaultsPortAndRejectsNonPostgresUrl() {
    assertThat(BackupDatabaseTarget.from("jdbc:postgresql://postgres/qaflex", "u", "p").port()).isEqualTo(5432);

    assertThatThrownBy(() -> BackupDatabaseTarget.from("jdbc:mysql://host/db", "u", "p"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("DATASOURCE_URL");
  }

  @Test
  void test_dumpCommand_carriesConnectionFormatAndFileContract() {
    BackupCommandFactory factory = new BackupCommandFactory(new BackupConfigurationProperties());
    BackupDatabaseTarget target = BackupDatabaseTarget.from("jdbc:postgresql://pg:5433/qaflex", "qaflex", "pw");
    Path dumpFile = tempDir.resolve("out.dump");

    List<String> command = factory.dumpCommand(target, dumpFile);

    assertThat(command).containsExactly(
        "pg_dump",
        "-h", "pg",
        "-p", "5433",
        "-U", "qaflex",
        "-d", "qaflex",
        "--format=custom",
        "--no-password",
        "--file=" + dumpFile);
  }

  @Test
  void test_verifyCommand_readsTocFromDumpFileOnly() {
    BackupCommandFactory factory = new BackupCommandFactory(new BackupConfigurationProperties());

    List<String> command = factory.verifyCommand(tempDir.resolve("out.dump"));

    assertThat(command).containsExactly("pg_restore", "--list", tempDir.resolve("out.dump").toString());
  }
}
