from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATION_DIR = ROOT / "backend/src/main/resources/db/migration"
MANIFEST = ROOT / "scripts/flyway-migration-checksums.json"


def migration_checksums() -> dict[str, str]:
    return {
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(MIGRATION_DIR.glob("*.sql"))
    }


def load_manifest() -> dict[str, str]:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def write_manifest(checksums: dict[str, str]) -> None:
    MANIFEST.write_text(
        json.dumps(checksums, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Check that existing Flyway migrations were not edited after being locked."
    )
    parser.add_argument(
        "--update",
        action="store_true",
        help="Rewrite the checksum manifest after intentionally adding a reviewed migration.",
    )
    args = parser.parse_args()

    actual = migration_checksums()
    if args.update:
        write_manifest(actual)
        print(f"Updated {MANIFEST.relative_to(ROOT).as_posix()} with {len(actual)} migrations.")
        return 0

    expected = load_manifest()
    changed = sorted(
        name
        for name, checksum in expected.items()
        if name in actual and actual[name] != checksum
    )
    missing = sorted(name for name in expected if name not in actual)
    untracked = sorted(name for name in actual if name not in expected)

    if changed or missing or untracked:
        print("Flyway migration immutability check failed:")
        for name in changed:
            print(f"  CHANGED {name}")
        for name in missing:
            print(f"  MISSING {name}")
        for name in untracked:
            print(f"  UNLOCKED {name}")
        print("Review the migration change, then add a new migration or update the manifest intentionally.")
        return 1

    print(f"Flyway migration immutability check passed: {len(actual)} migrations locked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
