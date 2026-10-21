package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class FactSearchIndexRepairService {
  private static final int FACT_BATCH_SIZE = 200;

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

  void refreshIssueFactSearchIndexesInBatches(
      FactRevisionTarget target, List<IssueFact> facts) {
    for (List<IssueFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      refreshIssueFactSearchIndexes(target, batch);
    }
  }

  void refreshMergeRequestFactSearchIndexesInBatches(
      FactRevisionTarget target, List<MergeRequestFact> facts) {
    for (List<MergeRequestFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      refreshMergeRequestFactSearchIndexes(target, batch);
    }
  }

  void refreshIssueFactSearchIndexes(FactRevisionTarget target, List<IssueFact> facts) {
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
         where created_by_publication_id = ?
           and source_system = ?
           and source_instance = ?
           and project_id = ?
           and issue_id = ?
        """,
        facts,
        FACT_BATCH_SIZE,
        (statement, fact) -> bindIssueSearchIndex(target, statement, fact));
  }

  void refreshMergeRequestFactSearchIndexes(
      FactRevisionTarget target, List<MergeRequestFact> facts) {
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
         where created_by_publication_id = ?
           and source_system = ?
           and source_instance = ?
           and project_id = ?
           and merge_request_id = ?
        """,
        facts,
        FACT_BATCH_SIZE,
        (statement, fact) -> bindMergeRequestSearchIndex(target, statement, fact));
  }

  private void bindIssueSearchIndex(
      FactRevisionTarget target, PreparedStatement statement, IssueFact fact)
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
    statement.setObject(index++, target.publicationId());
    statement.setString(index++, fact.getSourceSystem());
    statement.setString(index++, fact.getSourceInstance());
    statement.setLong(index++, fact.getProjectId());
    statement.setLong(index, fact.getIssueId());
  }

  private void bindMergeRequestSearchIndex(
      FactRevisionTarget target, PreparedStatement statement, MergeRequestFact fact)
      throws SQLException {
    FactSearchIndexSupport.MergeRequestSearchIndexes indexes =
        FactSearchIndexSupport.buildMergeRequestIndexes(fact);
    int index = 1;
    index = bindSearchIndex(statement, index, indexes.keyword());
    index = bindSearchIndex(statement, index, indexes.owner());
    statement.setObject(index++, target.publicationId());
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
