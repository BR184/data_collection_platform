package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceMetadataInspectorTest {
  @Test
  void shouldDelegateTableDiscoveryToExternalDbService() {
    GitlabExternalDbService externalDbService = mock(GitlabExternalDbService.class);
    SourceMetadataInspector inspector = new SourceMetadataInspector(externalDbService);
    GitlabSyncConfig config = new GitlabSyncConfig();
    Map<String, String> labels = Map.of("issues", "Issues");
    List<String> recommended = List.of("issues");
    List<TableWhitelistOption> expected =
        List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));
    when(externalDbService.discoverTables(config, labels, recommended)).thenReturn(expected);

    List<TableWhitelistOption> actual = inspector.discoverTables(config, labels, recommended);

    assertThat(actual).isEqualTo(expected);
    verify(externalDbService).discoverTables(config, labels, recommended);
  }

  @Test
  void shouldDelegateSchemaDiscoveryToExternalDbService() {
    GitlabExternalDbService externalDbService = mock(GitlabExternalDbService.class);
    SourceMetadataInspector inspector = new SourceMetadataInspector(externalDbService);
    GitlabSyncConfig config = new GitlabSyncConfig();
    TableWhitelistOption option = new TableWhitelistOption("issues", "Issues", "id", "updated_at", true);
    SourceTableSchema expected =
        new SourceTableSchema(
            "issues",
            List.of("id"),
            "updated_at",
            List.of(new SourceTableColumn("id", "bigint", false, 1)));
    when(externalDbService.discoverTableSchema(config, option)).thenReturn(expected);

    SourceTableSchema actual = inspector.discoverTableSchema(config, option);

    assertThat(actual).isEqualTo(expected);
    verify(externalDbService).discoverTableSchema(config, option);
  }
}
