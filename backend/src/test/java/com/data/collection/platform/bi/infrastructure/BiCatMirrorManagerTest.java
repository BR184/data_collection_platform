package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.SaveConfig;
import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.model.BiProductVersionOption;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogNode;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogProject;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.Config;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ScopeMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BiCatMirrorManagerTest {
  private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");

  private final BiCatMirrorRepository repository = mock(BiCatMirrorRepository.class);
  private final BiCatProductVersionAdapter productVersions =
      mock(BiCatProductVersionAdapter.class);
  private final ExecutorService executor = mock(ExecutorService.class);
  private BiCatMirrorManager manager;

  @BeforeEach
  void setUp() {
    manager = new BiCatMirrorManager(
        repository,
        productVersions,
        new BiCatProperties(),
        new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        executor);
  }

  @AfterEach
  void closeManager() {
    manager.close();
  }

  @Test
  void saveConfig_minutePrecisionTime_returnsCanonicalSecondPrecision() {
    SaveConfig command = new SaveConfig(
        true,
        "http://172.22.10.56:88",
        true,
        20,
        true,
        "02:00");

    var result = manager.saveConfig(command);

    assertThat(result.fullCompensationTime()).isEqualTo("02:00:00");
    ArgumentCaptor<Config> config = ArgumentCaptor.forClass(Config.class);
    verify(repository).saveConfig(config.capture(), eq(NOW));
    assertThat(config.getValue().fullCompensationTime()).isEqualTo(LocalTime.of(2, 0));
  }

  @Test
  void startFullSync_existingRun_rejectsDuplicateWithoutSubmittingTask() {
    when(repository.loadConfig()).thenReturn(config());
    when(repository.tryStartRun(any(), eq("FULL"), eq("MANUAL"), eq(NOW), any()))
        .thenReturn(false);

    var result = manager.startFullSync();

    assertThat(result.accepted()).isFalse();
    assertThat(result.runId()).isNull();
    assertThat(result.status()).isEqualTo("RUNNING");
    verify(executor, never()).execute(any());
  }

  @Test
  void settings_withoutSavedMapping_exposesDirectorySuggestionForUserConfirmation() {
    when(repository.loadConfig()).thenReturn(config());
    when(repository.loadMappings()).thenReturn(List.of());
    when(repository.loadPublishedCatalog()).thenReturn(Optional.of(catalog()));
    when(repository.recentRuns(10)).thenReturn(List.of());
    when(productVersions.catalog()).thenReturn(new BiProductVersionCatalog(
        10L,
        List.of(new BiProductVersionOption(10L, "CC2026R4", "CC 2026 R4", 1))));

    var settings = manager.settings();

    assertThat(settings.mappings()).isEmpty();
    assertThat(settings.mappingSuggestions()).singleElement().satisfies(suggestion -> {
      assertThat(suggestion.catProjectId()).isEqualTo("project-cc");
      assertThat(suggestion.catVersionId()).isEqualTo("version-r4");
      assertThat(suggestion.unitTestingPhaseId()).isEqualTo("unit-r4");
      assertThat(suggestion.integrationTestingPhaseId()).isEqualTo("integration-r4");
    });
  }

  @Test
  void saveMappingsKeepsOriginalDirectoryBusinessKeys() {
    when(repository.loadPublishedCatalog()).thenReturn(Optional.of(catalog()));
    when(productVersions.requireScope(10L))
        .thenReturn(scope(10L, "CC2027R1"));
    when(productVersions.requireScope(20L))
        .thenReturn(scope(20L, "CC2027R2"));

    manager.saveMappings(List.of(
        new BiCatMirrorManager.SaveMapping(10L, "project-cc", "version-r4", "unit-r4", "integration-r4"),
        new BiCatMirrorManager.SaveMapping(20L, "project-cc", "version-r4", "unit-r4", "integration-r4")));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScopeMapping>> mappings = ArgumentCaptor.forClass(List.class);
    verify(repository).replaceMappings(mappings.capture(), eq(NOW));
    assertThat(mappings.getValue()).extracting(ScopeMapping::productVersionKey)
        .containsExactly("CC2027R1", "CC2027R2");
  }

  private Config config() {
    return new Config(
        true,
        "http://172.22.10.56:88",
        true,
        20,
        true,
        LocalTime.of(2, 0));
  }

  private BiProductVersionScope scope(long id, String businessKey) {
    return new BiProductVersionScope(
        id,
        9L,
        businessKey,
        businessKey,
        (int) id,
        List.of(),
        List.of(businessKey),
        "catalog:" + businessKey);
  }

  private CatalogSnapshot catalog() {
    return new CatalogSnapshot(
        UUID.randomUUID(),
        NOW,
        List.of(new CatalogProject("project-cc", "CrownCAD", null, null, null, true)),
        List.of(
            node("VERSION", "version-r4", "CC2026R4", null, true),
            node("TEST_PHASE", "unit-r4", "单元测试", "version-r4", null),
            node("TEST_PHASE", "integration-r4", "集成测试", "version-r4", null)),
        List.of());
  }

  private CatalogNode node(
      String type,
      String id,
      String name,
      String versionId,
      Boolean currentVersion) {
    return new CatalogNode(
        "project-cc",
        type,
        id,
        versionId,
        name,
        null,
        null,
        null,
        currentVersion,
        null,
        null,
        null,
        versionId);
  }
}
