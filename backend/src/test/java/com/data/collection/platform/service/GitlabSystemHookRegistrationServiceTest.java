package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSystemHookRegistrationServiceTest {
  private GitlabSystemHookRegistrationService service;

  @BeforeEach
  void setUp() {
    service = new GitlabSystemHookRegistrationService(new GitlabMirrorProperties(), new ObjectMapper());
  }

  @Test
  void directModeShouldReportAutomaticRegistrationUnsupported() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setSystemHookEnabled(true);
    config.setSystemHookProjectId(325L);

    var status = service.getStatus(config, "http://platform.example.com/api/gitlab-sync/system-hook");

    assertThat(status.supported()).isFalse();
    assertThat(status.configured()).isTrue();
    assertThat(status.registered()).isFalse();
    assertThat(status.projectId()).isEqualTo(325L);
    assertThat(status.message()).contains("手动注册 System Hook");
  }

  @Test
  void directModeShouldRejectAutomaticRegistration() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setSystemHookProjectId(325L);

    assertThatThrownBy(
            () -> service.ensureRegistered(config, "http://platform.example.com/api/gitlab-sync/system-hook"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("手动注册 System Hook");
  }
}
