package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.domain.customerissue.CustomerIssueActivityTimeRules;
import com.data.collection.platform.domain.customerissue.CustomerIssueEfficiencyRules;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 真实 PostgreSQL 时间轴回归：timestamptz 投影不依赖连接会话时区。
 *
 * <p>覆盖 UTC/上海/纽约三种会话时区下，同一事件时刻分别落成 UTC 墙上值（响应模板时间）与
 * 上海墙上值（完成加标时间）；以及真实 24 小时解决周期必须是 1.0 天，而不是直接相减得到的 1.3 天。
 */
class CustomerIssueTimeAxisIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("customer_issue_time_axis_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @Test
  void test_jdbc_session_time_zone_does_not_change_utc_or_shanghai_projection() throws Exception {
    Instant instant = Instant.parse("2026-04-01T10:00:00Z");
    for (String sessionTimeZone : List.of("UTC", "Asia/Shanghai", "America/New_York")) {
      try (Connection connection = database.dataSource().getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("set time zone '" + sessionTimeZone + "'");
        try (ResultSet resultSet =
            statement.executeQuery(
                "select timestamptz '" + instant + "' as instant_value")) {
          assertThat(resultSet.next()).isTrue();
          assertThat(FactSourceRowValueSupport.readTemporalProjection(
                  resultSet, "instant_value", ZoneOffset.UTC))
              .as("会话时区 %s 的 UTC 投影", sessionTimeZone)
              .isEqualTo(LocalDateTime.of(2026, 4, 1, 10, 0));
          assertThat(FactSourceRowValueSupport.readTemporalProjection(
                  resultSet, "instant_value", CustomerIssueActivityTimeRules.BUSINESS_TIME_ZONE))
              .as("会话时区 %s 的上海投影", sessionTimeZone)
              .isEqualTo(LocalDateTime.of(2026, 4, 1, 18, 0));
        }
      }
    }
  }

  @Test
  void test_closed_at_fact_projection_is_utc_wall_independent_of_session_time_zone()
      throws Exception {
    Instant instant = Instant.parse("2026-04-01T10:00:00Z");
    for (String sessionTimeZone : List.of("UTC", "Asia/Shanghai", "America/New_York")) {
      try (Connection connection = database.dataSource().getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("set time zone '" + sessionTimeZone + "'");
        try (ResultSet resultSet =
            statement.executeQuery(
                "select timestamptz '" + instant + "' as closed_at")) {
          assertThat(resultSet.next()).isTrue();
          assertThat(FactSourceRowValueSupport.readTemporalProjection(
                  resultSet, "closed_at", ZoneOffset.UTC))
              .as("会话时区 %s 的 closed_at UTC 墙上值", sessionTimeZone)
              .isEqualTo(LocalDateTime.of(2026, 4, 1, 10, 0));
        }
      }
    }
  }

  @Test
  void test_real_twenty_four_hours_is_one_day_on_the_business_time_axis() {
    LocalDateTime createdUtc = LocalDateTime.of(2026, 4, 1, 10, 0);
    LocalDateTime completedShanghai = LocalDateTime.of(2026, 4, 2, 18, 0);

    assertThat(CustomerIssueEfficiencyRules.resolutionCycleDays(createdUtc, completedShanghai))
        .isEqualByComparingTo(new BigDecimal("1.000000"));
    assertThat(CustomerIssueEfficiencyRules.resolutionCycleDays(
            createdUtc, createdUtc.plusHours(32)))
        .isEqualByComparingTo(new BigDecimal("1.000000"));
    assertThat(CustomerIssueEfficiencyRules.responseCycleHours(
            createdUtc, createdUtc.plusHours(24)))
        .isEqualTo(24L);
  }
}
