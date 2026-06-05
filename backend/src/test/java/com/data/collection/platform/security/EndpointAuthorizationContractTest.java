package com.data.collection.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.controller.CodeReviewController;
import com.data.collection.platform.controller.CollectFormController;
import com.data.collection.platform.controller.CustomerIssueController;
import com.data.collection.platform.controller.DatabaseBrowserController;
import com.data.collection.platform.controller.FactBuildController;
import com.data.collection.platform.controller.GitlabSyncController;
import com.data.collection.platform.controller.QuestionMetricsController;
import com.data.collection.platform.controller.ReviewDataController;
import com.data.collection.platform.controller.StatisticBoardController;
import com.data.collection.platform.controller.TagGroupController;
import com.data.collection.platform.entity.AuthRole;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EndpointAuthorizationContractTest {
  @Test
  void shouldProtectSourceTableAndOperationalEndpointsWithAdminRole() {
    assertAdminRequired(DatabaseBrowserController.class);

    assertAdminRequired(GitlabSyncController.class, "status");
    assertAdminRequired(GitlabSyncController.class, "configs");
    assertAdminRequired(GitlabSyncController.class, "sourceHealth");
    assertAdminRequired(GitlabSyncController.class, "tableSyncDiagnostics");
    assertAdminRequired(GitlabSyncController.class, "systemHookRegistrationStatus");
    assertAdminRequired(GitlabSyncController.class, "whitelistOptions");

    assertAdminRequired(FactBuildController.class, "rebuildFacts");
    assertAdminRequired(FactBuildController.class, "getLatestBuildTask");
    assertAdminRequired(FactBuildController.class, "getIssueDiagnostics");
    assertAdminRequired(FactBuildController.class, "getIssueSourceReadiness");

    assertAdminRequired(StatisticBoardController.class, "refreshBoardRealtimeData");
    assertAdminRequired(CodeReviewController.class, "refreshIllegalRecords");
    assertAdminRequired(CodeReviewController.class, "refreshMultiBoard");
    assertAdminRequired(QuestionMetricsController.class, "refreshIssues");
    assertAdminRequired(QuestionMetricsController.class, "refreshIllegalRecords");
    assertAdminRequired(CustomerIssueController.class, "refreshRecords");
    assertAdminRequired(CustomerIssueController.class, "refreshIllegalRecords");
    assertAdminRequired(CollectFormController.class, "updateRecord");
    assertAdminRequired(TagGroupController.class, "reloadTagMappings");
  }

  private void assertAdminRequired(Class<?> controllerType) {
    RequireRole annotation = controllerType.getAnnotation(RequireRole.class);
    assertThat(annotation)
        .as("%s should require admin role", controllerType.getSimpleName())
        .isNotNull();
    assertThat(annotation.value()).contains(AuthRole.ADMIN);
  }

  private void assertAdminRequired(Class<?> controllerType, String methodName) {
    Method method =
        Arrays.stream(controllerType.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing method: " + controllerType.getName() + "#" + methodName));
    RequireRole annotation = method.getAnnotation(RequireRole.class);
    assertThat(annotation)
        .as("%s#%s should require admin role", controllerType.getSimpleName(), methodName)
        .isNotNull();
    assertThat(annotation.value()).contains(AuthRole.ADMIN);
  }
}
