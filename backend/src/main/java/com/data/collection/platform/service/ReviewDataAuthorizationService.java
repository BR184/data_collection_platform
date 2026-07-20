package com.data.collection.platform.service;

import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.security.PlatformPermissionCodes;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class ReviewDataAuthorizationService {
  private final JdbcTemplate jdbcTemplate;
  private final PlatformPermissionService permissionService;

  public ReviewDataAuthorizationService(
      JdbcTemplate jdbcTemplate, PlatformPermissionService permissionService) {
    this.jdbcTemplate = jdbcTemplate;
    this.permissionService = permissionService;
  }

  public void requireCanDeleteRecord(AuthUserResponse user, Long recordId) {
    recordId = resolveRecordId(recordId);
    requireCanDelete(
        user,
        recordId,
        PlatformPermissionCodes.REVIEW_RECORD_DELETE_ANY,
        PlatformPermissionCodes.REVIEW_RECORD_DELETE_OWN,
        "review_records");
  }

  public void requireCanDeleteProblemItem(AuthUserResponse user, Long recordId, Long itemId) {
    if (permissionService.hasPermission(user, PlatformPermissionCodes.REVIEW_PROBLEM_DELETE_ANY)) {
      return;
    }
    if (!permissionService.hasPermission(user, PlatformPermissionCodes.REVIEW_PROBLEM_DELETE_OWN)) {
      throw new AccessDeniedException("当前账号无权删除评审问题");
    }
    itemId = resolveProblemItemId(itemId);
    recordId = resolveRecordId(recordId);
    String owner = jdbcTemplate.query(
        "select created_by from review_problem_items where id = ? and review_record_id = ? and deleted = false",
        rs -> rs.next() ? rs.getString(1) : null,
        itemId,
        recordId);
    if (owner == null || !Objects.equals(owner, user.username())) {
      throw new AccessDeniedException("只能删除本人创建的评审问题");
    }
  }

  private void requireCanDelete(
      AuthUserResponse user,
      Long recordId,
      String anyPermission,
      String ownPermission,
      String tableName) {
    if (permissionService.hasPermission(user, anyPermission)) {
      return;
    }
    if (!permissionService.hasPermission(user, ownPermission)) {
      throw new AccessDeniedException("当前账号无权删除评审数据");
    }
    recordId = resolveRecordId(recordId);
    String owner = jdbcTemplate.query(
        "select created_by from " + tableName + " where id = ? and deleted = false",
        rs -> rs.next() ? rs.getString(1) : null,
        recordId);
    if (owner == null || !Objects.equals(owner, user.username())) {
      throw new AccessDeniedException("只能删除本人创建的评审数据");
    }
  }

  private Long resolveRecordId(Long recordId) {
    if (recordId == null || recordId > 0) {
      return recordId;
    }
    Long materialized = jdbcTemplate.query(
        "select review_record_id from review_data_match_mode_edit_links "
            + "where match_mode_report_id in (?, ?) limit 1",
        rs -> rs.next() ? rs.getLong(1) : null,
        recordId,
        Math.abs(recordId));
    return materialized == null ? recordId : materialized;
  }

  private Long resolveProblemItemId(Long itemId) {
    if (itemId == null || itemId > 0) {
      return itemId;
    }
    Long materialized = jdbcTemplate.query(
        "select review_problem_item_id from review_data_match_mode_problem_edit_links "
            + "where match_mode_problem_id in (?, ?) limit 1",
        rs -> rs.next() ? rs.getLong(1) : null,
        itemId,
        Math.abs(itemId));
    return materialized == null ? itemId : materialized;
  }
}
