package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CodeReviewIllegalRecordSourceLoader {
  private final MergeRequestFactQueryService mergeRequestFactQueryService;
  private final CodeReviewIllegalRecordRowMapper rowMapper;

  public CodeReviewIllegalRecordSourceLoader(
      MergeRequestFactQueryService mergeRequestFactQueryService,
      CodeReviewIllegalRecordRowMapper rowMapper) {
    this.mergeRequestFactQueryService = mergeRequestFactQueryService;
    this.rowMapper = rowMapper;
  }

  public List<CodeReviewIllegalRecordSource> loadSources(Map<String, String> filters) {
    try {
      return mergeRequestFactQueryService.query(
          CodeReviewIllegalRecordSqlQueryBuilder.factSql(), filters, rowMapper::mapFactSource);
    } catch (DataAccessException exception) {
      log.warn("Failed to load merge request facts", exception);
      return List.of();
    }
  }

  public CodeReviewIllegalRecordFilterOptionValues loadFilterOptions(
      CodeReviewIllegalRecordFilterOptionsRequest request) {
    CodeReviewIllegalRecordFilterOptionsRequest safeRequest =
        request == null
            ? new CodeReviewIllegalRecordFilterOptionsRequest(null, null, null, null)
            : request;
    CodeReviewIllegalRecordSqlQueryBuilder.QueryParts projectParts =
        CodeReviewIllegalRecordSqlQueryBuilder.buildFilterOptionQuery(
            null, null, null, safeRequest.source());
    CodeReviewIllegalRecordSqlQueryBuilder.QueryParts scopedParts =
        CodeReviewIllegalRecordSqlQueryBuilder.buildFilterOptionQuery(
            safeRequest.projectId(),
            safeRequest.repositoryName(),
            safeRequest.projectName(),
            safeRequest.source());
    Map<String, List<String>> projectValues =
        queryOptionValues(
            projectParts,
            Map.of("repositoryNames", "repository_name", "projectNames", "project_name"));
    Map<String, List<String>> scopedValues =
        queryOptionValues(
            scopedParts, CodeReviewIllegalRecordSqlQueryBuilder.scopedOptionColumns());
    return new CodeReviewIllegalRecordFilterOptionValues(
        queryProjectOptions(projectParts),
        projectValues.getOrDefault("repositoryNames", List.of()),
        scopedValues.getOrDefault("targetBranches", List.of()),
        scopedValues.getOrDefault("owners", List.of()),
        scopedValues.getOrDefault("mergedBys", List.of()),
        scopedValues.getOrDefault("moduleNames", List.of()),
        projectValues.getOrDefault("projectNames", List.of()));
  }

  public PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPage(
      CodeReviewIllegalRecordSourcePageQuery query) {
    if (!CodeReviewIllegalRecordSqlQueryBuilder.matchesRequestType(
        query.request().requestType())) {
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
    try {
      return loadDefaultIllegalPageOnce(query);
    } catch (DataAccessException exception) {
      log.warn("Failed to load paged merge request illegal facts", exception);
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
  }

  public List<CodeReviewIllegalRecordSource> loadLegacyAllExportSources(
      CodeReviewIllegalRecordQueryRequest request,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup filterGroup) {
    CodeReviewIllegalRecordSqlQueryBuilder.QueryParts parts =
        CodeReviewIllegalRecordSqlQueryBuilder.buildAllExportQuery(request, filterGroup);
    return mergeRequestFactQueryService.query(
        CodeReviewIllegalRecordSqlQueryBuilder.factSql()
            + parts.tailWhere()
            + " order by merge_request_iid desc nulls last, merged_at_source desc nulls last",
        parts.args(),
        rowMapper::mapFactSource);
  }

  public List<CodeReviewIllegalRecordSource> loadDefaultIllegalExportSources(
      CodeReviewIllegalRecordQueryRequest request,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup filterGroup) {
    if (!CodeReviewIllegalRecordSqlQueryBuilder.matchesRequestType(request.requestType())) {
      return List.of();
    }
    CodeReviewIllegalRecordSqlQueryBuilder.QueryParts parts =
        CodeReviewIllegalRecordSqlQueryBuilder.buildPageQuery(
            new CodeReviewIllegalRecordSourcePageQuery(
                request, filterGroup, 1, 1, request.sortField(), request.sortOrder()));
    return mergeRequestFactQueryService.query(
        CodeReviewIllegalRecordSqlQueryBuilder.factSql()
            + parts.tailWhere()
            + CodeReviewIllegalRecordSqlQueryBuilder.orderByClause(
                request.sortField(), request.sortOrder()),
        parts.args(),
        rowMapper::mapFactSource);
  }

  private PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPageOnce(
      CodeReviewIllegalRecordSourcePageQuery query) {
    CodeReviewIllegalRecordSqlQueryBuilder.QueryParts parts =
        CodeReviewIllegalRecordSqlQueryBuilder.buildPageQuery(query);
    long total =
        mergeRequestFactQueryService.count(
            "select count(*) from code_review_formal_records" + parts.where(), parts.args());
    if (total == 0) {
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
    List<Object> pageArgs = new ArrayList<>(parts.args());
    pageArgs.add(query.size());
    pageArgs.add((long) (query.page() - 1) * query.size());
    List<CodeReviewIllegalRecordSource> records =
        mergeRequestFactQueryService.query(
            CodeReviewIllegalRecordSqlQueryBuilder.factSql()
                + parts.tailWhere()
                + " order by "
                + CodeReviewIllegalRecordSqlQueryBuilder.sortColumn(query.sortField())
                + " "
                + CodeReviewIllegalRecordSqlQueryBuilder.sortOrder(query.sortOrder())
                + CodeReviewIllegalRecordSqlQueryBuilder.nullsClause(query.sortOrder())
                + ", merged_at_source "
                + CodeReviewIllegalRecordSqlQueryBuilder.sortOrder(query.sortOrder())
                + CodeReviewIllegalRecordSqlQueryBuilder.nullsClause(query.sortOrder())
                + ", merge_request_iid "
                + CodeReviewIllegalRecordSqlQueryBuilder.sortOrder(query.sortOrder())
                + " limit ? offset ?",
            pageArgs,
            rowMapper::mapFactSource);
    return new PageSlice<>(records, total, query.page(), query.size());
  }

  private List<CodeReviewIllegalRecordFilterProjectOption> queryProjectOptions(
      CodeReviewIllegalRecordSqlQueryBuilder.QueryParts parts) {
    return mergeRequestFactQueryService.query(
        "select project_id, project_name from code_review_formal_records "
            + parts.where()
            + """
               and project_id is not null
             group by project_id, project_name
             order by lower(coalesce(project_name, '')), project_id
            """,
        parts.args(),
        (resultSet, rowNumber) ->
            new CodeReviewIllegalRecordFilterProjectOption(
                resultSet.getLong("project_id"), resultSet.getString("project_name")));
  }

  private Map<String, List<String>> queryOptionValues(
      CodeReviewIllegalRecordSqlQueryBuilder.QueryParts parts,
      Map<String, String> optionColumns) {
    if (optionColumns.isEmpty()) {
      return Map.of();
    }
    String selectColumns =
        optionColumns.values().stream()
            .distinct()
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    String unionSql =
        optionColumns.entrySet().stream()
            .map(
                entry ->
                    "select '"
                        + entry.getKey()
                        + "' as option_group, "
                        + entry.getValue()
                        + " as option_value from scoped")
            .reduce((left, right) -> left + "\nunion all\n" + right)
            .orElse("");
    List<OptionGroupValue> rows =
        mergeRequestFactQueryService.query(
            "with scoped as (select "
                + selectColumns
                + " from code_review_formal_records "
                + parts.where()
                + ") select option_group, option_value from ("
                + unionSql
                + """
                  ) option_values
                 where nullif(btrim(coalesce(option_value, '')), '') is not null
                 group by option_group, option_value
                 order by option_group, lower(option_value)
                """,
            parts.args(),
            (resultSet, rowNumber) ->
                new OptionGroupValue(
                    resultSet.getString("option_group"), resultSet.getString("option_value")));
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (OptionGroupValue row : rows) {
      result.computeIfAbsent(row.group(), ignored -> new ArrayList<>()).add(row.value());
    }
    return result;
  }

  private record OptionGroupValue(String group, String value) {}
}
