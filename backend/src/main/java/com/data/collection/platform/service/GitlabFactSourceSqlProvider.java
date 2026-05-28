package com.data.collection.platform.service;

class GitlabFactSourceSqlProvider {
  private static final String ISSUE_SOURCE_SQL = """
      with distinct_issue_labels as (
        select distinct
               ll.target_id as issue_id,
               nullif(btrim(l.title), '') as title
          from ods_gitlab_label_links ll
          join ods_gitlab_labels l
            on l.id = ll.label_id
           and coalesce(l.mirror_deleted, false) = false
         where coalesce(ll.mirror_deleted, false) = false
           and ll.target_type = 'Issue'
           and l.title is not null
           and l.title <> ''
      ),
      issue_labels as (
        select issue_id,
               array_agg(title order by lower(title)) as label_titles
          from distinct_issue_labels
         group by issue_id
      ),
      issue_notes as (
        select n.noteable_id as issue_id,
               string_agg(coalesce(n.note, ''), E'\\n---\\n' order by coalesce(n.updated_at, n.created_at), n.id) as notes_text
          from ods_gitlab_notes n
         where coalesce(n.mirror_deleted, false) = false
           and n.noteable_type = 'Issue'
         group by n.noteable_id
      )
      select
        i.id as issue_id,
        i.iid as issue_iid,
        i.project_id,
        p.name as project_name,
        coalesce(milestone.title, '') as milestone_title,
        i.title,
        coalesce(author.name, '') as author_name,
        i.created_at,
        i.updated_at,
        coalesce(i.updated_at, i.created_at) as ods_updated_at,
        i.closed_at,
        i.state_id,
        labels.label_titles,
        coalesce(notes.notes_text, '') as notes_text
      from ods_gitlab_issues i
      left join ods_gitlab_projects p
        on p.id = i.project_id
       and coalesce(p.mirror_deleted, false) = false
      left join ods_gitlab_milestones milestone
        on milestone.id = i.milestone_id
       and coalesce(milestone.mirror_deleted, false) = false
      left join ods_gitlab_users author
        on author.id = i.author_id
       and coalesce(author.mirror_deleted, false) = false
      left join issue_labels labels
        on labels.issue_id = i.id
      left join issue_notes notes
        on notes.issue_id = i.id
      where coalesce(i.mirror_deleted, false) = false
      """;

  private static final String ISSUE_SOURCE_SQL_FALLBACK = """
      with distinct_issue_labels as (
        select distinct
               ll.target_id as issue_id,
               nullif(btrim(l.title), '') as title
          from ods_gitlab_label_links ll
          join ods_gitlab_labels l
            on l.id = ll.label_id
           and coalesce(l.mirror_deleted, false) = false
         where coalesce(ll.mirror_deleted, false) = false
           and ll.target_type = 'Issue'
           and l.title is not null
           and l.title <> ''
      ),
      issue_labels as (
        select issue_id,
               array_agg(title order by lower(title)) as label_titles
          from distinct_issue_labels
         group by issue_id
      ),
      issue_notes as (
        select n.noteable_id as issue_id,
               string_agg(coalesce(n.note, ''), E'\\n---\\n' order by coalesce(n.updated_at, n.created_at), n.id) as notes_text
          from ods_gitlab_notes n
         where coalesce(n.mirror_deleted, false) = false
           and n.noteable_type = 'Issue'
         group by n.noteable_id
      )
      select
        i.id as issue_id,
        i.iid as issue_iid,
        i.project_id,
        p.name as project_name,
        '' as milestone_title,
        i.title,
        coalesce(author.name, '') as author_name,
        i.created_at,
        i.updated_at,
        coalesce(i.updated_at, i.created_at) as ods_updated_at,
        i.closed_at,
        i.state_id,
        labels.label_titles,
        coalesce(notes.notes_text, '') as notes_text
      from ods_gitlab_issues i
      left join ods_gitlab_projects p
        on p.id = i.project_id
       and coalesce(p.mirror_deleted, false) = false
      left join ods_gitlab_users author
        on author.id = i.author_id
       and coalesce(author.mirror_deleted, false) = false
      left join issue_labels labels
        on labels.issue_id = i.id
      left join issue_notes notes
        on notes.issue_id = i.id
      where coalesce(i.mirror_deleted, false) = false
      """;

  private static final String MERGE_REQUEST_SOURCE_SQL = """
      with reviewer_names as (
        select mr.merge_request_id,
               string_agg(distinct u.name, ', ' order by u.name) as reviewer_names
          from ods_gitlab_merge_request_reviewers mr
          join ods_gitlab_users u
            on u.id = mr.user_id
           and coalesce(u.mirror_deleted, false) = false
         where coalesce(mr.mirror_deleted, false) = false
         group by mr.merge_request_id
      ),
      assignee_names as (
        select ma.merge_request_id,
               string_agg(distinct u.name, ', ' order by u.name) as assignee_names
          from ods_gitlab_merge_request_assignees ma
          join ods_gitlab_users u
            on u.id = ma.user_id
           and coalesce(u.mirror_deleted, false) = false
         where coalesce(ma.mirror_deleted, false) = false
         group by ma.merge_request_id
      ),
      merge_request_labels as (
        select ll.target_id as merge_request_id,
               array_remove(
                 array_agg(
                   nullif(btrim(l.title), '')
                   order by coalesce(ll.source_updated_at, ll.updated_at, ll.created_at) desc nulls last, ll.id desc
                 ),
                 null
               ) as label_titles
          from ods_gitlab_label_links ll
          join ods_gitlab_labels l
            on l.id = ll.label_id
           and coalesce(l.mirror_deleted, false) = false
         where coalesce(ll.mirror_deleted, false) = false
           and ll.target_type = 'MergeRequest'
         group by ll.target_id
      ),
      imported_metrics as (
        select m.project_id,
               m.merge_request_id,
               m.merge_request_iid,
               m.comment_rate,
               m.comment_rate_source,
               m.defect_count,
               m.defect_count_source,
               m.source_summary as metric_source_summary,
               m.raw_payload as metric_raw_payload
          from code_review_external_metrics m
      ),
      form_records as (
        select f.project_id,
               f.request_iid,
               max(nullif(btrim(f.reviewer), '')) as reviewer_name,
               max(f.review_duration_minutes) as review_duration_minutes
          from collect_form_records f
         where f.deleted = false
           and f.resource_type = 'merge_request'
         group by f.project_id, f.request_iid
      )
      select
        mr.id as merge_request_id,
        mr.iid as merge_request_iid,
        mr.target_project_id as project_id,
        mr.title,
        p.name as project_name,
        coalesce(owner_ns.path || '/' || p.path, p.path) as repository_name,
        coalesce(metrics.merged_at, mr.updated_at) as merged_at,
        mr.created_at,
        mr.updated_at,
        coalesce(mr.updated_at, mr.created_at) as ods_updated_at,
        coalesce(author.name, '') as author_name,
        coalesce(merge_user.name, '') as merge_user_name,
        coalesce(nullif(trim(reviewers.reviewer_names), ''), nullif(trim(assignees.assignee_names), ''), '') as owner_name,
        coalesce(reviewers.reviewer_names, '') as reviewer_names,
        coalesce(assignees.assignee_names, '') as assignee_names,
        coalesce(mr.target_branch, '') as target_branch,
        coalesce(mr.source_branch, '') as source_branch,
        coalesce((labels.label_titles)[1], '') as module_name,
        labels.label_titles as label_titles,
        metrics.added_lines as added_lines,
        forms.review_duration_minutes,
        imported_metrics.comment_rate,
        imported_metrics.comment_rate_source,
        imported_metrics.defect_count,
        imported_metrics.defect_count_source,
        imported_metrics.metric_source_summary,
        imported_metrics.metric_raw_payload
      from ods_gitlab_merge_requests mr
      left join ods_gitlab_merge_request_metrics metrics
        on metrics.merge_request_id = mr.id
       and coalesce(metrics.mirror_deleted, false) = false
      left join ods_gitlab_projects p
        on p.id = mr.target_project_id
       and coalesce(p.mirror_deleted, false) = false
      left join ods_gitlab_namespaces owner_ns
        on owner_ns.id = p.namespace_id
       and coalesce(owner_ns.mirror_deleted, false) = false
      left join ods_gitlab_users author
        on author.id = mr.author_id
       and coalesce(author.mirror_deleted, false) = false
      left join ods_gitlab_users merge_user
        on merge_user.id = mr.merge_user_id
       and coalesce(merge_user.mirror_deleted, false) = false
      left join reviewer_names reviewers
        on reviewers.merge_request_id = mr.id
      left join assignee_names assignees
        on assignees.merge_request_id = mr.id
      left join merge_request_labels labels
        on labels.merge_request_id = mr.id
      left join imported_metrics
        on imported_metrics.project_id = mr.target_project_id
       and (imported_metrics.merge_request_id = mr.id or imported_metrics.merge_request_iid = mr.iid)
      left join form_records forms
        on forms.project_id = mr.target_project_id
       and forms.request_iid = mr.iid
      where coalesce(mr.mirror_deleted, false) = false
      """;

  String issueSourceSql() {
    return ISSUE_SOURCE_SQL;
  }

  String issueSourceSqlFallback() {
    return ISSUE_SOURCE_SQL_FALLBACK;
  }

  String mergeRequestSourceSql() {
    return MERGE_REQUEST_SOURCE_SQL;
  }
}
