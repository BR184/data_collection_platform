package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.CandidateReadContext;
import com.data.collection.platform.entity.read.FactReadQueryContext;
import com.data.collection.platform.entity.read.PublishedReadContext;
import java.util.List;

/**
 * 事实可见性 SQL 谓词单点。公开读取与候选读取共用同一套“代际 + 半开可见区间”规则。
 *
 * <p>已发布版本的契约示意（实际还会叠加来源、项目、软删除、权限范围与现有业务条件）：
 *
 * <pre>
 *   where fact.publication_epoch_id = :epoch
 *     and fact.visible_from is not null
 *     and fact.visible_from &lt;= :sequence
 *     and (fact.visible_to is null or :sequence &lt; fact.visible_to)
 * </pre>
 *
 * <p><b>统一入口</b>：查询器应调用 {@link #visibleFacts(String, FactReadQueryContext)} 取得该上下文可见事实的完整谓词，
 * 不要把“是否全量 / 是否首次候选”的判断散落到各业务查询。需要自行拼 UNION ALL 时，按下列两段合成：
 * {@code publishedOnBase(alias, ctx).and(candidateBaseExclusion(alias, ctx))} 为基线可见事实，
 * {@code candidateRevisions(alias, ctx)} 为候选新修订。首次与全量候选（{@link CandidateReadContext#opensNewEpoch()}）下
 * {@code publishedOnBase} 恒为假，因此基线（含旧代际未触及的根）不会被读入。
 */
public final class FactReadVisibility {

  private static final SqlFilter NEVER = new SqlFilter("false", List.of());

  private FactReadVisibility() {}

  /** 该上下文可见事实的完整谓词；查询器应优先使用本入口，而不是各自判断上下文类型。 */
  public static SqlFilter visibleFacts(String alias, FactReadQueryContext context) {
    if (context instanceof PublishedReadContext published) {
      return published(alias, published);
    }
    CandidateReadContext candidate = (CandidateReadContext) context;
    SqlFilter ownRevisions = candidateRevisions(alias, candidate);
    if (candidate.opensNewEpoch()) {
      return ownRevisions;
    }
    return publishedOnBase(alias, candidate)
        .and(candidateBaseExclusion(alias, candidate))
        .or(ownRevisions);
  }

  /**
   * “当前活动视图”谓词：只取每个业务根此刻对用户生效的那一条修订。
   *
   * <p>用于生产者读回既有事实（延期重算、单条刷新的根定位等）：候选修订（代际已置但尚未打开可见区间）
   * 与已被后续修订取代的历史修订都必须排除，否则一次重算会把全部历史整行复制进新修订。
   * 尚未被初始接管标记的存量行（代际为空）仍属当前活动视图，保证接管前后读到的口径一致。
   *
   * @param alias 事实表别名，空串表示不限定
   * @return 可直接拼接的 SQL 条件片段
   */
  public static String liveRevisionPredicate(String alias) {
    String prefix = prefix(alias);
    return "(" + prefix + "publication_epoch_id is null or (" + prefix + "visible_from is not null and exists ("
        + "select 1 from read_publications origin join read_publication_domains live_domain on live_domain.id = origin.domain_id"
        + " join read_publications live_point on live_point.id = live_domain.current_publication_id"
        + " where origin.id = " + prefix + "created_by_publication_id and live_point.state = 'PUBLISHED'"
        + " and live_point.epoch_id = " + prefix + "publication_epoch_id"
        + " and " + prefix + "visible_from <= live_point.sequence_no and (" + prefix
        + "visible_to is null or live_point.sequence_no < " + prefix + "visible_to))))";
  }

  /** 关系按同一父修订匹配；尚未初始接管的存量关系只能连接尚未接管的事实行。 */
  public static String issueMemberParentPredicate(String factAlias, String memberAlias) {
    String fact = prefix(factAlias);
    String member = prefix(memberAlias);
    return "(" + member + "issue_fact_revision_id = " + fact + "id or ("
        + fact + "publication_epoch_id is null and " + member + "issue_fact_revision_id is null))";
  }

  /** 已发布版本的可见性谓词。 */
  public static SqlFilter published(String alias, PublishedReadContext context) {
    String prefix = prefix(alias);
    return new SqlFilter(
        prefix
            + "publication_epoch_id = ? and "
            + prefix
            + "visible_from is not null and "
            + prefix
            + "visible_from <= ? and ("
            + prefix
            + "visible_to is null or ? < "
            + prefix
            + "visible_to)",
        List.of(context.epochId(), context.sequence(), context.sequence()));
  }

  /**
   * 候选读取基线部分的可见性谓词。
   *
   * <p>首次候选（无基线）与全量候选（开启新代际）下基线恒不可见，返回恒假谓词且不绑定任何参数，
   * 因此不会读到旧代际或空基线的数据，也不会因空基线产生空值绑定。
   */
  public static SqlFilter publishedOnBase(String alias, CandidateReadContext context) {
    if (context.opensNewEpoch()) {
      return NEVER;
    }
    String prefix = prefix(alias);
    return new SqlFilter(
        prefix
            + "publication_epoch_id = ? and "
            + prefix
            + "visible_from is not null and "
            + prefix
            + "visible_from <= ? and ("
            + prefix
            + "visible_to is null or ? < "
            + prefix
            + "visible_to)",
        List.of(context.baseEpochId(), context.baseSequence(), context.baseSequence()));
  }

  /**
   * 基线中排除候选触及的全部根（含删除根，删除根没有新修订因此从基线排除后即消失）。
   *
   * <p>排除条件携带完整业务身份（来源系统 / 来源实例 / 事实类型 / 项目），不同来源或项目的同号根不会互相排除。
   */
  public static SqlFilter candidateBaseExclusion(String alias, CandidateReadContext context) {
    String prefix = prefix(alias);
    String rootColumn = prefix + context.table().rootColumn();
    return new SqlFilter(
        "not exists (select 1 from read_publication_fact_roots r"
            + " where r.publication_id = ?"
            + " and r.source_system = "
            + prefix
            + "source_system"
            + " and r.source_instance = "
            + prefix
            + "source_instance"
            + " and r.fact_type = ?"
            + " and r.project_id = "
            + prefix
            + "project_id"
            + " and r.root_id = "
            + rootColumn
            + ")",
        List.of(context.publicationId(), context.table().factType().name()));
  }

  /** 候选本候选产生的新修订。 */
  public static SqlFilter candidateRevisions(String alias, CandidateReadContext context) {
    String prefix = prefix(alias);
    return new SqlFilter(
        prefix
            + "created_by_publication_id = ? and "
            + prefix
            + "publication_epoch_id = ?",
        List.of(context.publicationId(), context.epochId()));
  }

  private static String prefix(String alias) {
    return alias == null || alias.isBlank() ? "" : alias + ".";
  }
}
