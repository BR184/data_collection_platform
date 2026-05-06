from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEST_SRC = ROOT / "backend/src/test/java"
SMOKE_LIST = ROOT / "scripts/flyway-profile-smoke-tests.txt"


def spring_boot_tests() -> set[str]:
    tests: set[str] = set()
    for path in TEST_SRC.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if "@SpringBootTest" not in text:
            continue
        match = re.search(r"\bclass\s+(\w+)", text)
        if match:
            tests.add(match.group(1))
    return tests


def configured_smoke_tests() -> set[str]:
    return {
        line.strip()
        for line in SMOKE_LIST.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    }


def main() -> int:
    boot_tests = spring_boot_tests()
    smoke_tests = configured_smoke_tests()
    unknown = sorted(smoke_tests - boot_tests)
    if unknown:
        print("Flyway profile smoke list references unknown SpringBootTest classes:")
        for name in unknown:
            print(f"  UNKNOWN {name}")
        return 1
    if not smoke_tests:
        print("Flyway profile smoke list is empty.")
        return 1
    print(
        f"Flyway profile smoke coverage check passed: "
        f"{len(smoke_tests)} representative tests for {len(boot_tests)} SpringBootTest classes."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
