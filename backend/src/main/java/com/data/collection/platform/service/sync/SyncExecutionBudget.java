package com.data.collection.platform.service.sync;

/**
 * 单个数据源同步运行的已解析执行预算。
 *
 * @param workerCount 表任务并发数
 * @param controlConnectionReserve 控制面保留连接数
 * @param directPoolSize DIRECT JDBC 连接池总容量
 * @param connectionAcquireTimeoutMs 从连接池获取连接的最长等待毫秒数
 */
public record SyncExecutionBudget(
    int workerCount,
    int controlConnectionReserve,
    int directPoolSize,
    long connectionAcquireTimeoutMs) {
}
