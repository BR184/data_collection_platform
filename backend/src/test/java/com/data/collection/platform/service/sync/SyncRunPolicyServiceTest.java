package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunType;
import org.junit.jupiter.api.Test;

class SyncRunPolicyServiceTest {
  private final SyncRunPolicyService policyService = new SyncRunPolicyService();

  @Test
  void shouldUseDefaultMirrorScopeForActiveSynchronizationRuns() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(12L);
    config.setSourceInstance("cc");

    String mirrorScope = "source:12:default:mirror";

    assertThat(policyService.exclusiveScopeOf(config, SyncRunType.INCREMENTAL_SYNC)).isEqualTo(mirrorScope);
    assertThat(policyService.exclusiveScopeOf(config, SyncRunType.FULL_COMPENSATION_SCAN)).isEqualTo(mirrorScope);
  }

  @Test
  void test_compensation_api_type_maps_to_the_only_full_compensation_run() {
    assertThat(policyService.toRunType(SyncType.COMPENSATION))
        .isEqualTo(SyncRunType.FULL_COMPENSATION_SCAN);
  }
}
