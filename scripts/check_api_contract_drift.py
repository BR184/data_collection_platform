from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND_CONTROLLERS = ROOT / "backend/src/main/java/com/data/collection/platform/controller"
FRONTEND_API_CLIENTS = ROOT / "frontend/src/api-client"


MAPPING_PATTERN = re.compile(
    r"@(?P<method>Get|Post|Put|Patch|Delete|Request)Mapping(?:\((?P<args>[^)]*)\))?"
)
PATH_LITERAL_PATTERN = re.compile(r'["`](/api/[^"`?]*)')


def extract_mapping_path(args: str | None) -> str:
    if not args:
        return ""
    match = re.search(r'"([^"]*)"', args)
    return match.group(1) if match else ""


def join_path(base: str, child: str) -> str:
    if not base:
        return child or "/"
    if not child:
        return base
    return f"{base.rstrip('/')}/{child.lstrip('/')}"


def normalize_path(path: str) -> str:
    normalized = path.replace("\\", "/")
    normalized = re.sub(r"\$\{[^}]+}", "{}", normalized)
    normalized = re.sub(r"\{[^}/]+\}", "{}", normalized)
    normalized = re.sub(r"/+", "/", normalized)
    return normalized.rstrip("/") or "/"


def backend_paths() -> set[str]:
    paths: set[str] = set()
    for path in BACKEND_CONTROLLERS.glob("*.java"):
        text = path.read_text(encoding="utf-8")
        class_mapping = re.search(r"@RequestMapping\(([^)]*)\)", text)
        base = extract_mapping_path(class_mapping.group(1) if class_mapping else None)
        for match in MAPPING_PATTERN.finditer(text):
            method = match.group("method")
            child = extract_mapping_path(match.group("args"))
            if method == "Request":
                continue
            full_path = normalize_path(join_path(base, child))
            if full_path.startswith("/api/"):
                paths.add(full_path)
    return paths


def frontend_paths() -> set[str]:
    paths: set[str] = set()
    for path in FRONTEND_API_CLIENTS.glob("*.ts"):
        text = path.read_text(encoding="utf-8")
        for match in PATH_LITERAL_PATTERN.finditer(text):
            raw = match.group(1)
            raw = raw.split("${query.", 1)[0]
            raw = raw.split("${searchParams.", 1)[0]
            raw = raw.split("${build", 1)[0]
            paths.add(normalize_path(raw))
    return paths


def main() -> int:
    backend = backend_paths()
    frontend = frontend_paths()
    missing = sorted(frontend - backend)
    print(f"backend_paths={len(backend)} frontend_paths={len(frontend)} missing_frontend_paths={len(missing)}")
    if missing:
        for path in missing:
            print(f"  MISSING_BACKEND {path}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
