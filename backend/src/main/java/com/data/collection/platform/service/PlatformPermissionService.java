package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.entity.PermissionCatalogItemResponse;
import com.data.collection.platform.entity.PermissionRoleResponse;
import com.data.collection.platform.entity.PermissionSettingsResponse;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformPermissionService {
  private static final Set<String> MANAGED_ROLE_CODES = Set.of(
      "SUPER_ADMIN", "ADMIN", "DIRECT_MANAGER", "TREE_MANAGER", "NORMAL_USER");
  private final JdbcTemplate jdbcTemplate;

  public PlatformPermissionService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Set<String> permissionsForRoles(Collection<String> roleCodes) {
    List<String> roles = normalizeValues(roleCodes);
    if (roles.isEmpty()) {
      return Set.of();
    }
    String placeholders = String.join(",", roles.stream().map(ignored -> "?").toList());
    // order by 固定权限码顺序：distinct 无排序时顺序随查询计划漂移，响应契约需要稳定
    return new LinkedHashSet<>(jdbcTemplate.queryForList(
        "select distinct permission_code from platform_role_permissions "
            + "where role_code in (" + placeholders + ") order by permission_code",
        String.class,
        roles.toArray()));
  }

  public List<String> roleNames(Collection<String> roleCodes) {
    List<String> roles = normalizeValues(roleCodes);
    if (roles.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", roles.stream().map(ignored -> "?").toList());
    return jdbcTemplate.query(
        "select role_code, role_name from platform_ldap_roles where role_code in (" + placeholders + ") "
            + "order by role_name, role_code",
        (rs, rowNum) -> new RoleName(rs.getString("role_code"), rs.getString("role_name")),
        roles.toArray()).stream().map(RoleName::name).toList();
  }

  public AuthUserResponse refreshUserPermissions(AuthUserResponse user) {
    if (user == null || !user.authenticated()) {
      return AuthUserResponse.guest();
    }
    List<String> names = roleNames(user.roleCodes());
    return new AuthUserResponse(
        user.username(),
        user.displayName(),
        user.roleCodes(),
        names.isEmpty() ? user.roleNames() : names,
        permissionsForRoles(user.roleCodes()),
        true);
  }

  public boolean hasPermission(AuthUserResponse user, String permissionCode) {
    return user != null && user.authenticated() && permissionsForRoles(user.roleCodes()).contains(permissionCode);
  }

  public void requirePermission(AuthUserResponse user, String permissionCode) {
    if (!hasPermission(user, permissionCode)) {
      throw new AccessDeniedException("当前账号无权执行该操作");
    }
  }

  public PermissionSettingsResponse loadSettings() {
    List<PermissionCatalogItemResponse> permissions = jdbcTemplate.query(
        "select permission_code, permission_name, module_name, description, sort_order "
            + "from platform_permissions where enabled = true order by sort_order, permission_code",
        (rs, rowNum) -> new PermissionCatalogItemResponse(
            rs.getString("permission_code"),
            rs.getString("permission_name"),
            rs.getString("module_name"),
            rs.getString("description"),
            rs.getInt("sort_order")));
    List<PermissionRoleResponse> roles = jdbcTemplate.query(
        "select role_code, role_name, display_order from platform_ldap_roles "
            + "where role_code in ('SUPER_ADMIN','ADMIN','DIRECT_MANAGER','TREE_MANAGER','NORMAL_USER') "
            + "order by display_order, role_name, role_code",
        (rs, rowNum) -> new PermissionRoleResponse(
            rs.getString("role_code"),
            rs.getString("role_name"),
            rs.getInt("display_order"),
            permissionsForRoles(Set.of(rs.getString("role_code")))));
    return new PermissionSettingsResponse(permissions, roles, initialSyncCompleted());
  }

  @Transactional
  public void updateRolePermissions(String operatorUserId, String roleCode, Set<String> requestedCodes) {
    if (!MANAGED_ROLE_CODES.contains(roleCode)) {
      throw new BizException("LDAP 角色不在平台权限配置范围内");
    }
    Integer roleCount = jdbcTemplate.queryForObject(
        "select count(*) from platform_ldap_roles where role_code = ?",
        Integer.class,
        roleCode);
    if (roleCount == null || roleCount == 0) {
      throw new BizException("LDAP 角色尚未同步到本地身份镜像");
    }
    Set<String> codes = new LinkedHashSet<>(requestedCodes == null ? Set.of() : requestedCodes);
    Set<String> validCodes = new LinkedHashSet<>(jdbcTemplate.queryForList(
        "select permission_code from platform_permissions where enabled = true", String.class));
    if (!validCodes.containsAll(codes)) {
      throw new BizException("权限配置包含未启用或不存在的权限");
    }
    Set<String> before = permissionsForRoles(Set.of(roleCode));
    if (before.equals(codes)) {
      return;
    }
    jdbcTemplate.update("delete from platform_role_permissions where role_code = ?", roleCode);
    for (String code : codes) {
      jdbcTemplate.update(
          "insert into platform_role_permissions(role_code, permission_code) values (?, ?)",
          roleCode,
          code);
    }
    Integer remainingManagers = jdbcTemplate.queryForObject(
        "select count(*) from platform_role_permissions where permission_code = 'system.permission.manage'",
        Integer.class);
    if (remainingManagers == null || remainingManagers == 0) {
      throw new BizException("至少需要保留一个权限管理角色");
    }
    jdbcTemplate.update(
        "insert into platform_permission_audit_logs(operator_user_id, target_role_code, before_permissions, after_permissions) "
            + "values (?, ?, ?, ?)",
        operatorUserId,
        roleCode,
        String.join(",", before.stream().sorted().toList()),
        String.join(",", codes.stream().sorted().toList()));
  }

  @Transactional
  public void restoreDefaultPermissions(String operatorUserId) {
    Map<String, Set<String>> before = new LinkedHashMap<>();
    for (String roleCode : MANAGED_ROLE_CODES) {
      before.put(roleCode, permissionsForRoles(Set.of(roleCode)));
    }

    Integer defaultCount = jdbcTemplate.queryForObject(
        "select count(*) from platform_default_role_permissions",
        Integer.class);
    if (defaultCount == null || defaultCount == 0) {
      throw new BizException("默认权限尚未初始化");
    }

    String placeholders = String.join(",", MANAGED_ROLE_CODES.stream().map(ignored -> "?").toList());
    jdbcTemplate.update(
        "delete from platform_role_permissions where role_code in (" + placeholders + ")",
        MANAGED_ROLE_CODES.toArray());
    jdbcTemplate.update(
        "insert into platform_role_permissions(role_code, permission_code) "
            + "select role_code, permission_code from platform_default_role_permissions "
            + "where role_code in (" + placeholders + ")",
        MANAGED_ROLE_CODES.toArray());

    for (String roleCode : MANAGED_ROLE_CODES) {
      Set<String> after = permissionsForRoles(Set.of(roleCode));
      if (!before.get(roleCode).equals(after)) {
        jdbcTemplate.update(
            "insert into platform_permission_audit_logs(operator_user_id, target_role_code, before_permissions, after_permissions) "
                + "values (?, ?, ?, ?)",
            operatorUserId,
            roleCode,
            String.join(",", before.get(roleCode).stream().sorted().toList()),
            String.join(",", after.stream().sorted().toList()));
      }
    }
  }

  public boolean initialSyncCompleted() {
    String value = jdbcTemplate.queryForObject(
        "select state_value from platform_ldap_sync_state where state_key = 'initial_full_sync_completed'",
        String.class);
    return "true".equalsIgnoreCase(value);
  }

  public void markInitialSyncCompleted() {
    jdbcTemplate.update(
        "update platform_ldap_sync_state set state_value = 'true', updated_at = current_timestamp "
            + "where state_key = 'initial_full_sync_completed'");
  }

  private List<String> normalizeValues(Collection<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
  }

  private record RoleName(String code, String name) {}
}
