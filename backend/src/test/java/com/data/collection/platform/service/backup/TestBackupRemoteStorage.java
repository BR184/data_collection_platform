package com.data.collection.platform.service.backup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 远程存储替身：记录全部操作并允许逐项注入结果，供服务与编排测试复用。 */
final class TestBackupRemoteStorage implements BackupRemoteStorage {
  final List<String> operations = new ArrayList<>();
  String fingerprint = "SHA256:test-fingerprint";
  String password;
  String authenticatedPassword;
  RuntimeException authenticateFailure;
  RuntimeException probeFailure;
  long freeSpaceBytes = 10L * 1024 * 1024 * 1024;
  RuntimeException freeSpaceFailure;
  Long fileSizeOverride;
  Runnable onUpload;
  Optional<String> sha256 = Optional.of("sha-value");
  List<String> remoteFiles = new ArrayList<>();

  @Override
  public String hostKeyFingerprint() {
    operations.add("fingerprint");
    return fingerprint;
  }

  @Override
  public void authenticate(String password) {
    operations.add("authenticate");
    authenticatedPassword = password;
    if (authenticateFailure != null) {
      throw authenticateFailure;
    }
    this.password = password;
  }

  @Override
  public void ensureDirectory(String directory) {
    operations.add("ensureDirectory:" + directory);
  }

  @Override
  public void writeProbeAndDelete(String directory) {
    operations.add("probe:" + directory);
    if (probeFailure != null) {
      throw probeFailure;
    }
  }

  @Override
  public long freeSpaceBytes(String directory) {
    operations.add("freeSpace:" + directory);
    if (freeSpaceFailure != null) {
      throw freeSpaceFailure;
    }
    return freeSpaceBytes;
  }

  @Override
  public void upload(Path localFile, String directory, String fileName) {
    operations.add("upload:" + directory + "/" + fileName);
    if (onUpload != null) {
      onUpload.run();
    }
    remoteFiles.add(fileName);
  }

  @Override
  public long fileSize(String directory, String fileName) {
    operations.add("fileSize:" + fileName);
    if (fileSizeOverride != null) {
      return fileSizeOverride;
    }
    return remoteFiles.contains(fileName) ? 100 : 0;
  }

  @Override
  public List<String> listFileNames(String directory, Pattern fileNamePattern) {
    operations.add("list:" + directory);
    return remoteFiles.stream().filter(name -> fileNamePattern.matcher(name).matches()).toList();
  }

  @Override
  public void deleteFile(String directory, String fileName) {
    operations.add("delete:" + directory + "/" + fileName);
    remoteFiles.remove(fileName);
  }

  @Override
  public Optional<String> sha256(String directory, String fileName) {
    operations.add("sha256:" + fileName);
    return sha256;
  }

  @Override
  public void close() {
    operations.add("close");
  }
}
