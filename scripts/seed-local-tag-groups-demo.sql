BEGIN;

-- Local demo fixtures for tag group filters.
--
-- This script seeds tag group configuration from the current local business data,
-- so manual testing uses real selectable values instead of a tiny hard-coded list.
-- It intentionally covers only group keys currently supported by
-- TagSelectionSqlPredicateService.

WITH upserted_groups AS (
  INSERT INTO tag_group (
    domain,
    group_key,
    label,
    selection_mode,
    sort_order,
    match_strategy_name,
    enabled,
    remark
  )
  VALUES
    ('issue', 'module', 'Issue Module', 'multiple', 10, 'split_exact_comma', true, 'Local demo tag group generated from issue_fact.module_names'),
    ('issue', 'severity', 'Issue Severity', 'multiple', 20, 'eq', true, 'Local demo tag group generated from issue_fact.severity_level'),
    ('issue', 'status', 'Issue Status', 'multiple', 30, 'eq', true, 'Local demo tag group generated from issue_fact.issue_state'),
    ('issue', 'milestone', 'Issue Milestone', 'multiple', 40, 'eq', true, 'Local demo tag group generated from issue_fact.milestone_title'),
    ('issue', 'testing_phase', 'Issue Testing Phase', 'multiple', 50, 'eq', true, 'Local demo tag group generated from issue_fact.phase_filter_value'),
    ('review_data', 'module', 'Review Module', 'multiple', 10, 'eq', true, 'Local demo tag group generated from review_records.module_name'),
    ('review_data', 'review_type', 'Review Type', 'multiple', 20, 'eq', true, 'Local demo tag group generated from review_records.review_type'),
    ('review_data', 'review_owner', 'Review Owner', 'multiple', 30, 'eq', true, 'Local demo tag group generated from review_records.review_owner'),
    ('review_data', 'problem_category', 'Problem Category', 'multiple', 40, 'eq', true, 'Local demo tag group generated from review_problem_items.problem_category'),
    ('review_data', 'problem_status', 'Problem Status', 'multiple', 50, 'eq', true, 'Local demo tag group generated from review_problem_items.problem_status')
  ON CONFLICT (domain, group_key) DO UPDATE
  SET label = EXCLUDED.label,
      selection_mode = EXCLUDED.selection_mode,
      sort_order = EXCLUDED.sort_order,
      match_strategy_name = EXCLUDED.match_strategy_name,
      enabled = true,
      remark = EXCLUDED.remark,
      updated_at = current_timestamp
  RETURNING id, domain, group_key
),
all_groups AS (
  SELECT DISTINCT ON (domain, group_key) id, domain, group_key
    FROM (
      SELECT id, domain, group_key
        FROM upserted_groups
      UNION ALL
      SELECT id, domain, group_key
        FROM tag_group
       WHERE (domain, group_key) IN (
         ('issue', 'module'),
         ('issue', 'severity'),
         ('issue', 'status'),
         ('issue', 'milestone'),
         ('issue', 'testing_phase'),
         ('review_data', 'module'),
         ('review_data', 'review_type'),
         ('review_data', 'review_owner'),
         ('review_data', 'problem_category'),
         ('review_data', 'problem_status')
       )
    ) candidates
   ORDER BY domain, group_key, id
),
issue_module_values AS (
  SELECT 'issue' AS domain,
         'module' AS group_key,
         module_value AS raw_value,
         min(module_value) AS label,
         row_number() OVER (ORDER BY lower(module_value)) * 10 AS sort_order,
         'normalized_field' AS source_type,
         'module_names' AS source_field
    FROM (
      SELECT nullif(trim(module_item), '') AS module_value
        FROM issue_fact fact
       CROSS JOIN LATERAL regexp_split_to_table(replace(coalesce(fact.module_names, ''), ', ', ','), ',') AS module_item
       WHERE fact.deleted = false
    ) modules
   WHERE module_value IS NOT NULL
     AND lower(module_value) NOT LIKE 'unset%'
   GROUP BY module_value
   LIMIT 80
),
issue_simple_values AS (
  SELECT 'issue' AS domain,
         group_key,
         raw_value,
         raw_value AS label,
         row_number() OVER (PARTITION BY group_key ORDER BY lower(raw_value)) * 10 AS sort_order,
         'normalized_field' AS source_type,
         source_field
    FROM (
      SELECT 'severity' AS group_key, nullif(trim(severity_level), '') AS raw_value, 'severity_level' AS source_field
        FROM issue_fact
       WHERE deleted = false
      UNION ALL
      SELECT 'status', nullif(trim(issue_state), ''), 'issue_state'
        FROM issue_fact
       WHERE deleted = false
      UNION ALL
      SELECT 'milestone', nullif(trim(milestone_title), ''), 'milestone_title'
        FROM issue_fact
       WHERE deleted = false
      UNION ALL
      SELECT 'testing_phase', nullif(trim(phase_filter_value), ''), 'phase_filter_value'
        FROM issue_fact
       WHERE deleted = false
    ) issue_values
   WHERE raw_value IS NOT NULL
   GROUP BY group_key, raw_value, source_field
),
review_simple_values AS (
  SELECT 'review_data' AS domain,
         group_key,
         raw_value,
         raw_value AS label,
         row_number() OVER (PARTITION BY group_key ORDER BY lower(raw_value)) * 10 AS sort_order,
         'review_field' AS source_type,
         source_field
    FROM (
      SELECT 'module' AS group_key, nullif(trim(module_name), '') AS raw_value, 'module_name' AS source_field
        FROM review_records
       WHERE deleted = false
      UNION ALL
      SELECT 'review_type', nullif(trim(review_type), ''), 'review_type'
        FROM review_records
       WHERE deleted = false
      UNION ALL
      SELECT 'review_owner', nullif(trim(review_owner), ''), 'review_owner'
        FROM review_records
       WHERE deleted = false
      UNION ALL
      SELECT 'problem_category', nullif(trim(problem_category), ''), 'problem_category'
        FROM review_problem_items
       WHERE deleted = false
      UNION ALL
      SELECT 'problem_status', nullif(trim(problem_status), ''), 'problem_status'
        FROM review_problem_items
       WHERE deleted = false
    ) review_values
   WHERE raw_value IS NOT NULL
   GROUP BY group_key, raw_value, source_field
),
seed_values AS (
  SELECT * FROM issue_module_values
  UNION ALL
  SELECT * FROM issue_simple_values
  UNION ALL
  SELECT * FROM review_simple_values
),
seed_keys AS (
  SELECT seed.*,
         left(
           trim(both '_' from regexp_replace(lower(seed.raw_value), '[^[:alnum:]]+', '_', 'g')),
           80
         ) AS cleaned_key,
         substr(md5(seed.domain || ':' || seed.group_key || ':' || seed.raw_value), 1, 12) AS key_hash
    FROM seed_values seed
),
upserted_values AS (
  INSERT INTO tag_value (
    group_id,
    value_key,
    label,
    value_type,
    sort_order,
    enabled,
    disabled,
    remark
  )
  SELECT group_ref.id AS group_id,
         left(
           coalesce(nullif(seed.cleaned_key, ''), 'value') || '_' || seed.key_hash,
           120
         ) AS value_key,
         seed.label,
         'standard' AS value_type,
         seed.sort_order,
         true AS enabled,
         false AS disabled,
         'Local demo option generated from existing data' AS remark
    FROM seed_keys seed
    JOIN all_groups group_ref
      ON group_ref.domain = seed.domain
     AND group_ref.group_key = seed.group_key
  ON CONFLICT (group_id, value_key) DO UPDATE
  SET label = EXCLUDED.label,
      value_type = EXCLUDED.value_type,
      sort_order = EXCLUDED.sort_order,
      enabled = true,
      disabled = false,
      remark = EXCLUDED.remark,
      updated_at = current_timestamp
  RETURNING id, group_id, value_key, label
)
INSERT INTO tag_value_mapping (
  value_id,
  source_type,
  source_field,
  raw_value,
  match_type,
  source_instance,
  enabled,
  remark
)
SELECT value_ref.id,
       seed.source_type,
       seed.source_field,
       seed.raw_value,
       'exact',
       NULL,
       true,
       'Local demo mapping generated from existing data'
  FROM seed_values seed
  JOIN seed_keys seed_key
    ON seed_key.domain = seed.domain
   AND seed_key.group_key = seed.group_key
   AND seed_key.raw_value = seed.raw_value
  JOIN all_groups group_ref
    ON group_ref.domain = seed.domain
   AND group_ref.group_key = seed.group_key
  JOIN tag_value value_ref
    ON value_ref.group_id = group_ref.id
   AND value_ref.value_key = left(
         coalesce(nullif(seed_key.cleaned_key, ''), 'value') || '_' || seed_key.key_hash,
         120
       )
 WHERE NOT EXISTS (
     SELECT 1
       FROM tag_value_mapping existing
      WHERE existing.value_id = value_ref.id
        AND existing.source_type = seed.source_type
        AND coalesce(existing.source_field, '') = coalesce(seed.source_field, '')
        AND existing.raw_value = seed.raw_value
        AND existing.match_type = 'exact'
        AND existing.source_instance IS NULL
   );

COMMIT;
