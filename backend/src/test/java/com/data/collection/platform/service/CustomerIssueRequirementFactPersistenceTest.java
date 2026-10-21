package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.CustomerIssueFactQueryService.FactScopeRequest;
import com.data.collection.platform.service.CustomerIssueFactQueryService.MemberSelection;
import com.data.collection.platform.service.read.BypassPublicationRequest;
import com.data.collection.platform.service.read.FactReadVisibility;
import com.data.collection.platform.service.read.InitialVersionVerification;
import com.data.collection.platform.service.read.ReadPublicationService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S02 证据：客户需求身份派生列的来源链路、落库与回读。
 *
 * <p>覆盖三件事，全部打在真实运行逻辑与真实 PostgreSQL 上：
 *
 * <ol>
 *   <li>标签成员变化（新增/替换/删除）后，事实构建器按当前 ODS 标签集合重算派生值，不依赖手工回填。
 *       本用例显式按根重建（候选开启→构建修订→原子发布），因此只证明构建器的派生口径；自动增量链
 *       （变更登记→根解析→定向发布→投影推进）由 {@link CustomerIssueRequirementLabelAutoChainIntegrationTest} 证明；
 *   <li>{@code IssueFactMapper.batchUpsert} 与持久化协作者都以候选修订身份写入，同一发布身份内重复写入
 *       按 {@code excluded} 更新该列；
 *   <li>NULL 表示"尚未重建"，经写入、SQL 读取与窄事实读模型回读后仍是 NULL，不被转成 false。
 * </ol>
 */
@SpringBootTest
class CustomerIssueRequirementFactPersistenceTest {
  private static final long CUSTOMER_PROJECT_ID = 325L;
  private static final long NON_CUSTOMER_PROJECT_ID = 9L;
  private static final long CUSTOMER_ISSUE_ID = 90001L;
  private static final long CUSTOMER_ISSUE_IID = 501L;
  private static final long NON_CUSTOMER_ISSUE_ID = 90002L;
  private static final long NON_CUSTOMER_ISSUE_IID = 502L;
  private static final String MILESTONE = "CC2026R4第一轮系统测试";
  private static final String SOURCE_INSTANCE = "default";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 6, 9, 0);
  /** 播种用的候选修订身份：同一发布身份内重复写入命中 do update 分支，只留下一条修订行。 */
  private UUID publicationId;
  private UUID epochId;
  private FactRevisionTarget seedTarget;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FactBuildService factBuildService;
  @Autowired private FactTargetPublicationService factTargetPublicationService;
  @Autowired private IssueFactMapper issueFactMapper;
  @Autowired private IssueFactPersistenceService issueFactPersistenceService;
  @Autowired private ReadPublicationService readPublicationService;
  @Autowired private CustomerIssueFactQueryService customerIssueFactQueryService;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private com.data.collection.platform.service.sync.SyncFactPublicationStateService sourceInputQualification;

  @BeforeEach
  void setUp() {
    // 事实组件测试用明确的完整输入替身；默认接管/完整依赖证明另有真库反例保护。
    org.mockito.Mockito.doReturn(true).when(sourceInputQualification).canBuildReadCandidate(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(FactType.class));
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanUp();
    jdbcTemplate.execute("truncate read_publications, read_publication_domains cascade");
    publicationId = null;
    epochId = null;
    seedTarget = null;
    LabelEventHistoryTestSupport.markComplete(jdbcTemplate, "default");
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void requirementFlagFollowsLabelMembershipThroughFactBuild() {
    insertMirrorIssue(CUSTOMER_PROJECT_ID, "CC_PRODUCT", CUSTOMER_ISSUE_ID, CUSTOMER_ISSUE_IID);
    insertLabel(1L, "模块：草图");
    linkLabel(1L, CUSTOMER_ISSUE_ID);

    // ① 命中“需求”标签 → true
    insertLabel(2L, "需求");
    linkLabel(2L, CUSTOMER_ISSUE_ID);
    rebuildIssueFactsFromSource(CUSTOMER_ISSUE_ID);
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isTrue();

    // ② 换成唯一建议标签“类别：建议” → 仍为 true（两个目标标签是 OR）
    unlinkLabel(2L, CUSTOMER_ISSUE_ID);
    insertLabel(3L, "类别：建议");
    linkLabel(3L, CUSTOMER_ISSUE_ID);
    rebuildIssueFactsFromSource(CUSTOMER_ISSUE_ID);
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isTrue();

    // ③ 删除全部目标标签 → 退出需求集合，派生值随事实自动更新
    unlinkLabel(3L, CUSTOMER_ISSUE_ID);
    rebuildIssueFactsFromSource(CUSTOMER_ISSUE_ID);
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isFalse();

    // ④ 名称相似但不是目标标签的精确成员 → 不得模糊命中
    insertLabel(4L, "需求如此");
    linkLabel(4L, CUSTOMER_ISSUE_ID);
    rebuildIssueFactsFromSource(CUSTOMER_ISSUE_ID);
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isFalse();
  }

  @Test
  void requirementFlagIsFalseForNonCustomerProject() {
    insertMirrorIssue(NON_CUSTOMER_PROJECT_ID, "CrownCAD", NON_CUSTOMER_ISSUE_ID, NON_CUSTOMER_ISSUE_IID);
    insertLabel(11L, "需求");
    linkLabel(11L, NON_CUSTOMER_ISSUE_ID);

    rebuildIssueFactsFromSource(NON_CUSTOMER_ISSUE_ID);

    assertThat(requirementFlag(NON_CUSTOMER_PROJECT_ID, NON_CUSTOMER_ISSUE_ID)).isFalse();
  }

  @Test
  void mapperUpsertPersistsUpdatesAndKeepsNullForRequirementFlag() {
    ensureSeedCandidate();
    IssueFact fact = minimalFact(CUSTOMER_ISSUE_ID, CUSTOMER_ISSUE_IID);
    fact.setIsCustomerRequirement(Boolean.TRUE);
    issueFactMapper.batchUpsert(List.of(fact), epochId, publicationId);
    assertThat(seededRequirementFlag()).isTrue();

    // 冲突分支必须更新该列：同一发布身份与同一业务根再次写入 false。
    fact.setIsCustomerRequirement(Boolean.FALSE);
    issueFactMapper.batchUpsert(List.of(fact), epochId, publicationId);
    assertThat(seededRequirementFlag()).isFalse();

    // null＝尚未重建：不得被 SQL 或实体回读转成 false。
    fact.setIsCustomerRequirement(null);
    issueFactMapper.batchUpsert(List.of(fact), epochId, publicationId);
    assertThat(seededRequirementFlag()).isNull();
    assertThat(seededRevisionCount()).isOne();
    assertThat(issueFactMapper.selectBySourceContext("GITLAB", "default", CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)
            .getIsCustomerRequirement())
        .isNull();
  }

  @Test
  void batchUpsertPersistsRequirementFlag() {
    ensureSeedCandidate();
    IssueFact fact = minimalFact(CUSTOMER_ISSUE_ID, CUSTOMER_ISSUE_IID);
    fact.setCustomerNames("客户甲");
    fact.setIsCustomerRequirement(Boolean.TRUE);

    issueFactPersistenceService.upsertIssueFacts(seedTarget, List.of(fact));

    assertThat(seededRequirementFlag()).isTrue();
  }

  @Test
  void narrowFactReadModelReturnsPersistedFlagAndPreservesNull() {
    ensureSeedCandidate();
    IssueFact fact = minimalFact(CUSTOMER_ISSUE_ID, CUSTOMER_ISSUE_IID);
    fact.setMilestoneTitle(MILESTONE);
    fact.setIsCustomerRequirement(Boolean.TRUE);
    issueFactMapper.batchUpsert(List.of(fact), epochId, publicationId);
    publishSeedCandidate();
    assertThat(loadedRequirement(MILESTONE)).containsExactly(Boolean.TRUE);

    fact.setIsCustomerRequirement(null);
    ensureSeedCandidate();
    issueFactMapper.batchUpsert(List.of(fact), epochId, publicationId);
    publishSeedCandidate();
    assertThat(loadedRequirement(MILESTONE)).singleElement().isNull();
  }

  private void publishSeedCandidate() {
    var repository = new com.data.collection.platform.service.read.ReadPublicationRepository(jdbcTemplate);
    repository.registerUpsertManifest(PublicationFactTable.ISSUE, publicationId, 0L);
    readPublicationService.markProjecting(publicationId, null);
    readPublicationService.markReadyToPublish(publicationId, null);
    readPublicationService.publish(publicationId, null);
  }

  private void ensureSeedCandidate() {
    var candidate = readPublicationService.openCandidate(new com.data.collection.platform.service.read.CandidateOpenRequest(
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE), UUID.randomUUID(),
        new com.data.collection.platform.service.read.ReadPublicationRepository(jdbcTemplate)
            .findDomain(ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE))
            .map(domain -> domain.currentPublicationId()).orElse(null),
        "{\"fixture\":true}", 0L, null, null, null, null, Instant.now()));
    publicationId = candidate.id();
    epochId = candidate.epochId();
    seedTarget = new FactRevisionTarget(publicationId, epochId, PublicationFactTable.ISSUE,
        "GITLAB", SOURCE_INSTANCE, 0L);
  }

  private List<Boolean> loadedRequirement(String milestone) {
    return customerIssueFactQueryService
        .forRead(FactRevisionFixture.currentPublishedSelection(jdbcTemplate, PublicationFactTable.ISSUE))
        .load(
            new FactScopeRequest(
                null,
                List.of(milestone),
                MemberSelection.all(),
                MemberSelection.all(),
                MemberSelection.all()))
        .stream()
        .map(CustomerIssueFact::customerRequirement)
        .toList();
  }

  /**
   * 显式按根重建：走旁路生产者的真实链路（开启候选→在候选内构建修订→原子发布），
   * 只用于驱动构建器重算派生值，不代表自动增量链。
   */
  private void rebuildIssueFactsFromSource(Long... rootIds) {
    ensureCertifiedIssueBaseline();
    factTargetPublicationService.publishWithinCandidate(
        new BypassPublicationRequest(
            ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
            "GITLAB",
            "{\"seal\":\"requirement-persistence-test\"}",
            0L,
            "requirement-persistence-test",
            null,
            Instant.now(), false),
        target -> factBuildService.rebuildIssueFactsByRootIds(target, List.of(rootIds)));
    FactRevisionFixture.completeRequiredProjections(jdbcTemplate, factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE), null);
  }

  /** 域尚无已认证完整基线时增量候选会被拒绝，先用初始接管把成熟行收为首个完整版本。 */
  private void ensureCertifiedIssueBaseline() {
    readPublicationService.bootstrapInitialVersion(
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        "GITLAB",
        Instant.now(),
        "{\"seal\":\"requirement-persistence-test\"}",
        0L,
        key -> InitialVersionVerification.VerificationResult.accepted());
  }

  /** 当前生效修订的派生列：历史修订仍留在表内，只读此刻对用户可见的那一条。 */
  private Boolean requirementFlag(long projectId, long issueId) {
    return jdbcTemplate.queryForObject(
        "select is_customer_requirement from issue_fact"
            + " where project_id = ? and issue_id = ? and "
            + FactReadVisibility.liveRevisionPredicate(""),
        Boolean.class,
        projectId,
        issueId);
  }

  /** 按写入身份回读播种修订：候选修订尚未发布，不在当前生效视图内。 */
  private Boolean seededRequirementFlag() {
    return jdbcTemplate.queryForObject(
        "select is_customer_requirement from issue_fact"
            + " where created_by_publication_id = ? and source_system = ?"
            + " and source_instance = ? and project_id = ? and issue_id = ?",
        Boolean.class,
        publicationId,
        "GITLAB",
        SOURCE_INSTANCE,
        CUSTOMER_PROJECT_ID,
        CUSTOMER_ISSUE_ID);
  }

  /** 同一发布身份与同一业务根必须只有一条修订行，重复写入走 do update 而不是追加。 */
  private long seededRevisionCount() {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from issue_fact"
                + " where created_by_publication_id = ? and source_system = ?"
                + " and source_instance = ? and project_id = ? and issue_id = ?",
            Long.class,
            publicationId,
            "GITLAB",
            SOURCE_INSTANCE,
            CUSTOMER_PROJECT_ID,
            CUSTOMER_ISSUE_ID);
    return count == null ? 0L : count;
  }

  private IssueFact minimalFact(long issueId, long issueIid) {
    IssueFact fact = new IssueFact();
    fact.setSourceSystem("GITLAB");
    fact.setSourceInstance("default");
    fact.setIngestChannel("MIRROR");
    fact.setSourceSummary("单元测试");
    fact.setProjectId(CUSTOMER_PROJECT_ID);
    fact.setProjectName("CC_PRODUCT");
    fact.setIssueId(issueId);
    fact.setIssueIid(issueIid);
    fact.setTitle("需求身份落库样例");
    fact.setIssueState("opened");
    fact.setMilestoneTitle(MILESTONE);
    fact.setCreatedAtSource(CREATED_AT);
    fact.setUpdatedAtSource(CREATED_AT);
    fact.setDeleted(false);
    fact.setExcluded(false);
    fact.setFixed(false);
    fact.setDelayIssue(false);
    fact.setResponseDelayed(false);
    fact.setResolveDelayed(false);
    fact.setRegression(false);
    fact.setCrash(false);
    fact.setLevel1Other(false);
    fact.setIllegal(false);
    fact.setHasResponse(false);
    fact.setResponseOverdue(false);
    fact.setResolveSlaDays(0);
    fact.setLegacy(false);
    return fact;
  }

  private void insertMirrorIssue(long projectId, String projectName, long issueId, long issueIid) {
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, ?, false)",
        projectId,
        projectName);
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (?, ?, false)",
        projectId,
        "提交人");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
          id, iid, project_id, title, author_id, created_at, updated_at, closed_at, state_id, mirror_deleted
        ) values (?, ?, ?, '需求身份样例', ?, ?, ?, null, 1, false)
        """,
        issueId,
        issueIid,
        projectId,
        projectId,
        CREATED_AT,
        CREATED_AT);
  }

  private void insertLabel(long id, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)", id, title);
  }

  private void linkLabel(long labelId, long issueId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(
          id, label_id, target_id, target_type, source_updated_at, updated_at, created_at, mirror_deleted
        ) values (?, ?, ?, 'Issue', current_timestamp, current_timestamp, current_timestamp, false)
        """,
        labelId * 1000 + issueId,
        labelId,
        issueId);
  }

  private void unlinkLabel(long labelId, long issueId) {
    jdbcTemplate.update(
        "delete from ods_gitlab_label_links where label_id = ? and target_id = ?", labelId, issueId);
  }

  private void cleanUp() {
    jdbcTemplate.update("delete from issue_fact_customer_members");
    jdbcTemplate.update("delete from issue_fact where project_id in (?, ?)", CUSTOMER_PROJECT_ID, NON_CUSTOMER_PROJECT_ID);
    jdbcTemplate.update("delete from ods_gitlab_label_links");
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_notes");
    jdbcTemplate.update("delete from ods_gitlab_issue_assignees");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    jdbcTemplate.update("delete from ods_gitlab_users");
    jdbcTemplate.update("delete from ods_gitlab_projects");
  }
}
