#!/usr/bin/env bash
set -euo pipefail

# 18181 -> 20001 评审数据迁移 · 第二步：在目标实例（20001）侧增量合并。
# 默认 dry-run：载入暂存数据后只计算决策并输出逐条报告，不写任何正式数据；
# 复核报告后追加 --apply 才在单个事务内真正落库（任何一步失败整体回滚）。
#
# 用法:
#   bash migrate-review-data-merge.sh <20001部署目录> <导出目录> [--apply] [--include-legacy-managed]
#
# 参数:
#   <20001部署目录>           20001 实例的部署目录（含 .env 与 docker-compose.yml）
#   <导出目录>                migrate-review-data-export.sh 产出的目录
#                             （7 个 CSV + manifest.env + SHA256SUMS.txt，两实例同机时直接复用同一目录）
#   --apply                   真正执行合并；缺省为 dry-run，只出报告不落库
#   --include-legacy-managed  一并迁移 18181 上 authority=LEGACY_MANAGED 的正式记录
#                             （默认跳过：20001 模型已废弃该形态，兼容快照已提供等价内容）
#
# 决策规则（对每条 18181 评审记录）:
#   deleted=true                        -> SKIP_DELETED  跳过
#   链接 authority=LEGACY_MANAGED       -> SKIP_LEGACY   跳过（可用开关强制迁移）
#   三层匹配键均未命中                  -> INSERT        插入为新记录
#   命中且 18181 更新时间严格更新       -> REPLACE       整树替换（保留 20001 记录 id）
#   命中且 20001 更新或时间相等         -> KEEP          保留 20001 现状
#   多条 18181 记录争抢同一 20001 目标 -> 除最新者外 SKIP_CONFLICT，需人工复核处理
#
# 匹配键优先级: ① 兼容快照 legacy_id（edit_links 表）② GitLab 上下文三元组
# （project_id+resource_type+resource_iid）③ 业务五元组（项目+标题+类型+日期+版本）。
# 更新时间 = 主行 updated_at 与四张子表 max(updated_at) 的最大值。
#
# 安全边界:
#   - 全程只操作传入部署目录 compose 项目内的 postgres 容器（三实例同机互不干扰；
#     只需 20001 的 postgres 在运行，前后端容器状态与本脚本无关）
#   - dry-run 对正式表零写入（暂存 schema mig_18181 属草稿，合并后自动删除）
#   - --apply 前自动全库 pg_dump 备份到导出目录，并经 pg_restore --list 校验后才继续
#   - 合并在单事务内执行：决策守恒 / 子树守恒 / 链接守恒自动校验，任一不符整体回滚
#   - 迁移提交后自动清空 page_record_snapshots 查询缓存（纯缓存，自动重建）
#   - 源实例（18181）全程只读，本脚本不接触它
#   - 合并完成后不需要重启或重新拉起 20001 的前后端：本脚本只经 docker exec 操作 postgres，
#     不经过应用层；查询缓存已在合并同一事务内清空，页面下次读取自动重建
#
# 在内网总流程中的位置（权威手册：deploy/runbooks/review-data-migration-18181-to-20001.md §5「总顺序」）:
#   ① backup.sh → ② upgrade.sh 先把最新版部署到 20001 → ③ export.sh 从 18181 只读导出
#   → ④ 本脚本 dry-run + 逐条人工复核报告 → ⑤ 本脚本 --apply（写入前自动全库备份并校验）
#   - 备份必须在部署之前，不是迁移完成之后：upgrade.sh 以预部署备份为准入闸门；
#     而 --apply 的备份已在写入前，完成后再补备份只是留档基线，不提供回退能力。
#   - 本迁移不得插在 ①② 之间：upgrade.sh 会比对静默迁移窗口前后的受保护表行数，中途写入会让它失败。
#   - 目标侧列集校验按 20260803.01 形态编写；V20260804_01 之后无任何迁移触及这 7 表，
#     因此 20001 升级到最新版后本校验仍成立（已逐文件核实）。

APPLY=false
INCLUDE_LEGACY=false
SRC_DIR=""
EXP_DIR=""

# Git Bash（Windows 演练）会把 /tmp/... 形态的容器内路径改写成 Windows 路径传给 docker；
# 豁免转换在 Linux 上无害（无人读取这两个变量）。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

fail() { echo "[merge] ERROR: $*" >&2; exit 1; }
log() { echo "[merge] $*"; }

usage() {
  # 打印文件头注释块（第 4 行起到首个非注释行止），随头部说明增删自动适配。
  awk 'NR>3 && $0 !~ /^#/ {exit} NR>3 {sub(/^# ?/, ""); print}' "$0"
  exit 1
}

for arg in "$@"; do
  case "$arg" in
    --apply) APPLY=true ;;
    --include-legacy-managed) INCLUDE_LEGACY=true ;;
    --help|-h) usage ;;
    --*) fail "unknown option: $arg" ;;
    *) if [[ -z "$SRC_DIR" ]]; then SRC_DIR="$arg"; elif [[ -z "$EXP_DIR" ]]; then EXP_DIR="$arg"; else fail "unexpected argument: $arg"; fi ;;
  esac
done
[[ -n "$SRC_DIR" && -n "$EXP_DIR" ]] || usage
SRC_DIR="$(cd -- "$SRC_DIR" && pwd)" || fail "20001 deployment dir not found: $SRC_DIR"
[[ -d "$EXP_DIR" ]] || fail "export dir not found (run migrate-review-data-export.sh first): $EXP_DIR"
EXP_DIR="$(cd -- "$EXP_DIR" && pwd)"

if docker info >/dev/null 2>&1; then
  DOCKER=(docker)
elif sudo docker info >/dev/null 2>&1; then
  DOCKER=(sudo docker)
else
  fail "Docker is unavailable"
fi

cd "$SRC_DIR"
[[ -f .env && -f docker-compose.yml ]] || fail "not a deployment directory (missing .env/docker-compose.yml): $SRC_DIR"

PG_ID="$("${DOCKER[@]}" compose --env-file .env ps -q postgres)"
[[ -n "$PG_ID" ]] || fail "postgres service is not running in $SRC_DIR"
log "target postgres container: $PG_ID (deployment dir: $SRC_DIR)"

HEALTH="$("${DOCKER[@]}" inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$PG_ID")"
[[ "$HEALTH" == "healthy" || "$HEALTH" == "none" ]] || fail "postgres container health is $HEALTH (expected healthy), aborting"

# 防重叠：dry-run 与 apply 互斥运行（锁文件放导出目录，两脚本共用）。
if command -v flock >/dev/null 2>&1; then
  exec 9>"$EXP_DIR/.merge.lock"
  flock -n 9 || fail "another merge process is running (lock: $EXP_DIR/.merge.lock)"
fi

psql_stdin() {
  "${DOCKER[@]}" exec -i "$PG_ID" sh -c 'exec psql -v ON_ERROR_STOP=1 -v include_legacy='"$INCLUDE_LEGACY"' -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off "$@"' psql "$@"
}

psql_one() {
  local sql="$1"; shift
  printf '%s\n' "$sql" | psql_stdin "$@"
}

# ── 第 1 步：校验导出物完整性 ────────────────────────────────────────────────
TABLES="review_records review_record_experts review_problem_items review_record_descriptions review_record_contents review_data_match_mode_edit_links review_data_match_mode_problem_edit_links"

[[ -f "$EXP_DIR/manifest.env" && -f "$EXP_DIR/SHA256SUMS.txt" ]] || fail "manifest.env / SHA256SUMS.txt missing in $EXP_DIR"
for t in $TABLES; do
  [[ -f "$EXP_DIR/$t.csv" ]] || fail "export CSV missing: $EXP_DIR/$t.csv"
done
(cd "$EXP_DIR" && sha256sum -c --quiet SHA256SUMS.txt) || fail "SHA256 verification failed for exported CSVs"
log "export artifacts verified (7 CSV + manifest + SHA256)"

# ── 第 2 步：20001 侧列集合校验（漂移即中止，绝不带病迁移）──────────────────
# 预期列集合（字母序）= 迁移史逐文件核对（18181 导出列集一致，唯一差异：
# 20001 的 edit_links 已删除 last_handover_at 且 authority 仅允许 PLATFORM_OWNED）。
EXPECT_RR="author_name,created_at,created_by,deleted,gitlab_project_id,gitlab_resource_iid,gitlab_resource_type,id,module_name,not_reach_standard_reason,project_name,review_date,review_owner,review_product,review_scale_pages,review_type,review_version,search_compact,search_initials,search_spell,search_text,source_file_name,title,title_search_compact,title_search_initials,title_search_spell,title_search_text,updated_at,weighted_defect_density"
EXPECT_RE="created_at,deleted,expert_name,id,review_record_id,sort_order,updated_at"
EXPECT_PI="created_at,created_by,deleted,document_position,id,owner_name,problem_category,problem_description,problem_status,rejection_reason,review_category,review_record_id,reviewer_name,suggested_solution,updated_at,workload_hours"
EXPECT_RD="author_name,created_at,deleted,id,review_product,review_record_id,review_scale_pages,review_version,sort_order,unit,updated_at"
EXPECT_RC="assignment_content,created_at,deleted,id,independent_problem_count,independent_workload_hours,meeting_problem_count,meeting_workload_hours,review_record_id,reviewer_name,sort_order,updated_at"
EXPECT_EL="authority,created_at,id,match_mode_report_id,match_mode_report_legacy_id,review_record_id,updated_at"
EXPECT_PL="created_at,id,match_mode_problem_id,match_mode_problem_legacy_id,review_problem_item_id,review_record_id,updated_at"

check_columns() {
  local table="$1" expected="$2" actual
  actual="$(psql_one "select coalesce(string_agg(column_name, ',' order by column_name), '') from information_schema.columns where table_schema = 'public' and table_name = '$table';" -At)"
  [[ "$actual" == "$expected" ]] || fail "table $table column set mismatch on target (20001):
  expected: $expected
  actual  : $actual"
  log "column check ok: $table"
}

check_columns review_records "$EXPECT_RR"
check_columns review_record_experts "$EXPECT_RE"
check_columns review_problem_items "$EXPECT_PI"
check_columns review_record_descriptions "$EXPECT_RD"
check_columns review_record_contents "$EXPECT_RC"
check_columns review_data_match_mode_edit_links "$EXPECT_EL"
check_columns review_data_match_mode_problem_edit_links "$EXPECT_PL"

DST_FLYWAY="$(psql_one "select version from flyway_schema_history where success order by installed_rank desc limit 1;" -At)"
log "target flyway version: $DST_FLYWAY"
SRC_FLYWAY="$(grep '^sourceFlywayVersion=' "$EXP_DIR/manifest.env" | cut -d= -f2-)"
log "source flyway version (from manifest): $SRC_FLYWAY"

# ── 第 3 步：重建暂存 schema 并载入导出数据 ─────────────────────────────────
# 列清单与 export 脚本逐字一致（CSV 列序的权威定义在 migrate-review-data-export.sh）。
RR_COLS="id, project_name, title, module_name, review_type, review_date, review_owner, review_scale_pages, review_product, author_name, review_version, not_reach_standard_reason, source_file_name, weighted_defect_density, gitlab_project_id, gitlab_resource_iid, gitlab_resource_type, search_text, search_compact, search_spell, search_initials, title_search_text, title_search_compact, title_search_spell, title_search_initials, created_by, deleted, created_at, updated_at"
RE_COLS="id, review_record_id, expert_name, sort_order, deleted, created_at, updated_at"
PI_COLS="id, review_record_id, reviewer_name, workload_hours, review_category, document_position, problem_category, problem_description, suggested_solution, owner_name, rejection_reason, problem_status, created_by, deleted, created_at, updated_at"
RD_COLS="id, review_record_id, review_product, review_version, author_name, review_scale_pages, unit, sort_order, deleted, created_at, updated_at"
RC_COLS="id, review_record_id, reviewer_name, assignment_content, independent_workload_hours, independent_problem_count, meeting_workload_hours, meeting_problem_count, sort_order, deleted, created_at, updated_at"
EL_COLS="id, match_mode_report_id, match_mode_report_legacy_id, review_record_id, authority, last_handover_at, created_at, updated_at"
PL_COLS="id, match_mode_problem_id, match_mode_problem_legacy_id, review_record_id, review_problem_item_id, created_at, updated_at"

psql_stdin <<'EOSQL' > /dev/null
drop schema if exists mig_18181 cascade;
create schema mig_18181;
create table mig_18181.review_records (like public.review_records);
create table mig_18181.review_record_experts (like public.review_record_experts);
create table mig_18181.review_problem_items (like public.review_problem_items);
create table mig_18181.review_record_descriptions (like public.review_record_descriptions);
create table mig_18181.review_record_contents (like public.review_record_contents);
create table mig_18181.review_data_match_mode_edit_links (like public.review_data_match_mode_edit_links);
alter table mig_18181.review_data_match_mode_edit_links add column last_handover_at timestamp;
create table mig_18181.review_data_match_mode_problem_edit_links (like public.review_data_match_mode_problem_edit_links);
EOSQL

load_table() {
  local table="$1" cols="$2"
  { printf '\\copy mig_18181.%s (%s) from stdin with (format csv)\n' "$table" "$cols"; cat "$EXP_DIR/$table.csv"; } | psql_stdin > /dev/null
}

load_table review_records "$RR_COLS"
load_table review_record_experts "$RE_COLS"
load_table review_problem_items "$PI_COLS"
load_table review_record_descriptions "$RD_COLS"
load_table review_record_contents "$RC_COLS"
load_table review_data_match_mode_edit_links "$EL_COLS"
load_table review_data_match_mode_problem_edit_links "$PL_COLS"

for t in $TABLES; do
  expected="$(grep "^${t}=" "$EXP_DIR/manifest.env" | head -1 | cut -d= -f2-)"
  actual="$(psql_one "select count(*) from mig_18181.$t;" -At)"
  [[ "$actual" == "$expected" ]] || fail "row count mismatch after load: $t expected $expected, loaded $actual"
  log "staging loaded: $t = $actual rows"
done

# ── 第 4 步：组装决策 SQL（dry-run 与 apply 共用同一决策定义）───────────────
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$TMP_DIR/decision-body.sql" <<'EOSQL'
with src_child_max as (
    select review_record_id, max(updated_at) as max_at from (
        select review_record_id, updated_at from mig_18181.review_record_experts
        union all select review_record_id, updated_at from mig_18181.review_problem_items
        union all select review_record_id, updated_at from mig_18181.review_record_descriptions
        union all select review_record_id, updated_at from mig_18181.review_record_contents
    ) u group by review_record_id
),
src_eff as (
    select s.id, greatest(s.updated_at, coalesce(cm.max_at, s.updated_at)) as eff_at
    from mig_18181.review_records s
    left join src_child_max cm on cm.review_record_id = s.id
),
dst_child_max as (
    select review_record_id, max(updated_at) as max_at from (
        select review_record_id, updated_at from public.review_record_experts
        union all select review_record_id, updated_at from public.review_problem_items
        union all select review_record_id, updated_at from public.review_record_descriptions
        union all select review_record_id, updated_at from public.review_record_contents
    ) u group by review_record_id
),
dst_eff as (
    select r.id, greatest(r.updated_at, coalesce(cm.max_at, r.updated_at)) as eff_at
    from public.review_records r
    left join dst_child_max cm on cm.review_record_id = r.id
),
src_pool as (
    select s.id
    from mig_18181.review_records s
    left join mig_18181.review_data_match_mode_edit_links el on el.review_record_id = s.id
    where s.deleted = false
      and (:include_legacy or el.authority is distinct from 'LEGACY_MANAGED')
),
k1 as (
    select p.id as src_id, t.id as dst_id, 1 as match_key
    from src_pool p
    join mig_18181.review_data_match_mode_edit_links el on el.review_record_id = p.id
    join public.review_data_match_mode_edit_links tl
      on tl.match_mode_report_legacy_id = el.match_mode_report_legacy_id
    join public.review_records t on t.id = tl.review_record_id
),
k2 as (
    select p.id as src_id, t.id as dst_id, 2 as match_key
    from src_pool p
    join mig_18181.review_records s on s.id = p.id
    join public.review_records t
      on t.deleted = false
     and s.gitlab_project_id is not null and t.gitlab_project_id = s.gitlab_project_id
     and s.gitlab_resource_type is not null and t.gitlab_resource_type = s.gitlab_resource_type
     and s.gitlab_resource_iid is not null and t.gitlab_resource_iid = s.gitlab_resource_iid
    where p.id not in (select src_id from k1)
),
k3 as (
    select p.id as src_id, t.id as dst_id, 3 as match_key
    from src_pool p
    join mig_18181.review_records s on s.id = p.id
    join public.review_records t
      on t.deleted = false
     and s.project_name is not distinct from t.project_name
     and s.title is not distinct from t.title
     and s.review_type is not distinct from t.review_type
     and s.review_date is not distinct from t.review_date
     and s.review_version is not distinct from t.review_version
    where p.id not in (select src_id from k1)
      and p.id not in (select src_id from k2)
),
matched as (
    select src_id, dst_id, match_key,
           row_number() over (partition by src_id order by de.eff_at desc, dst_id) as rn,
           count(*) over (partition by src_id) as match_count
    from (
        select * from k1 union all select * from k2 union all select * from k3
    ) m
    join dst_eff de on de.id = m.dst_id
),
pick as (
    select m.src_id, m.dst_id, m.match_key, m.match_count,
           row_number() over (partition by m.dst_id order by se.eff_at desc, m.src_id desc) as dst_rn
    from matched m
    join src_eff se on se.id = m.src_id
    where m.rn = 1
),
decisions as (
    select
        s.id as src_id,
        s.project_name,
        s.title,
        s.review_type,
        s.review_date,
        s.review_version,
        s.deleted,
        se.eff_at as src_eff,
        el.authority as link_authority,
        el.match_mode_report_legacy_id as link_legacy_id,
        p.dst_id,
        p.match_key,
        p.match_count,
        de.eff_at as dst_eff,
        case
            when s.deleted then 'SKIP_DELETED'
            when el.authority = 'LEGACY_MANAGED' and not :include_legacy then 'SKIP_LEGACY'
            when p.dst_id is null then 'INSERT'
            when p.dst_rn > 1 then 'SKIP_CONFLICT'
            when se.eff_at > de.eff_at then 'REPLACE'
            else 'KEEP'
        end as decision
    from mig_18181.review_records s
    left join mig_18181.review_data_match_mode_edit_links el on el.review_record_id = s.id
    left join src_eff se on se.id = s.id
    left join pick p on p.src_id = s.id
    left join dst_eff de on de.id = p.dst_id
)
select * from decisions
EOSQL

expand_sql() {
  awk -v body="$(cat "$TMP_DIR/decision-body.sql")" '{gsub(/@BODY@/, body); print}' "$1"
}

# dry-run 报告：6 段查询，全部只读。
cat > "$TMP_DIR/dryrun.template.sql" <<'EOSQL'
\pset pager off
\echo
\echo ============ 决策概要 ============
select decision, count(*) as cnt from ( @BODY@ ) d group by decision order by decision;

\echo
\echo ============ 逐条明细（按决策分组）============
select d.decision,
       d.src_id,
       coalesce(d.dst_id::text, '-') as dst_id,
       coalesce(d.match_key::text, '-') as key,
       coalesce(d.match_count::text, '-') as multi,
       left(d.project_name, 24) as src_project,
       left(d.title, 40) as src_title,
       to_char(d.review_date, 'YYYY-MM-DD') as review_date,
       to_char(d.src_eff, 'MM-DD HH24:MI') as src_updated,
       coalesce(to_char(d.dst_eff, 'MM-DD HH24:MI'), '-') as dst_updated,
       coalesce(d.link_authority, '-') as link_auth
from ( @BODY@ ) d
order by d.decision, d.src_id;

\echo
\echo ============ edit_link 重解析（仅 INSERT/REPLACE）============
select el.review_record_id as src_record,
       el.match_mode_report_legacy_id as legacy_report_id,
       el.authority,
       coalesce((select r.id::text from public.review_data_match_mode_reports r
                 where r.legacy_id = el.match_mode_report_legacy_id), 'DROP(20001无该快照)') as resolved_report_id,
       d.decision
from mig_18181.review_data_match_mode_edit_links el
join ( @BODY@ ) d on d.src_id = el.review_record_id
where d.decision in ('INSERT', 'REPLACE')
order by el.review_record_id;

\echo
\echo ============ problem_edit_link 重解析（仅 INSERT/REPLACE）============
select pl.review_record_id as src_record,
       pl.match_mode_problem_legacy_id as legacy_problem_id,
       coalesce((select pd.id::text from public.review_data_match_mode_problem_details pd
                 where pd.legacy_id = pl.match_mode_problem_legacy_id), 'DROP(20001无该问题)') as resolved_problem_id,
       coalesce(pi.deleted::text, '-') as src_item_deleted,
       d.decision
from mig_18181.review_data_match_mode_problem_edit_links pl
left join mig_18181.review_problem_items pi on pi.id = pl.review_problem_item_id
join ( @BODY@ ) d on d.src_id = pl.review_record_id
where d.decision in ('INSERT', 'REPLACE')
order by pl.review_record_id, pl.id;

\echo
\echo ============ 迁移记录子表规模（非删行）============
select s.id as src_id,
       d.decision,
       (select count(*) from mig_18181.review_record_experts c where c.review_record_id = s.id and c.deleted = false) as experts,
       (select count(*) from mig_18181.review_problem_items c where c.review_record_id = s.id and c.deleted = false) as problems,
       (select count(*) from mig_18181.review_record_descriptions c where c.review_record_id = s.id and c.deleted = false) as descriptions,
       (select count(*) from mig_18181.review_record_contents c where c.review_record_id = s.id and c.deleted = false) as contents
from mig_18181.review_records s
join ( @BODY@ ) d on d.src_id = s.id
where d.decision in ('INSERT', 'REPLACE', 'SKIP_CONFLICT')
order by s.id;

\echo
\echo [报告结束] 请逐条复核 INSERT/REPLACE/SKIP_CONFLICT 与链接重解析，确认后执行 --apply。
EOSQL

# apply：单事务。temp 表冻结决策 → 删旧链接/旧子树 → 插入/整树替换 → 链接按 legacy_id 重解析
# → 守恒校验（任一不符 raise，整体回滚）→ 清缓存 → 删暂存 → 提交。
cat > "$TMP_DIR/apply.template.sql" <<'EOSQL'
\pset pager off
begin;

create temp table mig_decisions as
@BODY@;

create temp table mig_map_records (src_id bigint primary key, dst_id bigint not null, action text not null);

insert into mig_map_records (src_id, dst_id, action)
select d.src_id, nextval(pg_get_serial_sequence('public.review_records', 'id')), 'INSERT'
from mig_decisions d
where d.decision = 'INSERT';

insert into mig_map_records (src_id, dst_id, action)
select d.src_id, d.dst_id, 'REPLACE'
from mig_decisions d
where d.decision = 'REPLACE';

-- 先删替换目标现有的链接（edit_links 对 review_record_id 有唯一约束）
delete from public.review_data_match_mode_problem_edit_links
where review_record_id in (select dst_id from mig_map_records);
delete from public.review_data_match_mode_edit_links
where review_record_id in (select dst_id from mig_map_records);

-- 先删替换目标现有子树（INSERT 的新 id 天然无行）；与平台 replaceExperts 相同的硬删语义
delete from public.review_record_experts
where review_record_id in (select dst_id from mig_map_records);
delete from public.review_problem_items
where review_record_id in (select dst_id from mig_map_records);
delete from public.review_record_descriptions
where review_record_id in (select dst_id from mig_map_records);
delete from public.review_record_contents
where review_record_id in (select dst_id from mig_map_records);

-- 主表：插入（新 id 取自 20001 序列）
insert into public.review_records (id, project_name, title, module_name, review_type, review_date, review_owner, review_scale_pages, review_product, author_name, review_version, not_reach_standard_reason, source_file_name, weighted_defect_density, gitlab_project_id, gitlab_resource_iid, gitlab_resource_type, search_text, search_compact, search_spell, search_initials, title_search_text, title_search_compact, title_search_spell, title_search_initials, created_by, deleted, created_at, updated_at)
select m.dst_id, s.project_name, s.title, s.module_name, s.review_type, s.review_date, s.review_owner, s.review_scale_pages, s.review_product, s.author_name, s.review_version, s.not_reach_standard_reason, s.source_file_name, s.weighted_defect_density, s.gitlab_project_id, s.gitlab_resource_iid, s.gitlab_resource_type, s.search_text, s.search_compact, s.search_spell, s.search_initials, s.title_search_text, s.title_search_compact, s.title_search_spell, s.title_search_initials, s.created_by, s.deleted, s.created_at, s.updated_at
from mig_18181.review_records s
join mig_map_records m on m.src_id = s.id
where m.action = 'INSERT';

-- 主表：整树替换（保留 20001 记录 id，全部业务列/影子列/时间戳/created_by 覆盖）
update public.review_records r
set project_name = s.project_name, title = s.title, module_name = s.module_name,
    review_type = s.review_type, review_date = s.review_date, review_owner = s.review_owner,
    review_scale_pages = s.review_scale_pages, review_product = s.review_product,
    author_name = s.author_name, review_version = s.review_version,
    not_reach_standard_reason = s.not_reach_standard_reason, source_file_name = s.source_file_name,
    weighted_defect_density = s.weighted_defect_density,
    gitlab_project_id = s.gitlab_project_id, gitlab_resource_iid = s.gitlab_resource_iid,
    gitlab_resource_type = s.gitlab_resource_type,
    search_text = s.search_text, search_compact = s.search_compact,
    search_spell = s.search_spell, search_initials = s.search_initials,
    title_search_text = s.title_search_text, title_search_compact = s.title_search_compact,
    title_search_spell = s.title_search_spell, title_search_initials = s.title_search_initials,
    created_by = s.created_by, deleted = s.deleted, created_at = s.created_at, updated_at = s.updated_at
from mig_18181.review_records s
join mig_map_records m on m.src_id = s.id
where r.id = m.dst_id and m.action = 'REPLACE';

-- 子表：专家（新 id）
insert into public.review_record_experts (id, review_record_id, expert_name, sort_order, deleted, created_at, updated_at)
select nextval(pg_get_serial_sequence('public.review_record_experts', 'id')), m.dst_id, s.expert_name, s.sort_order, s.deleted, s.created_at, s.updated_at
from mig_18181.review_record_experts s
join mig_map_records m on m.src_id = s.review_record_id
where s.deleted = false;

-- 子表：问题项（先建 id 映射，问题链接引用新 id）
create temp table mig_map_problems (src_id bigint primary key, dst_id bigint not null);

insert into mig_map_problems (src_id, dst_id)
select s.id, nextval(pg_get_serial_sequence('public.review_problem_items', 'id'))
from mig_18181.review_problem_items s
join mig_map_records m on m.src_id = s.review_record_id
where s.deleted = false;

insert into public.review_problem_items (id, review_record_id, reviewer_name, workload_hours, review_category, document_position, problem_category, problem_description, suggested_solution, owner_name, rejection_reason, problem_status, created_by, deleted, created_at, updated_at)
select mp.dst_id, m.dst_id, s.reviewer_name, s.workload_hours, s.review_category, s.document_position, s.problem_category, s.problem_description, s.suggested_solution, s.owner_name, s.rejection_reason, s.problem_status, s.created_by, s.deleted, s.created_at, s.updated_at
from mig_18181.review_problem_items s
join mig_map_records m on m.src_id = s.review_record_id
join mig_map_problems mp on mp.src_id = s.id;

-- 子表：工作产品描述（新 id）
insert into public.review_record_descriptions (id, review_record_id, review_product, review_version, author_name, review_scale_pages, unit, sort_order, deleted, created_at, updated_at)
select nextval(pg_get_serial_sequence('public.review_record_descriptions', 'id')), m.dst_id, s.review_product, s.review_version, s.author_name, s.review_scale_pages, s.unit, s.sort_order, s.deleted, s.created_at, s.updated_at
from mig_18181.review_record_descriptions s
join mig_map_records m on m.src_id = s.review_record_id
where s.deleted = false;

-- 子表：评审分工（新 id）
insert into public.review_record_contents (id, review_record_id, reviewer_name, assignment_content, independent_workload_hours, independent_problem_count, meeting_workload_hours, meeting_problem_count, sort_order, deleted, created_at, updated_at)
select nextval(pg_get_serial_sequence('public.review_record_contents', 'id')), m.dst_id, s.reviewer_name, s.assignment_content, s.independent_workload_hours, s.independent_problem_count, s.meeting_workload_hours, s.meeting_problem_count, s.sort_order, s.deleted, s.created_at, s.updated_at
from mig_18181.review_record_contents s
join mig_map_records m on m.src_id = s.review_record_id
where s.deleted = false;

-- 链接：正式记录 ↔ 兼容快照（match_mode_report_id 按 legacy_id 在 20001 重解析；
-- authority 恒写 PLATFORM_OWNED——20001 约束仅允许该值；解析不到的行由内连接自然丢弃并在报告中列出）
insert into public.review_data_match_mode_edit_links (id, match_mode_report_id, match_mode_report_legacy_id, review_record_id, authority, created_at, updated_at)
select nextval(pg_get_serial_sequence('public.review_data_match_mode_edit_links', 'id')),
       rpt.id, el.match_mode_report_legacy_id, m.dst_id, 'PLATFORM_OWNED', el.created_at, el.updated_at
from mig_18181.review_data_match_mode_edit_links el
join mig_map_records m on m.src_id = el.review_record_id
join public.review_data_match_mode_reports rpt on rpt.legacy_id = el.match_mode_report_legacy_id;

insert into public.review_data_match_mode_problem_edit_links (id, match_mode_problem_id, match_mode_problem_legacy_id, review_record_id, review_problem_item_id, created_at, updated_at)
select nextval(pg_get_serial_sequence('public.review_data_match_mode_problem_edit_links', 'id')),
       pd.id, pl.match_mode_problem_legacy_id, m.dst_id, mp.dst_id, pl.created_at, pl.updated_at
from mig_18181.review_data_match_mode_problem_edit_links pl
join mig_map_records m on m.src_id = pl.review_record_id
join mig_map_problems mp on mp.src_id = pl.review_problem_item_id
join public.review_data_match_mode_problem_details pd on pd.legacy_id = pl.match_mode_problem_legacy_id;

-- 守恒校验：任一不符即 raise，整个事务回滚
do $mig_guard$
declare
  v_src bigint;
  v_dec bigint;
  v_bad bigint;
begin
  select count(*) into v_src from mig_18181.review_records;
  select count(*) into v_dec from mig_decisions;
  if v_src <> v_dec then
    raise exception 'decision conservation failed: source % rows vs % decisions', v_src, v_dec;
  end if;

  select count(*) into v_bad
  from mig_map_records m
  where (select count(*) from public.review_record_experts c where c.review_record_id = m.dst_id)
     <> (select count(*) from mig_18181.review_record_experts c where c.review_record_id = m.src_id and c.deleted = false)
     or (select count(*) from public.review_problem_items c where c.review_record_id = m.dst_id)
     <> (select count(*) from mig_18181.review_problem_items c where c.review_record_id = m.src_id and c.deleted = false)
     or (select count(*) from public.review_record_descriptions c where c.review_record_id = m.dst_id)
     <> (select count(*) from mig_18181.review_record_descriptions c where c.review_record_id = m.src_id and c.deleted = false)
     or (select count(*) from public.review_record_contents c where c.review_record_id = m.dst_id)
     <> (select count(*) from mig_18181.review_record_contents c where c.review_record_id = m.src_id and c.deleted = false);
  if v_bad > 0 then
    raise exception 'child-tree conservation failed for % migrated records', v_bad;
  end if;

  select count(*) into v_bad
  from mig_map_records m
  where (select count(*) from public.review_data_match_mode_edit_links l where l.review_record_id = m.dst_id)
     <> (select count(*) from mig_18181.review_data_match_mode_edit_links l
         where l.review_record_id = m.src_id
           and exists (select 1 from public.review_data_match_mode_reports r
                       where r.legacy_id = l.match_mode_report_legacy_id));
  if v_bad > 0 then
    raise exception 'edit-link conservation failed for % migrated records', v_bad;
  end if;

  select count(*) into v_bad
  from mig_map_records m
  where (select count(*) from public.review_data_match_mode_problem_edit_links l where l.review_record_id = m.dst_id)
     <> (select count(*) from mig_18181.review_data_match_mode_problem_edit_links l
         where l.review_record_id = m.src_id
           and exists (select 1 from public.review_data_match_mode_problem_details pd
                       where pd.legacy_id = l.match_mode_problem_legacy_id)
           and exists (select 1 from mig_map_problems mp where mp.src_id = l.review_problem_item_id));
  if v_bad > 0 then
    raise exception 'problem-edit-link conservation failed for % migrated records', v_bad;
  end if;
end
$mig_guard$;

\echo
\echo ============ 已执行决策概要 ============
select decision, count(*) as cnt from mig_decisions group by decision order by decision;

\echo
\echo ============ edit_link 丢弃清单（20001 无对应兼容快照）============
select el.review_record_id as src_record,
       el.match_mode_report_legacy_id as legacy_report_id,
       'REPORT_NOT_FOUND' as reason
from mig_18181.review_data_match_mode_edit_links el
join mig_map_records m on m.src_id = el.review_record_id
where not exists (select 1 from public.review_data_match_mode_reports r
                  where r.legacy_id = el.match_mode_report_legacy_id)
order by el.review_record_id;

\echo
\echo ============ problem_edit_link 丢弃清单 ============
select pl.review_record_id as src_record,
       pl.match_mode_problem_legacy_id as legacy_problem_id,
       case
         when not exists (select 1 from public.review_data_match_mode_problem_details pd
                          where pd.legacy_id = pl.match_mode_problem_legacy_id)
           then 'PROBLEM_NOT_FOUND'
         else 'SRC_ITEM_NOT_MIGRATED'
       end as reason
from mig_18181.review_data_match_mode_problem_edit_links pl
join mig_map_records m on m.src_id = pl.review_record_id
where not exists (select 1 from public.review_data_match_mode_problem_details pd
                  where pd.legacy_id = pl.match_mode_problem_legacy_id)
   or not exists (select 1 from mig_map_problems mp where mp.src_id = pl.review_problem_item_id)
order by pl.review_record_id, pl.id;

-- 纯查询缓存：合并后旧行集可能短时命中，与数据变更同事务原子清空（自动重建）
truncate table page_record_snapshots;

-- 草稿暂存随数据变更同事务删除（失败回滚时暂存保留供排查）
drop schema mig_18181 cascade;

commit;
EOSQL

expand_sql "$TMP_DIR/dryrun.template.sql" > "$TMP_DIR/dryrun.sql"
expand_sql "$TMP_DIR/apply.template.sql" > "$TMP_DIR/apply.sql"

TS="$(date +%Y%m%dT%H%M%S)"

if [[ "$APPLY" == "true" ]]; then
  # ── 第 5 步：--apply 前置全库备份 + 校验 ──────────────────────────────────
  BACKUP_FILE="$EXP_DIR/pre-merge-backup-$TS.dump"
  log "full-database backup starting (may take a while): $BACKUP_FILE"
  "${DOCKER[@]}" exec "$PG_ID" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$BACKUP_FILE"
  [[ -s "$BACKUP_FILE" ]] || fail "backup produced an empty file, aborting"
  # 用纯文件名做 cp 源：规避 Git Bash 对宿主机路径与容器路径的转换差异（Linux 上行为一致）
  ( cd "$EXP_DIR" && "${DOCKER[@]}" cp "pre-merge-backup-$TS.dump" "$PG_ID:/tmp/mig-verify.dump" )
  "${DOCKER[@]}" exec "$PG_ID" pg_restore --list /tmp/mig-verify.dump > /dev/null
  "${DOCKER[@]}" exec "$PG_ID" rm -f /tmp/mig-verify.dump
  log "backup verified via pg_restore --list: $BACKUP_FILE"

  # ── 第 6 步：单事务合并 ──────────────────────────────────────────────────
  APPLY_LOG="$EXP_DIR/apply-log-$TS.txt"
  log "applying merge in a single transaction (any failure rolls back everything)..."
  psql_stdin < "$TMP_DIR/apply.sql" 2>&1 | tee "$APPLY_LOG"
  log "merge committed. staging schema dropped, page_record_snapshots cleared."
  log "apply log: $APPLY_LOG   pre-merge backup: $BACKUP_FILE"
  log "next: spot-check migrated records on the 20001 UI (list / detail / search); conservation checks passed inside the transaction."
else
  # ── 第 5 步：dry-run 报告（不落库）───────────────────────────────────────
  REPORT_FILE="$EXP_DIR/dry-run-report-$TS.txt"
  log "dry-run: computing decisions (no production data will be written)..."
  psql_stdin < "$TMP_DIR/dryrun.sql" 2>&1 | tee "$REPORT_FILE"
  log "dry-run report saved: $REPORT_FILE"
  log "next: review every INSERT/REPLACE/SKIP_CONFLICT row, then re-run with --apply to execute."
fi
