package com.data.collection.platform.service.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.UserAuthException;

/**
 * 基于 sshj 的远程备份存储实现。安全顺序：连接时仅完成密钥交换并采集 OpenSSH 兼容的
 * SHA256 主机密钥指纹（可对照服务器 ssh-keygen -lf 输出），调用方校验指纹通过后才发送密码。
 * 上传走 {@code .part-} 临时名 + 远端改名，保证远端目录内不出现半截成品。
 */
public final class SshjBackupRemoteStorage implements BackupRemoteStorage {
  private static final int EXEC_TIMEOUT_SECONDS = 30;
  private static final String PROBE_FILE_NAME = ".qaflex-backup-probe";
  private static final String PART_PREFIX = ".part-";

  private final SSHClient client;
  private final String username;
  private final String fingerprint;
  private SFTPClient sftp;

  public SshjBackupRemoteStorage(BackupRemoteEndpoint endpoint, int connectTimeoutMillis) {
    username = endpoint.username();
    FingerprintCapturingVerifier verifier = new FingerprintCapturingVerifier();
    client = new SSHClient();
    client.addHostKeyVerifier(verifier);
    client.setConnectTimeout(connectTimeoutMillis);
    client.setTimeout(connectTimeoutMillis);
    try {
      client.connect(endpoint.host(), endpoint.port());
    } catch (IOException failure) {
      throw new BackupRemoteException(
          "无法连接远程备份服务器 " + endpoint.host() + ":" + endpoint.port() + "：" + message(failure), failure);
    }
    fingerprint = verifier.lastFingerprint();
  }

  @Override
  public String hostKeyFingerprint() {
    return fingerprint;
  }

  @Override
  public void authenticate(String password) {
    try {
      client.authPassword(username, password);
    } catch (UserAuthException failure) {
      throw new BackupRemoteException("SSH 认证失败：用户名或密码错误", failure);
    } catch (IOException failure) {
      throw new BackupRemoteException("SSH 认证异常：" + message(failure), failure);
    }
  }

  @Override
  public void ensureDirectory(String directory) {
    try {
      String current = directory.startsWith("/") ? "/" : "";
      for (String segment : directory.split("/")) {
        if (segment.isEmpty()) {
          continue;
        }
        current = current.endsWith("/") ? current + segment : current + "/" + segment;
        if (sftp().statExistence(current) == null) {
          sftp().mkdir(current);
        }
      }
    } catch (IOException failure) {
      throw new BackupRemoteException("远程目录创建失败：" + message(failure), failure);
    }
  }

  @Override
  public void writeProbeAndDelete(String directory) {
    Path probe = null;
    try {
      probe = Files.createTempFile("qaflex-probe", ".tmp");
      Files.writeString(probe, String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8);
      sftp().put(probe.toString(), directory + "/" + PROBE_FILE_NAME);
      sftp().rm(directory + "/" + PROBE_FILE_NAME);
    } catch (IOException failure) {
      throw new BackupRemoteException("远程目录写探针失败（目录不可写）：" + message(failure), failure);
    } finally {
      if (probe != null) {
        try {
          Files.deleteIfExists(probe);
        } catch (IOException ignored) {
          // 本地探针临时文件清理失败不影响结果。
        }
      }
    }
  }

  @Override
  public long freeSpaceBytes(String directory) {
    String output = exec("df -P " + shellQuote(directory) + " 2>/dev/null", "df 查询远程磁盘空间失败");
    String[] lines = output.strip().split("\\r?\\n");
    if (lines.length < 2) {
      throw new BackupRemoteException("df 输出无法解析，无法确认远程磁盘空间");
    }
    String[] fields = lines[1].strip().split("\\s+");
    if (fields.length < 4) {
      throw new BackupRemoteException("df 输出列数不足，无法确认远程磁盘空间");
    }
    try {
      return Long.parseLong(fields[3]) * 1024L;
    } catch (NumberFormatException failure) {
      throw new BackupRemoteException("df 可用空间字段无法解析：" + fields[3]);
    }
  }

  @Override
  public void upload(Path localFile, String directory, String fileName) {
    String partName = directory + "/" + PART_PREFIX + fileName;
    String targetName = directory + "/" + fileName;
    try {
      sftp().put(localFile.toString(), partName);
      sftp().rename(partName, targetName);
    } catch (IOException failure) {
      throw new BackupRemoteException("远程上传失败：" + message(failure), failure);
    }
  }

  @Override
  public long fileSize(String directory, String fileName) {
    try {
      return sftp().stat(directory + "/" + fileName).getSize();
    } catch (IOException failure) {
      throw new BackupRemoteException("远端文件大小查询失败：" + message(failure), failure);
    }
  }

  @Override
  public List<String> listFileNames(String directory, Pattern fileNamePattern) {
    try {
      List<String> names = new ArrayList<>();
      for (RemoteResourceInfo entry : sftp().ls(directory)) {
        if (entry.isRegularFile() && fileNamePattern.matcher(entry.getName()).matches()) {
          names.add(entry.getName());
        }
      }
      return names;
    } catch (IOException failure) {
      throw new BackupRemoteException("远程目录列举失败：" + message(failure), failure);
    }
  }

  @Override
  public void deleteFile(String directory, String fileName) {
    try {
      sftp().rm(directory + "/" + fileName);
    } catch (IOException failure) {
      throw new BackupRemoteException(
          "远端文件删除失败：" + directory + "/" + fileName + "：" + message(failure), failure);
    }
  }

  @Override
  public Optional<String> sha256(String directory, String fileName) {
    try {
      String output = exec("sha256sum " + shellQuote(directory + "/" + fileName) + " 2>/dev/null", null);
      String[] fields = output.strip().split("\\s+");
      if (fields.length == 0 || fields[0].isBlank()) {
        return Optional.empty();
      }
      return Optional.of(fields[0]);
    } catch (BackupRemoteException unavailable) {
      // 远端无 sha256sum 或执行环境异常时以大小校验兜底，不视为失败。
      return Optional.empty();
    }
  }

  @Override
  public void close() {
    if (sftp != null) {
      sftp = null;
    }
    try {
      client.disconnect();
    } catch (IOException ignored) {
      // 会话断开失败不影响资源回收（进程退出时 socket 由操作系统回收）。
    }
  }

  private SFTPClient sftp() {
    if (sftp == null) {
      try {
        sftp = client.newSFTPClient();
      } catch (IOException failure) {
        throw new BackupRemoteException("SFTP 子系统启动失败：" + message(failure), failure);
      }
    }
    return sftp;
  }

  private String exec(String command, String failureMessage) {
    try (Session session = client.startSession()) {
      Session.Command process = session.exec(command);
      process.join(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      String output = readAll(process.getInputStream());
      Integer exit = process.getExitStatus();
      if (exit == null || exit != 0) {
        String detail = failureMessage == null ? "远端命令不可用：" + command : failureMessage;
        throw new BackupRemoteException(detail + "：" + readAll(process.getErrorStream()));
      }
      return output;
    } catch (IOException failure) {
      if (failureMessage == null) {
        throw new BackupRemoteException("远端命令执行通道异常：" + message(failure), failure);
      }
      throw new BackupRemoteException(failureMessage + "：" + message(failure), failure);
    }
  }

  private static String readAll(InputStream stream) throws IOException {
    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static String message(Throwable failure) {
    String text = failure.getMessage();
    return text == null || text.isBlank() ? failure.getClass().getSimpleName() : text;
  }

  /** 采集并接受任意主机密钥：真正的校验由调用方在认证前以指纹比对完成。 */
  private static final class FingerprintCapturingVerifier implements HostKeyVerifier {
    private String lastFingerprint;

    @Override
    public boolean verify(String hostname, int port, PublicKey key) {
      lastFingerprint = sha256Fingerprint(key);
      return true;
    }

    @Override
    public List<String> findExistingAlgorithms(String hostname, int port) {
      // 不做既有算法提示（密钥算法协商由 SSH 传输层自行完成）。
      return List.of();
    }

    String lastFingerprint() {
      return lastFingerprint;
    }

    private static String sha256Fingerprint(PublicKey key) {
      try {
        Buffer.PlainBuffer buffer = new Buffer.PlainBuffer();
        buffer.putPublicKey(key);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return "SHA256:"
            + Base64.getEncoder().withoutPadding().encodeToString(digest.digest(buffer.getCompactData()));
      } catch (GeneralSecurityException | RuntimeException failure) {
        throw new BackupRemoteException("服务器主机密钥指纹计算失败：" + message(failure), failure);
      }
    }
  }
}
