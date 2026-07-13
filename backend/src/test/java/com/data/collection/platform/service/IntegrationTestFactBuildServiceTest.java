package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.mapper.IntegrationTestFactMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IntegrationTestFactBuildServiceTest {

  @Test
  void rebuildByConfigIdResolvesAndValidatesTheRequestedConfiguration() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IntegrationTestFactMapper factMapper = mock(IntegrationTestFactMapper.class);
    ModuleDictionaryService moduleDictionaryService = mock(ModuleDictionaryService.class);
    GitlabSourceSchemaGuard sourceSchemaGuard = mock(GitlabSourceSchemaGuard.class);
    SqlQueryMonitor sqlQueryMonitor = mock(SqlQueryMonitor.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceInstance("secondary");
    when(configService.getConfigById(42L)).thenReturn(config);
    IllegalStateException schemaFailure = new IllegalStateException("stop after source resolution");
    org.mockito.Mockito.doThrow(schemaFailure)
        .when(sourceSchemaGuard)
        .verifyIntegrationTestSource(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    var service =
        new IntegrationTestFactBuildService(
            jdbcTemplate,
            factMapper,
            moduleDictionaryService,
            sourceSchemaGuard,
            sqlQueryMonitor,
            configService);

    assertThatThrownBy(() -> service.rebuildFacts(true, 42L)).isSameAs(schemaFailure);

    verify(configService).getConfigById(42L);
    verify(sourceSchemaGuard)
        .verifyIntegrationTestSource(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
  }
}
