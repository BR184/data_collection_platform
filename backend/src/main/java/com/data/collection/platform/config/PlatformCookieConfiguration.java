package com.data.collection.platform.config;

import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** 为每个平台实例建立独立且稳定的浏览器 Cookie 命名空间。 */
@Configuration
public class PlatformCookieConfiguration {
  private static final String DEFAULT_APPLICATION_NAME = "data-collection-platform-backend";
  private static final String DEFAULT_SERVER_PORT = "18080";

  /**
   * 解析实例身份并生成 Cookie 名；标准部署显式使用 Compose project，直接运行按监听端口隔离。
   *
   * @param environment 当前运行环境
   * @return 当前实例唯一的 Cookie 名称集合
   */
  @Bean
  public PlatformCookieNames platformCookieNames(Environment environment) {
    String explicitInstanceId = environment.getProperty("platform.instance.id");
    if (StringUtils.hasText(explicitInstanceId)) {
      return PlatformCookieNames.forInstance(explicitInstanceId);
    }
    String applicationName = environment.getProperty(
        "spring.application.name", DEFAULT_APPLICATION_NAME);
    String serverPort = environment.getProperty("server.port", DEFAULT_SERVER_PORT);
    return PlatformCookieNames.forInstance(applicationName + ":" + serverPort);
  }

  /**
   * 在 Servlet 容器创建 Session 前应用实例专属 Cookie 名。
   *
   * @param cookieNames 当前实例 Cookie 名称
   * @return Servlet 启动初始化器
   */
  @Bean
  public ServletContextInitializer platformSessionCookieInitializer(
      PlatformCookieNames cookieNames) {
    return servletContext -> servletContext.getSessionCookieConfig()
        .setName(cookieNames.sessionCookieName());
  }
}
