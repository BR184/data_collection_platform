package com.data.collection.platform.bi.application;

/** 延迟创建后由 BI Controller 使用的六页应用服务集合。 */
public record BiDashboardRuntime(
    BiVersionService versions,
    BiReviewPageService requirements,
    BiReviewPageService design,
    BiCodingPageService coding,
    BiCatTestPageService unitTest,
    BiCatTestPageService integrationTest,
    BiSystemTestPageService systemTest,
    BiDownloadAuthorizationService downloads,
    BiExcelExportService excel) {}
