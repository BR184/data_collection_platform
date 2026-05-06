from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FRONTEND_SRC = ROOT / "frontend/src"

API_LITERAL_PATTERN = re.compile(r"['\"`]\/api\/")
FETCH_PATTERN = re.compile(r"\bfetch\s*\(")


def is_allowed(path: Path) -> bool:
    normalized = path.relative_to(FRONTEND_SRC).as_posix()
    if normalized.startswith("api-client/"):
        return True
    if ".test." in path.name or path.name.endswith(".spec.ts"):
        return True
    return False


def main() -> int:
    violations: list[str] = []
    for path in sorted(FRONTEND_SRC.rglob("*")):
        if path.suffix not in {".ts", ".vue"} or is_allowed(path):
            continue
        text = path.read_text(encoding="utf-8")
        for index, line in enumerate(text.splitlines(), start=1):
            if API_LITERAL_PATTERN.search(line):
                violations.append(f"{path.relative_to(ROOT).as_posix()}:{index}: /api literal outside api-client")
            if FETCH_PATTERN.search(line):
                violations.append(f"{path.relative_to(ROOT).as_posix()}:{index}: fetch outside api-client")

    if violations:
        print("Frontend API boundary violations found:")
        for violation in violations:
            print(f"  {violation}")
        return 1

    print("Frontend API boundary check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
