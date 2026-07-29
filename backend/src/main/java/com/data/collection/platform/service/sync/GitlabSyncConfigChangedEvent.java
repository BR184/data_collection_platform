package com.data.collection.platform.service.sync;

/** GitLab 数据源配置事务提交后的运行时资源失效事件。 */
public record GitlabSyncConfigChangedEvent(Long configId) {
}
