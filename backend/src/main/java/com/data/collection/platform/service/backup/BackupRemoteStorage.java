package com.data.collection.platform.service.backup;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 远程备份存储会话：一次 SSH 连接内的目录探针、上传、远端列举与轮转操作。
 * 生命周期严格分两步——{@link BackupRemoteStorageFactory#open} 完成连接并采集主机密钥指纹
 * （此时不发送任何凭据），调用方校验指纹通过后再 {@link #authenticate}，防止凭据发往未验证主机。
 */
public interface BackupRemoteStorage extends AutoCloseable {

  /** 连接阶段采集到的服务器主机密钥指纹（SHA256: 开头的 OpenSSH 兼容格式）。 */
  String hostKeyFingerprint();

  /** 指纹校验通过后的密码认证。 */
  void authenticate(String password);

  /** 递归创建目录（已存在时幂等）。 */
  void ensureDirectory(String directory);

  /** 目录写探针：上传小文件后立即删除，验证目录真实可写。 */
  void writeProbeAndDelete(String directory);

  /** 目录所在文件系统可用字节数（df -P 解析）。 */
  long freeSpaceBytes(String directory);

  /**
   * 上传本地文件到目录下指定文件名：先写 {@code .part-} 临时名再原子改名为最终名，
   * 远端目录内永不出现半截成品文件。
   */
  void upload(Path localFile, String directory, String fileName);

  /** 目录下指定文件的大小（字节）；文件不存在抛 {@link BackupRemoteException}。 */
  long fileSize(String directory, String fileName);

  /** 列举目录下命中模式的文件名（不含路径）。 */
  List<String> listFileNames(String directory, Pattern fileNamePattern);

  /** 删除目录下指定文件。 */
  void deleteFile(String directory, String fileName);

  /** 远端 sha256sum 摘要；命令不可用时返回 empty（调用侧以大小校验兜底）。 */
  Optional<String> sha256(String directory, String fileName);

  @Override
  void close();
}
