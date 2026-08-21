package com.data.collection.platform.service.statistics.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 筛选引擎操作符语义契约：全部操作符在此矩阵锁定，SQL 下推路径须与之一致。 */
class StatisticFilterEngineTest {

  private record Row(String name, List<String> modules, LocalDateTime createdAt) {}

  private static final Row ROW =
      new Row("Alpha", List.of("装配", "钣金"), LocalDateTime.of(2026, 6, 15, 10, 30));

  private static Map<String, StatisticFieldDescriptor<Row>> fields() {
    return Map.of(
        "name", StatisticFieldDescriptor.multiValue("name", row -> List.of(row.name())),
        "modules", StatisticFieldDescriptor.multiValue("modules", Row::modules),
        "createdAt", StatisticFieldDescriptor.dateTime("createdAt", Row::createdAt));
  }

  private static boolean matches(StatisticFilterGroup group) {
    return StatisticFilterEngine.compile(group, fields()).test(ROW);
  }

  private static StatisticFilterCondition literal(String fieldKey, String operator, String value) {
    return new StatisticFilterCondition(fieldKey, operator, value, null);
  }

  private static StatisticFilterCondition labelGroup(
      String fieldKey, String operator, List<String> values) {
    return new StatisticFilterCondition(
        fieldKey, operator, null, null, "LABEL_GROUP", 1L, "组", values);
  }

  @Test
  void test_null_or_empty_group_matches_every_row() {
    assertThat(matches(new StatisticFilterGroup("AND", List.of()))).isTrue();
    assertThat(StatisticFilterEngine.compile(null, fields()).test(ROW)).isTrue();
  }

  @Test
  void test_unknown_field_key_never_narrows_result() {
    assertThat(matches(new StatisticFilterGroup("AND", List.of(literal("ghost", "eq", "x")))))
        .isTrue();
  }

  @Test
  void test_text_operator_matrix() {
    assertThat(matches(new StatisticFilterGroup("AND", List.of(literal("name", "eq", "alpha")))))
        .isTrue();
    assertThat(matches(new StatisticFilterGroup("AND", List.of(literal("name", "eq", "Beta")))))
        .isFalse();
    assertThat(matches(new StatisticFilterGroup("AND", List.of(literal("name", "ne", "Beta")))))
        .isTrue();
    assertThat(matches(new StatisticFilterGroup("AND", List.of(literal("name", "ne", "alpha")))))
        .isFalse();
    assertThat(matches(new StatisticFilterGroup("AND", List.of(literal("name", "contains", "lph")))))
        .isTrue();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(literal("name", "notContains", "lph")))))
        .isFalse();
    assertThat(matches(new StatisticFilterGroup("AND", List.of(literal("name", "isEmpty", null)))))
        .isFalse();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(literal("name", "isNotEmpty", null)))))
        .isTrue();
  }

  @Test
  void test_set_operator_matrix_on_multi_value_field() {
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(labelGroup("modules", "intersects", List.of("装配"))))))
        .isTrue();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(labelGroup("modules", "intersects", List.of("喷涂"))))))
        .isFalse();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(labelGroup("modules", "ne", List.of("喷涂"))))))
        .isTrue();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(labelGroup("modules", "eq", List.of("装配"))))))
        .isTrue();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(labelGroup("modules", "containsAll", List.of("装配"))))))
        .isTrue();
    assertThat(
        matches(
            new StatisticFilterGroup(
                "AND", List.of(labelGroup("modules", "containsAll", List.of("装配", "钣金"))))))
        .isTrue();
    assertThat(
        matches(
            new StatisticFilterGroup(
                "AND", List.of(labelGroup("modules", "notContainsAll", List.of("装配", "喷涂"))))))
        .isTrue();
    assertThat(
        matches(
            new StatisticFilterGroup(
                "AND", List.of(labelGroup("modules", "partialContainsAny", List.of("配"))))))
        .isTrue();
    assertThat(
        matches(
            new StatisticFilterGroup(
                "AND", List.of(labelGroup("modules", "partialContainsAny", List.of("焊"))))))
        .isFalse();
  }

  @Test
  void test_datetime_operator_matrix() {
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(literal("createdAt", "year", "2026-01-01T00:00:00")))))
        .isTrue();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(literal("createdAt", "month", "2026-06-01T00:00:00")))))
        .isTrue();
    assertThat(matches(new StatisticFilterGroup("AND", List.of(literal("createdAt", "day", "2026-06-15")))))
        .isTrue();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(literal("createdAt", "before", "2026-06-16")))))
        .isTrue();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(literal("createdAt", "after", "2026-06-01")))))
        .isTrue();
    assertThat(
        matches(
            new StatisticFilterGroup(
                "AND",
                List.of(
                    new StatisticFilterCondition(
                        "createdAt", "between", "2026-06-01", "2026-06-30")))))
        .isTrue();
    assertThat(
        matches(new StatisticFilterGroup("AND", List.of(literal("createdAt", "isNotEmpty", null)))))
        .isTrue();
  }

  @Test
  void test_and_group_requires_all_or_group_matches_any() {
    assertThat(
        matches(
            new StatisticFilterGroup(
                "AND",
                List.of(literal("name", "eq", "Alpha"), literal("name", "eq", "Beta")))))
        .isFalse();
    assertThat(
        matches(
            new StatisticFilterGroup(
                "OR",
                List.of(
                    literal("name", "eq", "Beta"),
                    labelGroup("modules", "intersects", List.of("装配"))))))
        .isTrue();
    assertThat(
        matches(
            new StatisticFilterGroup(
                "OR",
                List.of(literal("name", "eq", "Beta"), literal("name", "eq", "Gamma")))))
        .isFalse();
  }

  @Test
  void test_override_predicate_wins_over_generic_semantics() {
    record R(String tag) {}
    StatisticFieldDescriptor<R> descriptor =
        StatisticFieldDescriptor.multiValueWithOverride(
            "tag", r -> List.of(r.tag()), (row, condition) -> row.tag().equals("special"));
    var group =
        new StatisticFilterGroup("AND", List.of(literal("tag", "eq", "anything")));
    assertThat(StatisticFilterEngine.compile(group, Map.of("tag", descriptor)).test(new R("special")))
        .isTrue();
    assertThat(StatisticFilterEngine.compile(group, Map.of("tag", descriptor)).test(new R("other")))
        .isFalse();
  }
}
