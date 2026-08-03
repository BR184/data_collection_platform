package com.data.collection.platform.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = FlywayAutoConfiguration.class)
public class IntegrationTestFlywayConfiguration {
  @Bean
  FlywayMigrationStrategy isolatedTestSchemaMigrationStrategy() {
    return flyway -> {
      flyway.clean();
      flyway.migrate();
    };
  }
}
