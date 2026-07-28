package com.data.collection.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台后台调度总入口。
 *
 * <p>正常运行时默认启用全部 {@code @Scheduled} 任务。发布迁移进程可通过
 * {@code platform.background-jobs.enabled=false} 关闭调度，以便 Flyway 和数据守恒校验
 * 在无后台业务写入的静默窗口内完成；该开关不影响健康检查、Flyway 或普通请求处理。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "platform.background-jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BackgroundSchedulingConfiguration {
}
