package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticColumnGroup;
import com.data.collection.platform.entity.statistics.StatisticColumnLeaf;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatisticBoardCsvSupportTest {
  @Test
  void shouldExportBoardRowsAsCsv() {
    StatisticBoardResponse response =
        new StatisticBoardResponse(
            new StatisticBoardDefinition(
                "board",
                "title",
                "description",
                "",
                "",
                "对象",
                List.of(),
                List.of(
                    new StatisticColumnGroup(
                        "group",
                        "分组",
                        List.of(
                            new StatisticColumnLeaf("count", "数量", true, "count"),
                            new StatisticColumnLeaf("owner", "负责人", false, "text")))),
                List.of(),
                10,
                "empty"),
            Map.of(),
            null,
            List.of(
                new StatisticRowData(
                    "row-a",
                    "模块A",
                    List.of(
                        new StatisticCellData("count", 2L, "2", true, null, Map.of()),
                        new StatisticCellData("owner", 0L, "张三", false, null, Map.of())))),
            new StatisticBoardMeta(LocalDateTime.now(), 1L, 1, 2, 1),
        null,
        null);

    String csv = StatisticBoardCsvSupport.export(response);

    assertThat(csv).contains("\"对象\",\"数量\",\"负责人\"");
    assertThat(csv).contains("\"模块A\",\"2\",\"张三\"");
  }
}
