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


def normalize_frontend_path(path: str) -> str:
    """Normalize a frontend URL template while discarding its dynamic query suffix."""
    normalized = re.sub(r"/\$\{[^}]+}", "/{}", path)
    query_suffix = normalized.find("${")
    if query_suffix >= 0:
        normalized = normalized[:query_suffix]
    return normalize_path(normalized)


def backend_path_matches(backend_path: str, frontend_path: str) -> bool:
    """Return whether a backend mapping accepts the concrete frontend path."""
    backend_segments = backend_path.strip("/").split("/")
    frontend_segments = frontend_path.strip("/").split("/")
    return len(backend_segments) == len(frontend_segments) and all(
        backend_segment == "{}" or backend_segment == frontend_segment
        for backend_segment, frontend_segment in zip(backend_segments, frontend_segments, strict=True)
    )


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
            paths.add(normalize_frontend_path(match.group(1)))
    return paths


def main() -> int:
    backend = backend_paths()
    frontend = frontend_paths()
    missing = sorted(
        frontend_path
        for frontend_path in frontend
        if not any(backend_path_matches(backend_path, frontend_path) for backend_path in backend)
    )
    print(f"backend_paths={len(backend)} frontend_paths={len(frontend)} missing_frontend_paths={len(missing)}")
    if missing:
        for path in missing:
            print(f"  MISSING_BACKEND {path}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
