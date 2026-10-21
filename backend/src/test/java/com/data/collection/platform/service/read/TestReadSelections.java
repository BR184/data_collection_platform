package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.PublishedReadContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 纯计算单测的明确完整版本身份；不认证数据库产物，真库测试必须取得实际发布身份。 */
public final class TestReadSelections {
  private TestReadSelections() {}

  /** 提供三个事实族的固定来源身份，让业务公式单测显式携带版本而不连接数据库。 */
  public static FactReadSelection all() {
    return new FactReadSelection(List.of(
        context(PublicationFactTable.ISSUE, 1), context(PublicationFactTable.MERGE_REQUEST, 2),
        context(PublicationFactTable.INTEGRATION_TEST, 3)), java.time.LocalDate.of(2026, 10, 13));
  }

  /** 纯计算测试的正式表单及兼容来源组合，不作为数据库完整性证明。 */
  public static com.data.collection.platform.entity.read.DatasetReadSelection datasets() {
    return new com.data.collection.platform.entity.read.DatasetReadSelection(List.of(
        new com.data.collection.platform.entity.read.PublishedDatasetReadContext("default", "formal-records",
            new UUID(0, 20), 1, new UUID(0, 30), Instant.parse("2026-10-13T00:00:00Z"))),
        all(), all().businessDate());
  }

  private static PublishedReadContext context(PublicationFactTable table, long identity) {
    return new PublishedReadContext("default", table, new UUID(0, identity + 10), 1,
        new UUID(0, identity), Instant.parse("2026-10-13T00:00:00Z"));
  }
}
