#!/usr/bin/env bash
set -euo pipefail

# 18181 -> 20001 评审数据迁移 · 第一步：从源实例（18181）只读导出评审数据 7 张表
# （主表 + 四张子表 + 两张老平台链接表；兼容快照表不迁，20001 自有同源同步）。
# 源实例全程只读，不做任何写入；导出物为显式列清单的 7 个 CSV + manifest + SHA256。
#
# 用法:
#   bash migrate-review-data-export.sh <18181部署目录> <导出目录>
#
# 前置（源栈当前已 docker compose down、数据卷保留时，先临时只起 postgres）:
#   cd <18181部署目录>
#   docker volume ls | grep 18181                                 # 确认数据卷仍在（外部卷）
#   docker ps -a --format '{{.Names}}\t{{.Status}}' | grep qaflex # qaflex-postgres 名占用预检
#   docker compose up -d postgres                                 # 只起 postgres，勿整套 up
#   docker ps | grep postgres                                     # 等 healthy（WAL 回放约 10-60 秒）
#   - qaflex-postgres 名未被占用 -> 直接 up；已被同机其他实例占用（如其曾手动升级）->
#     在部署目录写临时 docker-compose.override.yml 把 postgres 的 container_name 改名
#     （如 qaflex-postgres-18181-export）再 up；本脚本按部署目录的项目+服务定位容器，
#     不依赖容器名，改名不影响导出。
#   - 勿整套 `docker compose up -d`：源栈 compose 声明固定名 qaflex-{frontend,backend,postgres}，
#     前后端名可能已被同机其他实例占用，整套 up 会撞名失败。
#   - 导出完成后 `docker compose down`（不带 -v）恢复 down 状态、数据卷保留；
#     用过 override 则一并删除该文件。
#
# 版本守卫：对 7 张表逐表做 information_schema 列集合精确比对，任何列漂移立即中止
# （18181 实况构建 20260729T093338Z；20260724→20260729 窗口迁移零触及评审表，列集已核对一致）。

SRC_DIR="${1:-}"
OUT_DIR="${2:-}"

fail() { echo "[export] ERROR: $*" >&2; exit 1; }
log() { echo "[export] $*"; }

[[ -n "$SRC_DIR" && -n "$OUT_DIR" ]] || fail "usage: migrate-review-data-export.sh <source-deployment-dir> <output-dir>"
SRC_DIR="$(cd -- "$SRC_DIR" && pwd)" || fail "source deployment dir not found: $SRC_DIR"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd -- "$OUT_DIR" && pwd)"

if docker info >/dev/null 2>&1; then
  DOCKER=(docker)
elif sudo docker info >/dev/null 2>&1; then
  DOCKER=(sudo docker)
else
  fail "Docker is unavailable"
fi

cd "$SRC_DIR"
[[ -f .env && -f docker-compose.yml ]] || fail "run from an existing deployment directory containing .env and docker-compose.yml"

PG_ID="$("${DOCKER[@]}" compose --env-file .env ps -q postgres)"
[[ -n "$PG_ID" ]] || fail "postgres service is not running in $SRC_DIR (if the stack was brought down: run 'docker compose up -d postgres' in that directory, wait for healthy, then rerun; do NOT bring up the full stack)"

# 评审数据持久化全集（导出列 = 20260724→20260729 链实例实际列集，迁移史逐文件核对：该窗口迁移零触及评审表）。
RR_COLS="id, project_name, title, module_name, review_type, review_date, review_owner, review_scale_pages, review_product, author_name, review_version, not_reach_standard_reason, source_file_name, weighted_defect_density, gitlab_project_id, gitlab_resource_iid, gitlab_resource_type, search_text, search_compact, search_spell, search_initials, title_search_text, title_search_compact, title_search_spell, title_search_initials, created_by, deleted, created_at, updated_at"
RE_COLS="id, review_record_id, expert_name, sort_order, deleted, created_at, updated_at"
PI_COLS="id, review_record_id, reviewer_name, workload_hours, review_category, document_position, problem_category, problem_description, suggested_solution, owner_name, rejection_reason, problem_status, created_by, deleted, created_at, updated_at"
RD_COLS="id, review_record_id, review_product, review_version, author_name, review_scale_pages, unit, sort_order, deleted, created_at, updated_at"
RC_COLS="id, review_record_id, reviewer_name, assignment_content, independent_workload_hours, independent_problem_count, meeting_workload_hours, meeting_problem_count, sort_order, deleted, created_at, updated_at"
EL_COLS="id, match_mode_report_id, match_mode_report_legacy_id, review_record_id, authority, last_handover_at, created_at, updated_at"
PL_COLS="id, match_mode_problem_id, match_mode_problem_legacy_id, review_record_id, review_problem_item_id, created_at, updated_at"

# 预期列集合（字母序），与 information_schema 比对，任何漂移立即中止。
EXPECT_RR="author_name,created_at,created_by,deleted,gitlab_project_id,gitlab_resource_iid,gitlab_resource_type,id,module_name,not_reach_standard_reason,project_name,review_date,review_owner,review_product,review_scale_pages,review_type,review_version,search_compact,search_initials,search_spell,search_text,source_file_name,title,title_search_compact,title_search_initials,title_search_spell,title_search_text,updated_at,weighted_defect_density"
EXPECT_RE="created_at,deleted,expert_name,id,review_record_id,sort_order,updated_at"
EXPECT_PI="created_at,created_by,deleted,document_position,id,owner_name,problem_category,problem_description,problem_status,rejection_reason,review_category,review_record_id,reviewer_name,suggested_solution,updated_at,workload_hours"
EXPECT_RD="author_name,created_at,deleted,id,review_product,review_record_id,review_scale_pages,review_version,sort_order,unit,updated_at"
EXPECT_RC="assignment_content,created_at,deleted,id,independent_problem_count,independent_workload_hours,meeting_problem_count,meeting_workload_hours,review_record_id,reviewer_name,sort_order,updated_at"
EXPECT_EL="authority,created_at,id,last_handover_at,match_mode_report_id,match_mode_report_legacy_id,review_record_id,updated_at"
EXPECT_PL="created_at,id,match_mode_problem_id,match_mode_problem_legacy_id,review_problem_item_id,review_record_id,updated_at"

psql_exec() {
  "${DOCKER[@]}" exec -i "$PG_ID" sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' psql "$@"
}

check_columns() {
  local table="$1" expected="$2" actual
  actual="$(printf "select coalesce(string_agg(column_name, ',' order by column_name), '') from information_schema.columns where table_schema = 'public' and table_name = '%s';\n" "$table" | psql_exec -At)"
  [[ "$actual" == "$expected" ]] || fail "table $table column set mismatch (expected by packaging-history analysis):
  expected: $expected
  actual  : $actual"
  log "column check ok: $table"
}

log "source postgres container: $PG_ID"
check_columns review_records "$EXPECT_RR"
check_columns review_record_experts "$EXPECT_RE"
check_columns review_problem_items "$EXPECT_PI"
check_columns review_record_descriptions "$EXPECT_RD"
check_columns review_record_contents "$EXPECT_RC"
check_columns review_data_match_mode_edit_links "$EXPECT_EL"
check_columns review_data_match_mode_problem_edit_links "$EXPECT_PL"

FLYWAY="$(printf "select version from flyway_schema_history where success order by installed_rank desc limit 1;\n" | psql_exec -At)"
log "source flyway version: $FLYWAY"

export_table() {
  local table="$1" cols="$2"
  # 空表合法（0 行导出即空文件），行数以 manifest 为准、merge 侧按 manifest 校验。
  printf '\\copy (select %s from %s order by id) to stdout with (format csv)\n' "$cols" "$table" \
    | psql_exec > "$OUT_DIR/$table.csv"
  local count
  count="$(printf "select count(*) from %s;\n" "$table" | psql_exec -At)"
  log "exported $table: $count rows"
  printf '%s=%s\n' "$table" "$count" >> "$OUT_DIR/manifest.env"
}

: > "$OUT_DIR/manifest.env"
printf 'exportedAt=%s\n' "$(date +%Y-%m-%dT%H:%M:%S%z)" >> "$OUT_DIR/manifest.env"
printf 'sourceDeploymentDir=%s\n' "$SRC_DIR" >> "$OUT_DIR/manifest.env"
printf 'sourcePostgresContainer=%s\n' "$PG_ID" >> "$OUT_DIR/manifest.env"
printf 'sourceFlywayVersion=%s\n' "$FLYWAY" >> "$OUT_DIR/manifest.env"

export_table review_records "$RR_COLS"
export_table review_record_experts "$RE_COLS"
export_table review_problem_items "$PI_COLS"
export_table review_record_descriptions "$RD_COLS"
export_table review_record_contents "$RC_COLS"
export_table review_data_match_mode_edit_links "$EL_COLS"
export_table review_data_match_mode_problem_edit_links "$PL_COLS"

(cd "$OUT_DIR" && sha256sum *.csv > SHA256SUMS.txt)
log "export complete: $OUT_DIR"
log "next: copy this directory + migrate-review-data-merge.sh to the 20001 side, run dry-run first"
