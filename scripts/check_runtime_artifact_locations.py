from __future__ import annotations

import fnmatch
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

ALLOWED_RUNTIME_DIRS = {
    ".tmp",
    ".tmp-logs",
    "backend/logs",
    "backend/target",
    "frontend/dist",
    "frontend/node_modules",
}

FORBIDDEN_ROOT_FILE_PATTERNS = [
    "*.log",
    "*.tmp",
    "tmp-*",
    ".tmp-*",
]


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def matches_any(name: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatch(name, pattern) for pattern in patterns)


def is_allowed_runtime_dir(path: Path) -> bool:
    rel = relative(path)
    return rel in ALLOWED_RUNTIME_DIRS


def main() -> int:
    root_entries = [path for path in ROOT.iterdir() if path.name != ".git"]
    forbidden_root_files = [
        path.name
        for path in root_entries
        if path.is_file() and matches_any(path.name, FORBIDDEN_ROOT_FILE_PATTERNS)
    ]
    unexpected_runtime_dirs = [
        relative(path)
        for path in root_entries
        if path.is_dir()
        and matches_any(path.name, ["tmp-*", ".tmp-*"])
        and not is_allowed_runtime_dir(path)
    ]

    if forbidden_root_files or unexpected_runtime_dirs:
        print("Runtime artifacts must stay in documented runtime directories.")
        for name in sorted(forbidden_root_files):
            print(f"  FORBIDDEN ROOT FILE {name}")
        for name in sorted(unexpected_runtime_dirs):
            print(f"  FORBIDDEN ROOT DIR {name}")
        print("Allowed local runtime directories: .tmp/, .tmp-logs/, backend/logs/.")
        return 1

    print("Runtime artifact locations are clean.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
