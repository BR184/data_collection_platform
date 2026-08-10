package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRunType;
import java.util.UUID;

/** 生成符合数据库长度约束的同步运行标识。 */
final class SyncRunIdGenerator {
  private static final int RUN_ID_MAX_LENGTH = 64;
  private static final int SOURCE_SEGMENT_MAX_LENGTH = 24;

  private SyncRunIdGenerator() {}

  static String generate(SyncRunType runType, String sourceInstance) {
    String randomPart = UUID.randomUUID().toString().replace("-", "");
    String sourceSegment = sourceInstance == null ? "default" : sourceInstance;
    if (sourceSegment.length() > SOURCE_SEGMENT_MAX_LENGTH) {
      sourceSegment = sourceSegment.substring(0, SOURCE_SEGMENT_MAX_LENGTH);
    }
    String alias = alias(runType);
    String runId = "sr_" + alias + "_" + sourceSegment + "_" + randomPart;
    if (runId.length() <= RUN_ID_MAX_LENGTH) {
      return runId;
    }
    int allowedSourceLength =
        RUN_ID_MAX_LENGTH - "sr_".length() - alias.length() - 2 - randomPart.length();
    sourceSegment = sourceSegment.substring(0, Math.max(1, allowedSourceLength));
    return "sr_" + alias + "_" + sourceSegment + "_" + randomPart;
  }

  private static String alias(SyncRunType runType) {
    return switch (runType) {
      case FULL_SYNC -> "fs";
      case INCREMENTAL_SYNC -> "is";
      case TABLE_REFRESH -> "tr";
      case SYSTEM_HOOK -> "sh";
      case FULL_COMPENSATION_SCAN -> "fc";
      case DELETE_RECONCILIATION -> "dr";
      case FACT_REFRESH -> "fr";
    };
  }
}
