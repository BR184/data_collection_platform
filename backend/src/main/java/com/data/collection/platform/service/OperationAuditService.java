package com.data.collection.platform.service;

import com.data.collection.platform.entity.AuthUserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OperationAuditService {
  private final JdbcTemplate jdbcTemplate;

  public OperationAuditService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void record(
      AuthUserResponse user,
      String method,
      String path,
      String remoteAddress,
      int responseStatus,
      String errorMessage) {
    try {
      jdbcTemplate.update(
          """
              insert into operation_audit_logs
                (username, role, http_method, request_path, remote_address, response_status, error_message)
              values (?, ?, ?, ?, ?, ?, ?)
              """,
          user == null ? "guest" : user.username(),
          user == null ? "GUEST" : user.role().name(),
          method,
          path,
          remoteAddress == null ? "" : remoteAddress,
          responseStatus,
          errorMessage == null ? "" : errorMessage);
    } catch (Exception ex) {
      log.warn("Failed to write operation audit log for {} {}", method, path, ex);
    }
  }
}
