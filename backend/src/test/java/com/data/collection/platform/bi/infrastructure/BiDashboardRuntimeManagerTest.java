package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.application.BiDashboardRuntime;
import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.common.exception.BizException;
import org.junit.jupiter.api.Test;

class BiDashboardRuntimeManagerTest {
  @Test
  void reusesRuntimeAfterSuccessfulLazyInitialization() {
    BiDashboardRuntime runtime = mock(BiDashboardRuntime.class);
    BiDashboardRuntimeFactory factory = mock(BiDashboardRuntimeFactory.class);
    when(factory.create()).thenReturn(runtime);
    BiDashboardRuntimeManager manager = new BiDashboardRuntimeManager(factory);

    assertThat(manager.runtime()).isSameAs(runtime);
    assertThat(manager.runtime()).isSameAs(runtime);
  }

  @Test
  void retriesInitializationAfterFailureWithoutCachingPartialRuntime() {
    BiDashboardRuntime runtime = mock(BiDashboardRuntime.class);
    BiDashboardRuntimeFactory factory = mock(BiDashboardRuntimeFactory.class);
    when(factory.create()).thenThrow(new IllegalStateException("broken")).thenReturn(runtime);
    BiDashboardRuntimeManager manager = new BiDashboardRuntimeManager(factory);

    assertThatThrownBy(manager::runtime).isInstanceOf(IllegalStateException.class);
    assertThat(manager.runtime()).isSameAs(runtime);
  }

  @Test
  void convertsOrdinaryPageFailureButPreservesBusinessValidation() {
    BiDashboardRuntime runtime = mock(BiDashboardRuntime.class);
    BiDashboardRuntimeFactory factory = mock(BiDashboardRuntimeFactory.class);
    when(factory.create()).thenReturn(runtime);
    BiDashboardRuntimeManager manager = new BiDashboardRuntimeManager(factory);

    var error = manager.page("coding", ignored -> {
      throw new IllegalStateException("query failed");
    });

    assertThat(error.status()).isEqualTo(BiDataStatus.ERROR);
    assertThatThrownBy(() -> manager.page("coding", ignored -> {
      throw new BizException("invalid scope");
    })).isInstanceOf(BizException.class).hasMessage("invalid scope");
  }
}
