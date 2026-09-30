package com.data.collection.platform.security;

public final class PlatformPermissionCodes {
  public static final String BUSINESS_DATA_REFRESH = "business_data.refresh";
  // BI 看板按页面拆分：每个页面各自持有查看与下载权限，与统计板 view/export 成对范式一致。
  public static final String BI_DASHBOARD_REQUIREMENTS_VIEW = "bi.dashboard.requirements.view";
  public static final String BI_DASHBOARD_REQUIREMENTS_DOWNLOAD = "bi.dashboard.requirements.download";
  public static final String BI_DASHBOARD_DESIGN_VIEW = "bi.dashboard.design.view";
  public static final String BI_DASHBOARD_DESIGN_DOWNLOAD = "bi.dashboard.design.download";
  public static final String BI_DASHBOARD_CODING_VIEW = "bi.dashboard.coding.view";
  public static final String BI_DASHBOARD_CODING_DOWNLOAD = "bi.dashboard.coding.download";
  public static final String BI_DASHBOARD_UNIT_TEST_VIEW = "bi.dashboard.unit_test.view";
  public static final String BI_DASHBOARD_UNIT_TEST_DOWNLOAD = "bi.dashboard.unit_test.download";
  public static final String BI_DASHBOARD_INTEGRATION_TEST_VIEW = "bi.dashboard.integration_test.view";
  public static final String BI_DASHBOARD_INTEGRATION_TEST_DOWNLOAD = "bi.dashboard.integration_test.download";
  public static final String BI_DASHBOARD_SYSTEM_TEST_VIEW = "bi.dashboard.system_test.view";
  public static final String BI_DASHBOARD_SYSTEM_TEST_DOWNLOAD = "bi.dashboard.system_test.download";
  public static final String BI_DASHBOARD_CUSTOMER_ISSUES_VIEW = "bi.dashboard.customer_issues.view";
  public static final String BI_DASHBOARD_CUSTOMER_ISSUES_DOWNLOAD = "bi.dashboard.customer_issues.download";
  public static final String REVIEW_DATA_VIEW = "review.data.view";
  public static final String REVIEW_RECORD_CREATE = "review.record.create";
  public static final String REVIEW_RECORD_EDIT = "review.record.edit";
  public static final String REVIEW_RECORD_DELETE_ANY = "review.record.delete_any";
  public static final String REVIEW_RECORD_DELETE_OWN = "review.record.delete_own";
  public static final String REVIEW_PROBLEM_CREATE = "review.problem.create";
  public static final String REVIEW_PROBLEM_EDIT = "review.problem.edit";
  public static final String REVIEW_PROBLEM_DELETE_ANY = "review.problem.delete_any";
  public static final String REVIEW_PROBLEM_DELETE_OWN = "review.problem.delete_own";
  public static final String REVIEW_LEGACY_IMPORT = "review.legacy_import";
  public static final String SYSTEM_LABEL_GROUP_MANAGE = "system.label_group.manage";
  public static final String SYSTEM_TESTING_PHASE_MANAGE = "system.testing_phase.manage";
  public static final String SYSTEM_PERMISSION_VIEW = "system.permission.view";
  public static final String SYSTEM_PERMISSION_MANAGE = "system.permission.manage";
  public static final String SYSTEM_DROPDOWN_OPTION_VIEW = "system.dropdown_option.view";
  public static final String SYSTEM_DROPDOWN_OPTION_MANAGE = "system.dropdown_option.manage";
  public static final String SYSTEM_BACKUP_VIEW = "system.backup.view";
  public static final String SYSTEM_BACKUP_MANAGE = "system.backup.manage";
  public static final String SYSTEM_MIRROR_VIEW = "system.mirror.view";
  public static final String SYSTEM_MIRROR_CONFIG = "system.mirror.config";
  public static final String SYSTEM_MIRROR_SYNC = "system.mirror.sync";
  public static final String SYSTEM_MIRROR_PURGE = "system.mirror.purge";
  public static final String SYSTEM_FACT_REBUILD = "system.fact.rebuild";
  public static final String SYSTEM_MATCH_MODE_VIEW = "system.match_mode.view";
  public static final String SYSTEM_MATCH_MODE_CONFIG = "system.match_mode.config";
  public static final String SYSTEM_MATCH_MODE_SYNC = "system.match_mode.sync";
  public static final String SYSTEM_MATCH_MODE_FORMAL_IMPORT = "system.match_mode.formal_import";
  public static final String SYSTEM_DATABASE_VIEW = "system.database.view";
  public static final String SYSTEM_DATABASE_REFRESH = "system.database.refresh";

  private PlatformPermissionCodes() {}
}
