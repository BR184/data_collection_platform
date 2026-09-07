package com.data.collection.platform.service;

/** 全量事实构建的进度回调；实现方用于续期任务租约与上报用户可见进度。 */
@FunctionalInterface
public interface FactBuildProgress {
  FactBuildProgress NO_OP = (completedChunks, totalChunks, processedRows) -> {};

  /**
   * 每个分批事务提交后回调一次。
   *
   * <p>回调发生在批次事务提交之后；实现方抛出的运行时异常会中止后续批次构建，已提交批次保留并由
   * 任务重试幂等收敛。
   *
   * @param completedChunks 已提交批次数，从 1 递增
   * @param totalChunks 总批次数
   * @param processedRows 已累计写入的事实行数
   */
  void chunkCommitted(int completedChunks, int totalChunks, long processedRows);
}
