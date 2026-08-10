package com.data.collection.platform.config;

import com.data.collection.platform.bi.infrastructure.BiCatProperties;
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
    BiCatProperties.class
})
public class PlatformConfiguration {
}
