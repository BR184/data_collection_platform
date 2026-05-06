from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

TEXT_SUFFIXES = {
    ".css",
    ".html",
    ".java",
    ".js",
    ".json",
    ".md",
    ".ps1",
    ".py",
    ".sql",
    ".toml",
    ".ts",
    ".vue",
    ".xml",
    ".yaml",
    ".yml",
}


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


def candidate_paths() -> list[Path]:
    paths = set(git_lines("ls-files"))
    paths.update(git_lines("ls-files", "--others", "--exclude-standard"))
    return sorted(ROOT / path for path in paths if Path(path).suffix.lower() in TEXT_SUFFIXES)


def main() -> int:
    violations: list[str] = []
    for path in candidate_paths():
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        for index, line in enumerate(lines, start=1):
            if line.endswith((" ", "\t")):
                violations.append(f"{path.relative_to(ROOT).as_posix()}:{index}: trailing whitespace")

    if violations:
        print("Trailing whitespace found:")
        for violation in violations:
            print(f"  {violation}")
        return 1
    print("No trailing whitespace found in tracked or untracked text files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
