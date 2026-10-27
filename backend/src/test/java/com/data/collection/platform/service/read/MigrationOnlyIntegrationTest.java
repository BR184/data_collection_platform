package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.migration.MigrationApplication;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实执行升级前后的仅迁移上下文，证明它不会加载平台业务生产者、安装器或 Web。 */
class MigrationOnlyIntegrationTest {
  @Test
  void test_migration_process_upgrades_without_business_components_or_writes() {
    try (PostgresIntegrationTestDatabase database = PostgresIntegrationTestDatabase.open("migration_only")) {
      DriverManagerDataSource dataSource = (DriverManagerDataSource) database.dataSource();
      Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
          .target("20261015.02").load().migrate();
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      jdbc.update("insert into issue_fact(source_system, source_instance, project_id, issue_id, issue_iid, title)"
          + " values ('GITLAB', 'default', 1, 1, 1, '迁移不得改写')");
      var before = jdbc.queryForMap("select * from issue_fact where issue_id = 1");
      long requestsBefore = jdbc.queryForObject("select count(*) from read_dataset_refresh_requests", Long.class);

      SpringApplication application = new SpringApplication(MigrationApplication.class);
      application.setWebApplicationType(WebApplicationType.NONE);
      try (ConfigurableApplicationContext context = application.run(
          "--spring.datasource.url=" + dataSource.getUrl(),
          "--spring.datasource.username=" + dataSource.getUsername(),
          "--spring.datasource.password=" + dataSource.getPassword(),
          "--spring.flyway.enabled=true", "--spring.sql.init.mode=never",
          // 通用测试上下文的 clean 策略不能进入本测试的真实向前迁移路径。
          "--spring.autoconfigure.exclude=com.data.collection.platform.config.IntegrationTestFlywayConfiguration",
          "--spring.main.banner-mode=off")) {
        assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorDatasetPublicationWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(DatasetRefreshWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(ReadModelInstallationScheduler.class)).isEmpty();
        assertThat(context).isNotInstanceOf(org.springframework.boot.web.context.WebServerApplicationContext.class);
        assertThat(context.getBean(Flyway.class).info().current().getVersion().getVersion()).isEqualTo("20261027.01");
      }
      assertThat(jdbc.queryForMap("select * from issue_fact where issue_id = 1")).isEqualTo(before);
      assertThat(jdbc.queryForObject("select count(*) from read_dataset_refresh_requests", Long.class)).isEqualTo(requestsBefore);
      assertThat(jdbc.queryForObject("select count(*) from read_publications", Long.class)).isZero();
    }
  }
}
