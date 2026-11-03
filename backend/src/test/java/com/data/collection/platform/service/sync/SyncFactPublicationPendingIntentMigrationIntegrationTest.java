package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 手工全量事实重建的发布意图登记回归（真实 Flyway schema）。
 *
 * <p>意图在创建事实刷新运行之前登记，此时还没有任何镜像运行可以引用；发布状态行必须允许
 * {@code latest_mirror_run_id} 为空，否则登记本身就会触发非空约束失败，手工重建根本无法提交。
 *
 * <p>与 {@link SyncFactPublicationStateServiceIntegrationTest} 的分工：后者使用内存搭建的最小表结构
 * 验证发布语义；本类使用应用真实迁移后的表结构，锁定“迁移允许意图先于镜像运行登记”这一部署前提。
 */
@SpringBootTest
class SyncFactPublicationPendingIntentMigrationIntegrationTest {
  private static final String PROBE_NAME = "fact-publication-intent-probe";
  private static final String PROBE_SOURCE = "default";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SyncFactPublicationStateService publicationStateService;

  private long configId;

  @BeforeEach
  void setUp() {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  """
                  insert into gitlab_sync_configs(name, db_name, db_username, db_password)
                  values (?, '', '', '')
                  """,
                  new String[] {"id"});
          statement.setString(1, PROBE_NAME);
          return statement;
        },
        keyHolder);
    configId = keyHolder.getKey().longValue();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "delete from source_fact_publication_states where config_id = ?", configId);
    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", configId);
  }

  @Test
  void requestFullPublicationRegistersIntentBeforeAnyMirrorRunExists() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(configId);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);

    int registered = publicationStateService.requestFullPublication(config);

    assertThat(registered).isEqualTo(3);
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select fact_type, latest_mirror_run_id, readiness_status, full_publication_requested
              from source_fact_publication_states
             where config_id = ? and source_instance = ?
             order by fact_type
            """,
            configId,
            PROBE_SOURCE);
    assertThat(rows).hasSize(3);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.get("latest_mirror_run_id")).isNull();
              assertThat(row.get("readiness_status")).isEqualTo("BLOCKED");
              assertThat(row.get("full_publication_requested")).isEqualTo(true);
            });
  }

  @Test
  void repeatedRequestOnExistingStatesStaysIdempotent() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(configId);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);

    publicationStateService.requestFullPublication(config);
    int registered = publicationStateService.requestFullPublication(config);

    assertThat(registered).isEqualTo(3);
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from source_fact_publication_states where config_id = ?",
            Integer.class,
            configId);
    assertThat(count).isEqualTo(3);
  }
}
