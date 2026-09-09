package com.data.collection.platform.service.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 基于本机进程的 {@link BackupProcessRunner} 实现：环境注入、超时强杀与标准错误捕获。 */
@Component
public class ProcessBackupProcessRunner implements BackupProcessRunner {
  private static final int MAX_STDERR_CHARS = 8000;
  private static final long DESTROY_GRACE_MILLIS = 10_000;

  @Override
  public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
    Path stderrFile = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.environment().putAll(env);
      // stdout 无用途；stderr 重定向到临时文件后再读取，避免管道缓冲填满导致进程阻塞。
      builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      stderrFile = Files.createTempFile("qaflex-backup-process", ".stderr");
      builder.redirectError(stderrFile.toFile());
      Process process = builder.start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        throw new BackupProcessException(
            "命令执行超过 " + timeout.toSeconds() + " 秒被终止：" + command.get(0));
      }
      String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
      return new ProcessResult(process.exitValue(), truncate(stderr));
    } catch (IOException failure) {
      throw new BackupProcessException(
          "命令启动失败（请确认 " + command.get(0) + " 已安装且在 PATH 中）：" + failure.getMessage(), failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new BackupProcessException("命令等待被中断：" + command.get(0), failure);
    } finally {
      if (stderrFile != null) {
        try {
          Files.deleteIfExists(stderrFile);
        } catch (IOException cleanupFailure) {
          // 临时 stderr 文件删除失败不影响主流程。
        }
      }
    }
  }

  private static String truncate(String value) {
    String trimmed = value == null ? "" : value.strip();
    return trimmed.length() <= MAX_STDERR_CHARS ? trimmed : trimmed.substring(0, MAX_STDERR_CHARS);
  }
}
