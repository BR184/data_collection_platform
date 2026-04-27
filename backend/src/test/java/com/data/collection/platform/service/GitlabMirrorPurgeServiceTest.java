package com.data.collection.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class GitlabMirrorPurgeServiceTest {

  @Mock
  private JdbcTemplate jdbcTemplate;

  @Mock
  private GitlabConfigService configService;

  @Mock
  private GitlabSyncLogService logService;

  @Mock
  private GitlabSyncTaskService taskService;

  @Mock
  private GitlabWhitelistService whitelistService;

  private GitlabMirrorPurgeService purgeService;

  @BeforeEach
  void setUp() {
    purgeService =
        new GitlabMirrorPurgeService(
            jdbcTemplate, configService, logService, taskService, whitelistService);
  }

  @Test
  void shouldRejectPurgeWhenActiveTaskExists() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfig()).thenReturn(config);
    when(taskService.hasActiveTask(1L)).thenReturn(true);

    assertThrows(BizException.class, () -> purgeService.purge(MirrorPurgeScope.MIRROR_DATA_ONLY));
    verify(jdbcTemplate, never()).execute(anyString());
  }

  @Test
  void shouldPurgeMirrorDataOnly() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(2L);
    when(configService.getConfig()).thenReturn(config);
    when(taskService.hasActiveTask(2L)).thenReturn(false);
    when(logService.start(2L, SyncType.PURGE, List.of(), "删除全部镜像数据")).thenReturn(21L);
    when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
        .thenReturn(List.of("ods_gitlab_issues", "ods_gitlab_merge_requests"));

    MirrorPurgeResult result = purgeService.purge(MirrorPurgeScope.MIRROR_DATA_ONLY);

    assertEquals(MirrorPurgeScope.MIRROR_DATA_ONLY, result.scope());
    assertEquals(2, result.droppedMirrorTables());
    assertEquals(2, result.truncatedTables());
    assertEquals(true, result.syncTimestampsReset());
    verify(configService).resetSyncTime(2L);
    verify(logService).finish(21L, SyncStatus.SUCCESS, "删除全部镜像数据", 4, 0);
    verify(jdbcTemplate).execute("drop table if exists \"ods_gitlab_issues\"");
    verify(jdbcTemplate).execute("drop table if exists \"ods_gitlab_merge_requests\"");
    verify(jdbcTemplate).execute("truncate table \"sys_table_registry\"");
    verify(jdbcTemplate).execute("truncate table \"gitlab_mirror_records\"");
  }

  @Test
  void shouldPurgeMirrorDataOutsideCurrentWhitelist() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(3L);
    when(configService.getConfig()).thenReturn(config);
    when(taskService.hasActiveTask(3L)).thenReturn(false);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(
        new TableWhitelistOption("issues", "Issues", "id", "updated_at", true)));
    when(logService.start(3L, SyncType.PURGE, List.of("issues"), "删除白名单外镜像数据")).thenReturn(31L);
    when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("pg_tables"), org.mockito.ArgumentMatchers.eq(String.class)))
        .thenReturn(List.of("ods_gitlab_issues", "ods_gitlab_merge_requests"));
    when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("select mirror_table_name"), org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of("ods_gitlab_issues"));

    MirrorPurgeResult result = purgeService.purge(MirrorPurgeScope.MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST);

    assertEquals(MirrorPurgeScope.MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST, result.scope());
    assertEquals(1, result.droppedMirrorTables());
    assertEquals(2, result.truncatedTables());
    assertEquals(false, result.syncTimestampsReset());
    verify(configService, never()).resetSyncTime(3L);
    verify(logService).finish(31L, SyncStatus.SUCCESS, "删除白名单外镜像数据", 3, 0);
    verify(jdbcTemplate).execute("drop table if exists \"ods_gitlab_merge_requests\"");
  }

  @Test
  void shouldWriteFailedLogWhenPurgeThrows() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(4L);
    when(configService.getConfig()).thenReturn(config);
    when(taskService.hasActiveTask(4L)).thenReturn(false);
    when(logService.start(4L, SyncType.PURGE, List.of(), "删除全部镜像数据")).thenReturn(41L);
    when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
        .thenReturn(List.of("ods_gitlab_issues"));
    doThrow(new BizException("drop failed"))
        .when(jdbcTemplate)
        .execute("drop table if exists \"ods_gitlab_issues\"");

    assertThrows(BizException.class, () -> purgeService.purge(MirrorPurgeScope.MIRROR_DATA_ONLY));

    verify(logService).finish(41L, SyncStatus.FAILED, "删除全部镜像数据失败: drop failed", 0, 0);
    verify(configService, never()).resetSyncTime(4L);
  }
}
