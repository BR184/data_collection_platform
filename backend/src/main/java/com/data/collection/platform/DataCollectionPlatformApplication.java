package com.data.collection.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.data.collection.migration.MigrationApplication;

@SpringBootApplication
public class DataCollectionPlatformApplication {
  /** service 启动平台服务；migrate 使用隔离上下文执行一次 Flyway，不装配业务生产者。 */
  public static void main(String[] args) {
    String mode = System.getenv().getOrDefault("PLATFORM_RUN_MODE", "service");
    if ("migrate".equals(mode)) {
      MigrationApplication.main(args);
      return;
    }
    if (!"service".equals(mode)) {
      throw new IllegalArgumentException("未知 PLATFORM_RUN_MODE：" + mode);
    }
    SpringApplication.run(DataCollectionPlatformApplication.class, args);
  }
}
