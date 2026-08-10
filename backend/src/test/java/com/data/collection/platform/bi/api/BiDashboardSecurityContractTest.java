package com.data.collection.platform.bi.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class BiDashboardSecurityContractTest {
  @Test
  void requiresViewForPagesAndViewPlusDownloadForPngAuthorization() throws Exception {
    RequirePermission controllerPermission = BiDashboardController.class.getAnnotation(RequirePermission.class);
    Method download = BiDashboardController.class.getMethod(
        "authorizeDownload", BiDownloadAuthorizeRequest.class);
    RequirePermission downloadPermission = download.getAnnotation(RequirePermission.class);

    assertThat(controllerPermission.value()).containsExactly(PlatformPermissionCodes.BI_DASHBOARD_VIEW);
    assertThat(downloadPermission.requireAll()).isTrue();
    assertThat(downloadPermission.value()).containsExactlyInAnyOrder(
        PlatformPermissionCodes.BI_DASHBOARD_VIEW,
        PlatformPermissionCodes.BI_DASHBOARD_DOWNLOAD);
  }
}
