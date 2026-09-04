package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestFact;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class FactSearchIndexRepairService {
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final int FACT_BATCH_SIZE = 200;
  private static final int SEARCH_INDEX_REPAIR_LIMIT = 1000;

  private final JdbcTemplate jdbcTemplate;
  private final IssueFactSourceRowMapper issueRowMapper;
  private final MergeRequestFactSourceRowMapper mergeRequestRowMapper;

  FactSearchIndexRepairService(
      JdbcTemplate jdbcTemplate,
      IssueFactSourceRowMapper issueRowMapper,
      MergeRequestFactSourceRowMapper mergeRequestRowMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.issueRowMapper = issueRowMapper;
    this.mergeRequestRowMapper = mergeRequestRowMapper;
  }

  int repairIssueFactSearchIndexes(String sourceInstance) {
    int repaired = 0;
    while (repaired < SEARCH_INDEX_REPAIR_LIMIT) {
      int batchLimit = Math.min(FACT_BATCH_SIZE, SEARCH_INDEX_REPAIR_LIMIT - repaired);
      List<IssueFact> batch = loadIssueFactsMissingSearchIndexes(sourceInstance, batchLimit);
      if (batch.isEmpty()) {
        return repaired;
      }
      refreshIssueFactSearchIndexes(batch);
      repaired += batch.size();
      if (batch.size() < batchLimit) {
        return repaired;
      }
    }
    return repaired;
  }

  int repairMergeRequestFactSearchIndexes(String sourceInstance) {
    int repaired = 0;
    while (repaired < SEARCH_INDEX_REPAIR_LIMIT) {
      int batchLimit = Math.min(FACT_BATCH_SIZE, SEARCH_INDEX_REPAIR_LIMIT - repaired);
      List<MergeRequestFact> batch =
          loadMergeRequestFactsMissingSearchIndexes(sourceInstance, batchLimit);
      if (batch.isEmpty()) {
        return repaired;
      }
      refreshMergeRequestFactSearchIndexes(batch);
      repaired += batch.size();
      if (batch.size() < batchLimit) {
        return repaired;
      }
    }
    return repaired;
  }

  void refreshIssueFactSearchIndexesInBatches(List<IssueFact> facts) {
    for (List<IssueFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      refreshIssueFactSearchIndexes(batch);
    }
  }

  void refreshMergeRequestFactSearchIndexesInBatches(List<MergeRequestFact> facts) {
    for (List<MergeRequestFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      refreshMergeRequestFactSearchIndexes(batch);
    }
  }

  void refreshIssueFactSearchIndexes(List<IssueFact> facts) {
    jdbcTemplate.batchUpdate(
        """
        update issue_fact
           set search_text = ?,
               search_compact = ?,
               search_spell = ?,
               search_initials = ?,
               title_search_text = ?,
               title_search_compact = ?,
               title_search_spell = ?,
               title_search_initials = ?,
               module_search_text = ?,
               module_search_compact = ?,
               module_search_spell = ?,
               module_search_initials = ?,
               milestone_search_text = ?,
               milestone_search_compact = ?,
               milestone_search_spell = ?,
               milestone_search_initials = ?,
               author_search_text = ?,
               author_search_compact = ?,
               author_search_spell = ?,
               author_search_initials = ?,
               assignee_search_text = ?,
               assignee_search_compact = ?,
               assignee_search_spell = ?,
               assignee_search_initials = ?,
               primary_phase_label = ?,
               phase_filter_value = ?,
               phase_search_text = ?,
               phase_search_compact = ?,
               phase_search_spell = ?,
               phase_search_initials = ?
         where source_system = ?
           and source_instance = ?
           and project_id = ?
           and issue_id = ?
        """,
        facts,
        FACT_BATCH_SIZE,
        this::bindIssueSearchIndex);
  }

  void refreshMergeRequestFactSearchIndexes(List<MergeRequestFact> facts) {
    jdbcTemplate.batchUpdate(
        """
        update merge_request_fact
           set search_text = ?,
               search_compact = ?,
               search_spell = ?,
               search_initials = ?,
               owner_search_text = ?,
               owner_search_compact = ?,
               owner_search_spell = ?,
               owner_search_initials = ?
         where source_system = ?
           and source_instance = ?
           and project_id = ?
           and merge_request_id = ?
        """,
        facts,
        FACT_BATCH_SIZE,
        this::bindMergeRequestSearchIndex);
  }

  private List<IssueFact> loadIssueFactsMissingSearchIndexes(
      String sourceInstance, int limit) {
    return jdbcTemplate.query(
        """
        select source_system,
               source_instance,
               project_id,
               issue_id,
               issue_iid,
               title,
               project_name,
               module_names,
               customer_names,
               testing_phase,
               system_test_label,
               label_names,
               reason_category,
               illegal_reason,
               author_name,
               handler_name,
               assignee_name,
               bug_status,
               category,
               milestone_title
          from issue_fact
         where source_system = ?
           and source_instance = ?
           and deleted = false
           and (
                search_text is null
             or search_compact is null
             or search_spell is null
             or search_initials is null
             or primary_phase_label is null
             or phase_filter_value is null
           )
         order by updated_at_source desc nulls last, issue_id desc
         limit ?
        """,
        (resultSet, rowNumber) ->
            issueRowMapper.mapSearchIndexFact(resultSet, sourceInstance),
        DEFAULT_SOURCE_SYSTEM,
        sourceInstance,
        limit);
  }

  private List<MergeRequestFact> loadMergeRequestFactsMissingSearchIndexes(
      String sourceInstance, int limit) {
    return jdbcTemplate.query(
        """
        select source_system,
               source_instance,
               project_id,
               merge_request_id,
               title,
               author_name,
               owner_name,
               project_name,
               repository_name,
               module_name,
               target_branch,
               merge_user_name
          from merge_request_fact
         where source_system = ?
           and source_instance = ?
           and deleted = false
           and (
                search_text is null
             or search_compact is null
             or search_spell is null
             or search_initials is null
             or owner_search_text is null
             or owner_search_compact is null
             or owner_search_spell is null
             or owner_search_initials is null
           )
         order by merged_at_source desc nulls last, merge_request_id desc
         limit ?
        """,
        (resultSet, rowNumber) ->
            mergeRequestRowMapper.mapSearchIndexFact(resultSet, sourceInstance),
        DEFAULT_SOURCE_SYSTEM,
        sourceInstance,
        limit);
  }

  private void bindIssueSearchIndex(PreparedStatement statement, IssueFact fact)
      throws SQLException {
    FactSearchIndexSupport.IssueSearchIndexes indexes =
        FactSearchIndexSupport.buildIssueIndexes(fact);
    int index = 1;
    index = bindSearchIndex(statement, index, indexes.keyword());
    index = bindSearchIndex(statement, index, indexes.title());
    index = bindSearchIndex(statement, index, indexes.module());
    index = bindSearchIndex(statement, index, indexes.milestone());
    index = bindSearchIndex(statement, index, indexes.author());
    index = bindSearchIndex(statement, index, indexes.assignee());
    statement.setString(index++, indexes.primaryPhaseLabel());
    statement.setString(index++, indexes.phaseFilterValue());
    index = bindSearchIndex(statement, index, indexes.phase());
    statement.setString(index++, fact.getSourceSystem());
    statement.setString(index++, fact.getSourceInstance());
    statement.setLong(index++, fact.getProjectId());
    statement.setLong(index, fact.getIssueId());
  }

  private void bindMergeRequestSearchIndex(
      PreparedStatement statement, MergeRequestFact fact) throws SQLException {
    FactSearchIndexSupport.MergeRequestSearchIndexes indexes =
        FactSearchIndexSupport.buildMergeRequestIndexes(fact);
    int index = 1;
    index = bindSearchIndex(statement, index, indexes.keyword());
    index = bindSearchIndex(statement, index, indexes.owner());
    statement.setString(index++, fact.getSourceSystem());
    statement.setString(index++, fact.getSourceInstance());
    statement.setLong(index++, fact.getProjectId());
    statement.setLong(index, fact.getMergeRequestId());
  }

  private int bindSearchIndex(
      PreparedStatement statement,
      int parameterIndex,
      TextQuerySupport.SearchIndex searchIndex)
      throws SQLException {
    statement.setString(parameterIndex++, searchIndex.normalized());
    statement.setString(parameterIndex++, searchIndex.compact());
    statement.setString(parameterIndex++, searchIndex.spell());
    statement.setString(parameterIndex++, searchIndex.initials());
    return parameterIndex;
  }

  private <T> List<List<T>> partition(List<T> items, int batchSize) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }
    List<List<T>> result = new ArrayList<>();
    for (int start = 0; start < items.size(); start += batchSize) {
      int end = Math.min(start + batchSize, items.size());
      result.add(items.subList(start, end));
    }
    return result;
  }
}
