package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.ReviewDataRecordListResponse;
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

  private List<String> titlesForKeyword(String keyword) {
    ReviewDataRecordListResponse response =
        reviewDataRecordService.listRecords(
            new ReviewDataRecordQueryRequest(
                keyword, null, null, null, null, null, null, null, null, 1, 20, "title", "asc"));
    return response.records().stream().map(row -> row.title()).toList();
  }

  private ReviewDataRecordSaveRequest recordRequest(String owner, String title) {
    return new ReviewDataRecordSaveRequest(
        "\u9879\u76eeA",
        title,
        "\u6a21\u5757A",
        "\u8bbe\u8ba1\u8bc4\u5ba1",
        LocalDate.of(2026, 4, 29),
        owner,
        List.of(owner),
        10,
        "\u4ea7\u54c1A",
        "\u4f5c\u8005A",
        "V1.0");
  }
}
