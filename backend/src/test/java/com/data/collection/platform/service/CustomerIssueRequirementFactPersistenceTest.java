package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.CustomerIssueFactQueryService.FactScopeRequest;
import com.data.collection.platform.service.CustomerIssueFactQueryService.MemberSelection;
import java.time.LocalDateTime;
import java.util.List;
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
 *       本用例显式调用全量重建，因此只证明构建器的派生口径；自动增量链（变更登记→根解析→定向发布→
 *       投影推进）由 {@link CustomerIssueRequirementLabelAutoChainIntegrationTest} 证明；
 *   <li>{@code IssueFactMapper.upsert} 与 {@code batchUpsert} 两条写入语句都包含该列，冲突时按
 *       {@code excluded} 更新；
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
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 6, 9, 0);

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FactBuildService factBuildService;
  @Autowired private IssueFactMapper issueFactMapper;
  @Autowired private IssueFactPersistenceService issueFactPersistenceService;
  @Autowired private CustomerIssueFactQueryService customerIssueFactQueryService;

  @BeforeEach
  void setUp() {
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanUp();
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
    rebuildAllIssueFacts();
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isTrue();

    // ② 换成唯一建议标签“类别：建议” → 仍为 true（两个目标标签是 OR）
    unlinkLabel(2L, CUSTOMER_ISSUE_ID);
    insertLabel(3L, "类别：建议");
    linkLabel(3L, CUSTOMER_ISSUE_ID);
    rebuildAllIssueFacts();
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isTrue();

    // ③ 删除全部目标标签 → 退出需求集合，派生值随事实自动更新
    unlinkLabel(3L, CUSTOMER_ISSUE_ID);
    rebuildAllIssueFacts();
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isFalse();

    // ④ 名称相似但不是目标标签的精确成员 → 不得模糊命中
    insertLabel(4L, "需求如此");
    linkLabel(4L, CUSTOMER_ISSUE_ID);
    rebuildAllIssueFacts();
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isFalse();
  }

  @Test
  void requirementFlagIsFalseForNonCustomerProject() {
    insertMirrorIssue(NON_CUSTOMER_PROJECT_ID, "CrownCAD", NON_CUSTOMER_ISSUE_ID, NON_CUSTOMER_ISSUE_IID);
    insertLabel(11L, "需求");
    linkLabel(11L, NON_CUSTOMER_ISSUE_ID);

    factBuildService.rebuildIssueFacts(true);

    assertThat(requirementFlag(NON_CUSTOMER_PROJECT_ID, NON_CUSTOMER_ISSUE_ID)).isFalse();
  }

  @Test
  void mapperUpsertPersistsUpdatesAndKeepsNullForRequirementFlag() {
    IssueFact fact = minimalFact(CUSTOMER_ISSUE_ID, CUSTOMER_ISSUE_IID);
    fact.setIsCustomerRequirement(Boolean.TRUE);
    issueFactMapper.upsert(fact);
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isTrue();

    // 冲突分支必须更新该列：同一身份再次写入 false。
    fact.setIsCustomerRequirement(Boolean.FALSE);
    issueFactMapper.upsert(fact);
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isFalse();

    // null＝尚未重建：不得被 SQL 或实体回读转成 false。
    fact.setIsCustomerRequirement(null);
    issueFactMapper.upsert(fact);
    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isNull();
    assertThat(issueFactMapper.selectBySourceContext("GITLAB", "default", CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)
            .getIsCustomerRequirement())
        .isNull();
  }

  @Test
  void batchUpsertPersistsRequirementFlag() {
    IssueFact fact = minimalFact(CUSTOMER_ISSUE_ID, CUSTOMER_ISSUE_IID);
    fact.setCustomerNames("客户甲");
    fact.setIsCustomerRequirement(Boolean.TRUE);

    issueFactPersistenceService.upsertIssueFacts(List.of(fact));

    assertThat(requirementFlag(CUSTOMER_PROJECT_ID, CUSTOMER_ISSUE_ID)).isTrue();
  }

  @Test
  void narrowFactReadModelReturnsPersistedFlagAndPreservesNull() {
    IssueFact fact = minimalFact(CUSTOMER_ISSUE_ID, CUSTOMER_ISSUE_IID);
    fact.setMilestoneTitle(MILESTONE);
    fact.setIsCustomerRequirement(Boolean.TRUE);
    issueFactMapper.upsert(fact);
    assertThat(loadedRequirement(MILESTONE)).containsExactly(Boolean.TRUE);

    fact.setIsCustomerRequirement(null);
    issueFactMapper.upsert(fact);
    assertThat(loadedRequirement(MILESTONE)).singleElement().isNull();
  }

  private List<Boolean> loadedRequirement(String milestone) {
    return customerIssueFactQueryService
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

  /** 显式全量重建：只用于驱动构建器重算派生值，不代表自动增量链。 */
  private void rebuildAllIssueFacts() {
    factBuildService.rebuildIssueFacts(true);
  }

  private Boolean requirementFlag(long projectId, long issueId) {
    return jdbcTemplate.queryForObject(
        "select is_customer_requirement from issue_fact where project_id = ? and issue_id = ?",
        Boolean.class,
        projectId,
        issueId);
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
