package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ReviewDataSearchIndexIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ReviewDataRecordService reviewDataRecordService;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from review_problem_items");
    jdbcTemplate.update("delete from review_record_experts");
    jdbcTemplate.update("delete from review_records");
  }

  @Test
  void shouldKeepPinyinAndInitialSearchWhenUsingPersistedSearchIndex() {
    reviewDataRecordService.createRecord(recordRequest("\u5f20\u4e09", "\u8bc4\u5ba1\u65b9\u6848A"));
    reviewDataRecordService.createRecord(recordRequest("\u7ae0\u4f1e", "\u8bc4\u5ba1\u65b9\u6848B"));

    List<String> spellIndexes =
        jdbcTemplate.queryForList(
            "select search_spell from review_records order by title asc", String.class);
    List<String> initialIndexes =
        jdbcTemplate.queryForList(
            "select search_initials from review_records order by title asc", String.class);

    assertThat(spellIndexes).allSatisfy(value -> assertThat(value).contains("zhangsan"));
    assertThat(initialIndexes).allSatisfy(value -> assertThat(value).contains("zs"));

    assertThat(titlesForKeyword("zhangsan"))
        .containsExactlyInAnyOrder("\u8bc4\u5ba1\u65b9\u6848A", "\u8bc4\u5ba1\u65b9\u6848B");
    assertThat(titlesForKeyword("zs"))
        .containsExactlyInAnyOrder("\u8bc4\u5ba1\u65b9\u6848A", "\u8bc4\u5ba1\u65b9\u6848B");
  }

  @Test
  void shouldApplySupportedAdvancedFiltersInSqlPageQuery() {
    ReviewDataRecordDetailResponse matched =
        reviewDataRecordService.createRecord(
            recordRequest("\u5f20\u4e09", "\u8bbe\u8ba1\u8bc4\u5ba1\u5355", LocalDate.of(2026, 4, 20), 20));
    ReviewDataRecordDetailResponse ignored =
        reviewDataRecordService.createRecord(
            recordRequest("\u674e\u56db", "\u9700\u6c42\u8bc4\u5ba1\u5355", LocalDate.of(2026, 3, 20), 8));
    reviewDataRecordService.createProblemItem(matched.record().id(), problemRequest("\u5f85\u6574\u6539"));
    reviewDataRecordService.createProblemItem(ignored.record().id(), problemRequest("\u5df2\u5173\u95ed"));

    ReviewDataRecordListResponse response =
        reviewDataRecordService.listRecords(
            new ReviewDataRecordQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                """
                {
                  "logic": "AND",
                  "conditions": [
                    {"fieldKey": "problemStatus", "operator": "eq", "value": "\u5f85\u6574\u6539"},
                    {"fieldKey": "problemCount", "operator": "gte", "value": "1"},
                    {"fieldKey": "reviewDate", "operator": "between", "value": "2026-04-01", "secondaryValue": "2026-04-30"}
                  ]
                }
                """,
                1,
                20,
                "title",
                "asc"));

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.records()).extracting(row -> row.title()).containsExactly("\u8bbe\u8ba1\u8bc4\u5ba1\u5355");
    assertThat(response.summary().totalRecords()).isEqualTo(1);
    assertThat(response.summary().totalProblemItems()).isEqualTo(1);
  }

  @Test
  void shouldApplyTitleContainsFilterWithTitleOnlySearchIndex() {
    reviewDataRecordService.createRecord(
        recordRequest("\u5f20\u4e09", "\u8bbe\u8ba1\u8bc4\u5ba1\u5355", LocalDate.of(2026, 4, 20), 20));
    reviewDataRecordService.createRecord(
        recordRequest("\u8bbe\u8ba1\u4eba", "\u9700\u6c42\u8bc4\u5ba1\u5355", LocalDate.of(2026, 4, 21), 20));

    List<String> titleSpellIndexes =
        jdbcTemplate.queryForList(
            "select title_search_spell from review_records order by title asc", String.class);
    assertThat(titleSpellIndexes).anySatisfy(value -> assertThat(value).contains("shejipingshendan"));

    ReviewDataRecordListResponse containsResponse =
        reviewDataRecordService.listRecords(
            new ReviewDataRecordQueryRequest(
                null, null, null, null, null, null, null, null,
                """
                {
                  "logic": "AND",
                  "conditions": [
                    {"fieldKey": "title", "operator": "contains", "value": "sheji"}
                  ]
                }
                """,
                1, 20, "title", "asc"));
    assertThat(containsResponse.records())
        .extracting(row -> row.title())
        .containsExactly("\u8bbe\u8ba1\u8bc4\u5ba1\u5355");

    ReviewDataRecordListResponse notContainsResponse =
        reviewDataRecordService.listRecords(
            new ReviewDataRecordQueryRequest(
                null, null, null, null, null, null, null, null,
                """
                {
                  "logic": "AND",
                  "conditions": [
                    {"fieldKey": "title", "operator": "notContains", "value": "sj"}
                  ]
                }
                """,
                1, 20, "title", "asc"));
    assertThat(notContainsResponse.records())
        .extracting(row -> row.title())
        .containsExactly("\u9700\u6c42\u8bc4\u5ba1\u5355");
  }

  private List<String> titlesForKeyword(String keyword) {
    ReviewDataRecordListResponse response =
        reviewDataRecordService.listRecords(
            new ReviewDataRecordQueryRequest(
                keyword, null, null, null, null, null, null, null, null, 1, 20, "title", "asc"));
    return response.records().stream().map(row -> row.title()).toList();
  }

  private ReviewDataRecordSaveRequest recordRequest(String owner, String title) {
    return recordRequest(owner, title, LocalDate.of(2026, 4, 29), 10);
  }

  private ReviewDataRecordSaveRequest recordRequest(
      String owner, String title, LocalDate reviewDate, Integer reviewScalePages) {
    return new ReviewDataRecordSaveRequest(
        "\u9879\u76eeA",
        title,
        "\u6a21\u5757A",
        "\u8bbe\u8ba1\u8bc4\u5ba1",
        reviewDate,
        owner,
        List.of(owner),
        reviewScalePages,
        "\u4ea7\u54c1A",
        "\u4f5c\u8005A",
        "V1.0");
  }

  private ReviewDataProblemItemSaveRequest problemRequest(String problemStatus) {
    return new ReviewDataProblemItemSaveRequest(
        "\u8bc4\u5ba1\u4ebaA",
        0.5,
        "\u4f1a\u8bae\u8bc4\u5ba1",
        "1.1",
        "\u5b8c\u6574\u6027",
        "\u95ee\u9898\u63cf\u8ff0",
        "\u5efa\u8bae\u65b9\u6848",
        "\u8d1f\u8d23\u4ebaA",
        "",
        problemStatus);
  }
}
