package com.data.collection.platform.bi.domain.source;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiTestQualityPageData;

/** CAT 页面结果、来源完整性和可用来源身份。 */
public record BiCatTestSource(
    BiDataStatus status,
    String sourceVersion,
    String snapshotId,
    String message,
    BiTestQualityPageData data) {}
