package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.read.DatasetReadSelection;
import com.data.collection.platform.entity.read.PublishedDatasetReadContext;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.service.read.AcquireReadViewRequest;
import com.data.collection.platform.service.read.PublishedReadService;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import com.data.collection.platform.service.read.ReadViewRepository;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 CAT 发布、统一视图租约与回收：超过十份后旧页/旧下载仍读取原快照。 */
class BiCatReadPublicationIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  @BeforeAll
  static void migrate() {
    database = PostgresIntegrationTestDatabase.open("cat_read_publication");
    var flyway = Flyway.configure().dataSource(database.dataSource()).locations("classpath:db/migration")
        .cleanDisabled(false).load();
    flyway.clean();
    flyway.migrate();
  }

  @AfterAll
  static void close() { if (database != null) { database.close(); } }

  @Test
  void old_cat_snapshot_and_download_fingerprint_survive_twelve_new_publications_and_mapping_change() {
    var source = database.dataSource();
    var jdbc = new JdbcTemplate(source);
    var transactions = new DataSourceTransactionManager(source);
    var properties = new ReadFallbackProperties();
    properties.setRetirementGraceMinutes(0);
    var publications = new ReadPublicationRepository(jdbc);
    var lifecycle = proxy(new ReadPublicationService(publications, properties), transactions);
    var bridge = proxy(new BiCatReadPublicationService(jdbc, publications, lifecycle), transactions);
    var repository = new BiCatMirrorRepository(jdbc, new TransactionTemplate(transactions), bridge);
    var reads = proxy(new PublishedReadService(publications, new ReadViewRepository(jdbc), properties, transactions), transactions);
    var domain = new ReadPublicationDomainKey(ReadPublicationProducerKind.CAT_MIRROR, "default", "DATASET", "cat:10:UNIT_TEST");
    var mapping = new BiCatMirrorModels.ScopeMapping(10, "CC2026R4", "project", "version", "unit", "integration");
    var scope = new com.data.collection.platform.bi.domain.source.BiProductVersionScope(10, 9, "CC2026R4", "CC2026R4", 0, List.of());
    UUID initial = publish(repository, jdbc, mapping, 90);
    var view = reads.acquireView(new AcquireReadViewRequest("tester", "session", "bi:unit-test", "version:10",
        LocalDate.of(2026, 10, 13), "rule", Set.of(domain), UUID.randomUUID()));
    PublishedDatasetReadContext context = (PublishedDatasetReadContext) view.domains().get(domain);
    var selection = new DatasetReadSelection(List.of(context), null, view.businessDate());
    String fingerprint = reads.withinPublishedRead(view,
        actual -> repository.forRead(selection).requireCurrentSourceVersion(scope, "UNIT_TEST"));
    for (int index = 0; index < 12; index++) { publish(repository, jdbc, mapping, 91 + index % 9); }
    jdbc.update("insert into bi_cat_scope_mappings(product_version_id, product_version_key, cat_project_id, cat_version_id,"
        + " unit_testing_phase_id, integration_testing_phase_id) values (10, 'CHANGED', 'other', 'other', 'other', 'other')"
        + " on conflict (product_version_id) do update set product_version_key = 'CHANGED', cat_project_id = 'other'");
    lifecycle.collectGarbage(domain);
    repository.pruneSnapshots(10);
    var old = reads.withinPublishedRead(view, actual -> repository.forRead(selection).loadPublishedTestSnapshot(scope, "UNIT_TEST").orElseThrow());
    assertThat(old.snapshotId()).isEqualTo(initial);
    assertThat(old.overallPassRate()).isEqualByComparingTo("90");
    assertThat(old.modules()).hasSize(1);
    assertThat(old.functions()).hasSize(1);
    String retainedFingerprint = reads.withinPublishedRead(view,
        actual -> repository.forRead(selection).requireCurrentSourceVersion(scope, "UNIT_TEST"));
    assertThat(retainedFingerprint).isEqualTo(fingerprint);
    assertThat(jdbc.queryForObject("select count(*) from bi_cat_test_snapshots", Long.class)).isEqualTo(11);
    reads.releaseView(view.viewId(), "tester", "session");
    lifecycle.collectGarbage(domain);
    repository.pruneSnapshots(10);
    assertThat(jdbc.queryForObject("select count(*) from bi_cat_test_snapshots where snapshot_id = ?", Long.class, initial)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from bi_cat_test_snapshots", Long.class)).isEqualTo(10);
  }

  private UUID publish(BiCatMirrorRepository repository, JdbcTemplate jdbc, BiCatMirrorModels.ScopeMapping mapping, int rate) {
    UUID run = UUID.randomUUID();
    UUID snapshot = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update("insert into bi_cat_sync_runs(run_id, run_type, trigger_type, status, started_at)"
        + " values (?, 'FULL', 'MANUAL', 'RUNNING', ?)", run, java.sql.Timestamp.from(now));
    repository.publishTestSnapshot(run, new BiCatMirrorModels.TestSnapshot(snapshot, mapping, "UNIT_TEST", "unit", "READY",
        BigDecimal.valueOf(rate), 1L, 1L, now, now,
        List.of(new BiCatMirrorModels.ModuleSnapshot("module", "模块", 1, 1, BigDecimal.valueOf(rate), 0)),
        List.of(new BiCatMirrorModels.FunctionSnapshot("module", "function", "功能", "功能", BigDecimal.valueOf(rate), 0)),
        List.of()), now);
    return snapshot;
  }

  private <T> T proxy(T target, DataSourceTransactionManager transactions) {
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    @SuppressWarnings("unchecked") T result = (T) factory.getProxy();
    return result;
  }
}
