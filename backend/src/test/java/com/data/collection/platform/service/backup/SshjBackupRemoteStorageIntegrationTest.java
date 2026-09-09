package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * sshj 远程存储会话与内嵌 MINA SSH 服务器的真实协议级集成验证：
 * 连接与指纹采集、密码认证、目录递归创建、写探针、上传（临时名+改名）、远端列举/删除/df/sha256sum 解析。
 */
class SshjBackupRemoteStorageIntegrationTest {
  private static final String USER = "oper";
  private static final String PASSWORD = "s3cret";
  private static final String FAKE_SHA = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2";
  private static final String DUMP_NAME = "qaflex_it_20260910-030000.dump";

  @TempDir
  static Path remoteRoot;

  private static SshServer server;
  private static int port;

  @BeforeAll
  static void startEmbeddedServer() throws Exception {
    server = SshServer.setUpDefaultServer();
    server.setPort(0);
    SimpleGeneratorHostKeyProvider keyProvider = new SimpleGeneratorHostKeyProvider();
    keyProvider.setAlgorithm("RSA");
    keyProvider.setKeySize(2048);
    server.setKeyPairProvider(keyProvider);
    server.setPasswordAuthenticator(
        (username, password, session) -> USER.equals(username) && PASSWORD.equals(password));
    server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
    server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
    server.setCommandFactory(cannedCommandFactory());
    server.start();
    port = server.getPort();
  }

  @AfterAll
  static void stopEmbeddedServer() throws Exception {
    if (server != null) {
      server.stop(true);
    }
  }

  @Test
  void test_connect_collectsOpenSshCompatibleFingerprintBeforeAuthentication() {
    try (BackupRemoteStorage storage = open()) {
      assertThat(storage.hostKeyFingerprint()).startsWith("SHA256:");
    }
  }

  @Test
  void test_authenticate_wrongPassword_failsWithExplicitReason() {
    try (BackupRemoteStorage storage = open()) {
      assertThatThrownBy(() -> storage.authenticate("wrong-password"))
          .isInstanceOf(BackupRemoteException.class)
          .hasMessageContaining("用户名或密码错误");
    }
  }

  @Test
  void test_fullSession_ensureProbeUploadListShaDeleteRoundTrip() throws Exception {
    Path dump = Files.createTempFile("qaflex-it", ".dump");
    Files.writeString(dump, "backup-content", StandardCharsets.UTF_8);
    try (BackupRemoteStorage storage = open()) {
      storage.authenticate(PASSWORD);
      // SFTP 会话内路径为 POSIX 风格；服务器端虚拟文件系统把 / 映射到 remoteRoot。
      String directory = "/nested/backups";
      Path mappedDirectory = remoteRoot.resolve("nested/backups");

      storage.ensureDirectory(directory);
      assertThat(Files.isDirectory(mappedDirectory)).isTrue();

      storage.writeProbeAndDelete(directory);
      try (var entries = Files.list(mappedDirectory)) {
        assertThat(entries.toList()).isEmpty();
      }

      storage.upload(dump, directory, DUMP_NAME);
      assertThat(storage.fileSize(directory, DUMP_NAME)).isEqualTo(Files.size(dump));

      List<String> names =
          storage.listFileNames(directory, Pattern.compile("^qaflex_\\w+_\\d{8}-\\d{6}\\.dump$"));
      assertThat(names).containsExactly(DUMP_NAME);

      assertThat(storage.sha256(directory, DUMP_NAME)).hasValue(FAKE_SHA);
      assertThat(storage.freeSpaceBytes(directory)).isEqualTo(943_718L * 1024L);

      storage.deleteFile(directory, DUMP_NAME);
      assertThat(storage.listFileNames(directory, Pattern.compile(".*\\.dump$"))).isEmpty();
    } finally {
      Files.deleteIfExists(dump);
    }
  }

  private static BackupRemoteStorage open() {
    return new SshjBackupRemoteStorage(new BackupRemoteEndpoint("127.0.0.1", port, USER), 10_000);
  }

  /** exec 命令以 canned 响应实现（纯 Java，不依赖宿主机 shell），覆盖 df 与 sha256sum 的解析逻辑。 */
  private static CommandFactory cannedCommandFactory() {
    return (channel, command) ->
        new Command() {
          private OutputStream out;
          private ExitCallback exit;

          @Override
          public void setInputStream(InputStream in) {}

          @Override
          public void setOutputStream(OutputStream out) {
            this.out = out;
          }

          @Override
          public void setErrorStream(OutputStream err) {}

          @Override
          public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
          }

          @Override
          public void start(ChannelSession channel, Environment env) throws IOException {
            String response;
            if (command.contains("df -P")) {
              response =
                  "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
                      + "/dev/sda1 1048576 100000 943718 10% /backup\n";
            } else if (command.startsWith("sha256sum")) {
              response = FAKE_SHA + "  /backup/" + DUMP_NAME + "\n";
            } else {
              response = "";
            }
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
            exit.onExit(0);
          }

          @Override
          public void destroy(ChannelSession channel) {}
        };
  }
}
