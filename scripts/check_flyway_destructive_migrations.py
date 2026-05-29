from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATION_DIR = ROOT / "backend/src/main/resources/db/migration"
REQUIRED_MARKERS = [
    "-- destructive-migration-reviewed:",
    "-- destructive-migration-recovery:",
]
DESTRUCTIVE_PATTERNS = [
    re.compile(r"\bdrop\s+table\b", re.IGNORECASE),
    re.compile(r"\bdrop\s+column\b", re.IGNORECASE),
    re.compile(r"\balter\s+table\b.*\brename\s+to\b", re.IGNORECASE),
    re.compile(r"\balter\s+table\b.*\brename\s+column\b", re.IGNORECASE),
]


def git_lines(*args: str) -> list[str]:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def optional_git_lines(*args: str) -> list[str]:
    try:
        return git_lines(*args)
    except subprocess.CalledProcessError:
        return []


def changed_migrations() -> list[Path]:
    paths: set[str] = set()
    merge_request_base = os.environ.get("CI_MERGE_REQUEST_TARGET_BRANCH_SHA")
    commit_before = os.environ.get("CI_COMMIT_BEFORE_SHA")
    if merge_request_base:
        paths.update(optional_git_lines("diff", "--name-only", "--diff-filter=ACMR", f"{merge_request_base}...HEAD"))
    elif commit_before and set(commit_before) != {"0"}:
        paths.update(optional_git_lines("diff", "--name-only", "--diff-filter=ACMR", commit_before, "HEAD"))
    paths.update(git_lines("diff", "--name-only", "--diff-filter=ACMR"))
    paths.update(git_lines("diff", "--cached", "--name-only", "--diff-filter=ACMR"))
    paths.update(git_lines("ls-files", "--others", "--exclude-standard"))
    migration_prefix = MIGRATION_DIR.relative_to(ROOT).as_posix() + "/"
    return sorted(
        ROOT / path
        for path in paths
        if path.startswith(migration_prefix) and path.endswith(".sql")
    )


def contains_destructive_sql(text: str) -> bool:
    return any(pattern.search(text) for pattern in DESTRUCTIVE_PATTERNS)


def missing_markers(text: str) -> list[str]:
    lowered = text.lower()
    return [marker for marker in REQUIRED_MARKERS if marker not in lowered]


def main() -> int:
    violations: list[str] = []
    for path in changed_migrations():
        text = path.read_text(encoding="utf-8")
        if not contains_destructive_sql(text):
            continue
        missing = missing_markers(text)
        if missing:
            markers = ", ".join(missing)
            violations.append(f"{path.relative_to(ROOT).as_posix()}: missing {markers}")

    if violations:
        print("Flyway destructive migration review check failed:")
        for violation in violations:
            print(f"  {violation}")
        print("Add review markers before destructive SQL, for example:")
        print("  -- destructive-migration-reviewed: approved by <name> on 2026-05-29")
        print("  -- destructive-migration-recovery: rename to *_legacy first or document rollback/backup path")
        return 1

    print("Flyway destructive migration review check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
