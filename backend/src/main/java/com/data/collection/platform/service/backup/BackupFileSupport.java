package com.data.collection.platform.service.backup;

import com.data.collection.platform.common.exception.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 备份文件命名、轮转与本地目录解析的纯函数支撑。
 * 文件名 {@code qaflex_<实例标识>_<yyyyMMdd-HHmmss>.dump}：字典序即时间序，轮转据此保留最新 N 份；
 * 匹配严格限定本实例命名模式，同目录其他实例或其他文件绝不进入删除候选。
 */
public final class BackupFileSupport {
  public static final String STAGING_DIR_NAME = ".staging";
  private static final DateTimeFormatter FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final Pattern SUBDIRECTORY_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

  private BackupFileSupport() {}

  /** 实例标识收敛为文件系统安全字符；空白回退 default。 */
  public static String sanitizeLabel(String raw) {
    if (raw == null || raw.isBlank()) {
      return "default";
    }
    String sanitized = raw.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    return sanitized.length() > 100 ? sanitized.substring(0, 100) : sanitized;
  }

  /** 生成最终备份文件名。 */
  public static String fileNameFor(String label, LocalDateTime timestamp) {
    return "qaflex_" + label + "_" + FILE_NAME_TIME.format(timestamp) + ".dump";
  }

  /** 本实例备份文件的匹配模式；只有完全命中该模式的文件才可能被轮转删除。 */
  public static Pattern dumpFilePattern(String label) {
    return Pattern.compile("^qaflex_" + Pattern.quote(label) + "_\\d{8}-\\d{6}\\.dump$");
  }

  /**
   * 从目录条目中选出超出保留份数、应删除的文件名（按文件名升序即时间升序，删最旧）。
   * 不匹配模式的名称一律忽略，永不返回。
   */
  public static List<String> namesBeyondRetention(
      List<String> candidateNames, Pattern pattern, int retentionCopies) {
    List<String> matched = new ArrayList<>();
    for (String name : candidateNames) {
      if (pattern.matcher(name).matches()) {
        matched.add(name);
      }
    }
    matched.sort(String::compareTo);
    if (matched.size() <= retentionCopies) {
      return List.of();
    }
    return List.copyOf(matched.subList(0, matched.size() - retentionCopies));
  }

  /** 解析并校验用户配置的本地子目录：相对路径、禁止上跳，段字符收敛为文件系统安全集。 */
  public static String resolveLocalSubdirectory(String configured) {
    if (configured == null || configured.isBlank()) {
      return null;
    }
    String normalized = configured.trim().replace('\\', '/');
    if (normalized.startsWith("/") || normalized.endsWith("/")) {
      throw new BizException("本地子目录必须是不含首尾斜杠的相对路径，例如 backups/20001");
    }
    for (String segment : normalized.split("/")) {
      if (!SUBDIRECTORY_SEGMENT.matcher(segment).matches()) {
        throw new BizException("本地子目录段 \"" + segment + "\" 非法：仅允许字母数字与 . _ -，且禁止 .. 上跳");
      }
    }
    if (normalized.length() > 128) {
      throw new BizException("本地子目录过长（超过 128 字符）");
    }
    return normalized;
  }

  /** 计算文件 SHA-256 十六进制摘要（流式读取，不占额外内存）。 */
  public static String sha256Hex(Path file) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 摘要算法不可用", failure);
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    } catch (IOException failure) {
      throw new BizException("备份文件读取失败：" + failure.getMessage());
    }
    StringBuilder hex = new StringBuilder(digest.getDigestLength() * 2);
    for (byte b : digest.digest()) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }

  /** 列出目录下命中模式的常规文件名；目录不存在按空处理。 */
  public static List<String> listMatchedFileNames(Path directory, Pattern pattern) {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(directory)) {
      List<String> names = new ArrayList<>();
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        if (Files.isRegularFile(entry) && pattern.matcher(name).matches()) {
          names.add(name);
        }
      }
      return names;
    } catch (IOException failure) {
      throw new BizException("备份目录读取失败：" + directory + "：" + failure.getMessage());
    }
  }

  /** 预检公式的余量换算：兆字节 → 字节。 */
  public static long megabytesToBytes(int megabytes) {
    return megabytes * 1024L * 1024L;
  }

  /** 字节数的人类可读展示（B/MB/GB），用于页面与错误信息。 */
  public static String humanBytes(long bytes) {
    if (bytes < 1024L * 1024L) {
      return bytes + " B";
    }
    if (bytes < 1024L * 1024L * 1024L) {
      return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
    return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
  }
}
