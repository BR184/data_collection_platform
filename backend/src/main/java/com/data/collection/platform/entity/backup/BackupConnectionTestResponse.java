package com.data.collection.platform.entity.backup;

import java.util.List;

/** 远程连接测试结果：逐项检查结果 + 服务器实际主机密钥指纹（供保存固化或更新）。 */
public record BackupConnectionTestResponse(boolean ok, List<CheckResult> checks, String actualFingerprint) {

  /** 单项检查结果；message 面向页面直接展示。 */
  public record CheckResult(String name, boolean passed, String message) {}
}
