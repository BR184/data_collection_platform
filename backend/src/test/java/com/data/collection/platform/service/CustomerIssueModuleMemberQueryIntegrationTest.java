package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.CustomerIssueFactQueryService.FactScopeRequest;
import com.data.collection.platform.service.CustomerIssueFactQueryService.MemberSelection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 客户问题窄事实读取的模块成员语义（真实 PostgreSQL）。
 *
 * <p>SQL 侧筛选与 Java 侧成员解析必须逐项一致：同一分隔符集合、同一空白与空成员处理、同一“未设定”
 * 占位排除，并且精确匹配不得把 {@code %} 与 {@code _} 当成通配符。本测试直接对真实库执行读取，
 * 断言的是返回的议题集合，不是 SQL 文本。
 */
@SpringBootTest
class CustomerIssueModuleMemberQueryIntegrationTest {
  private static final long PROJECT_ID = 325L;
  private static final String MILESTONE = "CC2026R9模块成员用例";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 6, 9, 0);
  private static final long FIRST_ISSUE_ID = 94001L;

  /** 覆盖空值、纯空白、占位、单一与多分隔符、混合分隔符、重复成员与通配符字面量。 */
  private static final List<String> MODULE_FIXTURES = Arrays.asList(
      null,
      "",
      "   ",
      "未设定",
      "未设定模块",
      " 未设定模块 ",
      "模块A",
      " 模块A ",
      "模块A,模块B",
      "模块A，模块B",
      "模块A、模块B",
      "模块A & 模块B",
      "模块A,模块B，模块C、模块D&模块E",
      "模块A，模块A",
      "模块A,,模块B",
      "未设定模块，模块F",
      "模块%A",
      "模块_B",
      "模块XA",
      "未设定模块,模块_A",
      "\t",
      "\t\n ",
      "\t模块G\t",
      "\n模块H\n",
      " \t未设定模块\t ",
      "未设定模块\n",
      "模块I\n,\t模块J");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IssueFactMapper issueFactMapper;
  @Autowired private CustomerIssueFactQueryService customerIssueFactQueryService;

  @BeforeEach
  void setUp() {
    cleanUp();
    for (int index = 0; index < MODULE_FIXTURES.size(); index++) {
      insertFact(FIRST_ISSUE_ID + index, "议题" + index, MODULE_FIXTURES.get(index));
    }
  }

  @Test
  void missingSelectionSucceedsAndMatchesRowsWithoutRealMembers() {
    List<String> missingTitles = loadTitles(MemberSelection.missing());

    List<String> expectedMissing = new ArrayList<>();
    for (int index = 0; index < MODULE_FIXTURES.size(); index++) {
      if (IssueModuleMembers.split(MODULE_FIXTURES.get(index)).isEmpty()) {
        expectedMissing.add("议题" + index);
      }
    }

    assertThat(missingTitles)
        .as("缺失分支必须能执行，并且只包含拆分后没有真实成员的议题")
        .containsExactlyInAnyOrderElementsOf(expectedMissing);
    assertThat(missingTitles)
        .as("纯空白与“未设定”占位都算缺失，真实成员议题不得进入")
        .doesNotContain("议题6", "议题7", "议题15", "议题16", "议题17", "议题18", "议题19");
  }

  @Test
  void separatorsAreRecognisedIdenticallyBySqlAndJava() {
    assertThat(loadTitles(MemberSelection.of("模块B")))
        .as("半角逗号、全角逗号、顿号、& 与混合串、空成员串中的模块B都必须命中")
        .containsExactlyInAnyOrder("议题8", "议题9", "议题10", "议题11", "议题12", "议题14");
    assertThat(loadTitles(MemberSelection.of("模块C")))
        .containsExactly("议题12");
    assertThat(loadTitles(MemberSelection.of("模块E")))
        .containsExactly("议题12");
    assertThat(loadTitles(MemberSelection.of("模块F")))
        .as("占位与真实成员混合时仍可按真实成员命中")
        .containsExactly("议题15");
  }

  @Test
  void exactMemberMatchNeverTreatsWildcardCharactersAsPatterns() {
    assertThat(loadTitles(MemberSelection.of("模块%A")))
        .as("% 是模块名的一部分，不得匹配任意字符")
        .containsExactly("议题16");
    assertThat(loadTitles(MemberSelection.of("模块_B")))
        .as("_ 是模块名的一部分，不得匹配单个字符")
        .containsExactly("议题17");
    assertThat(loadTitles(MemberSelection.of("模块_A")))
        .as("_ 不得匹配 'A'/'X' 之类单字符，因此模块XA 不属于该选择")
        .containsExactly("议题19");
  }

  @Test
  void everyFixtureAgreesBetweenJavaMembersAndSqlSelection() {
    for (int index = 0; index < MODULE_FIXTURES.size(); index++) {
      String rawValue = MODULE_FIXTURES.get(index);
      String title = "议题" + index;
      List<String> members = IssueModuleMembers.split(rawValue);
      for (String member : members) {
        assertThat(loadTitles(MemberSelection.of(member)))
            .as("Java 解析出的成员 %s（原始值 %s）必须能被同一 SQL 选择命中".formatted(member, rawValue))
            .contains(title);
      }
      if (members.isEmpty()) {
        assertThat(loadTitles(MemberSelection.missing()))
            .as("Java 判定无真实成员时 SQL 缺失选择必须命中同一议题")
            .contains(title);
      } else {
        assertThat(loadTitles(MemberSelection.missing()))
            .as("Java 判定有真实成员时 SQL 缺失选择不得命中同一议题")
            .doesNotContain(title);
      }
    }
  }

  @Test
  void whitespaceAroundMembersIsStrippedIdenticallyBySqlAndJava() {
    assertThat(loadTitles(MemberSelection.of("模块G")))
        .as("成员首尾的制表符必须被去掉，模块G 必须可按精确名命中")
        .containsExactly("议题22");
    assertThat(loadTitles(MemberSelection.of("模块H")))
        .as("成员首尾的换行必须被去掉，模块H 必须可按精确名命中")
        .containsExactly("议题23");
    assertThat(loadTitles(MemberSelection.of("模块I")))
        .as("分隔符两侧的换行与制表符属于分隔符，不影响真实成员识别")
        .containsExactly("议题26");
    assertThat(loadTitles(MemberSelection.of("模块J")))
        .as("同一串中第二个成员也必须被识别")
        .containsExactly("议题26");
    assertThat(loadTitles(MemberSelection.missing()))
        .as("纯制表符/纯空白成员串没有真实成员，占位名称周边的制表符与换行同样不构成真实成员")
        .contains("议题20", "议题21", "议题24", "议题25")
        .doesNotContain("议题22", "议题23", "议题26");
  }

  @Test
  void narrowReadModelReturnsTheSameMembersAsTheSqlRule() {
    List<CustomerIssueFact> facts = customerIssueFactQueryService.load(
        new FactScopeRequest(null, List.of(MILESTONE), MemberSelection.all(), MemberSelection.all(), MemberSelection.all()));

    assertThat(facts).hasSize(MODULE_FIXTURES.size());
    for (int index = 0; index < MODULE_FIXTURES.size(); index++) {
      int position = index;
      CustomerIssueFact fact = facts.stream()
          .filter(item -> ("议题" + position).equals(item.title()))
          .findFirst()
          .orElseThrow();
      assertThat(fact.moduleNames()).isEqualTo(IssueModuleMembers.split(MODULE_FIXTURES.get(index)));
    }
  }

  private List<String> loadTitles(MemberSelection moduleSelection) {
    return customerIssueFactQueryService
        .load(
            new FactScopeRequest(
                null,
                List.of(MILESTONE),
                MemberSelection.all(),
                moduleSelection,
                MemberSelection.all()))
        .stream()
        .map(CustomerIssueFact::title)
        .toList();
  }

  private void insertFact(long issueId, String title, String moduleNames) {
    IssueFact fact = new IssueFact();
    fact.setSourceSystem("GITLAB");
    fact.setSourceInstance("default");
    fact.setIngestChannel("MIRROR");
    fact.setSourceSummary("模块成员语义用例");
    fact.setProjectId(PROJECT_ID);
    fact.setProjectName("CC_PRODUCT");
    fact.setIssueId(issueId);
    fact.setIssueIid(issueId);
    fact.setTitle(title);
    fact.setIssueState("opened");
    fact.setMilestoneTitle(MILESTONE);
    fact.setModuleNames(moduleNames);
    fact.setSeverityLevel("LEVEL2");
    fact.setPriorityLevel("P2");
    fact.setBugStatus("处理中");
    fact.setCategory("功能");
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
    issueFactMapper.upsert(fact);
  }

  private void cleanUp() {
    jdbcTemplate.update(
        "delete from issue_fact_customer_members where project_id = ?", PROJECT_ID);
    jdbcTemplate.update("delete from issue_fact where project_id = ?", PROJECT_ID);
  }
}
