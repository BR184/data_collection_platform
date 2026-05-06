from __future__ import annotations

import fnmatch
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

TRACKED_ARTIFACT_PATTERNS = [
    ".tmp",
    ".tmp/**",
    ".tmp-*",
    ".tmp-*.*",
    ".tmp-logs",
    ".tmp-logs/**",
    "tmp-*",
    "tmp-*.*",
    "*.log",
    "**/*.log",
    "backend/target/**",
    "frontend/dist/**",
    "frontend/node_modules/**",
    "backend/logs/**",
    "frontend/logs/**",
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


def matches_any(path: str, patterns: list[str]) -> bool:
    normalized = path.replace("\\", "/")
    return any(fnmatch.fnmatch(normalized, pattern) for pattern in patterns)


def main() -> int:
    tracked = git_lines("ls-files")
    polluted = [
        path
        for path in tracked
        if (ROOT / path).exists() and matches_any(path, TRACKED_ARTIFACT_PATTERNS)
    ]
    if polluted:
        print("Tracked generated/log/temp artifacts found:")
        for path in polluted:
            print(f"  {path}")
        return 1
    print("No tracked generated/log/temp artifacts found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
