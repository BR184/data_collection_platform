package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.external.ExternalDatasetDescriptor;
import com.data.collection.platform.security.ExternalApiAuthenticationToken;
import com.data.collection.platform.security.ExternalApiClientPrincipal;
import com.data.collection.platform.service.external.ExternalDatasetProvider;
import com.data.collection.platform.service.external.ExternalDatasetRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

class ExternalDatasetControllerTest {
  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void catalogOnlyIncludesDatasetsAllowedForCallingClient() {
    ExternalDatasetProvider<String> allowed = provider("bi-dashboard");
    ExternalDatasetProvider<String> denied = provider("internal");
    ExternalDatasetRegistry registry = new ExternalDatasetRegistry(List.of(allowed, denied));
    authenticate(Set.of("bi-dashboard"));

    var response = new ExternalDatasetController(registry).catalog();

    assertThat(response.getData().datasets()).extracting(ExternalDatasetDescriptor::datasetKey)
        .containsExactly("bi-dashboard");
  }

  @Test
  void forbiddenDatasetReturns403AndUnknownDatasetReturns404() {
    ExternalDatasetRegistry registry = new ExternalDatasetRegistry(List.of(provider("bi-dashboard")));
    ExternalDatasetController controller = new ExternalDatasetController(registry);
    authenticate(Set.of());

    var forbidden = controller.read("bi-dashboard", Map.of());
    var missing = controller.read("missing", Map.of());

    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void providerValidationErrorReturns400() {
    ExternalDatasetProvider<String> provider = provider("bi-dashboard");
    when(provider.load(Map.of())).thenThrow(new IllegalArgumentException("productVersion 不能为空"));
    ExternalDatasetController controller = new ExternalDatasetController(
        new ExternalDatasetRegistry(List.of(provider)));
    authenticate(Set.of("bi-dashboard"));

    var response = controller.read("bi-dashboard", Map.of());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getMessage()).contains("productVersion");
  }

  private ExternalDatasetProvider<String> provider(String key) {
    ExternalDatasetProvider<String> provider = mock(ExternalDatasetProvider.class);
    when(provider.datasetKey()).thenReturn(key);
    when(provider.descriptor()).thenReturn(new ExternalDatasetDescriptor(
        key, key, "", "1.0", List.of(), List.of()));
    when(provider.load(Map.of())).thenReturn("payload");
    return provider;
  }

  private void authenticate(Set<String> datasets) {
    SecurityContextHolder.getContext().setAuthentication(
        new ExternalApiAuthenticationToken(new ExternalApiClientPrincipal("test", datasets)));
  }
}
