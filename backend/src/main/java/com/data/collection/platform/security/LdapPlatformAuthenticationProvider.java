package com.data.collection.platform.security;

import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.service.LdapPlatformClient;
import com.data.collection.platform.service.PlatformIdentityService;
import com.data.collection.platform.service.PlatformPermissionService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "platform.auth", name = "provider", havingValue = "ldap")
public class LdapPlatformAuthenticationProvider implements PlatformAuthenticationProvider {
  private static final Logger log = LoggerFactory.getLogger(LdapPlatformAuthenticationProvider.class);
  private final com.data.collection.platform.config.PlatformAuthProperties properties;
  private final LdapPlatformClient client;
  private final PlatformIdentityService identityService;
  private final PlatformPermissionService permissionService;

  public LdapPlatformAuthenticationProvider(
      com.data.collection.platform.config.PlatformAuthProperties properties,
      LdapPlatformClient client,
      PlatformIdentityService identityService,
      PlatformPermissionService permissionService) {
    this.properties = properties;
    this.client = client;
    this.identityService = identityService;
    this.permissionService = permissionService;
  }

  @Override
  public AuthUserResponse authenticate(String username, String password) {
    LdapPlatformClient.LdapLoginData login;
    LdapPlatformClient.LdapUserData user;
    try {
      login = client.login(username, password);
      user = client.currentUser(login.accessToken());
    } catch (LdapPlatformClient.LdapInvalidCredentialsException exception) {
      log.info("LDAP 拒绝登录凭据，未创建数据平台会话");
      return null;
    } catch (Exception exception) {
      log.warn("LDAP 认证服务不可用，未创建数据平台会话: {}", exception.getMessage());
      throw new AuthenticationServiceUnavailableException("LDAP 认证服务不可用", exception);
    }

    Set<String> roleCodes = user.roleCodes() == null || user.roleCodes().isEmpty()
        ? (login.roleCodes() == null ? Set.of() : login.roleCodes())
        : user.roleCodes();
    refreshRoleCatalog(login.accessToken());
    synchronizeDirectoryIfNeeded(login.accessToken());
    try {
      identityService.upsertUser(
          user.id(), user.userId(), user.realName(), user.email(), user.intranetEmail(), user.mobile(),
          user.employeeNo(), user.deptName(), user.deptCode(), user.jobTitle(), user.directLeaderRaw(),
          user.leaderRef(), user.accountStatus(), user.status(), user.employmentStatus(), user.ldapDn(), roleCodes);
      identityService.markLogin(user.userId());
    } catch (Exception exception) {
      log.warn("LDAP 用户镜像写入失败，未创建数据平台会话: {}", exception.getMessage());
      throw new AuthenticationServiceUnavailableException("认证身份镜像不可用", exception);
    }
    return new AuthUserResponse(
        user.userId(),
        user.realName() == null || user.realName().isBlank() ? user.userId() : user.realName(),
        roleCodes,
        permissionService.roleNames(roleCodes),
        permissionService.permissionsForRoles(roleCodes),
        true);
  }

  private void refreshRoleCatalog(String accessToken) {
    try {
      client.roles(accessToken).forEach(role -> identityService.upsertRole(
          role.id(), role.roleCode(), role.roleName(), role.builtIn(), role.status(), role.remark()));
    } catch (Exception exception) {
      log.info("当前 LDAP 账号无权刷新角色目录，继续使用本地角色镜像: {}", exception.getMessage());
    }
  }

  private void synchronizeDirectoryIfNeeded(String accessToken) {
    if (!properties.getLdap().isInitialSyncRequired() || permissionService.initialSyncCompleted()) {
      return;
    }
    try {
      client.users(accessToken).forEach(item -> identityService.upsertUser(
          item.id(), item.userId(), item.realName(), item.email(), item.intranetEmail(), item.mobile(),
          item.employeeNo(), item.deptName(), item.deptCode(), item.jobTitle(), item.directLeaderRaw(),
          item.leaderRef(), item.accountStatus(), item.status(), item.employmentStatus(), item.ldapDn(), item.roleCodes()));
      permissionService.markInitialSyncCompleted();
    } catch (Exception exception) {
      log.info("当前 LDAP 账号无权执行首次用户同步，保留待同步状态: {}", exception.getMessage());
    }
  }
}
