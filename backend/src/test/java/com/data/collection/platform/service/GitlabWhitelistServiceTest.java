package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabWhitelistServiceTest {

  @Test
  void customWhitelistShouldIgnoreTablesNotDiscoveredFromSource() {
    SourceMetadataInspector sourceMetadataInspector = mock(SourceMetadataInspector.class);
    GitlabWhitelistService whitelistService = new GitlabWhitelistService(sourceMetadataInspector);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(List.of("issues", "unknown_table"));

    when(sourceMetadataInspector.discoverTables(eq(config), anyMap(), anyList()))
        .thenReturn(List.of(new TableWhitelistOption("issues", "issues", "id", "updated_at", true)));

    List<TableWhitelistOption> options = whitelistService.resolveOptions(config);

    assertThat(options).extracting(TableWhitelistOption::tableName).containsExactly("issues");
  }

  @Test
  void listOptionsShouldKeepFallbackForSettingsUiWhenDiscoveryFails() {
    SourceMetadataInspector sourceMetadataInspector = mock(SourceMetadataInspector.class);
    GitlabWhitelistService whitelistService = new GitlabWhitelistService(sourceMetadataInspector);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);

    when(sourceMetadataInspector.discoverTables(eq(config), anyMap(), anyList()))
        .thenThrow(new BizException("metadata denied"));

    List<TableWhitelistOption> options = whitelistService.listOptions(config);

    assertThat(options).isNotEmpty();
    assertThat(options).extracting(TableWhitelistOption::tableName).contains("issues");
  }

  @Test
  void listOptionsStrictShouldSurfaceDiscoveryFailureForDiagnostics() {
    SourceMetadataInspector sourceMetadataInspector = mock(SourceMetadataInspector.class);
    GitlabWhitelistService whitelistService = new GitlabWhitelistService(sourceMetadataInspector);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);

    when(sourceMetadataInspector.discoverTables(eq(config), anyMap(), anyList()))
        .thenThrow(new BizException("metadata denied"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> whitelistService.listOptionsStrict(config))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("metadata denied");
  }
}
