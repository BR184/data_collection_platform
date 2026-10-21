package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.data.collection.platform.common.JsonUtils;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** 启动只创建运行环境，数据生产和可恢复发布错误不能进入启动调用链。 */
class ReadStartupBoundaryTest {
  @Test
  void test_read_publication_has_no_synchronous_application_runner() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(ApplicationRunner.class));
    assertThat(scanner.findCandidateComponents("com.data.collection.platform.service.read")).isEmpty();
  }

  @Test
  void test_ready_event_does_not_write_dataset_requests() {
    assertThat(java.util.Arrays.stream(DatasetRefreshWorker.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(EventListener.class))).isEmpty();
  }

  @Test
  void test_disabling_background_scheduling_does_not_execute_installation_or_request_writes() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ReadModelInitialInstallation facts = mock(ReadModelInitialInstallation.class);
    var cat = mock(com.data.collection.platform.bi.infrastructure.BiCatReadPublicationService.class);
    var datasets = mock(DatasetPublicationService.class);
    var retry = mock(ReadProducerRetryService.class);
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(com.data.collection.platform.config.BackgroundSchedulingConfiguration.class,
            ReadModelInstallationScheduler.class, DatasetRefreshWorker.class, MirrorDatasetPublicationWorker.class)
        .withBean(JdbcTemplate.class, () -> jdbc)
        .withBean(ReadModelInitialInstallation.class, () -> facts)
        .withBean(com.data.collection.platform.bi.infrastructure.BiCatReadPublicationService.class, () -> cat)
        .withBean(org.springframework.transaction.support.TransactionTemplate.class,
            () -> mock(org.springframework.transaction.support.TransactionTemplate.class))
        .withBean(DatasetPublicationService.class, () -> datasets)
        .withBean(com.data.collection.platform.service.DatasetProducerCatalog.class,
            () -> mock(com.data.collection.platform.service.DatasetProducerCatalog.class))
        .withBean(JsonUtils.class, () -> mock(JsonUtils.class))
        .withBean(ReadProducerRetryService.class, () -> retry)
        .withPropertyValues("platform.background-jobs.enabled=false")
        .run(context -> {
          assertThat(context).hasNotFailed();
          // Spring 初始化 JdbcTemplate 会调用 afterPropertiesSet；此生命周期调用不执行 SQL。
          assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations())
              .allMatch(call -> call.getMethod().getName().equals("afterPropertiesSet"));
          verifyNoInteractions(facts, cat, datasets, retry);
        });
  }

  @Test
  void test_prepared_mirror_timeout_is_contained_by_worker() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DatasetPublicationService datasets = mock(DatasetPublicationService.class);
    UUID publication = UUID.randomUUID();
    ResultSet row = mock(ResultSet.class);
    when(row.getObject(1, UUID.class)).thenReturn(publication);
    when(row.getString(2)).thenReturn("mirror-dataset:517:7");
    when(row.getString(3)).thenReturn("default");
    when(row.getString(4)).thenReturn("ods_fixture");
    when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<Object>>getArgument(1).mapRow(row, 0)));
    when(datasets.publishPreparedDataset(any(), anyString())).thenThrow(new QueryTimeoutException("模拟发布超时"));
    var worker = new MirrorDatasetPublicationWorker(jdbc, datasets, mock(JsonUtils.class),
        mock(ReadProducerRetryService.class));

    assertDoesNotThrow(worker::publishOneCompleteTable);
  }
}
