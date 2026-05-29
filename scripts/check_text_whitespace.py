from __future__ import annotations

import os
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


def optional_git_lines(*args: str) -> list[str]:
    try:
        return git_lines(*args)
    except subprocess.CalledProcessError:
        return []


def changed_paths() -> set[str]:
    paths: set[str] = set()
    merge_request_base = os.environ.get("CI_MERGE_REQUEST_TARGET_BRANCH_SHA")
    commit_before = os.environ.get("CI_COMMIT_BEFORE_SHA")
    if merge_request_base:
        paths.update(optional_git_lines("diff", "--name-only", "--diff-filter=ACMR", f"{merge_request_base}...HEAD"))
    elif commit_before and set(commit_before) != {"0"}:
        paths.update(optional_git_lines("diff", "--name-only", "--diff-filter=ACMR", commit_before, "HEAD"))
    paths.update(git_lines("diff", "--name-only", "--diff-filter=ACMR"))
    paths.update(git_lines("diff", "--cached", "--name-only", "--diff-filter=ACMR"))
    return paths


def candidate_paths() -> list[Path]:
    paths = changed_paths()
    paths.update(git_lines("ls-files", "--others", "--exclude-standard"))
    return sorted(ROOT / path for path in paths if Path(path).suffix.lower() in TEXT_SUFFIXES)


def contains_bare_cr(content: bytes) -> bool:
    return any(
        byte == 0x0D and (index + 1 >= len(content) or content[index + 1] != 0x0A)
        for index, byte in enumerate(content)
    )


def main() -> int:
    violations: list[str] = []
    for path in candidate_paths():
        content = path.read_bytes()
        if contains_bare_cr(content):
            violations.append(f"{path.relative_to(ROOT).as_posix()}: contains bare CR line ending")
        if b"\xc2\x85" in content:
            violations.append(f"{path.relative_to(ROOT).as_posix()}: contains NEL line separator")
        try:
            lines = content.decode("utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        for index, line in enumerate(lines, start=1):
            if line.endswith((" ", "\t")):
                violations.append(f"{path.relative_to(ROOT).as_posix()}:{index}: trailing whitespace")

    if violations:
        print("Text whitespace or line-ending violations found:")
        for violation in violations:
            print(f"  {violation}")
        return 1
    print("No text whitespace or line-ending violations found in tracked or untracked text files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
