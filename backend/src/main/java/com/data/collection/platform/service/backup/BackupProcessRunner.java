package com.data.collection.platform.service.backup;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 外部进程（pg_dump / pg_restore）执行抽象；生产实现走本机进程，测试注入替身。 */
public interface BackupProcessRunner {

  /**
   * 执行外部命令并等待结束。
   *
   * @param command 完整命令行（含可执行文件与全部参数）
   * @param env 追加到进程环境变量（如 PGPASSWORD）
   * @param timeout 最长等待时长；超时进程被强制销毁并抛出 {@link BackupProcessException}
   * @return 退出码与合并后的标准错误内容（已截断）
   * @throws BackupProcessException 进程启动失败或超时
   */
  ProcessResult run(List<String> command, Map<String, String> env, Duration timeout);

  record ProcessResult(int exitCode, String stderr) {}
}
