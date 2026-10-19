package com.data.collection.platform.config;

import com.data.collection.platform.bi.infrastructure.BiCatProperties;
import com.data.collection.platform.service.backup.BackupConfigurationProperties;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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

  /**
   * 平台统一的“当前时刻”来源，供随日历派生产出口径的业务读取注入。
   *
   * <p>业务实现不得自行调用 {@code Clock.systemUTC()}：黄金基线套件需要用固定时钟覆盖该
   * Bean，使滞留小时一类以当前时刻为基准的派生字段可复现。生产环境仍为系统 UTC 时钟。
   *
   * @return 系统 UTC 时钟
   */
  @Bean
  Clock platformClock() {
    return Clock.systemUTC();
  }
}
