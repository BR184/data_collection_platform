package com.data.collection.platform.config;

import com.data.collection.platform.bi.infrastructure.BiCatProperties;
import com.data.collection.platform.service.backup.BackupConfigurationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
@MapperScan("com.data.collection.platform.mapper")
@EnableConfigurationProperties({
    GitlabMirrorProperties.class,
    PlatformAuthProperties.class,
    ReviewDataProperties.class,
    ExternalApiProperties.class,
    BiCatProperties.class,
    BackupConfigurationProperties.class
})
public class PlatformConfiguration {
}
