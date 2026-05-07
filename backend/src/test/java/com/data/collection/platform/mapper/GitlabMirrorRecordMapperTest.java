package com.data.collection.platform.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

class GitlabMirrorRecordMapperTest {

  @Test
  void upsertShouldKeepNewerMirrorRecordWhenSourceTimestampIsOlder() throws Exception {
    Insert insert =
        GitlabMirrorRecordMapper.class
            .getMethod("upsertBatch", java.util.List.class)
            .getAnnotation(Insert.class);

    String sql = String.join(" ", insert.value()).toLowerCase();

    assertThat(sql).contains("where excluded.updated_at_source is null");
    assertThat(sql).contains("gitlab_mirror_records.updated_at_source is null");
    assertThat(sql).contains("excluded.updated_at_source >= gitlab_mirror_records.updated_at_source");
  }
}
