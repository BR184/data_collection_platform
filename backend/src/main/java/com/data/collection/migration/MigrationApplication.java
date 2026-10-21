package com.data.collection.migration;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** 单次迁移进程：只装配自动配置的数据库/Flyway 基础设施，不扫描平台业务组件或启动 Web。 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class MigrationApplication {
  /** 执行配置中的完整迁移并校验；失败向进程传播，成功关闭连接池后退出，不提供业务服务。 */
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(MigrationApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.addInitializers(context -> {
      if (context instanceof org.springframework.boot.web.context.WebServerApplicationContext) {
        throw new IllegalStateException("迁移模式禁止启动 Web，请移除覆盖 web-application-type 的配置");
      }
    });
    try (ConfigurableApplicationContext context = application.run(args)) {
      context.getBean(Flyway.class).validate();
    }
  }
}
