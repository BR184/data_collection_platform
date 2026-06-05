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
