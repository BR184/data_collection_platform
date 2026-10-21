-- 告警观察时刻独立于内容截止；重试不会反复重置，新的完整点发布后清除。
alter table read_publication_domains add column unsettled_since timestamptz;
alter table read_publication_domains add column last_successful_verification_at timestamptz;
