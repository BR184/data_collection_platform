package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class GitlabMirrorTableStorageServiceTest {

  @Test
  void markRowsDeletedShouldSetMirrorDeletedWithLookupCondition() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, mock(JsonUtils.class));
    SourceTableSchema schema =
        new SourceTableSchema(
            "ods_gitlab_issues",
            List.of("id"),
            "updated_at",
            List.of(new SourceTableColumn("id", "bigint", false, 1)));
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(), eq(99L), eq(101L))).thenReturn(1);

    int updated = service.markRowsDeleted(schema, "id", 101L, 99L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).update(sqlCaptor.capture(), eq(99L), eq(101L));
    assertThat(updated).isEqualTo(1);
    assertThat(sqlCaptor.getValue()).contains("update \"ods_gitlab_issues\"");
    assertThat(sqlCaptor.getValue()).contains("mirror_deleted = true");
    assertThat(sqlCaptor.getValue()).contains("where \"id\" = ?");
  }
}
