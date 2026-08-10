package com.data.collection.platform.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.domain.port.BiCurrentSourceVersionPort;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiDownloadAuthorizationServiceTest {
  @Test
  void authorizesOnlyRegisteredTemplateWithCurrentSourceVersion() {
    BiProductVersionScope scope = new BiProductVersionScope(
        10L, 9L, "CC2026R4", "CC2026R4", 1, List.of());
    BiProductVersionPort versions = mock(BiProductVersionPort.class);
    BiCurrentSourceVersionPort sourceVersions = mock(BiCurrentSourceVersionPort.class);
    when(versions.requireScope(10L)).thenReturn(scope);
    when(sourceVersions.current("system-test", scope)).thenReturn("issue-version-3");
    BiDownloadAuthorizationService service = new BiDownloadAuthorizationService(versions, sourceVersions);

    var result = service.authorize(new BiDownloadAuthorizationService.Request(
        10L, "system-test", "overlay-category-bar", "issue-version-3"));

    assertThat(result.authorized()).isTrue();
    assertThatThrownBy(() -> service.authorize(new BiDownloadAuthorizationService.Request(
        10L, "system-test", "coding-trend-combo", "issue-version-3")))
        .isInstanceOf(BizException.class).hasMessageContaining("不支持");
    assertThatThrownBy(() -> service.authorize(new BiDownloadAuthorizationService.Request(
        10L, "system-test", "overlay-category-bar", "issue-version-2")))
        .isInstanceOf(BizException.class).hasMessageContaining("刷新整页");
  }
}
