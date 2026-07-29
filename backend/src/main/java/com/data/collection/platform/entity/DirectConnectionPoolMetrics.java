package com.data.collection.platform.entity;

/** DIRECT GitLab 数据源连接池的即时容量快照。 */
public record DirectConnectionPoolMetrics(
    int maximumConnections,
    int totalConnections,
    int activeConnections,
    int idleConnections,
    int waitingThreads) {}
