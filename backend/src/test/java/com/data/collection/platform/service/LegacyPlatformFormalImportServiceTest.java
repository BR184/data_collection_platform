package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsResponse;
import com.data.collection.platform.entity.LegacyPlatformFormalImportRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class LegacyPlatformFormalImportServiceTest {
  private final CodeReviewMatchModeSyncService mysqlSyncService = mock(CodeReviewMatchModeSyncService.class);
  private final CodeReviewMatchModeMongoReviewSyncService mongoSyncService =
      mock(CodeReviewMatchModeMongoReviewSyncService.class);
  private final CodeReviewMatchModeConfigService configService = mock(CodeReviewMatchModeConfigService.class);
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

  @Test
  void staleSettingsVersionRejectsHandoverBeforeSourceSyncOrDatabaseWrites() {
    LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 21, 10, 30);
    when(configService.loadConfig()).thenReturn(config());
    when(configService.getResponse()).thenReturn(settings(updatedAt));
    LegacyPlatformFormalImportService service = service();

    assertThatThrownBy(() -> service.importToFormal(
        new LegacyPlatformFormalImportRequest(
            true,
            true,
            LegacyPlatformFormalImportService.CONFIRMATION_TEXT,
            updatedAt.minusSeconds(1)),
        "tester"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("设置已变化");

    verifyNoInteractions(mysqlSyncService, mongoSyncService, jdbcTemplate);
  }

  @Test
  void concurrentHandoverIsRejectedBeforeSourceSync() throws Exception {
    LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 21, 10, 30);
    when(configService.loadConfig()).thenReturn(config());
    when(configService.getResponse()).thenReturn(settings(updatedAt));
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getBoolean(1)).thenReturn(false);
    when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Object>>any()))
        .thenAnswer(invocation -> invocation.<ConnectionCallback<Object>>getArgument(0)
            .doInConnection(connection));
    LegacyPlatformFormalImportService service = service();

    assertThatThrownBy(() -> service.importToFormal(
        new LegacyPlatformFormalImportRequest(
            true,
            true,
            LegacyPlatformFormalImportService.CONFIRMATION_TEXT,
            updatedAt),
        "tester"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("正在执行");

    verifyNoInteractions(mysqlSyncService, mongoSyncService);
  }

  private LegacyPlatformFormalImportService service() {
    return new LegacyPlatformFormalImportService(
        mysqlSyncService,
        mongoSyncService,
        mock(CodeReviewMatchModeLegacyRefreshService.class),
        mock(ReviewDataMatchModeRecordRepository.class),
        mock(ReviewDataMatchModeMaterializeService.class),
        configService,
        mock(PageRecordSnapshotService.class),
        jdbcTemplate,
        mock(JsonUtils.class),
        mock(PlatformTransactionManager.class));
  }

  private CodeReviewMatchModeConfig config() {
    return new CodeReviewMatchModeConfig(
        "jdbc:mysql://legacy/cc",
        "jdbc:mysql://legacy/dgm",
        "reader",
        "secret",
        "spider_crowncad_data",
        "http://legacy",
        "http://legacy-dgm",
        List.of("spider_crowncad_data"),
        1000,
        "mongodb://legacy",
        "spider",
        List.of("reviewReport", "problemDetail", "description"),
        "reviewReport",
        "problemDetail",
        "compatibility",
        true);
  }

  private CodeReviewMatchModeDbSettingsResponse settings(LocalDateTime updatedAt) {
    return new CodeReviewMatchModeDbSettingsResponse(
        true,
        true,
        "legacy",
        3306,
        "gitlab_spider",
        "gitlab_spider_dgm",
        "reader",
        true,
        "spider_crowncad_data",
        "http://legacy",
        "http://legacy-dgm",
        List.of("spider_crowncad_data"),
        1000,
        true,
        "spider",
        List.of("reviewReport", "problemDetail", "description"),
        "reviewReport",
        "problemDetail",
        "compatibility",
        "IDLE",
        "",
        0,
        null,
        null,
        updatedAt);
  }
}
