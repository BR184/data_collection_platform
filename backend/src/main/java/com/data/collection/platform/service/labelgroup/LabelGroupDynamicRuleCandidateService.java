package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValueResponse;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupDynamicRuleCandidateService {
  private static final String SOURCE_DYNAMIC_RULE = "DYNAMIC_RULE";

  private final LabelGroupDynamicRuleCatalogService catalogService;
  private final NamedParameterJdbcTemplate jdbcTemplate;

  public LabelGroupDynamicRuleCandidateService(
      LabelGroupDynamicRuleCatalogService catalogService,
      NamedParameterJdbcTemplate jdbcTemplate) {
    this.catalogService = catalogService;
    this.jdbcTemplate = jdbcTemplate;
  }

  public LabelValuePageResponse listCandidates(
      String sourceKey,
      String fieldKey,
      String keyword,
      int page,
      int size) {
    LabelGroupDynamicRuleCatalogService.DynamicRuleSourceDefinition source = catalogService.requireSource(sourceKey);
    LabelGroupDynamicRuleCatalogService.DynamicRuleFieldDefinition field = catalogService.requireField(sourceKey, fieldKey);
    if (!field.filterSupported()) {
      throw new BizException("字段不支持过滤候选值：" + field.name());
    }
    int safePage = Math.max(1, page);
    int safeSize = Math.min(100, Math.max(1, size));
    return switch (field.candidateMode()) {
      case LabelGroupDynamicRuleCatalogService.CANDIDATE_STATIC ->
          staticCandidates(field, keyword, safePage, safeSize);
      case LabelGroupDynamicRuleCatalogService.CANDIDATE_DISTINCT ->
          distinctCandidates(source, field, keyword, safePage, safeSize);
      default -> new LabelValuePageResponse(List.of(), 0, safePage, safeSize);
    };
  }

  private LabelValuePageResponse staticCandidates(
      LabelGroupDynamicRuleCatalogService.DynamicRuleFieldDefinition field,
      String keyword,
      int page,
      int size) {
    List<LabelValueResponse> values = field.staticCandidates().stream()
        .filter(option -> matchesKeyword(option, keyword))
        .map(option -> toResponse(option, field))
        .sorted(Comparator.comparing(LabelValueResponse::label, String::compareToIgnoreCase))
        .toList();
    return page(values, page, size);
  }

  private LabelValuePageResponse distinctCandidates(
      LabelGroupDynamicRuleCatalogService.DynamicRuleSourceDefinition source,
      LabelGroupDynamicRuleCatalogService.DynamicRuleFieldDefinition field,
      String keyword,
      int page,
      int size) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("limit", size)
        .addValue("offset", (page - 1) * size);
    StringBuilder where = new StringBuilder("""
        where %1$s is not null
          and btrim(cast(%1$s as text)) <> ''
          and btrim(cast(%1$s as text)) not like '未设定%%'
          and btrim(cast(%1$s as text)) not like '未标注%%'
          and btrim(cast(%1$s as text)) <> 'GitLab接口报错'
        """.formatted(field.columnName()));
    String normalizedKeyword = TextQuerySupport.trimToNull(keyword);
    if (normalizedKeyword != null) {
      where.append(" and lower(cast(").append(field.columnName()).append(" as text)) like :keyword");
      params.addValue("keyword", "%" + normalizedKeyword.toLowerCase(java.util.Locale.ROOT) + "%");
    }

    String valueExpression = "btrim(cast(" + field.columnName() + " as text))";
    String countSql = "select count(*) from (select distinct " + valueExpression
        + " as value from " + source.tableName() + " " + where + ") candidate_values";
    long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
    String dataSql = "select distinct " + valueExpression + " as value from "
        + source.tableName() + " " + where + " order by value asc limit :limit offset :offset";
    List<LabelValueResponse> values = jdbcTemplate.query(
        dataSql,
        params,
        (rs, rowNum) -> {
          String value = rs.getString("value");
          return new LabelValueResponse(value, value, valueKind(field), SOURCE_DYNAMIC_RULE, 0L);
        });
    return new LabelValuePageResponse(values, total, page, size);
  }

  private LabelValuePageResponse page(List<LabelValueResponse> values, int page, int size) {
    int from = Math.min((page - 1) * size, values.size());
    int to = Math.min(from + size, values.size());
    return new LabelValuePageResponse(values.subList(from, to), values.size(), page, size);
  }

  private boolean matchesKeyword(OptionItemResponse option, String keyword) {
    return TextQuerySupport.containsAbstractSearch(option.label(), keyword)
        || TextQuerySupport.containsAbstractSearch(option.value(), keyword);
  }

  private LabelValueResponse toResponse(
      OptionItemResponse option,
      LabelGroupDynamicRuleCatalogService.DynamicRuleFieldDefinition field) {
    return new LabelValueResponse(option.value(), option.label(), valueKind(field), SOURCE_DYNAMIC_RULE, 0L);
  }

  private LabelValueKind valueKind(LabelGroupDynamicRuleCatalogService.DynamicRuleFieldDefinition field) {
    if (LabelGroupDynamicRuleCatalogService.CANDIDATE_STATIC.equals(field.candidateMode())) {
      return LabelValueKind.ENUM_KEY;
    }
    if ("targetBranch".equals(field.key())) {
      return LabelValueKind.BRANCH_NAME;
    }
    return LabelValueKind.STRING_LITERAL;
  }
}
