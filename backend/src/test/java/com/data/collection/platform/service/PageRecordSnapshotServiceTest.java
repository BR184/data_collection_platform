package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class PageRecordSnapshotServiceTest {

  @Test
  void reviewSourceVersionIncludesTheConfiguredReadMode() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("source-version");
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));

    String sourceVersion = service.reviewDataSourceVersion();

    assertThat(sourceVersion).startsWith("source-version|source-version|source-version|");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, atLeastOnce()).queryForObject(sqlCaptor.capture(), eq(String.class));
    List<String> sourceQueries = sqlCaptor.getAllValues();
    assertThat(sourceQueries)
        .anyMatch(query -> query.contains("review_data_read_mode") && query.contains("enabled"));
  }

  @Test
  void reviewSourceVersionDegradesToStableMarkerWhenFingerprintQueryFails() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
        .thenThrow(new DataAccessResourceFailureException("fingerprint blocked"));
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));

    String sourceVersion = service.reviewDataSourceVersion();

    assertThat(sourceVersion).contains("degraded-");
    // 首个分段失败后必须短路余下分段，避免每个请求串行承受多次超时。
    verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(String.class));
  }

  @Test
  void reviewSourceVersionKeepsHealthySegmentsBeforeTheFailingOne() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
        .thenReturn("review-read-mode:1:COMPAT:2026-09-04T10:00:00")
        .thenThrow(new DataAccessResourceFailureException("fingerprint blocked"));
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));

    String sourceVersion = service.reviewDataSourceVersion();

    assertThat(sourceVersion).startsWith("review-read-mode:1:COMPAT:2026-09-04T10:00:00|");
    assertThat(sourceVersion).contains("degraded-");
  }

  @Test
  void readOrRefreshServesTheLatestSnapshotWithoutRebuildWhenVersionIsDegraded() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any(), any(), any()))
        .thenReturn(List.of("{\"records\":[]}"));
    when(jsonUtils.fromJson(anyString(), eq(String.class))).thenReturn("stale-payload");
    when(jsonUtils.toJson(any())).thenReturn("{}");
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            jsonUtils,
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));
    AtomicInteger supplierInvocations = new AtomicInteger();
    PageRecordSnapshotService.SnapshotRequest request =
        new PageRecordSnapshotService.SnapshotRequest(
            "review-data-records", "LIST", "all", "review-v6", "degraded-48215", Map.of());

    String payload =
        service.readOrRefresh(request, String.class, () -> {
          supplierInvocations.incrementAndGet();
          return "rebuilt";
        });

    assertThat(payload).isEqualTo("stale-payload");
    assertThat(supplierInvocations.get()).isZero();
  }

  @Test
  void readOrRefreshSharesOneRebuildBetweenConcurrentMisses() throws Exception {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(jsonUtils.toJson(any())).thenReturn("{}");
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            jsonUtils,
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));
    CountDownLatch supplierEntered = new CountDownLatch(1);
    CountDownLatch releaseSupplier = new CountDownLatch(1);
    AtomicInteger supplierInvocations = new AtomicInteger();
    PageRecordSnapshotService.SnapshotRequest request =
        new PageRecordSnapshotService.SnapshotRequest(
            "review-data-records", "LIST", "all", "review-v6", "source-version", Map.of());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      Future<String> first =
          pool.submit(
              () ->
                  service.readOrRefresh(
                      request,
                      String.class,
                      () -> {
                        supplierInvocations.incrementAndGet();
                        supplierEntered.countDown();
                        try {
                          if (!releaseSupplier.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("supplier was never released");
                          }
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(error);
                        }
                        return "rebuilt";
                      }));
      assertThat(supplierEntered.await(5, TimeUnit.SECONDS)).isTrue();

      AtomicReference<String> secondResult = new AtomicReference<>();
      AtomicReference<Throwable> secondError = new AtomicReference<>();
      Thread second =
          new Thread(
              () -> {
                try {
                  secondResult.set(
                      service.readOrRefresh(request, String.class, () -> "late-rebuild"));
                } catch (Throwable error) {
                  secondError.set(error);
                }
              });
      second.start();
      // 等第二个请求确实 park 在 winner.join() 上再放行重建；若只依赖查询桩计数，
      // 调度延迟会让它错过合并窗口、合法地自行重建，测试自身产生假失败。
      assertThat(waitUntilParkedInJoin(second)).isTrue();

      releaseSupplier.countDown();

      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("rebuilt");
      second.join(TimeUnit.SECONDS.toMillis(5));
      assertThat(secondError.get()).isNull();
      assertThat(secondResult.get()).isEqualTo("rebuilt");
      assertThat(supplierInvocations.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  /** 轮询等待线程进入 WAITING——该路径上唯一的 park 点是合并窗口内的 winner.join()。 */
  private static boolean waitUntilParkedInJoin(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == Thread.State.WAITING) {
        return true;
      }
      if (!thread.isAlive()) {
        return false;
      }
      TimeUnit.MILLISECONDS.sleep(5);
    }
    return thread.getState() == Thread.State.WAITING;
  }

  @Test
  void reviewSourceVersionIncludesDropdownOptionConfigAndBindingFingerprint() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("source-version");
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));

    service.reviewDataSourceVersion();

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, atLeastOnce()).queryForObject(sqlCaptor.capture(), eq(String.class));
    assertThat(sqlCaptor.getAllValues())
        .anyMatch(query -> query.contains("dropdown_option_configs") && query.contains("dropdown_option_field_bindings"));
  }

  @Test
  void reviewSourceVersionRetainsDynamicComponentsBeyondLegacyColumnLimit() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    FactProjectionVersionService projectionVersionService = mock(FactProjectionVersionService.class);
    String component = "source-version-component-".repeat(12);
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(component);
    when(projectionVersionService.globalSourceVersion(anyString(), any(FactType.class)))
        .thenReturn(component);
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            mock(JsonUtils.class),
            projectionVersionService,
            mock(IssueProjectionScopeResolver.class));

    String sourceVersion = service.reviewDataSourceVersion();

    assertThat(sourceVersion).hasSizeGreaterThan(256);
  }

  @Test
  void savePassesTheCompleteLongSourceVersionToSnapshotPersistence() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    when(jsonUtils.toJson(any())).thenReturn("{}");
    PageRecordSnapshotService service =
        new PageRecordSnapshotService(
            jdbcTemplate,
            jsonUtils,
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class));
    String sourceVersion = "source-version-component-".repeat(12);
    PageRecordSnapshotService.SnapshotRequest request =
        new PageRecordSnapshotService.SnapshotRequest(
            "review-data", "LIST", "all", "review-v1", sourceVersion, java.util.Map.of());

    service.save(request, java.util.Map.of());

    ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).update(anyString(), argumentsCaptor.capture());
    Object[] arguments = argumentsCaptor.getValue();
    assertThat(arguments).hasSize(8);
    assertThat(arguments[4]).isEqualTo(sourceVersion);
  }
}
