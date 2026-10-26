#!/usr/bin/env bash
# 在Ubuntu主机运行冻结工具；凭据从只读挂载的pgpass取得，不写入命令行。
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
kind="${1:?usage: bash transfer.sh source|target|preflight [tool arguments]}"
shift
case "$kind" in
  source|preflight)
    : "${PGHOST:?请先填写源库非秘密连接信息}"
    : "${PGPORT:?}" "${PGDATABASE:?}" "${PGUSER:?}"
    [[ -f .secrets/source.pgpass ]] || { echo '缺少.secrets/source.pgpass' >&2; exit 2; }
    passfile=source.pgpass
    tool=gitlab_bounded_sql_export.py
    [[ "$kind" != preflight ]] || tool=source-preflight.py
    ;;
  target)
    PGHOST=127.0.0.1
    PGPORT="${TARGET_PGPORT:-25434}"
    PGDATABASE=gitlabhq_production
    PGUSER=gitlab
    passfile=target.pgpass
    tool=gitlab_bounded_sql_import.py
    [[ -f .secrets/target-token ]] || { echo '缺少目标归属令牌' >&2; exit 2; }
    export DCP_TARGET_TOKEN
    DCP_TARGET_TOKEN="$(cat .secrets/target-token)"
    ;;
  *) echo '只接受source、target或preflight' >&2; exit 2 ;;
esac
export PGHOST PGPORT PGDATABASE PGUSER
docker run --rm --network host \
  -e PGHOST -e PGPORT -e PGDATABASE -e PGUSER -e PGSSLMODE \
  -e PGPASSFILE="/secrets/$passfile" -e DCP_TARGET_TOKEN \
  -v "$PWD/.secrets:/secrets:ro" -v "$PWD/scripts:/scripts:ro" \
  -v "$PWD/work:/work" -v "$PWD/reports:/reports" \
  --workdir /work dcp-gitlab-transfer-runtime:20261026 \
  python "/scripts/$tool" "$@"
