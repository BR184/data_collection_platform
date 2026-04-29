package com.data.collection.platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableAsync
@MapperScan("com.data.collection.platform.mapper")
@EnableConfigurationProperties({GitlabMirrorProperties.class, ReviewDataProperties.class})
public class PlatformConfiguration {
}
