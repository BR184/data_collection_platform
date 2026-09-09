package com.data.collection.platform.service.backup;

import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * pg_dump / pg_restore 命令行参数的唯一构造点。
 * 参数形态集中于此，使进程执行器与测试替身共享同一契约；客户端版本由镜像保证与 PG 主版本一致。
 */
@Component
public class BackupCommandFactory {
  private final BackupConfigurationProperties properties;

  public BackupCommandFactory(BackupConfigurationProperties properties) {
    this.properties = properties;
  }

  /** 全库自定义格式导出：-Fc 带压缩、--no-password 禁止交互挂起、口令经 PGPASSWORD 环境变量注入。 */
  public List<String> dumpCommand(BackupDatabaseTarget target, Path dumpFile) {
    return List.of(
        properties.getPgDumpBin(),
        "-h", target.host(),
        "-p", String.valueOf(target.port()),
        "-U", target.username(),
        "-d", target.database(),
        "--format=custom",
        "--no-password",
        "--file=" + dumpFile);
  }

  /** 可恢复性校验：TOC 位于 custom 格式文件尾部，文件被截断或损坏时 pg_restore --list 必失败。 */
  public List<String> verifyCommand(Path dumpFile) {
    return List.of(properties.getPgRestoreBin(), "--list", dumpFile.toString());
  }
}
