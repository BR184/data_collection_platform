package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class StatisticIssueDetailLinkContractTest {
  @Test
  void statisticDetailRecordsShouldUseIssueLinkSupportForIidValues() throws IOException {
    Path statisticsDir = Path.of("src/main/java/com/data/collection/platform/service/statistics");
    List<Path> javaFiles;
    try (Stream<Path> stream = Files.walk(statisticsDir)) {
      javaFiles =
          stream
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> !path.getFileName().toString().equals("StatisticIssueLinkSupport.java"))
              .toList();
    }

    List<String> offenders = new ArrayList<>();
    for (Path javaFile : javaFiles) {
      String source = Files.readString(javaFile);
      if (source.contains("put(\"iid\",") || source.contains(".put(\"iid\",")) {
        offenders.add(statisticsDir.relativize(javaFile).toString());
      }
    }

    assertThat(offenders)
        .as(
            "统计下钻详情的议题编号必须通过 StatisticIssueLinkSupport 输出 {label, href}，不能退回纯文本 iid")
        .isEmpty();
  }
}
