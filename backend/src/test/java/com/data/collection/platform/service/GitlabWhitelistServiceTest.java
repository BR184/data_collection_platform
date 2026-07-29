package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceCursorStrategy;
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
        .thenReturn(List.of(new TableWhitelistOption(
            "issues", "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true)));

    List<TableWhitelistOption> options = whitelistService.resolveOptions(config);

    assertThat(options).extracting(TableWhitelistOption::tableName).containsExactly("issues");
  }

  @Test
  void allWhitelistModeShouldResolveToAllDiscoveredTables() {
    SourceMetadataInspector sourceMetadataInspector = mock(SourceMetadataInspector.class);
    GitlabWhitelistService whitelistService = new GitlabWhitelistService(sourceMetadataInspector);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setWhitelistMode(WhitelistMode.ALL);

    when(sourceMetadataInspector.discoverTables(eq(config), anyMap(), anyList()))
        .thenReturn(List.of(
            new TableWhitelistOption(
                "issues", "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true),
            new TableWhitelistOption(
                "audit_events", "audit_events", "id", "updated_at", SourceCursorStrategy.PRIMARY_KEY_KEYSET, false)));

    List<TableWhitelistOption> options = whitelistService.resolveOptions(config);

    assertThat(options).extracting(TableWhitelistOption::tableName)
        .containsExactly("issues", "audit_events");
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

  @Test
  void resolveOptionsShouldReuseCachedSourceMetadataForSameSource() {
    SourceMetadataInspector sourceMetadataInspector = mock(SourceMetadataInspector.class);
    GitlabWhitelistService whitelistService = new GitlabWhitelistService(sourceMetadataInspector);
    GitlabSyncConfig config = directConfig("cc", 15434);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);

    when(sourceMetadataInspector.discoverTables(eq(config), anyMap(), anyList()))
        .thenReturn(List.of(new TableWhitelistOption(
            "issues", "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true)));

    List<TableWhitelistOption> first = whitelistService.resolveOptions(config);
    List<TableWhitelistOption> second = whitelistService.resolveOptions(config);

    assertThat(first).extracting(TableWhitelistOption::tableName).containsExactly("issues");
    assertThat(second).extracting(TableWhitelistOption::tableName).containsExactly("issues");
    verify(sourceMetadataInspector, times(1)).discoverTables(eq(config), anyMap(), anyList());
  }

  @Test
  void resolveOptionsShouldCacheMetadataPerSourceSignature() {
    SourceMetadataInspector sourceMetadataInspector = mock(SourceMetadataInspector.class);
    GitlabWhitelistService whitelistService = new GitlabWhitelistService(sourceMetadataInspector);
    GitlabSyncConfig ccConfig = directConfig("cc", 15434);
    GitlabSyncConfig dgmConfig = directConfig("dgm", 15435);

    when(sourceMetadataInspector.discoverTables(eq(ccConfig), anyMap(), anyList()))
        .thenReturn(List.of(new TableWhitelistOption(
            "issues", "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true)));
    when(sourceMetadataInspector.discoverTables(eq(dgmConfig), anyMap(), anyList()))
        .thenReturn(List.of(new TableWhitelistOption(
            "merge_requests",
            "merge_requests",
            "id",
            "updated_at",
            SourceCursorStrategy.PRIMARY_KEY_KEYSET,
            true)));

    assertThat(whitelistService.resolveOptions(ccConfig)).extracting(TableWhitelistOption::tableName).containsExactly("issues");
    assertThat(whitelistService.resolveOptions(dgmConfig)).extracting(TableWhitelistOption::tableName)
        .containsExactly("merge_requests");
    assertThat(whitelistService.resolveOptions(ccConfig)).extracting(TableWhitelistOption::tableName).containsExactly("issues");

    verify(sourceMetadataInspector, times(1)).discoverTables(eq(ccConfig), anyMap(), anyList());
    verify(sourceMetadataInspector, times(1)).discoverTables(eq(dgmConfig), anyMap(), anyList());
  }

  private GitlabSyncConfig directConfig(String sourceInstance, int port) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceInstance(sourceInstance);
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost("localhost");
    config.setDbPort(port);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    return config;
  }
}
