package com.data.collection.platform.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformIdentityService {
  private final JdbcTemplate jdbcTemplate;
  private final PlatformPermissionService permissionService;

  public PlatformIdentityService(JdbcTemplate jdbcTemplate, PlatformPermissionService permissionService) {
    this.jdbcTemplate = jdbcTemplate;
    this.permissionService = permissionService;
  }

  @Transactional
  public void upsertRole(
      Long ldapId, String roleCode, String roleName, Integer builtIn, Integer status, String remark) {
    jdbcTemplate.update(
        "insert into platform_ldap_roles(role_code, ldap_id, role_name, status, built_in, remark, source_synced_at, updated_at) "
            + "values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp) "
            + "on conflict (role_code) do update set ldap_id = excluded.ldap_id, role_name = excluded.role_name, "
            + "status = excluded.status, built_in = excluded.built_in, remark = excluded.remark, "
            + "source_synced_at = current_timestamp, updated_at = current_timestamp",
        roleCode,
        ldapId,
        roleName == null || roleName.isBlank() ? "未命名角色" : roleName,
        status,
        builtIn,
        remark);
  }

  @Transactional
  public void upsertUser(
      Long ldapId,
      String userId,
      String realName,
      String email,
      String intranetEmail,
      String mobile,
      String employeeNo,
      String deptName,
      String deptCode,
      String jobTitle,
      String directLeaderRaw,
      String leaderRef,
      String accountStatus,
      Integer status,
      String employmentStatus,
      String ldapDn,
      Collection<String> roleCodes) {
    jdbcTemplate.update(
        "insert into platform_ldap_users(user_id, ldap_id, real_name, email, intranet_email, mobile, employee_no, dept_name, dept_code, "
            + "job_title, direct_leader_raw, leader_ref, account_status, status, employment_status, ldap_dn, last_login_at, source_synced_at, updated_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp, current_timestamp) "
            + "on conflict (user_id) do update set ldap_id = excluded.ldap_id, real_name = excluded.real_name, email = excluded.email, "
            + "intranet_email = excluded.intranet_email, mobile = excluded.mobile, employee_no = excluded.employee_no, dept_name = excluded.dept_name, "
            + "dept_code = excluded.dept_code, job_title = excluded.job_title, direct_leader_raw = excluded.direct_leader_raw, leader_ref = excluded.leader_ref, "
            + "account_status = excluded.account_status, status = excluded.status, employment_status = excluded.employment_status, ldap_dn = excluded.ldap_dn, "
            + "last_login_at = current_timestamp, source_synced_at = current_timestamp, updated_at = current_timestamp",
        userId,
        ldapId,
        realName,
        email,
        intranetEmail,
        mobile,
        employeeNo,
        deptName,
        deptCode,
        jobTitle,
        directLeaderRaw,
        leaderRef,
        accountStatus,
        status,
        employmentStatus,
        ldapDn);
    jdbcTemplate.update("delete from platform_ldap_user_roles where user_id = ?", userId);
    if (roleCodes != null) {
      for (String roleCode : roleCodes.stream().filter(code -> code != null && !code.isBlank()).distinct().toList()) {
        jdbcTemplate.update(
            "insert into platform_ldap_user_roles(user_id, role_code) values (?, ?) on conflict do nothing",
            userId,
            roleCode);
      }
    }
  }

  public void markLogin(String userId) {
    jdbcTemplate.update(
        "update platform_ldap_users set last_login_at = current_timestamp, updated_at = current_timestamp where user_id = ?",
        userId);
  }

  public Set<String> rolesForUser(String userId) {
    return Set.copyOf(jdbcTemplate.queryForList(
        "select role_code from platform_ldap_user_roles where user_id = ? order by role_code",
        String.class,
        userId));
  }

  public String displayNameForUser(String userId, String fallback) {
    List<String> values = jdbcTemplate.queryForList(
        "select real_name from platform_ldap_users where user_id = ?", String.class, userId);
    return values.isEmpty() || values.get(0) == null || values.get(0).isBlank() ? fallback : values.get(0);
  }

  public PlatformPermissionService permissions() {
    return permissionService;
  }
}
