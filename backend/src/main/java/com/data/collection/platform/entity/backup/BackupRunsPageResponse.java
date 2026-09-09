package com.data.collection.platform.entity.backup;

import java.util.List;

/** 备份历史分页。 */
public record BackupRunsPageResponse(long total, int page, int size, List<BackupRunResponse> records) {}
