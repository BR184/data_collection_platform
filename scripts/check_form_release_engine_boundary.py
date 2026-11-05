"""检查旧基线表单包没有引入新的同步引擎或事实多修订发布模型。"""

from pathlib import Path
import subprocess
import sys

BASELINE = "f76d5be145673cf5d8490bb2eff9522a9557ea5c"
ROOT = Path(__file__).resolve().parents[1]
PREFIX = "backend/src/main/java/com/data/collection/platform/"
PROTECTED = (
    PREFIX + "service/sync/",
    PREFIX + "service/FactBuildService.java",
    PREFIX + "service/FactPublicationTransaction.java",
    PREFIX + "service/FactBuildTaskService.java",
    PREFIX + "service/GitlabFactSourceSqlProvider.java",
    PREFIX + "service/GitlabFactSourceQueryExecutor.java",
)


def normalize_engine(data: bytes, name: str) -> bytes:
    text = data.decode("utf-8").replace("\r\n", "\n")
    if name.endswith("/FactBuildService.java"):
        # 唯一允许的接入是依赖注入和表单延期输入；构建/发布/清理/水位算法逐字保留。
        text = text.replace("  private final FormIssueFactAdapter formAdapter;\n", "")
        text = text.replace("FactProjectionGenerationService projectionGenerationService, FormIssueFactAdapter formAdapter)", "FactProjectionGenerationService projectionGenerationService)")
        text = text.replace("    this.formAdapter = formAdapter;\n", "")
        text = text.replace("    Map<Long, IssueSlaRules.Evaluation> formEvaluations = formAdapter.delayEvaluations(sourceInstance, facts, now);\n", "")
        text = text.replace("      IssueSlaRules.Evaluation formEvaluation = formEvaluations.get(fact.getId());\n      if (formEvaluation != null) {\n        nextResponseDelayed = formEvaluation.responseDelayed();\n        nextResolveDelayed = formEvaluation.resolveDelayed();\n      }\n", "")
    return text.encode("utf-8")


def violations() -> list[str]:
    """对比Git已确认基线与工作树，返回违规文件；不改变仓库。"""
    errors: list[str] = []
    names = subprocess.check_output(
        ["git", "ls-tree", "-r", "--name-only", BASELINE, "--", *PROTECTED],
        cwd=ROOT, text=True, encoding="utf-8",
    ).splitlines()
    for name in names:
        expected = subprocess.check_output(["git", "show", f"{BASELINE}:{name}"], cwd=ROOT)
        path = ROOT / name
        if not path.exists() or normalize_engine(path.read_bytes(), name) != normalize_engine(expected, name):
            errors.append(f"旧引擎文件被改动：{name}")
    tracked_baseline = set(names)
    for path in (ROOT / PREFIX / "service/sync").rglob("*.java"):
        relative = path.relative_to(ROOT).as_posix()
        if relative not in tracked_baseline:
            errors.append(f"同步引擎出现基线之外的文件：{relative}")
    forbidden = ROOT / PREFIX / "service/read"
    if forbidden.exists():
        errors.append("不得引入新版service/read发布引擎")
    for path in (ROOT / "backend/src/main/resources/db/migration").glob("*.sql"):
        if "read_publications" in path.read_text(encoding="utf-8").lower():
            errors.append(f"迁移引入新版读发布模型：{path.name}")
    return errors


if __name__ == "__main__":
    failures = violations()
    print("\n".join(failures) if failures else "旧同步与事实引擎边界检查通过。")
    sys.exit(bool(failures))
