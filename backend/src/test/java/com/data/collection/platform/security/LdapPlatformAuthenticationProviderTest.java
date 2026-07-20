package com.data.collection.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.PlatformAuthProperties;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.service.LdapPlatformClient;
import com.data.collection.platform.service.PlatformIdentityService;
import com.data.collection.platform.service.PlatformPermissionService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LdapPlatformAuthenticationProviderTest {
  @Test
  void shouldCreateSessionWhenDirectoryEnrichmentEndpointsAreNotAllowed() {
    PlatformAuthProperties properties = new PlatformAuthProperties();
    properties.getLdap().setInitialSyncRequired(true);
    LdapPlatformClient client = Mockito.mock(LdapPlatformClient.class);
    PlatformIdentityService identityService = Mockito.mock(PlatformIdentityService.class);
    PlatformPermissionService permissionService = Mockito.mock(PlatformPermissionService.class);
    when(permissionService.initialSyncCompleted()).thenReturn(false);
    when(permissionService.roleNames(Set.of("NORMAL_USER"))).thenReturn(List.of("普通用户"));
    when(permissionService.permissionsForRoles(Set.of("NORMAL_USER")))
        .thenReturn(Set.of("quality.rd.view"));
    when(client.login("alice", "secret"))
        .thenReturn(new LdapPlatformClient.LdapLoginData(
            7L, "alice", Set.of("NORMAL_USER"), "ldap-token", Instant.now()));
    when(client.currentUser("ldap-token"))
        .thenReturn(new LdapPlatformClient.LdapUserData(
            7L, "alice", "Alice", null, null, null, null, null, null, null,
            null, null, "ACTIVE", 1, "在职", null, Set.of("NORMAL_USER")));
    when(client.roles("ldap-token"))
        .thenThrow(new LdapPlatformClient.LdapPlatformClientException("无权读取角色目录"));
    when(client.users("ldap-token"))
        .thenThrow(new LdapPlatformClient.LdapPlatformClientException("无权读取用户目录"));

    AuthUserResponse result = new LdapPlatformAuthenticationProvider(
        properties, client, identityService, permissionService).authenticate("alice", "secret");

    assertThat(result.authenticated()).isTrue();
    assertThat(result.username()).isEqualTo("alice");
    assertThat(result.roleNames()).containsExactly("普通用户");
    assertThat(result.permissions()).containsExactly("quality.rd.view");
    verify(identityService).markLogin("alice");
  }

  @Test
  void shouldNotCreateSessionWhenLdapAuthenticationFails() {
    PlatformAuthProperties properties = new PlatformAuthProperties();
    LdapPlatformClient client = Mockito.mock(LdapPlatformClient.class);
    PlatformIdentityService identityService = Mockito.mock(PlatformIdentityService.class);
    PlatformPermissionService permissionService = Mockito.mock(PlatformPermissionService.class);
    when(client.login("alice", "bad"))
        .thenThrow(new LdapPlatformClient.LdapPlatformClientException("账号或密码错误"));

    AuthUserResponse result = new LdapPlatformAuthenticationProvider(
        properties, client, identityService, permissionService).authenticate("alice", "bad");

    assertThat(result).isNull();
    verify(client, never()).currentUser(any());
    verify(identityService, never()).markLogin(any());
  }
}
