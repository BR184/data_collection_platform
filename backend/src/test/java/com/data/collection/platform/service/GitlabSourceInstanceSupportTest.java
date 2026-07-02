package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitlabSourceInstanceSupportTest {

  @Test
  void shouldNormalizeSourceInstanceForStorageAndIdentifiers() {
    assertThat(GitlabSourceInstanceSupport.normalizeSourceInstance(null)).isEqualTo("default");
    assertThat(GitlabSourceInstanceSupport.normalizeSourceInstance("  CC  ")).isEqualTo("cc");
    assertThat(GitlabSourceInstanceSupport.normalizeSourceInstance("DGM-InnerNet")).isEqualTo("dgm_innernet");
  }

  @Test
  void shouldBuildSingleMirrorTableNameWithoutSourcePrefix() {
    assertThat(GitlabSourceInstanceSupport.buildMirrorTableName("issues"))
        .isEqualTo("ods_gitlab_issues");
    assertThat(GitlabSourceInstanceSupport.buildMirrorTableName("merge_requests"))
        .isEqualTo("ods_gitlab_merge_requests");
  }
}
