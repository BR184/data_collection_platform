package com.data.collection.platform.service;

import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.service.read.CandidateOpenRequest;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 事实组件测试夹具：显式候选身份替代已移除的无版本写入，不模拟平台必需投影已经完成。 */
public final class FactRevisionFixture {
  private FactRevisionFixture() {}

  /** 业务查询组件从真实已发布指针取身份；没有完整点时失败，不使用伪 UUID。 */
  public static com.data.collection.platform.entity.read.FactReadSelection currentPublishedSelection(
      JdbcTemplate jdbc, PublicationFactTable table) {
    java.util.List<com.data.collection.platform.entity.read.FactReadQueryContext> contexts = jdbc.query("""
        select d.source_instance, p.epoch_id, p.sequence_no, p.id, p.data_as_of
          from read_publication_domains d join read_publications p on p.id = d.current_publication_id
         where d.fact_type = ? and p.state = 'PUBLISHED' order by d.source_instance
        """, (rs, row) -> new com.data.collection.platform.entity.read.PublishedReadContext(rs.getString(1), table,
            rs.getObject(2, UUID.class), rs.getLong(3), rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant()), table.factType().name());
    if (contexts.isEmpty()) { throw new IllegalStateException("查询组件夹具尚未发布"); }
    return new com.data.collection.platform.entity.read.FactReadSelection(contexts, java.time.LocalDate.of(2026, 10, 13));
  }

  /** 原始字段映射组件的完整播种夹具；验收目标是查询映射，首次认证证据由安装集成测试另行保护。 */
  public static com.data.collection.platform.entity.read.FactReadSelection adoptSeededFacts(
      JdbcTemplate jdbc, PublicationFactTable table) {
    var sources = jdbc.queryForList("select distinct source_instance from " + table.tableName()
        + " where source_system = 'GITLAB' order by source_instance", String.class);
    if (sources.isEmpty()) { sources = java.util.List.of("default"); }
    var service = new ReadPublicationService(new ReadPublicationRepository(jdbc), new ReadFallbackProperties());
    for (String source : sources) {
      String fixedSource = source;
      new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status ->
          service.bootstrapInitialVersion(ReadPublicationDomainKey.gitlabFact(fixedSource, table.factType()),
              "GITLAB", Instant.now(), "{\"fixture\":true}", 0,
              domain -> com.data.collection.platform.service.read.InitialVersionVerification.VerificationResult.accepted()));
    }
    return currentPublishedSelection(jdbc, table);
  }

  /** 组件测试使用真实持久产物核验器，运行授权和事实身份仍由真库校验；通知栅栏是边界替身。 */
  public static com.data.collection.platform.service.read.CandidateProjectionArtifactService projectionArtifacts(
      JdbcTemplate jdbc, ReadPublicationService publications, ReadPublicationRepository repository) {
    FactProjectionExecutionContext execution = new FactProjectionExecutionContext();
    com.data.collection.platform.service.read.CandidateCacheArtifactStore artifacts =
        new com.data.collection.platform.service.read.CandidateCacheArtifactStore(jdbc);
    return new com.data.collection.platform.service.read.CandidateProjectionArtifactService(jdbc,
        new com.data.collection.platform.common.JsonUtils(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
        repository, new com.data.collection.platform.service.read.FactReadSelectionService(jdbc, repository, publications, () -> java.time.LocalDate.of(2026, 10, 13)),
        new FactProjectionPublicationGuardService(jdbc, execution, artifacts,
            new DataSourceTransactionManager(jdbc.getDataSource())),
        new FactProjectionTaskService(jdbc, org.mockito.Mockito.mock(
            com.data.collection.platform.service.sync.SyncRunPublicationFenceService.class)), artifacts);
  }

  /** Mockito 构建测试的写入身份；不访问数据库，也不代表已发布版本。 */
  public static FactRevisionTarget target(PublicationFactTable table, String source) {
    return new FactRevisionTarget(UUID.randomUUID(), UUID.randomUUID(), table, "GITLAB", source, 0);
  }

  /** 读取一致性测试的受控版本变化输入；不模拟真实事实发布，真实原子提交由发布编排集成测试保护。 */
  public static int advanceScopeVersion(JdbcTemplate jdbc, String source,
      java.util.Set<com.data.collection.platform.entity.FactProjectionScope> scopes) {
    for (com.data.collection.platform.entity.FactProjectionScope scope : scopes) {
      if (!source.equals(scope.sourceInstance())) {
        throw new IllegalArgumentException("测试范围与来源不一致");
      }
      jdbc.update("""
          insert into fact_projection_generations(source_instance, fact_type, scope_type, scope_key, generation)
          values (?, ?, ?, ?, 1) on conflict(source_instance, fact_type, scope_type, scope_key)
          do update set generation = fact_projection_generations.generation + 1
          """, source, scope.factType().name(), scope.scopeType().name(), scope.scopeKey());
    }
    return scopes.size();
  }

  /** 目录组件测试把已播种的完整议题事实接管为可寻址身份，供版本限定的目录对账使用。 */
  public static com.data.collection.platform.entity.read.PublishedReadContext adoptIssueContext(JdbcTemplate jdbc) {
    ReadPublicationRepository repository = new ReadPublicationRepository(jdbc);
    ReadPublicationService service = new ReadPublicationService(repository, new ReadFallbackProperties());
    return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
      ReadPublicationDomainKey key = ReadPublicationDomainKey.gitlabFact("default", PublicationFactTable.ISSUE.factType());
      service.bootstrapInitialVersion(key, "GITLAB", Instant.now(), "{\"fixture\":true}", 0,
          domain -> com.data.collection.platform.service.read.InitialVersionVerification.VerificationResult.accepted());
      ReadPublication publication = repository.findCurrentPublication(repository.findDomain(key).orElseThrow().id()).orElseThrow();
      return new com.data.collection.platform.entity.read.PublishedReadContext("default", PublicationFactTable.ISSUE,
          publication.epochId(), publication.sequenceNo(), publication.id(), publication.dataAsOf());
    });
  }

  /**
   * 上游事实组件的真实投影登记链：领取持久任务、限定候选事实、生成核验清单再走唯一发布。
   * 该组件没有业务看板消费者；真实业务预热必须由投影消费者集成测试另行验证，不能据此宣称全页面已接入。
   */
  public static void completeRequiredProjections(JdbcTemplate jdbc, FactTargetPublicationService publisher,
      ReadPublicationDomainKey key, String runLeaseToken) {
    ReadPublication candidate = prepareRequiredProjections(jdbc, key, runLeaseToken);
    if (candidate != null) {
      publisher.publishPreparedCandidate(candidate.id(), runLeaseToken);
    }
  }

  /** 组件边界只准备真实候选产物，不切指针，供最终结算失败和令牌撤销反例使用。 */
  public static ReadPublication prepareRequiredProjections(JdbcTemplate jdbc,
      ReadPublicationDomainKey key, String runLeaseToken) {
    ReadPublicationRepository repository = new ReadPublicationRepository(jdbc);
    ReadPublication candidate = repository.findDomain(key)
        .flatMap(domain -> repository.findActiveCandidate(domain.id())).orElse(null);
    if (candidate == null || candidate.state() != com.data.collection.platform.entity.read.ReadPublicationState.PROJECTING) {
      return null;
    }
    ReadPublicationService publications = new ReadPublicationService(repository, new ReadFallbackProperties());
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    FactProjectionExecutionContext execution = new FactProjectionExecutionContext();
    com.data.collection.platform.service.read.CandidateCacheArtifactStore artifactStore =
        new com.data.collection.platform.service.read.CandidateCacheArtifactStore(jdbc);
    FactProjectionTaskService tasks = new FactProjectionTaskService(jdbc, org.mockito.Mockito.mock(
        com.data.collection.platform.service.sync.SyncRunPublicationFenceService.class));
    com.data.collection.platform.service.read.CandidateProjectionArtifactService artifacts =
        new com.data.collection.platform.service.read.CandidateProjectionArtifactService(jdbc,
            new com.data.collection.platform.common.JsonUtils(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
            repository, new com.data.collection.platform.service.read.FactReadSelectionService(jdbc, repository, publications, () -> java.time.LocalDate.of(2026, 10, 13)),
            new FactProjectionPublicationGuardService(jdbc, execution, artifactStore,
                new DataSourceTransactionManager(jdbc.getDataSource())), tasks, artifactStore);
    Long runId = candidate.producerTaskId() == null ? null : Long.valueOf(candidate.producerRunId());
    tx.executeWithoutResult(status -> tasks.enqueuePreparedCandidates(runId));
    while (true) {
      com.data.collection.platform.entity.QueuedFactProjectionTask task = tx.execute(status ->
          runId == null ? tasks.claimNextStandalone(300) : tasks.claimNext(runId, runLeaseToken, 300));
      if (task == null) { break; }
      try (FactProjectionExecutionContext.Scope ignored = execution.open(task)) {
        com.data.collection.platform.entity.FactPublicationContext context =
            tx.execute(status -> artifacts.contextForTask(task));
        tx.executeWithoutResult(status ->
            artifacts.complete(task, context, java.util.List.of(), execution.writtenArtifacts()));
      }
    }
    return candidate;
  }

  /**
   * 在真实事务内为只消费事实的组件夹具建立完整版本（无平台投影消费者）。
   * 平台发布编排测试必须走 FactTargetPublicationService 与真实投影证明门禁，不使用本方法代替它。
   */
  public static FactBuildResponse publishFacts(JdbcTemplate jdbc, PublicationFactTable table,
      String source, boolean full, Function<FactRevisionTarget, FactBuildResponse> build) {
    return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
      ReadPublicationRepository repository = new ReadPublicationRepository(jdbc);
      ReadPublicationService service = new ReadPublicationService(repository, new ReadFallbackProperties());
      ReadPublicationDomainKey key = ReadPublicationDomainKey.gitlabFact(source, table.factType());
      UUID base = repository.findDomain(key).map(domain -> domain.currentPublicationId()).orElse(null);
      ReadPublication candidate = service.openCandidate(new CandidateOpenRequest(key,
          full ? UUID.randomUUID() : null, base, "{\"fixture\":true}", 0,
          repository.freezeCurrentFactDefinitions(source),
          null, null, null, Instant.now()));
      FactBuildResponse result = build.apply(new FactRevisionTarget(candidate.id(), candidate.epochId(),
          table, "GITLAB", source, 0));
      repository.registerUpsertManifest(table, candidate.id(), 0);
      service.markProjecting(candidate.id(), null);
      service.markReadyToPublish(candidate.id(), null);
      service.publish(candidate.id(), null);
      repository.linkManifestPreviousRevisions(table, candidate.id());
      return result;
    });
  }
}
