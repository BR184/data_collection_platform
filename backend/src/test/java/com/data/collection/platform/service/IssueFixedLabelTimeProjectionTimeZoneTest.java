package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.domain.customerissue.CustomerIssueActivityTimeRules;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 完成加标时间事实投影的跨时区回归（D-27）。
 *
 * <p>链路是真实 JDBC：带时区列的 ODS 镜像表 → 实际来源 SQL → 实际 {@link IssueFactSourceRowMapper} →
 * 今日/本周/累计完成窗口。验收对象是“同一个时刻在任何写入进程与任何连接会话时区上都必须得到同一个业务日历值”，
 * 所以每个用例都在多种 JVM 默认时区与会话时区组合下重读同一批数据。
 *
 * <p>两列各自的语义都锁在这里：{@code created_at} 无时区，投影保持来源 naive 墙上值；
 * {@code fixed_label_time} 带时区，投影显式落上海墙上值。任何一侧改用另一侧的口径都会破坏窗口判断。
 */
class IssueFixedLabelTimeProjectionTimeZoneTest {
  /** 上海周一零点（UTC 周日 16:00）之后的事件：必须计入本周、属于 10-12 当天。 */
  private static final LocalDateTime MONDAY_0030_BEIJING = LocalDateTime.of(2026, 10, 12, 0, 30);

  private static final long ISSUE_AFTER_MONDAY_START = 9001L;

  /** 周一零点前一瞬（UTC 周日 15:59:59）：属于 10-11，不在本周内。 */
  private static final LocalDateTime SUNDAY_LAST_INSTANT_BEIJING =
      LocalDateTime.of(2026, 10, 11, 23, 59, 59);

  private static final long ISSUE_BEFORE_MONDAY_START = 9002L;

  /** 已完成但来源没有加标事件：时间未知，不得伪造业务零。 */
  private static final long ISSUE_WITHOUT_EVENT = 9003L;

  private static final long PROJECT_ID = 325L;
  private static final long FIX_LABEL_ID = 513L;

  /** 周三：本周窗口锚定的业务日。 */
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 14);

  private static final String[] WRITE_PROCESS_ZONES = {
    "Asia/Shanghai", "Etc/UTC", "America/Los_Angeles"
  };
  private static final String[] SESSION_ZONES = {"UTC", "Asia/Shanghai", "America/New_York"};

  private static PostgresIntegrationTestDatabase database;
  private static TimeZone originalZone;

  private final GitlabFactSourceSqlProvider provider = new GitlabFactSourceSqlProvider();
  private DataSource dataSource;

  @BeforeAll
  static void openDatabase() {
    originalZone = TimeZone.getDefault();
    database = PostgresIntegrationTestDatabase.open("fixed_label_time_timezone_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
    if (originalZone != null) {
      TimeZone.setDefault(originalZone);
    }
  }

  @BeforeEach
  void setUp() {
    dataSource = database.dataSource();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    dropSchema(jdbcTemplate);
    createSchema(jdbcTemplate);
    seed(jdbcTemplate);
  }

  @AfterEach
  void restoreZone() {
    TimeZone.setDefault(originalZone);
  }

  @Test
  void fixedLabelTime_projectsSameBeijingWallClock_underEveryWriteProcessAndSessionZone()
      throws Exception {
    for (String writeZone : WRITE_PROCESS_ZONES) {
      for (String sessionZone : SESSION_ZONES) {
        TimeZone.setDefault(TimeZone.getTimeZone(writeZone));
        Map<Long, IssueFact> facts = readFacts(sessionZone);

        assertThat(facts.get(ISSUE_AFTER_MONDAY_START).getFixedLabelTime())
            .as("同一 UTC 时刻在写入进程 %s / 会话 %s 下必须得到同一个上海墙上值", writeZone, sessionZone)
            .isEqualTo(MONDAY_0030_BEIJING);
        assertThat(facts.get(ISSUE_BEFORE_MONDAY_START).getFixedLabelTime())
            .as("周一前一瞬在写入进程 %s / 会话 %s 下仍是上海周日 23:59:59", writeZone, sessionZone)
            .isEqualTo(SUNDAY_LAST_INSTANT_BEIJING);
        assertThat(facts.get(ISSUE_WITHOUT_EVENT).getFixedLabelTime())
            .as("无加标事件时完成时间保持未知（写入进程 %s / 会话 %s）", writeZone, sessionZone)
            .isNull();
      }
    }
  }

  @Test
  void createdAtSource_keepsNaiveSourceWallClock_underEveryWriteProcessAndSessionZone()
      throws Exception {
    for (String writeZone : WRITE_PROCESS_ZONES) {
      for (String sessionZone : SESSION_ZONES) {
        TimeZone.setDefault(TimeZone.getTimeZone(writeZone));
        Map<Long, IssueFact> facts = readFacts(sessionZone);

        assertThat(facts.get(ISSUE_AFTER_MONDAY_START).getCreatedAtSource())
            .as("无时区列不做偏移猜测，写入进程 %s / 会话 %s 下保持来源墙上值", writeZone, sessionZone)
            .isEqualTo(LocalDateTime.of(2026, 10, 11, 16, 0));
      }
    }
  }

  @Test
  void weekDayAndCumulativeBuckets_followBeijingBoundaries_underEveryWriteProcessZone()
      throws Exception {
    for (String writeZone : WRITE_PROCESS_ZONES) {
      TimeZone.setDefault(TimeZone.getTimeZone(writeZone));
      Map<Long, IssueFact> facts = readFacts("UTC");

      LocalDateTime monday = facts.get(ISSUE_AFTER_MONDAY_START).getFixedLabelTime();
      LocalDateTime sundayLastInstant = facts.get(ISSUE_BEFORE_MONDAY_START).getFixedLabelTime();

      assertThat(CustomerIssueActivityTimeRules.withinBusinessWeekFromBusinessClock(
              monday, BUSINESS_DATE))
          .as("上海周一零点后的完成时间必须落在本周（写入进程 %s）", writeZone)
          .isTrue();
      assertThat(CustomerIssueActivityTimeRules.withinBusinessWeekFromBusinessClock(
              sundayLastInstant, BUSINESS_DATE))
          .as("周一前一瞬的完成时间不得算进本周（写入进程 %s）", writeZone)
          .isFalse();
      assertThat(CustomerIssueActivityTimeRules.businessDateFromBusinessClock(monday))
          .as("累计完成趋势分桶按上海日历日（写入进程 %s）", writeZone)
          .isEqualTo(LocalDate.of(2026, 10, 12));
      assertThat(CustomerIssueActivityTimeRules.withinBusinessDayFromBusinessClock(
              monday, LocalDate.of(2026, 10, 12)))
          .as("今日完成窗口命中上海自然日（写入进程 %s）", writeZone)
          .isTrue();
      assertThat(CustomerIssueActivityTimeRules.withinBusinessDayFromBusinessClock(
              monday, LocalDate.of(2026, 10, 11)))
          .as("同一时刻不得同时属于两个业务日（写入进程 %s）", writeZone)
          .isFalse();
    }
  }

  private Map<Long, IssueFact> readFacts(String sessionZone) throws Exception {
    Map<Long, IssueFact> facts = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection()) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("set time zone '" + sessionZone + "'");
      }
      try (Statement statement = connection.createStatement();
          ResultSet resultSet = statement.executeQuery(provider.issueSourceSql())) {
        while (resultSet.next()) {
          IssueFact fact = mapSource(resultSet);
          facts.put(fact.getIssueId(), fact);
        }
      }
    }
    assertThat(facts).as("夹具必须产出三行议题事实").hasSize(3);
    return facts;
  }

  private IssueFact mapSource(ResultSet resultSet) throws Exception {
    return new IssueFactSourceRowMapper()
        .mapSource(resultSet, "default", Map.of(), moduleDictionary(), Map.of());
  }

  private ModuleDictionaryService.ModuleDictionary moduleDictionary() {
    ModuleDictionaryService.ModuleDictionary dictionary =
        mock(ModuleDictionaryService.ModuleDictionary.class);
    when(dictionary.normalizeIssueModules(anyLong(), any())).thenReturn(List.of());
    return dictionary;
  }

  private void seed(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, 'cc-product', false)",
        PROJECT_ID);
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, '状态：已修复/完成', false)",
        FIX_LABEL_ID);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
            id, iid, project_id, title, description, created_at, updated_at, closed_at,
            state_id, mirror_deleted) values
          (9001, 9001, ?, '跨时区完成时间一', '', timestamp '2026-10-11 16:00:00',
           timestamp '2026-10-12 01:00:00', null, 1, false),
          (9002, 9002, ?, '跨时区完成时间二', '', timestamp '2026-10-11 16:00:00',
           timestamp '2026-10-11 16:00:00', null, 1, false),
          (9003, 9003, ?, '跨时区完成时间三', '', timestamp '2026-10-11 16:00:00',
           timestamp '2026-10-12 01:00:00', null, 1, false)
        """,
        PROJECT_ID, PROJECT_ID, PROJECT_ID);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(id, label_id, target_id, target_type, mirror_deleted)
        values
          (1, ?, 9001, 'Issue', false),
          (2, ?, 9002, 'Issue', false),
          (3, ?, 9003, 'Issue', false)
        """,
        FIX_LABEL_ID, FIX_LABEL_ID, FIX_LABEL_ID);
    // 带时区列写入绝对时刻：+00 显式给出偏移，落库与插入连接的会话时区无关。
    jdbcTemplate.update(
        """
        insert into ods_gitlab_resource_label_events(
            id, issue_id, merge_request_id, label_id, action, created_at, mirror_deleted) values
          (11, 9001, null, ?, 1, timestamptz '2026-10-11 16:30:00+00', false),
          (12, 9002, null, ?, 1, timestamptz '2026-10-11 15:59:59+00', false)
        """,
        FIX_LABEL_ID, FIX_LABEL_ID);
  }

  private void dropSchema(JdbcTemplate jdbcTemplate) {
    List.of(
            "ods_gitlab_resource_label_events",
            "ods_gitlab_label_links",
            "ods_gitlab_labels",
            "ods_gitlab_issue_assignees",
            "ods_gitlab_notes",
            "ods_gitlab_milestones",
            "ods_gitlab_users",
            "ods_gitlab_issues",
            "ods_gitlab_projects")
        .forEach(table -> jdbcTemplate.execute("drop table if exists " + table + " cascade"));
  }

  /** 列类型按本地开发库 {@code information_schema} 实测复刻：事件与 closed_at 带时区，议题时间列不带。 */
  private void createSchema(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute(
        "create table ods_gitlab_projects (id bigint primary key, name varchar(255),"
            + " mirror_deleted boolean not null default false)");
    jdbcTemplate.execute(
        "create table ods_gitlab_users (id bigint primary key, name varchar(255),"
            + " mirror_deleted boolean not null default false)");
    jdbcTemplate.execute(
        "create table ods_gitlab_labels (id bigint primary key, title varchar(255),"
            + " mirror_deleted boolean not null default false)");
    jdbcTemplate.execute(
        "create table ods_gitlab_milestones (id bigint primary key, project_id bigint,"
            + " title varchar(255), mirror_deleted boolean not null default false)");
    jdbcTemplate.execute(
        "create table ods_gitlab_issues (id bigint primary key, iid bigint, project_id bigint,"
            + " milestone_id bigint, title varchar(255), description text, author_id bigint,"
            + " created_at timestamp, updated_at timestamp, closed_at timestamptz,"
            + " state_id integer, mirror_deleted boolean not null default false)");
    jdbcTemplate.execute(
        "create table ods_gitlab_label_links (id bigint primary key, label_id bigint,"
            + " target_id bigint, target_type varchar(64),"
            + " mirror_deleted boolean not null default false)");
    jdbcTemplate.execute(
        "create table ods_gitlab_issue_assignees (issue_id bigint, user_id bigint,"
            + " mirror_deleted boolean not null default false)");
    jdbcTemplate.execute(
        "create table ods_gitlab_notes (id bigint primary key, noteable_id bigint,"
            + " noteable_type varchar(64), note text, author_id bigint, created_at timestamp,"
            + " updated_at timestamp, mirror_deleted boolean not null default false)");
    jdbcTemplate.execute(
        "create table ods_gitlab_resource_label_events (id bigint primary key, issue_id bigint,"
            + " merge_request_id bigint, label_id bigint, action smallint,"
            + " created_at timestamptz, mirror_deleted boolean not null default false)");
  }
}
