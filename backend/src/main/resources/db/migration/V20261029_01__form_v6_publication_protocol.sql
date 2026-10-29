-- v6 N3：发布协议 v2（VALIDATING/BUILDING/SWITCHING）、旧任务兼容与 head 源集合证据。
-- 蓝本为 N0 五审批准合同 §4（R01/Q4/Q9）；不修改 160 条既有迁移，不迁移/复制旧记录。

-- ===== 发布任务协议版本 =====
alter table form_template_publications
    add column protocol_version smallint not null default 1
        check (protocol_version in (1, 2)),
    add column frozen_source_set_revision bigint,
    add column frozen_source_set_hash char(64);

alter table form_template_publications
    drop constraint if exists form_template_publications_stage_check;

alter table form_template_publications
    add constraint ck_ftp_stage check (
        stage in ('PREPARED', 'COPYING', 'VERIFIED', 'PUBLISHED', 'FAILED',
                  'VALIDATING', 'BUILDING', 'SWITCHING')),
    add constraint ck_ftp_protocol_stage check (
        (protocol_version = 1
         and stage in ('PREPARED', 'COPYING', 'VERIFIED', 'PUBLISHED', 'FAILED'))
        or (protocol_version = 2
            and stage in ('VALIDATING', 'BUILDING', 'SWITCHING', 'PUBLISHED', 'FAILED'))),
    add constraint ck_ftp_switching_fence check (
        stage <> 'SWITCHING' or fence_token is not null);

drop index if exists uq_form_publications_active;
create unique index uq_form_publications_active
    on form_template_publications(scope_id)
    where stage in ('PREPARED', 'COPYING', 'VERIFIED',
                    'VALIDATING', 'BUILDING', 'SWITCHING');

drop index if exists idx_form_publications_claim;
create index idx_form_publications_claim
    on form_template_publications(protocol_version, stage, next_attempt_at, lease_until)
    where stage in ('PREPARED', 'COPYING', 'VERIFIED',
                    'VALIDATING', 'BUILDING', 'SWITCHING');

-- ===== 尝试记录兼容 v1/v2 =====
alter table form_template_publication_attempts
    add column protocol_version smallint not null default 1
        check (protocol_version in (1, 2));

alter table form_template_publication_attempts
    drop constraint if exists form_template_publication_attempts_phase_check;

alter table form_template_publication_attempts
    add constraint ck_ftpa_phase check (
        phase in ('COPY', 'VERIFY', 'PUBLISH', 'FAILED',
                  'VALIDATE', 'BUILD', 'BUILD_TAKEOVER',
                  'SWITCH_ENTER', 'SWITCH_APPLY', 'SWITCH_TAKEOVER'));

-- ===== 变更 head 的源集合待处理证据（N4 消费） =====
alter table form_change_heads
    add column pending_source_set_revision bigint,
    add column pending_source_set_hash char(64);
