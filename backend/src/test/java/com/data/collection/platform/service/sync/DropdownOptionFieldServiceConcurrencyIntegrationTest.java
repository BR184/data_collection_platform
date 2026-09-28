package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.dropdown.DropdownOptionBindingRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionBindingTarget;
import com.data.collection.platform.entity.dropdown.DropdownOptionConfigSaveRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionFieldConfigResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionRulesPayload;
import com.data.collection.platform.service.dropdown.DropdownOptionConfigRepository;
import com.data.collection.platform.service.dropdown.DropdownOptionFieldRegistry;
import com.data.collection.platform.service.dropdown.DropdownOptionFieldService;
import com.data.collection.platform.service.dropdown.DropdownOptionFilterService;
import com.data.collection.platform.service.dropdown.DropdownOptionRuleSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DropdownOptionFieldServiceConcurrencyIntegrationTest {
  private static final String FIELD = DropdownOptionFieldRegistry.REVIEW_FORM_PROJECT_NAME_FIELD;
  private static final String DISPLAY_NAME = "评审数据-新增评审-项目名称";
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private DataSource dataSource;
  private DropdownOptionConfigRepository repository;
  private DropdownOptionFieldService service;

  @BeforeAll
  static void openDatabase() {
    database = PostgresIntegrationTestDatabase.open("dropdown_option_concurrency_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) database.close();
  }

  @BeforeEach
  void setUp() {
    dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("drop table if exists dropdown_option_field_bindings");
    jdbcTemplate.execute("drop table if exists dropdown_option_configs");
    jdbcTemplate.execute("""
        create table dropdown_option_configs (
          id bigserial primary key,
          rules_json jsonb not null,
          manual_options_json jsonb not null,
          version bigint not null,
          updated_by varchar(64),
          created_at timestamptz not null default current_timestamp,
          updated_at timestamptz not null default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table dropdown_option_field_bindings (
          field_key varchar(128) primary key,
          config_id bigint not null references dropdown_option_configs(id),
          bound_at timestamptz not null default current_timestamp,
          bound_by varchar(64)
        )
        """);
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    repository = new DropdownOptionConfigRepository(jdbcTemplate, new JsonUtils(new ObjectMapper()));
    service = createService();
  }

  @Test
  void test_concurrentFirstSavesCreateOnlyTheWinningBinding() throws Exception {
    CountDownLatch firstOwnsLock = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<DropdownOptionFieldConfigResponse> first = executor.submit(() ->
          transactionTemplate.execute(status -> {
            repository.lockFieldMutation(FIELD);
            firstOwnsLock.countDown();
            await(releaseFirst);
            return service.saveConfig(FIELD, firstSave(), "first");
          }));
      assertThat(firstOwnsLock.await(5, TimeUnit.SECONDS)).isTrue();

      Future<Boolean> second = executor.submit(() -> {
        secondStarted.countDown();
        try {
          transactionTemplate.execute(status -> service.saveConfig(FIELD, firstSave(), "second"));
          return true;
        } catch (BizException conflict) {
          return false;
        }
      });
      assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitAdvisoryWaiter();
      releaseFirst.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
      assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
      assertThat(jdbcTemplate.queryForObject("select count(*) from dropdown_option_configs", Integer.class))
          .isEqualTo(1);
      assertThat(jdbcTemplate.queryForObject("select count(*) from dropdown_option_field_bindings", Integer.class))
          .isEqualTo(1);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void test_staleSaveAfterRebindCannotChangeEitherConfiguration() {
    DropdownOptionFieldConfigResponse original = transactionTemplate.execute(status ->
        service.saveConfig(FIELD, firstSave(), "admin"));
    Long originalConfigId = original.configId();

    DropdownOptionFieldConfigResponse rebound = transactionTemplate.execute(status ->
        service.bindField(FIELD, new DropdownOptionBindingRequest(DropdownOptionBindingTarget.NEW, null), "admin"));

    assertThatThrownBy(() -> transactionTemplate.execute(status -> service.saveConfig(
        FIELD,
        new DropdownOptionConfigSaveRequest(
            originalConfigId, DropdownOptionRulesPayload.empty(), List.of("stale"), original.version()),
        "stale-editor")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("身份已变化");
    assertThat(repository.findBoundConfigId(FIELD)).contains(rebound.configId());
    assertThat(repository.loadConfig(originalConfigId).orElseThrow().manualOptions()).isEmpty();
    assertThat(repository.loadConfig(rebound.configId()).orElseThrow().manualOptions()).isEmpty();
    assertThat(jdbcTemplate.queryForObject("select count(*) from dropdown_option_configs", Integer.class))
        .isEqualTo(2);
  }

  private DropdownOptionConfigSaveRequest firstSave() {
    return new DropdownOptionConfigSaveRequest(null, DropdownOptionRulesPayload.empty(), List.of(), 0L);
  }

  private DropdownOptionFieldService createService() {
    DropdownOptionFieldRegistry fieldRegistry = mock(DropdownOptionFieldRegistry.class);
    var definition = new DropdownOptionFieldRegistry.DropdownOptionFieldDefinition(
        FIELD, DISPLAY_NAME, List::<String>of);
    when(fieldRegistry.requireField(FIELD)).thenReturn(definition);
    when(fieldRegistry.find(FIELD)).thenReturn(Optional.of(definition));

    DropdownOptionRuleSupport ruleSupport = mock(DropdownOptionRuleSupport.class);
    when(ruleSupport.normalizeAndValidate(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(ruleSupport.normalizeManualOptions(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    return new DropdownOptionFieldService(
        fieldRegistry,
        repository,
        ruleSupport,
        mock(DropdownOptionFilterService.class),
        new JsonUtils(new ObjectMapper()));
  }

  private void awaitAdvisoryWaiter() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      Integer waiters = jdbcTemplate.queryForObject(
          "select count(*) from pg_locks where locktype = 'advisory' and not granted", Integer.class);
      if (waiters != null && waiters > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError("第二个事务没有进入 PostgreSQL advisory lock 等待");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("等待测试屏障超时");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("测试线程被中断", interrupted);
    }
  }
}
