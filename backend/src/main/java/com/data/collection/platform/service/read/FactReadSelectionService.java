package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.FactReadQueryContext;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.PublishedReadContext;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 构造显式事实版本组合；候选的其它来源基线只捕获一次，并受发布依赖引用保护。 */
@Service
public class FactReadSelectionService {
  private final JdbcTemplate jdbcTemplate;
  private final ReadPublicationRepository repository;
  private final ReadPublicationService publicationService;
  private final com.data.collection.platform.service.BusinessDateProvider businessDateProvider;

  public FactReadSelectionService(JdbcTemplate jdbcTemplate, ReadPublicationRepository repository,
      ReadPublicationService publicationService, com.data.collection.platform.service.BusinessDateProvider businessDateProvider) {
    this.jdbcTemplate = jdbcTemplate;
    this.repository = repository;
    this.publicationService = publicationService;
    this.businessDateProvider = businessDateProvider;
  }

  /** 从已核验/持锁的公开视图构造组合；不接受客户端指定候选身份。 */
  public FactReadSelection fromView(ReadView view) {
    return new FactReadSelection(view.domains().values().stream()
        .filter(PublishedReadContext.class::isInstance).map(PublishedReadContext.class::cast)
        .map(FactReadQueryContext.class::cast).toList(), view.businessDate());
  }

  /**
   * 在候选域锁内冻结其它来源基线，随后所有范围任务复用相同组合。
   * 调用者先验证父运行和投影任务执行权，不能以最新来源代际替换已捕获输入。
   */
  @Transactional
  public FactReadSelection forCandidate(UUID publicationId) {
    ReadPublication candidate = repository.findPublication(publicationId).orElseThrow();
    repository.lockDomain(candidate.domainId());
    candidate = repository.lockPublication(publicationId).orElseThrow();
    if (candidate.state() != com.data.collection.platform.entity.read.ReadPublicationState.PROJECTING) {
      throw new IllegalStateException("只能为 PROJECTING 候选准备投影读取身份");
    }
    jdbcTemplate.update("update read_publications set read_business_date = coalesce(read_business_date, ?) where id = ?",
        java.sql.Date.valueOf(businessDateProvider.businessDate()), publicationId);
    java.time.LocalDate businessDate = jdbcTemplate.queryForObject("select read_business_date from read_publications where id = ?",
        java.time.LocalDate.class, publicationId);
    Boolean captured = jdbcTemplate.queryForObject(
        "select projection_input_captured_at is not null from read_publications where id = ?",
        Boolean.class, publicationId);
    String factType = repository.findDomain(candidate.domainId()).orElseThrow().key().factType();
    if (!Boolean.TRUE.equals(captured)) {
      List<UUID> baselines = jdbcTemplate.queryForList("""
          select p.id from read_publication_domains d join read_publications p on p.id = d.current_publication_id
           where d.producer_kind in ('GITLAB_FACT', 'COMPAT_REVIEW') and d.fact_type = ? and d.id <> ? and p.state = 'PUBLISHED'
           order by p.id for key share of p
          """, UUID.class, factType, candidate.domainId());
      for (UUID baseline : baselines) {
        jdbcTemplate.update("""
            insert into read_publication_dependencies(publication_id, domain_id, dependency_publication_id)
            select ?, domain_id, id from read_publications where id = ?
            """, publicationId, baseline);
      }
      jdbcTemplate.update("update read_publications set projection_input_captured_at = current_timestamp where id = ?",
          publicationId);
    }
    List<FactReadQueryContext> contexts = new ArrayList<>();
    contexts.add(publicationService.candidateContext(publicationId));
    contexts.addAll(jdbcTemplate.query("""
        select d.source_instance, d.fact_type, p.id, p.epoch_id, p.sequence_no, p.data_as_of
          from read_publication_dependencies dependency
          join read_publications p on p.id = dependency.dependency_publication_id
          join read_publication_domains d on d.id = dependency.domain_id
         where dependency.publication_id = ? and p.state = 'PUBLISHED' order by p.id
        """, (rs, row) -> new PublishedReadContext(rs.getString("source_instance"),
            PublicationFactTable.ofFactType(rs.getString("fact_type")), rs.getObject("epoch_id", UUID.class),
            rs.getLong("sequence_no"), rs.getObject("id", UUID.class), rs.getTimestamp("data_as_of").toInstant()),
        publicationId));
    Long required = jdbcTemplate.queryForObject("select count(*) from read_publication_dependencies where publication_id = ?",
        Long.class, publicationId);
    if (required == null || contexts.size() != required + 1) {
      throw new IllegalStateException("候选捕获的其它来源基线已不可读，必须重新构建投影");
    }
    return new FactReadSelection(contexts, businessDate);
  }
}
