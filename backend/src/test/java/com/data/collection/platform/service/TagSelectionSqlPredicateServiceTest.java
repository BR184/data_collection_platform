package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.TagGroupResponse;
import com.data.collection.platform.entity.TagGroupValueResponse;
import com.data.collection.platform.entity.TagGroupsResponse;
import com.data.collection.platform.entity.TagSelectionRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TagSelectionSqlPredicateServiceTest {

  private final TagGroupService tagGroupService = Mockito.mock(TagGroupService.class);
  private final TagSelectionSqlPredicateService service =
      new TagSelectionSqlPredicateService(tagGroupService);

  @Test
  void shouldConvertIssueSelectionsToGroupAndWithValueOrPredicates() {
    when(tagGroupService.getTagGroups("issue"))
        .thenReturn(
            new TagGroupsResponse(
                "issue",
                "hash",
                List.of(
                    group(
                        "module",
                        "模块",
                        TagGroupMatchStrategyRegistry.SPLIT_EXACT_COMMA,
                        value("sketch", "草图"),
                        value("surface", "曲面")),
                    group(
                        "severity",
                        "严重程度",
                        TagGroupMatchStrategyRegistry.EQ,
                        value("critical", "严重")))));
    when(tagGroupService.resolveMappings("issue", "module", "sketch", "cc"))
        .thenReturn(List.of("草图别名"));
    when(tagGroupService.resolveMappings("issue", "module", "surface", "cc"))
        .thenReturn(List.of());
    when(tagGroupService.resolveMappings("issue", "severity", "critical", "cc"))
        .thenReturn(List.of());

    Optional<SqlPredicate> result =
        service.toSql(
            "issue",
            "cc",
            List.of(
                new TagSelectionRequest("module", List.of("sketch", "surface")),
                new TagSelectionRequest("severity", List.of("critical"))));

    assertThat(result).isPresent();
    assertThat(result.get().predicate())
        .isEqualTo(
            "(lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?"
                + " or lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?"
                + " or lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?)"
                + " and (lower(coalesce(severity_level, '')) = ?)");
    assertThat(result.get().args())
        .containsExactly("%,草图,%", "%,草图别名,%", "%,曲面,%", "严重");
  }

  @Test
  void shouldKeepCommaSplitModuleMatchingFromHittingSubstringValues() {
    when(tagGroupService.getTagGroups("issue"))
        .thenReturn(
            new TagGroupsResponse(
                "issue",
                "hash",
                List.of(
                    group(
                        "module",
                        "模块",
                        TagGroupMatchStrategyRegistry.SPLIT_EXACT_COMMA,
                        value("tool", "工具")))));
    when(tagGroupService.resolveMappings("issue", "module", "tool", null)).thenReturn(List.of());

    Optional<SqlPredicate> result =
        service.toSql("issue", null, List.of(new TagSelectionRequest("module", List.of("tool"))));

    assertThat(result).isPresent();
    assertThat(result.get().predicate())
        .isEqualTo("(lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?)");
    assertThat(result.get().args()).containsExactly("%,工具,%");
  }

  @Test
  void shouldSupportLikeStrategyWithCurveSurfaceExclusion() {
    when(tagGroupService.getTagGroups("issue"))
        .thenReturn(
            new TagGroupsResponse(
                "issue",
                "hash",
                List.of(
                    group(
                        "module_keyword",
                        "模块关键词",
                        TagGroupMatchStrategyRegistry.LIKE,
                        value("curve", "曲线")))));
    when(tagGroupService.resolveMappings("issue", "module_keyword", "curve", null))
        .thenReturn(List.of());

    Optional<SqlPredicate> result =
        service.toSql(
            "issue", null, List.of(new TagSelectionRequest("module_keyword", List.of("curve"))));

    assertThat(result).isPresent();
    assertThat(result.get().predicate())
        .isEqualTo(
            "((lower(coalesce(module_names, '')) like ?"
                + " and lower(coalesce(module_names, '')) not like ?))");
    assertThat(result.get().args()).containsExactly("%曲线%", "%曲线曲面%");
  }

  @Test
  void shouldAllowUnmappedAndRawValuesToParticipateInQueries() {
    when(tagGroupService.getTagGroups("issue"))
        .thenReturn(
            new TagGroupsResponse(
                "issue",
                "hash",
                List.of(
                    new TagGroupResponse(
                        "severity",
                        "严重程度",
                        "multiple",
                        1,
                        TagGroupMatchStrategyRegistry.EQ,
                        List.of(
                            new TagGroupValueResponse(
                                "legacy_level_one", "一级严重", "unmapped", 1, false, "历史口径"),
                            new TagGroupValueResponse(
                                "raw_critical", "Critical", "raw", 2, false, null))))));
    when(tagGroupService.resolveMappings("issue", "severity", "legacy_level_one", null))
        .thenReturn(List.of());
    when(tagGroupService.resolveMappings("issue", "severity", "raw_critical", null))
        .thenReturn(List.of());

    Optional<SqlPredicate> result =
        service.toSql(
            "issue",
            null,
            List.of(new TagSelectionRequest("severity", List.of("legacy_level_one", "raw_critical"))));

    assertThat(result).isPresent();
    assertThat(result.get().predicate())
        .isEqualTo("(lower(coalesce(severity_level, '')) = ? or lower(coalesce(severity_level, '')) = ?)");
    assertThat(result.get().args()).containsExactly("一级严重", "critical");
  }

  @Test
  void shouldSupportLegacyIssueLabelGroupsWithoutSubstringFallback() {
    when(tagGroupService.getTagGroups("issue"))
        .thenReturn(
            new TagGroupsResponse(
                "issue",
                "hash",
                List.of(
                    group(
                        "software",
                        "软件",
                        TagGroupMatchStrategyRegistry.LIKE,
                        value("crowncad", "CrownCAD")),
                    group(
                        "project_label",
                        "项目",
                        TagGroupMatchStrategyRegistry.LIKE,
                        value("cc2026r1", "CC2026R1")),
                    group(
                        "urgency",
                        "紧急程度",
                        TagGroupMatchStrategyRegistry.EQ,
                        value("p1", "P1")),
                    group(
                        "delay_cause",
                        "延期原因",
                        TagGroupMatchStrategyRegistry.EQ,
                        value("technical", "技术卡点")),
                    group(
                        "category",
                        "类别",
                        TagGroupMatchStrategyRegistry.EQ,
                        value("logic", "业务逻辑错误")))));
    when(tagGroupService.resolveMappings("issue", "software", "crowncad", null)).thenReturn(List.of("软件：CrownCAD"));
    when(tagGroupService.resolveMappings("issue", "project_label", "cc2026r1", null)).thenReturn(List.of("项目：CC2026R1"));
    when(tagGroupService.resolveMappings("issue", "urgency", "p1", null)).thenReturn(List.of());
    when(tagGroupService.resolveMappings("issue", "delay_cause", "technical", null)).thenReturn(List.of());
    when(tagGroupService.resolveMappings("issue", "category", "logic", null)).thenReturn(List.of());

    Optional<SqlPredicate> result =
        service.toSql(
            "issue",
            null,
            List.of(
                new TagSelectionRequest("software", List.of("crowncad")),
                new TagSelectionRequest("project_label", List.of("cc2026r1")),
                new TagSelectionRequest("urgency", List.of("p1")),
                new TagSelectionRequest("delay_cause", List.of("technical")),
                new TagSelectionRequest("category", List.of("logic"))));

    assertThat(result).isPresent();
    assertThat(result.get().predicate())
        .isEqualTo(
            "((lower(coalesce(label_names, '')) like ?) or (lower(coalesce(label_names, '')) like ?))"
                + " and ((lower(coalesce(label_names, '')) like ?) or (lower(coalesce(label_names, '')) like ?))"
                + " and (lower(coalesce(urgency, '')) = ?)"
                + " and (lower(coalesce(delay_cause, '')) = ?)"
                + " and (lower(coalesce(category, '')) = ?)");
    assertThat(result.get().args())
        .containsExactly(
            "%crowncad%",
            "%软件：crowncad%",
            "%cc2026r1%",
            "%项目：cc2026r1%",
            "p1",
            "技术卡点",
            "业务逻辑错误");
  }

  @Test
  void shouldConvertReviewDataProblemStatusToExistsPredicate() {
    when(tagGroupService.getTagGroups("review_data"))
        .thenReturn(
            new TagGroupsResponse(
                "review_data",
                "hash",
                List.of(
                    group(
                        "problem_status",
                        "问题状态",
                        TagGroupMatchStrategyRegistry.EQ,
                        value("confirmed", "已确认")))));
    when(tagGroupService.resolveMappings("review_data", "problem_status", "confirmed", null))
        .thenReturn(List.of("待解决"));

    Optional<SqlPredicate> result =
        service.toSql(
            "review_data",
            null,
            List.of(new TagSelectionRequest("problem_status", List.of("confirmed"))));

    assertThat(result).isPresent();
    assertThat(result.get().predicate())
        .isEqualTo(
            "(exists (select 1 from review_problem_items tag_problem"
                + " where tag_problem.review_record_id = r.id"
                + " and tag_problem.deleted = false"
                + " and lower(coalesce(tag_problem.problem_status, '')) = ?)"
                + " or exists (select 1 from review_problem_items tag_problem"
                + " where tag_problem.review_record_id = r.id"
                + " and tag_problem.deleted = false"
                + " and lower(coalesce(tag_problem.problem_status, '')) = ?))");
    assertThat(result.get().args()).containsExactly("已确认", "待解决");
  }

  private static TagGroupResponse group(
      String groupKey,
      String label,
      String matchStrategyName,
      TagGroupValueResponse... values) {
    return new TagGroupResponse(
        groupKey, label, "multiple", 1, matchStrategyName, List.of(values));
  }

  private static TagGroupValueResponse value(String valueKey, String label) {
    return new TagGroupValueResponse(valueKey, label, "standard", 1, false, null);
  }
}
