package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.entity.read.ReadPublicationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 页面读取上下文与“多域版本组合”的持久化入口。 */
@Repository
public class ReadViewRepository {
  private final JdbcTemplate jdbcTemplate;

  public ReadViewRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 登记页面读取上下文租约。 */
  public void insertViewContext(
      UUID viewId,
      String subject,
      String sessionId,
      String pageKey,
      String scopeKey,
      String mode,
      LocalDate businessDate,
      String ruleVersion,
      Instant expiresAt,
      Instant rebindDeadline,
      UUID clientViewId) {
    jdbcTemplate.update(
        """
        insert into read_view_contexts(
            id, subject, session_id, page_key, scope_key, mode, business_date, rule_version,
            expires_at, rebind_deadline, client_view_id)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        viewId,
        subject,
        sessionId,
        pageKey,
        scopeKey,
        mode,
        businessDate == null ? null : java.sql.Date.valueOf(businessDate),
        ruleVersion,
        Timestamp.from(expiresAt),
        rebindDeadline == null ? null : Timestamp.from(rebindDeadline),
        clientViewId);
  }

  /** 同一账号/会话的准备请求重放使用原身份；其它会话不能凭客户端 UUID 取到此视图。 */
  public Optional<ReadViewRow> findClientView(String subject, String sessionId, UUID clientViewId) {
    return jdbcTemplate.query("select * from read_view_contexts where subject = ? and session_id = ? and client_view_id = ?",
        (rs, row) -> mapViewRow(rs), subject, sessionId, clientViewId).stream().findFirst();
  }

  /** 串行化同一客户端准备请求；事务结束自动释放，不保留跨 HTTP 连接或全局应用锁。 */
  public void lockClientPreparation(String subject, String sessionId, UUID clientViewId) {
    jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
      try (java.sql.PreparedStatement statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        statement.setString(1, subject + "/" + sessionId + "/" + clientViewId);
        statement.execute();
      }
      return null;
    });
  }

  /** 冻结视图中的一个域版本。 */
  public void insertViewPublication(UUID viewId, UUID domainId, UUID publicationId) {
    jdbcTemplate.update(
        """
        insert into read_view_publications(view_id, domain_id, publication_id) values (?, ?, ?)
        """,
        viewId,
        domainId,
        publicationId);
  }

  /** 行级排他锁定视图，串行化续约与释放。 */
  public Optional<ReadViewRow> lockViewForUpdate(UUID viewId) {
    return queryView(
        "select * from read_view_contexts where id = ? for update", viewId);
  }

  /** 共享锁定视图，使回收不能在读取期间删除其引用。 */
  public Optional<ReadViewRow> lockViewForShare(UUID viewId) {
    return queryView(
        "select * from read_view_contexts where id = ? for key share", viewId);
  }

  /** 只读视图快照。 */
  public Optional<ReadViewRow> findView(UUID viewId) {
    return queryView("select * from read_view_contexts where id = ?", viewId);
  }

  private Optional<ReadViewRow> queryView(String sql, UUID viewId) {
    return jdbcTemplate
        .query(sql, (rs, rowNum) -> mapViewRow(rs), viewId)
        .stream()
        .findFirst();
  }

  private ReadViewRow mapViewRow(ResultSet rs) throws SQLException {
    java.sql.Date businessDate = rs.getDate("business_date");
    Timestamp rebindDeadline = rs.getTimestamp("rebind_deadline");
    return new ReadViewRow(
        rs.getObject("id", UUID.class),
        rs.getString("subject"),
        rs.getString("session_id"),
        rs.getString("page_key"),
        rs.getString("scope_key"),
        businessDate == null ? null : businessDate.toLocalDate(),
        rs.getString("rule_version"),
        rs.getTimestamp("expires_at").toInstant(),
        rebindDeadline == null ? null : rebindDeadline.toInstant());
  }

  /** 续约：延长租约并给出安全换版时限。 */
  public int extendLease(UUID viewId, Instant expiresAt, Instant rebindDeadline) {
    return jdbcTemplate.update(
        """
        update read_view_contexts
           set expires_at = ?, rebind_deadline = ?
         where id = ?
        """,
        Timestamp.from(expiresAt),
        rebindDeadline == null ? null : Timestamp.from(rebindDeadline),
        viewId);
  }

  /** 释放页面租约（丢包由租约自然过期收敛）。 */
  public int deleteView(UUID viewId) {
    return jdbcTemplate.update("delete from read_view_contexts where id = ?", viewId);
  }

  /** 视图冻结的域版本绑定。 */
  public List<ReadViewBinding> findBindings(UUID viewId) {
    return jdbcTemplate.query(
        """
        select d.producer_kind, d.source_instance, d.fact_type, d.table_key,
               p.id as publication_id, p.domain_id, p.epoch_id, p.sequence_no, p.data_as_of, p.state
          from read_view_publications vp
          join read_publication_domains d on d.id = vp.domain_id
          join read_publications p on p.id = vp.publication_id
         where vp.view_id = ?
         order by p.id
        """,
        (rs, rowNum) ->
            new ReadViewBinding(
                new ReadPublicationDomainKey(
                    ReadPublicationProducerKind.valueOf(rs.getString("producer_kind")),
                    rs.getString("source_instance"),
                    rs.getString("fact_type"),
                    rs.getString("table_key")),
                rs.getObject("domain_id", UUID.class),
                rs.getObject("publication_id", UUID.class),
                rs.getObject("epoch_id", UUID.class),
                rs.getLong("sequence_no"),
                rs.getTimestamp("data_as_of").toInstant(),
                ReadPublicationState.valueOf(rs.getString("state"))),
        viewId);
  }

  /** 页面读取上下文行。 */
  public record ReadViewRow(
      UUID viewId,
      String subject,
      String sessionId,
      String pageKey,
      String scopeKey,
      LocalDate businessDate,
      String ruleVersion,
      Instant expiresAt,
      Instant rebindDeadline) {}

  /** 视图冻结的单个域版本绑定。 */
  public record ReadViewBinding(
      ReadPublicationDomainKey domainKey,
      UUID domainId,
      UUID publicationId,
      UUID epochId,
      long sequence,
      Instant dataAsOf,
      ReadPublicationState state) {}
}
