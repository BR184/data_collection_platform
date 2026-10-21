package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationState;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactProjectionGenerationService;
import com.data.collection.platform.service.FactProjectionScopeResolver;
import com.data.collection.platform.service.FactPublicationTransaction;
import com.data.collection.platform.service.FactTargetPublicationService;
import com.data.collection.platform.service.FactTaskExecutionContext;
import com.data.collection.platform.service.FactTaskExecutionGuard;
import com.data.collection.platform.service.read.BypassPublicationRequest;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在真实 PostgreSQL（完整 Flyway 迁移链）上验证事实候选发布编排的目标行为。
 *
 * <p>覆盖：新增/更新按完整业务身份生成候选修订并原子切换、来源消失只关闭对应身份的旧修订、无认证基线时
 * 拒绝把局部集合发布成首个完整版本、全量结算失败不留活动候选、以及同域候选互斥不变量。此前经理提出的
 * SIDE_UPDATE、SIDE_DELETE、NO_BASELINE、FULL_SETTLEMENT_FAILURE、FULL_ROOT_IDENTITY 五个反例在本类中
 * 以“目标行为成立”的方向被锁定。
 */
class FactTargetPublicationServiceIntegrationTest {
  private static final String SOURCE_SYSTEM = "GITLAB";
  private static final String SOURCE_INSTANCE = "alpha";
  private static final ReadPublicationDomainKey ISSUE_DOMAIN =
      ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE);
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private DataSourceTransactionManager transactionManager;
  private TransactionTemplate transactionTemplate;
  private ReadPublicationRepository readPublicationRepository;
  private ReadPublicationService readPublicationService;
  private SyncFactPublicationStateService publicationStateService;
  private FactBuildTaskService taskService;
  private SyncRunEventRecorder eventRecorder;
  private FactTaskExecutionGuard executionGuard;
  private FactTargetPublicationService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_target_publication_test");
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .schemas("public")
        .defaultSchema("public")
        .cleanDisabled(false)
        .load()
        .migrate();
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    transactionManager = new DataSourceTransactionManager(dataSource);
    transactionTemplate = new TransactionTemplate(transactionManager);
    truncateEverything();
    jdbcTemplate.update("insert into gitlab_sync_configs(id, name, source_instance, db_name, db_username, db_password) values (9900, 'candidate-test', 'alpha', 'gitlab', 'fixture', 'fixture')");
    readPublicationRepository = new ReadPublicationRepository(jdbcTemplate);
    readPublicationService =
        transactionalProxy(
            new ReadPublicationService(readPublicationRepository, new ReadFallbackProperties()));
    publicationStateService = mock(SyncFactPublicationStateService.class);
    when(publicationStateService.canBuildReadCandidate(anyString(), any(FactType.class))).thenReturn(true);
    when(publicationStateService.sourceSeal(anyString(), any(FactType.class), anyLong()))
        .thenReturn("{\"seal\":\"test-source\"}");
    when(publicationStateService.publicationUpperBound(anyString(), any(FactType.class)))
        .thenAnswer(invocation -> jdbcTemplate.queryForObject("select coalesce(max(latest_change_version),0) from fact_change_heads where source_instance = 'alpha' and fact_type = 'ISSUE'", Long.class));
    when(publicationStateService.currentFullRequestRevision(anyString(), any(FactType.class)))
        .thenReturn(7L);
    taskService = mock(FactBuildTaskService.class);
    when(taskService.renewTaskLease(any(), anyInt())).thenReturn(true);
    eventRecorder = mock(SyncRunEventRecorder.class);
    executionGuard = new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext());
    service =
        transactionalProxy(
            new FactTargetPublicationService(
                jdbcTemplate,
                new FactProjectionScopeResolver(jdbcTemplate, readPublicationService),
                new FactProjectionGenerationService(jdbcTemplate),
                mock(SyncRunPublicationFenceService.class),
                publicationStateService,
                transactionalProxy(new FactPublicationTransaction(executionGuard)),
                executionGuard,
                taskService,
                eventRecorder,
                new GitlabMirrorProperties(),
                readPublicationService,
                readPublicationRepository,
                new com.data.collection.platform.service.CustomerIssueMilestoneCatalogReconciliationService(jdbcTemplate),
                com.data.collection.platform.service.FactRevisionFixture.projectionArtifacts(
                    jdbcTemplate, readPublicationService, readPublicationRepository)));
  }

  /** 更新既有根：新修订接管可见性，旧修订在上一发布序号上仍然可读。 */
  @Test
  void test_publish_updates_root_and_keeps_previous_version_readable() {
    seedPublishedIssue(501L, 42L, "old");
    ReadPublication baseline = currentPublication();
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);

    FactBuildResponse response =
        service.publish(
            task(100L, "worker-a"),
            (target, rootIds) -> {
              assertThat(rootIds).containsExactly(501L);
              insertCandidateIssue(501L, 42L, "new", target);
              return new FactBuildResponse("issue", false, 1, "published");
            });

    assertThat(response.affectedRows()).isOne();
    assertThat(currentPublication().id()).as("构建完不能提前切指针").isEqualTo(baseline.id());
    completeCandidate();
    ReadPublication published = currentPublication();
    assertThat(published.sequenceNo()).isEqualTo(baseline.sequenceNo() + 1L);
    assertThat(published.state()).isEqualTo(ReadPublicationState.PUBLISHED);
    assertThat(visibleTitles(baseline.sequenceNo())).containsExactly("old");
    assertThat(visibleTitles(published.sequenceNo())).containsExactly("new");
    assertThat(openRevisionCount()).as("同一业务根只能有一个开放修订").isOne();
    assertThat(
            jdbcTemplate.queryForObject(
                "select published_version from fact_change_heads where root_id = 501", Long.class))
        .isEqualTo(12L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from fact_build_tasks where id = 100", String.class))
        .isEqualTo("SUCCESS");
    assertThat(
            jdbcTemplate.queryForObject(
                "select publication_id from fact_build_tasks where id = 100", UUID.class))
        .isEqualTo(published.id());
  }

  @Test
  void test_real_board_prewarm_is_private_until_publication_and_old_board_keeps_its_version() {
    Long catalog = jdbcTemplate.queryForObject("select id from issue_scope_catalogs where project_id = 325 and dimension = 'MILESTONE'", Long.class);
    Long group = jdbcTemplate.queryForObject("""
        insert into issue_scope_groups(catalog_id, business_key, display_name, sort_order)
        values (?, 'p3-prewarm', 'P3 预热夹具', -100) returning id
        """, Long.class, catalog);
    try {
      jdbcTemplate.update("insert into issue_scope_members(catalog_id, group_id, source_value, display_name) values (?, ?, 'p3-prewarm', 'P3 预热夹具')",
          catalog, group);
      insertUnversionedIssue(501L, 325L, "旧议题");
      jdbcTemplate.update("""
          update issue_fact set module_names = '旧模块', milestone_title = 'p3-prewarm',
            issue_state = 'opened', severity_level = '一级缺陷', priority_level = 'P1', category = '功能',
            created_at_source = timestamp '2026-10-01 00:00:00', is_response_delayed = true
          """);
      bootstrapBaseline();
      ReadPublication old = currentPublication();
      var oldSelection = new com.data.collection.platform.entity.read.FactReadSelection(List.of(
          new com.data.collection.platform.entity.read.PublishedReadContext(SOURCE_INSTANCE, PublicationFactTable.ISSUE,
              old.epochId(), old.sequenceNo(), old.id(), old.dataAsOf())), java.time.LocalDate.of(2026, 10, 13));
      seedHead(501L, 325L, 12L, 0L);
      seedTask(100L, "worker-a", false);
      service.publish(task(100L, "worker-a"), (target, roots) -> {
        insertCandidateIssue(501L, 325L, "新议题", target);
        jdbcTemplate.update("""
            update issue_fact set module_names = '新模块', milestone_title = 'p3-prewarm',
              issue_state = 'opened', severity_level = '一级缺陷', priority_level = 'P1', category = '功能',
              created_at_source = timestamp '2026-10-01 00:00:00', is_response_delayed = true
             where created_by_publication_id = ?
            """, target.publicationId());
        return new FactBuildResponse("issue", false, 1, "built");
      });
      ReadPublication candidate = activeCandidate();
      var execution = new com.data.collection.platform.service.FactProjectionExecutionContext();
      var artifactStore = new com.data.collection.platform.service.read.CandidateCacheArtifactStore(jdbcTemplate);
      var guard = new com.data.collection.platform.service.FactProjectionPublicationGuardService(
          jdbcTemplate, execution, artifactStore, transactionManager);
      var projectionTasks = transactionalProxy(new com.data.collection.platform.service.FactProjectionTaskService(
          jdbcTemplate, mock(SyncRunPublicationFenceService.class)));
      var json = new com.data.collection.platform.common.JsonUtils(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
      var snapshots = new com.data.collection.platform.service.statistics.StatisticBoardSnapshotService(
          jdbcTemplate, json, new com.data.collection.platform.service.FactProjectionVersionService(jdbcTemplate),
          publicationStateService, guard, transactionManager);
      var directory = new com.data.collection.platform.service.IssueScopeCatalogService(jdbcTemplate);
      var scopeResolver = new com.data.collection.platform.service.statistics.StatisticBoardReadScopeResolver(
          new com.data.collection.platform.service.FactProjectionVersionService(jdbcTemplate),
          new com.data.collection.platform.service.IssueProjectionScopeResolver(directory));
      var board = new com.data.collection.platform.service.statistics.CustomerIssueDelayIssuesBoardService(json,
          new com.data.collection.platform.service.IssueFactQueryService(jdbcTemplate, new com.data.collection.platform.service.SqlQueryMonitor(Long.MAX_VALUE)),
          new com.data.collection.platform.service.CustomerIssueScopeProfile(),
          mock(com.data.collection.platform.service.statistics.StatisticIssueLinkSupport.class),
          new com.data.collection.platform.service.statistics.CustomerIssueMilestoneCatalogService(directory), snapshots,
          new com.data.collection.platform.service.statistics.StatisticBoardSnapshotRequestFactory(scopeResolver));
      var artifacts = transactionalProxy(new com.data.collection.platform.service.read.CandidateProjectionArtifactService(
          jdbcTemplate, json, readPublicationRepository,
          new com.data.collection.platform.service.read.FactReadSelectionService(jdbcTemplate, readPublicationRepository, readPublicationService, () -> java.time.LocalDate.of(2026, 10, 13)),
          guard, projectionTasks, artifactStore));
      var worker = new com.data.collection.platform.service.FactProjectionTaskWorkerService(projectionTasks,
          new com.data.collection.platform.service.statistics.StatisticBoardSnapshotRefreshService(List.of(board)),
          new com.data.collection.platform.service.PageRecordSnapshotRefreshService(List.of()), execution,
          new GitlabMirrorProperties(), artifacts);
      projectionTasks.enqueuePreparedCandidates(900L);
      while (true) {
        var projection = projectionTasks.claimNext(900L, "run-lease-token", 300);
        if (projection == null) { break; }
        assertThat(worker.execute(projection)).as("真实看板预热必须成功").isTrue();
      }

      assertThat(jdbcTemplate.queryForObject("select count(*) from statistic_board_snapshots where publication_id = ? and status = 'READY'",
          Long.class, candidate.id())).isZero();
      assertThat(jdbcTemplate.queryForObject("select count(*) from statistic_board_snapshots where publication_id = ? and status = 'PREPARED' and row_payload::text like '%新模块%'",
          Long.class, candidate.id())).isPositive();
      assertThat(json.toJson(board.forRead(oldSelection).loadBoard(java.util.Map.of())))
          .contains("旧模块").doesNotContain("新模块");

      service.publishPreparedCandidate(candidate.id(), "run-lease-token");
      assertThat(jdbcTemplate.queryForObject("select count(*) from statistic_board_snapshots where publication_id = ? and status = 'READY'",
          Long.class, candidate.id())).isPositive();
      assertThat(json.toJson(board.forRead(oldSelection).loadBoard(java.util.Map.of())))
          .contains("旧模块").doesNotContain("新模块");
    } finally {
      jdbcTemplate.update("delete from issue_scope_groups where id = ?", group);
    }
  }

  /** 删除证明按完整身份判定：同号但不同项目的根不得被一起标记删除。 */
  @Test
  void test_delete_proof_only_closes_the_identity_that_vanished() {
    insertUnversionedIssue(501L, 42L, "stay-old");
    insertUnversionedIssue(501L, 77L, "gone");
    bootstrapBaseline();
    ReadPublication baseline = currentPublication();
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);

    service.publish(
        task(100L, "worker-a"),
        (target, rootIds) -> {
          insertCandidateIssue(501L, 42L, "stay-new", target);
          // 项目 77 同号根来自另一个任务集合：它不在本候选里，但也不能被“裸 root 删除”误伤。
          readPublicationRepository.recordDeletionsForRoots(
              PublicationFactTable.ISSUE,
              target.publicationId(),
              SOURCE_SYSTEM,
              SOURCE_INSTANCE,
              List.of(999L),
              target.coveredChangeVersion());
          return new FactBuildResponse("issue", false, 1, "published");
        });

    completeCandidate();
    ReadPublication published = currentPublication();
    assertThat(visibleTitles(published.sequenceNo()))
        .as("另一个项目的同号根仍属于当前发布版本")
        .containsExactlyInAnyOrder("stay-new", "gone");
    assertThat(visibleTitles(baseline.sequenceNo())).containsExactlyInAnyOrder("stay-old", "gone");

    // 真正的删除：同一考虑集合里，候选写出了项目 42 的身份，只有项目 77 的同号根被关闭旧修订。
    seedHead(501L, 42L, 13L, 12L);
    seedTask(101L, "worker-b", false);
    service.publish(
        task(101L, "worker-b"),
        (target, rootIds) -> {
          insertCandidateIssue(501L, 42L, "stay-new-2", target);
          readPublicationRepository.recordDeletionsForRoots(
              PublicationFactTable.ISSUE,
              target.publicationId(),
              SOURCE_SYSTEM,
              SOURCE_INSTANCE,
              List.of(501L),
              target.coveredChangeVersion());
          return new FactBuildResponse("issue", false, 1, "published-and-deleted");
        });

    completeCandidate();
    ReadPublication afterDelete = currentPublication();
    assertThat(visibleTitles(afterDelete.sequenceNo())).containsExactly("stay-new-2");
    assertThat(visibleTitles(published.sequenceNo()))
        .as("删除只对新发布序号生效，旧版本保持可读")
        .containsExactlyInAnyOrder("stay-new", "gone");
  }

  /** 旁路生产者在无认证基线的域上不得把局部集合发布成首个完整版本。 */
  @Test
  void test_bypass_without_certified_baseline_is_refused_and_then_works_after_takeover() {
    insertUnversionedIssue(501L, 42L, "mature-1");
    insertUnversionedIssue(502L, 42L, "mature-2");

    assertThatThrownBy(
            () ->
                service.publishWithinCandidate(
                    bypassRequest(),
                    target -> {
                      insertCandidateIssue(503L, 42L, "partial", target);
                      return new FactBuildResponse("bypass", false, 1, "written");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("尚无已认证的完整基线");

    assertThat(jdbcTemplate.queryForObject("select count(*) from read_publications", Long.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from issue_fact where created_by_publication_id is not null",
                Long.class))
        .isZero();

    bootstrapBaseline();

    service.publishWithinCandidate(
        bypassRequest(),
        target -> {
          insertCandidateIssue(503L, 42L, "partial", target);
          return new FactBuildResponse("bypass", false, 1, "written");
        });

    completeCandidate();
    ReadPublication published = currentPublication();
    assertThat(visibleTitles(published.sequenceNo()))
        .as("成熟基线必须继续出现在新版本里，局部候选不能冒充完整视图")
        .containsExactlyInAnyOrder("mature-1", "mature-2", "partial");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from read_publication_fact_roots where operation = 'UPSERT'",
                Long.class))
        .isOne();
  }

  /** 全量最终结算失败先中止候选；未公开修订分批清理不阻塞下一轮，旧指针保持。 */
  @Test
  void test_full_publication_settlement_failure_aborts_candidate() {
    seedPublishedIssue(501L, 42L, "old");
    seedTask(100L, "worker-a", true);
    doThrow(new IllegalStateException("结算授权已失效"))
        .when(publicationStateService)
        .settleFullPublicationIntent(anyString(), any(FactType.class), anyLong());

    service.publishFull(fullTask(100L, "worker-a"), (target, progress) -> {
      insertCandidateIssue(501L, 42L, "full-new", target);
      return new FactBuildResponse("issue", true, 1, "built");
    });
    assertThatThrownBy(this::completeCandidate).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("结算授权已失效");

    assertThat(activeCandidateCount()).as("失败不得留下活动候选").isZero();
    assertThat(currentPublication().state()).isEqualTo(ReadPublicationState.PUBLISHED);
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("old");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from issue_fact where created_by_publication_id is not null"
                    + " and title = 'full-new'",
                Long.class))
        .as("被中止候选的修订等待维护批次，仍不可公开").isOne();

    org.mockito.Mockito.reset(publicationStateService);
    when(publicationStateService.canBuildReadCandidate(anyString(), any(FactType.class))).thenReturn(true);
    when(publicationStateService.sourceSeal(anyString(), any(FactType.class), anyLong()))
        .thenReturn("{\"seal\":\"test-source\"}");
    seedTask(101L, "worker-b", true);
    FactBuildResponse retry =
        service.publishFull(
            fullTask(101L, "worker-b"),
            (target, progress) -> {
              insertCandidateIssue(501L, 42L, "retry-new", target);
              return new FactBuildResponse("issue", true, 1, "built");
            });
    assertThat(retry.affectedRows()).isOne();
    completeCandidate();
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("retry-new");
    cleanupAbortedFacts();
    assertThat(jdbcTemplate.queryForObject("select count(*) from issue_fact where title = 'full-new'", Long.class)).isZero();
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("retry-new");
  }

  /** 同一域同时只允许一个活动候选：后来者必须让位而不是拼接上一轮的半成品。 */
  @Test
  void test_second_candidate_on_same_domain_yields_without_touching_published_version() {
    seedPublishedIssue(501L, 42L, "old");
    ReadPublication baseline = currentPublication();
    readPublicationService.openCandidate(
        new com.data.collection.platform.service.read.CandidateOpenRequest(
            ISSUE_DOMAIN,
            null,
            baseline.id(),
            "{\"seal\":\"other\"}",
            40L,
            null,
            "other-run",
            null,
            null,
            Instant.now()));
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);

    assertThatThrownBy(
            () ->
                service.publish(
                    task(100L, "worker-a"),
                    (target, rootIds) -> {
                      insertCandidateIssue(501L, 42L, "must-not-survive", target);
                      return new FactBuildResponse("issue", false, 1, "published");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已有活动候选");

    assertThat(currentPublication().id()).isEqualTo(baseline.id());
    assertThat(visibleTitles(baseline.sequenceNo())).containsExactly("old");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from issue_fact where visible_from is null and created_by_publication_id is not null",
                Long.class))
        .as("失败事务不得残留候选修订")
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select published_version from fact_change_heads where root_id = 501", Long.class))
        .isZero();
  }

  private void cleanupAbortedFacts() {
    var maintenance = new com.data.collection.platform.service.read.ReadMaintenanceService(
        jdbcTemplate, readPublicationService, new ReadFallbackProperties());
    transactionTemplate.executeWithoutResult(status -> maintenance.collectAbortedFactCandidate());
  }

  private ReadPublication activeCandidate() {
    return readPublicationRepository.findActiveCandidate(
        readPublicationRepository.findDomain(ISSUE_DOMAIN).orElseThrow().id()).orElseThrow();
  }

  /** 真实候选产物已准备后调用唯一发布；本组件未注册业务看板消费者。 */
  private void completeCandidate() {
    ReadPublication candidate = recordCandidateProjectionProofs();
    service.publishPreparedCandidate(candidate.id(), "run-lease-token");
  }

  private ReadPublication recordCandidateProjectionProofs() {
    return com.data.collection.platform.service.FactRevisionFixture.prepareRequiredProjections(
        jdbcTemplate, ISSUE_DOMAIN, "run-lease-token");
  }

  @Test
  void test_changed_projection_generation_invalidates_previous_completion_proof() {
    seedPublishedIssue(501L, 42L, "old");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "new", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    ReadPublication candidate = recordCandidateProjectionProofs();
    java.util.Map<String, Object> required = jdbcTemplate.queryForMap(
        "select scope_key, candidate_generation from read_publication_scope_generations where publication_id = ? and scope_type = 'GLOBAL_VIEW'",
        candidate.id());
    readPublicationService.recordScopeGeneration(candidate.id(),
        com.data.collection.platform.entity.ProjectionScopeType.GLOBAL_VIEW,
        (String) required.get("scope_key"), ((Number) required.get("candidate_generation")).longValue() + 1);
    assertThatThrownBy(() -> service.publishPreparedCandidate(candidate.id(), "run-lease-token"))
        .hasMessageContaining("必需投影尚未完成");
    assertThat(activeCandidate().state()).isEqualTo(ReadPublicationState.PROJECTING);
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("old");
  }

  @Test
  void test_prepared_ready_candidate_publishes_once_and_repeated_call_keeps_same_identity() {
    seedPublishedIssue(501L, 42L, "old");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "new", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    ReadPublication candidate = recordCandidateProjectionProofs();
    readPublicationService.markReadyToPublish(candidate.id(), candidate.producerLeaseOwner());
    ReadPublication published = service.publishPreparedCandidate(candidate.id(), "run-lease-token");
    assertThat(published.state()).isEqualTo(ReadPublicationState.PUBLISHED);
    assertThat(service.publishPreparedCandidate(candidate.id(), "run-lease-token").id()).isEqualTo(published.id());
    assertThat(currentPublication().id()).isEqualTo(published.id());
    assertThat(visibleTitles(published.sequenceNo())).containsExactly("new");
  }

  @Test
  void test_cleanup_skips_batch_task_lock_and_releases_domain_instead_of_waiting_in_reverse_order() throws Exception {
    seedPublishedIssue(501L, 42L, "old");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "new", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    ReadPublication candidate = activeCandidate();
    try (java.sql.Connection connection = database.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (java.sql.PreparedStatement statement = connection.prepareStatement(
          "select id from fact_build_tasks where id = 100 for key share");
          java.sql.ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        Boolean aborted = transactionTemplate.execute(status -> {
          jdbcTemplate.execute("set local lock_timeout = '250ms'");
          return readPublicationService.abortOwnedTaskCandidate(candidate.id(), 100L, "worker-a",
              "900", "run-lease-token", "并发清理");
        });
        assertThat(aborted).isFalse();
        // 批次仍持任务锁时也能随后获得域锁，证明中止尝试没有遗留域锁。
        try (java.sql.PreparedStatement domainLock = connection.prepareStatement(
            "select id from read_publication_domains where id = ? for update nowait")) {
          domainLock.setObject(1, candidate.domainId());
          try (java.sql.ResultSet domain = domainLock.executeQuery()) {
            assertThat(domain.next()).isTrue();
          }
        }
      } finally {
        connection.rollback();
      }
    }
    assertThat(readPublicationService.abortOwnedTaskCandidate(candidate.id(), 100L, "worker-a",
        "900", "run-lease-token", "批次锁已释放")).isTrue();
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("old");
  }

  @Test
  void test_two_batches_share_fixed_candidate_and_never_publish_a_subset() {
    insertUnversionedIssue(501L, 42L, "old-1");
    insertUnversionedIssue(502L, 42L, "old-2");
    bootstrapBaseline();
    ReadPublication baseline = currentPublication();
    seedHead(501L, 42L, 10L, 0L);
    seedHead(502L, 42L, 20L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "new-1", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    ReadPublication candidate = activeCandidate();
    assertThat(candidate.state()).isEqualTo(ReadPublicationState.BUILDING);
    assertThat(readPublicationRepository.countUnbuiltInputs(candidate.id())).isOne();
    assertThat(currentPublication().id()).isEqualTo(baseline.id());
    assertThatThrownBy(() -> service.publishPreparedCandidate(candidate.id(), "run-lease-token"))
        .hasMessageContaining("禁止发布子集");
    seedTask(101L, "worker-b", false);
    jdbcTemplate.update("update fact_build_task_roots set root_id = 502 where task_id = 101");
    service.publish(task(101L, "worker-b"), (target, roots) -> {
      assertThat(target.publicationId()).isEqualTo(candidate.id());
      assertThat(roots).containsExactly(502L);
      insertCandidateIssue(502L, 42L, "new-2", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    assertThat(activeCandidate().state()).isEqualTo(ReadPublicationState.PROJECTING);
    assertThat(jdbcTemplate.queryForObject("select sum(published_version) from fact_change_heads", Long.class)).isZero();
    assertThatThrownBy(() -> service.publishPreparedCandidate(candidate.id(), "run-lease-token"))
        .hasMessageContaining("必需投影尚未完成");
    completeCandidate();
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("new-1", "new-2");
    assertThat(visibleTitles(baseline.sequenceNo())).containsExactly("old-1", "old-2");
  }

  @Test
  void test_changes_after_build_remain_pending_after_prepared_publication() {
    seedPublishedIssue(501L, 42L, "old");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "captured-12", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    seedHead(501L, 42L, 30L, 0L);
    completeCandidate();
    assertThat(jdbcTemplate.queryForMap("select latest_change_version, published_version from fact_change_heads where root_id = 501"))
        .containsEntry("latest_change_version", 30L).containsEntry("published_version", 12L);
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("captured-12");
  }

  @Test
  void test_unbuilt_input_that_advanced_is_rejected_and_all_candidate_work_is_released() {
    insertUnversionedIssue(501L, 42L, "old-1");
    insertUnversionedIssue(502L, 42L, "old-2");
    bootstrapBaseline();
    seedHead(501L, 42L, 10L, 0L);
    seedHead(502L, 42L, 20L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "must-abort", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    seedTask(101L, "worker-b", false);
    jdbcTemplate.update("update fact_build_task_roots set root_id = 502 where task_id = 101");
    seedHead(502L, 42L, 30L, 0L);
    assertThatThrownBy(() -> service.publish(task(101L, "worker-b"), (target, roots) -> {
      throw new AssertionError("漂移输入不能进入事实构建");
    })).hasMessageContaining("候选输入已变化");
    // 发布编排在批次回滚后立即中止整个候选；工作器重复清理仍须幂等。
    service.abortTaskCandidate(task(101L, "worker-b"), "输入变化");
    assertThat(activeCandidateCount()).isZero();
    cleanupAbortedFacts();
    assertThat(jdbcTemplate.queryForObject("select count(*) from issue_fact where title = 'must-abort'", Long.class)).isZero();
    assertThat(jdbcTemplate.queryForObject("select count(*) from fact_build_task_roots where task_id = 100", Long.class)).isZero();
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("old-1", "old-2");
  }

  @Test
  void test_projection_proof_for_other_source_seal_is_not_accepted() {
    seedPublishedIssue(501L, 42L, "old");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "new", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    ReadPublication candidate = activeCandidate();
    var required = jdbcTemplate.queryForMap("select * from read_publication_scope_generations where publication_id = ? and scope_type = 'GLOBAL_VIEW'", candidate.id());
    assertThat(readPublicationRepository.recordProjectionPrepared(candidate.id(),
        com.data.collection.platform.entity.ProjectionScopeType.GLOBAL_VIEW,
        (String) required.get("scope_key"), ((Number) required.get("candidate_generation")).longValue(),
        "{\"seal\":\"other\"}", candidate.definitionSnapshotId())).isZero();
    assertThatThrownBy(() -> service.publishPreparedCandidate(candidate.id(), "run-lease-token"))
        .hasMessageContaining("必需投影尚未完成");
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("old");
  }

  // ---- 测试夹具 ----

  @Test
  void test_changed_fact_calculation_definition_requires_complete_rebuild() {
    seedPublishedIssue(501L, 42L, "old");
    jdbcTemplate.update("insert into issue_customer_name_aliases(alias_name, canonical_name) values ('old-name', 'new-name')");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    assertThatThrownBy(() -> service.publish(task(100L, "worker-a"), (target, roots) -> {
      throw new AssertionError("新旧计算定义不能混合复用");
    })).isInstanceOf(com.data.collection.platform.service.FactCandidateBusyException.class)
        .hasMessageContaining("需要完整重建");
    assertThat(activeCandidateCount()).isZero();
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("old");
  }

  @Test
  void test_missing_complete_input_evidence_yields_without_creating_candidate() {
    seedPublishedIssue(501L, 42L, "old");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    when(publicationStateService.canBuildReadCandidate(anyString(), any(FactType.class))).thenReturn(false);
    assertThatThrownBy(() -> service.publish(task(100L, "worker-a"), (target, roots) -> {
      throw new AssertionError("不完整来源不能进入事实写入");
    })).isInstanceOf(com.data.collection.platform.service.FactCandidateBusyException.class);
    assertThat(activeCandidateCount()).isZero();
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("old");
  }

  @Test
  void test_active_mirror_makes_bypass_yield_without_waiting_for_source_io() {
    seedPublishedIssue(501L, 42L, "old");
    seedTask(100L, "worker-a", false);
    jdbcTemplate.update("update sync_runs set run_type = 'INCREMENTAL_SYNC' where id = 900");
    assertThatThrownBy(() -> service.publishWithinCandidate(bypassRequest(), target -> {
      throw new AssertionError("镜像在途不应读取其未结算输入");
    })).isInstanceOf(com.data.collection.platform.service.FactCandidateBusyException.class)
        .hasMessageContaining("镜像来源尚未结算");
    assertThat(activeCandidateCount()).isZero();
  }

  @Test
  void test_old_parent_token_cannot_abort_candidate_after_parent_lease_transfer() {
    seedPublishedIssue(501L, 42L, "old");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "new", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    UUID candidateId = activeCandidate().id();
    jdbcTemplate.update("update sync_runs set lease_owner = 'new-parent-token' where id = 900");
    service.abortTaskCandidate(task(100L, "worker-a"), "旧执行器晚到的异常");
    assertThat(activeCandidate().id()).isEqualTo(candidateId);
    assertThat(jdbcTemplate.queryForObject("select count(*) from issue_fact where title = 'new'", Long.class)).isOne();
  }

  @Test
  void test_cancelled_parent_cannot_publish_and_recovery_keeps_last_complete_version() {
    seedPublishedIssue(501L, 42L, "old");
    seedHead(501L, 42L, 12L, 0L);
    seedTask(100L, "worker-a", false);
    service.publish(task(100L, "worker-a"), (target, roots) -> {
      insertCandidateIssue(501L, 42L, "new", target);
      return new FactBuildResponse("issue", false, 1, "built");
    });
    ReadPublication prepared = recordCandidateProjectionProofs();
    jdbcTemplate.update("update sync_runs set status = 'CANCELLED', lease_owner = null where id = 900");
    assertThatThrownBy(() -> service.publishPreparedCandidate(prepared.id(), "run-lease-token"))
        .isInstanceOf(com.data.collection.platform.service.FactTaskLeaseLostException.class);
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("old");
    assertThat(service.recoverAbandonedCandidates()).isOne();
    assertThat(activeCandidateCount()).isZero();
    assertThat(jdbcTemplate.queryForObject("select count(*) from read_publication_root_inputs", Long.class)).isZero();
  }

  @Test
  void test_persistence_guard_refuses_a_published_identity() {
    seedPublishedIssue(501L, 42L, "old");
    ReadPublication published = currentPublication();
    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
        readPublicationRepository.requireWritableTarget(new FactRevisionTarget(published.id(),
            published.epochId(), PublicationFactTable.ISSUE, SOURCE_SYSTEM, SOURCE_INSTANCE, 0))))
        .hasMessageContaining("不是匹配的 BUILDING 候选");
    assertThat(visibleTitles(published.sequenceNo())).containsExactly("old");
  }

  private <T> T transactionalProxy(T target) {
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(
            transactionManager, new AnnotationTransactionAttributeSource()));
    @SuppressWarnings("unchecked")
    T casted = (T) proxy.getProxy();
    return casted;
  }

  private BypassPublicationRequest bypassRequest() {
    return new BypassPublicationRequest(
        ISSUE_DOMAIN,
        SOURCE_SYSTEM,
        "{\"seal\":\"test-source\"}",
        0L,
        "bypass-run",
        null,
        Instant.now(), false);
  }

  private QueuedFactBuildTask task(long taskId, String owner) {
    return new QueuedFactBuildTask(
        taskId,
        900L,
        "run-lease-token",
        null,
        SOURCE_INSTANCE,
        "ISSUE",
        "issue",
        false,
        0,
        3,
        owner,
        LocalDateTime.now().plusMinutes(1));
  }

  private QueuedFactBuildTask fullTask(long taskId, String owner) {
    QueuedFactBuildTask base = task(taskId, owner);
    return new QueuedFactBuildTask(
        base.id(),
        base.factRunId(),
        base.factRunLeaseToken(),
        base.configId(),
        base.sourceInstance(),
        base.factType(),
        base.scope(),
        true,
        base.retryCount(),
        base.maxRetryCount(),
        base.leaseOwner(),
        base.leaseUntil());
  }

  private void seedTask(long taskId, String owner, boolean full) {
    jdbcTemplate.update("insert into gitlab_sync_configs(id, name, source_instance, db_name, db_username, db_password) values (9900, 'candidate-test', 'alpha', 'gitlab', 'fixture', 'fixture') on conflict (id) do nothing");
    jdbcTemplate.update("""
        insert into sync_runs(id, run_id, config_id, source_instance, run_type, trigger_type,
            status, exclusive_scope, lease_owner, lease_until)
        values (900, 'candidate-test-run', 9900, 'alpha', 'FACT_REFRESH', 'SCHEDULE', 'RUNNING',
            'candidate-test-scope', 'run-lease-token', current_timestamp + interval '10 minutes')
        on conflict (id) do nothing
        """);
    jdbcTemplate.update(
        """
        insert into fact_build_tasks(
            id, run_id, scope, full_build, status, lock_owner, source_instance, fact_type,
            lease_until, updated_at)
        values (?, ?, 'issue', ?, 'RUNNING', ?, ?, 'ISSUE', ?, current_timestamp)
        """,
        taskId,
        "900",
        full,
        owner,
        SOURCE_INSTANCE,
        LocalDateTime.now().plusMinutes(1));
    jdbcTemplate.update(
        "insert into fact_build_task_roots(task_id, source_instance, fact_type, root_id)"
            + " values (?, ?, 'ISSUE', 501)",
        taskId,
        SOURCE_INSTANCE);
  }

  private void seedHead(long rootId, long projectId, long latest, long published) {
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, project_id, latest_change_version, published_version)
        values ('alpha', 'ISSUE', ?, ?, ?, ?)
        on conflict (source_instance, fact_type, root_id) do update
          set latest_change_version = excluded.latest_change_version,
              published_version = excluded.published_version
        """,
        rootId,
        projectId,
        latest,
        published);
  }

  private void seedPublishedIssue(long rootId, long projectId, String title) {
    insertUnversionedIssue(rootId, projectId, title);
    bootstrapBaseline();
  }

  /** 把当前未版本化事实登记为域的初始完整版本，作为增量与旁路发布的基线。 */
  private void bootstrapBaseline() {
    ReadPublication initial = readPublicationService.bootstrapInitialVersion(
        ISSUE_DOMAIN,
        SOURCE_SYSTEM,
        Instant.now(),
        "{\"seal\":\"test-source\"}",
        0L,
        key ->
            com.data.collection.platform.service.read.InitialVersionVerification.VerificationResult
                .accepted());
    assertThat(initial).as("确定性夹具必须先建立完整基线，不能静默接受安装让位").isNotNull();
  }

  private void insertUnversionedIssue(long rootId, long projectId, String title) {
    jdbcTemplate.update(
        """
        insert into issue_fact(source_system, source_instance, project_id, issue_id, issue_iid, title)
        values ('GITLAB', 'alpha', ?, ?, ?, ?)
        """,
        projectId,
        rootId,
        rootId,
        title);
  }

  private void insertCandidateIssue(
      long rootId, long projectId, String title, FactRevisionTarget target) {
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_system, source_instance, project_id, issue_id, issue_iid, title,
            publication_epoch_id, created_by_publication_id)
        values ('GITLAB', 'alpha', ?, ?, ?, ?, ?, ?)
        """,
        projectId,
        rootId,
        rootId,
        title,
        target.epochId(),
        target.publicationId());
  }

  private ReadPublication currentPublication() {
    return readPublicationRepository
        .findCurrentPublication(
            readPublicationRepository.findDomain(ISSUE_DOMAIN).orElseThrow().id())
        .orElseThrow();
  }

  private List<String> visibleTitles(long sequenceNo) {
    ReadPublication publication = currentPublication();
    return jdbcTemplate.queryForList(
        """
        select title
          from issue_fact
         where publication_epoch_id = ?
           and visible_from is not null
           and visible_from <= ?
           and (visible_to is null or ? < visible_to)
         order by project_id, title
        """,
        String.class,
        publication.epochId(),
        sequenceNo,
        sequenceNo);
  }

  private long openRevisionCount() {
    return jdbcTemplate.queryForObject(
        "select count(*) from issue_fact where visible_from is not null and visible_to is null",
        Long.class);
  }

  private long activeCandidateCount() {
    return jdbcTemplate.queryForObject(
        """
        select count(*) from read_publications
         where state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')
        """,
        Long.class);
  }

  private void truncateEverything() {
    jdbcTemplate.execute(
        """
        truncate read_view_publications, read_view_contexts, read_publication_fact_roots,
            read_publication_scope_generations, read_publications, read_publication_domains,
            read_definition_snapshots, issue_fact_customer_members, issue_fact,
            merge_request_commit_fact, merge_request_fact, integration_test_fact,
            fact_change_heads, fact_build_task_roots, fact_build_tasks,
            fact_projection_generations, fact_projection_refresh_tasks,
            source_fact_publication_states, source_fact_dependency_states,
            gitlab_sync_configs restart identity cascade
        """);
  }
}
