package com.data.collection.platform.domain.customerissue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 客户问题活动时间的纯规则：今日与本自然周窗口、业务日归属及精确完成贡献。
 *
 * <p>两个页面（平台客户问题统计与客户问题 BI）共用本类，保证“今日/本周/趋势”使用同一时间映射，
 * 不在任一页面局部换算或改取当前墙钟。
 *
 * <p>事实层两列的存储语义不同，2026-10-15 在本地来源与事实库实测对照：
 * <ul>
 *   <li>{@code created_at_source}：来源 {@code issues.created_at} 是 {@code timestamp without time zone}
 *       且来源会话时区为 GMT，镜像与事实按 naive 原样传递，因此存储值是 <b>UTC 墙上时间</b>；
 *       由 {@code *FromSourceUtc} 入口换算到北京时间后再比窗口。</li>
 *   <li>{@code fixed_label_time}：来源 {@code resource_label_events.created_at} 是 {@code timestamptz}，
 *       事实投影在边界上显式落成 <b>北京时间墙上值</b>（见 D-27），写入进程与连接会话的默认时区不参与结果；
 *       由 {@code *FromBusinessClock} 入口直接按业务日解释，不再二次偏移。</li>
 * </ul>
 * 两列不得混用同一入口，也不得按物理列类型猜测时区。缺失时间一律保持未知，不补成业务零。
 */
public final class CustomerIssueActivityTimeRules {
  /** 精确完成状态成员；斜杠是名称的一部分，必须整体命中。 */
  public static final String EXACT_COMPLETION_MEMBER = "已修复/完成";

  /**
   * 平台业务日历时区，与业务日、今日/本周窗口、趋势分桶及完成事实投影同一条边界。
   *
   * <p>完成加标时间在来源是带时区类型，投影到事实层时必须显式落成本时区的墙上值（见 D-27），
   * 使写入进程与连接会话的默认时区都不参与结果。
   */
  public static final ZoneId BUSINESS_TIME_ZONE = ZoneId.of("Asia/Shanghai");

  private CustomerIssueActivityTimeRules() {}

  /**
   * 把 UTC naive 的来源时间映射为北京时间墙上时间。
   *
   * @param sourceUtc 事实层按 UTC 墙上值存储的时间，缺失时为 {@code null}
   * @return 北京时间 naive 时间；入参缺失时为 {@code null}（未知不补业务零）
   */
  public static LocalDateTime businessTimeFromSourceUtc(LocalDateTime sourceUtc) {
    if (sourceUtc == null) {
      return null;
    }
    return sourceUtc.atZone(ZoneOffset.UTC).withZoneSameInstant(BUSINESS_TIME_ZONE).toLocalDateTime();
  }

  /**
   * UTC naive 来源时间所属的北京时间日历日。
   *
   * @param sourceUtc 事实层按 UTC 墙上值存储的时间，缺失时为 {@code null}
   * @return 业务日历日；入参缺失时为 {@code null}
   */
  public static LocalDate businessDateFromSourceUtc(LocalDateTime sourceUtc) {
    LocalDateTime businessTime = businessTimeFromSourceUtc(sourceUtc);
    return businessTime == null ? null : businessTime.toLocalDate();
  }

  /**
   * 判断 UTC naive 来源时间是否落入该业务日的北京自然日半开窗口 {@code [dayStart, nextDayStart)}。
   *
   * @param sourceUtc 事实层按 UTC 墙上值存储的时间，缺失时为 {@code null}
   * @param day 页面固定的业务日
   * @return 命中窗口；任一入参缺失时为 {@code false}
   */
  public static boolean withinBusinessDayFromSourceUtc(LocalDateTime sourceUtc, LocalDate day) {
    return withinBusinessDayFromBusinessClock(businessTimeFromSourceUtc(sourceUtc), day);
  }

  /**
   * 判断 UTC naive 来源时间是否落入本自然周的半开窗口（北京时间）。
   *
   * @param sourceUtc 事实层按 UTC 墙上值存储的时间，缺失时为 {@code null}
   * @param businessDate 页面固定的业务日
   * @return 命中本周窗口；任一入参缺失时为 {@code false}
   */
  public static boolean withinBusinessWeekFromSourceUtc(LocalDateTime sourceUtc, LocalDate businessDate) {
    return withinBusinessWeekFromBusinessClock(businessTimeFromSourceUtc(sourceUtc), businessDate);
  }

  /**
   * 已按业务时区落成 naive 的事实时间所属业务日。
   *
   * @param businessTime 事实层按业务时区墙上值存储的时间，缺失时为 {@code null}
   * @return 业务日历日；入参缺失时为 {@code null}
   */
  public static LocalDate businessDateFromBusinessClock(LocalDateTime businessTime) {
    return businessTime == null ? null : businessTime.toLocalDate();
  }

  /**
   * 判断业务时区墙上值是否落入该业务日的半开自然日窗口 {@code [dayStart, nextDayStart)}。
   *
   * @param businessTime 事实层按业务时区墙上值存储的时间，缺失时为 {@code null}
   * @param day 页面固定的业务日
   * @return 命中窗口；{@code businessTime} 或 {@code day} 缺失时为 {@code false}（未知不补业务零）
   */
  public static boolean withinBusinessDayFromBusinessClock(LocalDateTime businessTime, LocalDate day) {
    if (businessTime == null || day == null) {
      return false;
    }
    return !businessTime.isBefore(day.atStartOfDay()) && businessTime.isBefore(day.plusDays(1).atStartOfDay());
  }

  /**
   * 本自然周起点：固定业务日所在周的周一。
   *
   * @param businessDate 页面固定的业务日
   * @return 该业务日所在自然周的周一
   */
  public static LocalDate weekStart(LocalDate businessDate) {
    if (businessDate == null) {
      throw new IllegalArgumentException("本周窗口必须持有固定业务日");
    }
    return businessDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  /**
   * 判断业务时区墙上值是否落入本自然周的半开窗口 {@code [weekStart, nextBusinessDayStart)}。
   *
   * <p>终点沿今日窗口同一条边界推进一天，统计至固定业务日结束；不随调用时刻滚动。
   *
   * @param businessTime 事实层按业务时区墙上值存储的时间，缺失时为 {@code null}
   * @param businessDate 页面固定的业务日
   * @return 命中本周窗口；{@code businessTime} 缺失时为 {@code false}
   */
  public static boolean withinBusinessWeekFromBusinessClock(LocalDateTime businessTime, LocalDate businessDate) {
    if (businessTime == null || businessDate == null) {
      return false;
    }
    return !businessTime.isBefore(weekStart(businessDate).atStartOfDay())
        && businessTime.isBefore(businessDate.plusDays(1).atStartOfDay());
  }

  /**
   * 精确完成贡献：状态成员集合整体等于“已修复/完成”。
   *
   * <p>沿既有今日解决口径，只认当前状态的精确成员，不使用关闭或更新时间替代。
   *
   * @param statusMembers 由现有状态成员解析器得到的成员列表
   * @return 是否含精确完成成员
   */
  public static boolean hasExactCompletionMember(List<String> statusMembers) {
    return statusMembers != null && statusMembers.stream()
        .anyMatch(EXACT_COMPLETION_MEMBER::equalsIgnoreCase);
  }
}
