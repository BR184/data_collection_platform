package com.data.collection.platform.service;

import com.data.collection.platform.domain.customerissue.CustomerIssueActivityTimeRules;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

final class FactSourceRowValueSupport {
  private FactSourceRowValueSupport() {}

  static boolean isClosed(ResultSet resultSet) throws SQLException {
    Timestamp closedAt = resultSet.getTimestamp("closed_at");
    Integer stateId = (Integer) resultSet.getObject("state_id");
    return closedAt != null || (stateId != null && stateId != 1);
  }

  static String mergeRequestState(ResultSet resultSet) throws SQLException {
    Integer stateId = (Integer) resultSet.getObject("state_id");
    if (stateId != null) {
      return switch (stateId) {
        case 3 -> "merged";
        case 2 -> "closed";
        default -> "opened";
      };
    }
    return toLocalDateTime(resultSet.getTimestamp("merged_at")) == null
        ? "opened"
        : "merged";
  }

  static LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  /**
   * 把带时区的来源时刻显式落成上海墙上值，供完成加标事件→事实投影使用（D-27）。
   *
   * <p>与 {@link #toLocalDateTime} 的区别是结果不依赖 JVM、连接会话或数据库的默认时区：只取时刻本身，
   * 再按平台业务时区解释。因此同一事件在任何写入进程上都得到同一个业务日历值。
   * 仅适用于来源确为带时区类型的列；无时区列仍按其真实语义走 {@link #toLocalDateTime}，不得借用本方法猜测偏移。
   *
   * @param timestamp 来源带时区时刻，缺失时为 {@code null}
   * @return 上海墙上时间；入参缺失时为 {@code null}
   */
  static LocalDateTime toShanghaiWallClock(Timestamp timestamp) {
    return timestamp == null
        ? null
        : timestamp
            .toInstant()
            .atZone(CustomerIssueActivityTimeRules.BUSINESS_TIME_ZONE)
            .toLocalDateTime();
  }

  static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
    Object value = resultSet.getObject(columnName);
    return value instanceof Number number ? number.longValue() : null;
  }

  static List<String> readTextArray(Array array) throws SQLException {
    if (array == null || !(array.getArray() instanceof Object[] values)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object value : values) {
      String normalized = defaultText(value == null ? null : String.valueOf(value), null);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }

  static String defaultText(String value) {
    return defaultText(value, "");
  }

  static String defaultText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }
}
