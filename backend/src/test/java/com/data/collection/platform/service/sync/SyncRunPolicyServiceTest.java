package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunType;
import org.junit.jupiter.api.Test;

class SyncRunPolicyServiceTest {
  private final SyncRunPolicyService policyService = new SyncRunPolicyService();

  @Test
  void shouldUseSameMirrorScopeForIncrementalCompensationAndSystemHookRuns() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(12L);
    config.setSourceInstance("cc");

    String mirrorScope = "source:12:cc:mirror";

    assertThat(policyService.exclusiveScopeOf(config, SyncRunType.INCREMENTAL_SYNC)).isEqualTo(mirrorScope);
    assertThat(policyService.exclusiveScopeOf(config, SyncRunType.COMPENSATION_SCAN)).isEqualTo(mirrorScope);
    assertThat(policyService.exclusiveScopeOf(config, SyncRunType.FULL_COMPENSATION_SCAN)).isEqualTo(mirrorScope);
    assertThat(policyService.exclusiveScopeOf(config, SyncRunType.SYSTEM_HOOK)).isEqualTo(mirrorScope);
  }
}
