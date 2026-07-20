package com.data.collection.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.controller.CodeReviewController;
import com.data.collection.platform.controller.CollectFormController;
import com.data.collection.platform.controller.CustomerIssueController;
import com.data.collection.platform.controller.DatabaseBrowserController;
import com.data.collection.platform.controller.FactBuildController;
import com.data.collection.platform.controller.GitlabSyncController;
import com.data.collection.platform.controller.QuestionMetricsController;
import com.data.collection.platform.controller.PermissionSettingsController;
import com.data.collection.platform.controller.StatisticBoardController;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EndpointAuthorizationContractTest {
  @Test
  void shouldProtectSourceTableAndOperationalEndpointsWithFineGrainedPermissions() {
    assertPermissionRequired(
        PermissionSettingsController.class, "restoreDefaultPermissions", PlatformPermissionCodes.SYSTEM_PERMISSION_MANAGE);
    assertPermissionRequired(
        DatabaseBrowserController.class, PlatformPermissionCodes.SYSTEM_DATABASE_VIEW);

    assertPermissionRequired(
        GitlabSyncController.class, "status", PlatformPermissionCodes.SYSTEM_MIRROR_VIEW);
    assertPermissionRequired(
        GitlabSyncController.class, "configs", PlatformPermissionCodes.SYSTEM_MIRROR_VIEW);
    assertPermissionRequired(
        GitlabSyncController.class, "sourceHealth", PlatformPermissionCodes.SYSTEM_MIRROR_VIEW);
    assertPermissionRequired(
        GitlabSyncController.class, "tableSyncDiagnostics", PlatformPermissionCodes.SYSTEM_MIRROR_VIEW);
    assertPermissionRequired(
        GitlabSyncController.class, "systemHookRegistrationStatus", PlatformPermissionCodes.SYSTEM_MIRROR_VIEW);
    assertPermissionRequired(
        GitlabSyncController.class, "whitelistOptions", PlatformPermissionCodes.SYSTEM_MIRROR_VIEW);

    assertPermissionRequired(FactBuildController.class, "rebuildFacts", PlatformPermissionCodes.SYSTEM_FACT_REBUILD);
    assertPermissionRequired(FactBuildController.class, "getLatestBuildTask", PlatformPermissionCodes.SYSTEM_FACT_REBUILD);
    assertPermissionRequired(FactBuildController.class, "getIssueDiagnostics", PlatformPermissionCodes.SYSTEM_FACT_REBUILD);
    assertPermissionRequired(FactBuildController.class, "getIssueSourceReadiness", PlatformPermissionCodes.SYSTEM_FACT_REBUILD);

    assertPermissionRequired(StatisticBoardController.class, "refreshBoardRealtimeData", PlatformPermissionCodes.BUSINESS_DATA_REFRESH);
    assertPermissionRequired(CodeReviewController.class, "refreshIllegalRecords", PlatformPermissionCodes.BUSINESS_DATA_REFRESH);
    assertPermissionRequired(CodeReviewController.class, "refreshMultiBoard", PlatformPermissionCodes.BUSINESS_DATA_REFRESH);
    assertPermissionRequired(QuestionMetricsController.class, "refreshIssues", PlatformPermissionCodes.BUSINESS_DATA_REFRESH);
    assertPermissionRequired(QuestionMetricsController.class, "refreshIllegalRecords", PlatformPermissionCodes.BUSINESS_DATA_REFRESH);
    assertPermissionRequired(CustomerIssueController.class, "refreshRecords", PlatformPermissionCodes.BUSINESS_DATA_REFRESH);
    assertPermissionRequired(CustomerIssueController.class, "refreshIllegalRecords", PlatformPermissionCodes.BUSINESS_DATA_REFRESH);
    assertPermissionRequired(CollectFormController.class, "updateRecord", "code_review.form.edit");
  }

  private void assertPermissionRequired(Class<?> controllerType, String permissionCode) {
    RequirePermission annotation = controllerType.getAnnotation(RequirePermission.class);
    assertThat(annotation)
        .as("%s should require %s", controllerType.getSimpleName(), permissionCode)
        .isNotNull();
    assertThat(annotation.value()).contains(permissionCode);
  }

  private void assertPermissionRequired(
      Class<?> controllerType, String methodName, String permissionCode) {
    Method method =
        Arrays.stream(controllerType.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing method: " + controllerType.getName() + "#" + methodName));
    RequirePermission annotation = method.getAnnotation(RequirePermission.class);
    if (annotation == null) {
      annotation = controllerType.getAnnotation(RequirePermission.class);
    }
    assertThat(annotation)
        .as("%s#%s should require %s", controllerType.getSimpleName(), methodName, permissionCode)
        .isNotNull();
    assertThat(annotation.value()).contains(permissionCode);
  }
}
