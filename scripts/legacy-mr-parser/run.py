"""在隔离目录复现、编译并封装老平台MR修复；不启动应用、不读取配置值或访问数据库。"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import struct
import subprocess
import zipfile
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
EXPECTED_CLASSES = {
    "com/huayun/service/impl/MergeRequestDAOImpl.class",
    "com/huayun/entity/mergeRequest/MergeRequest.class",
    "com/huayun/entity/mergeRequest/MergeRequest$MergedUser.class",
    "com/huayun/service/GitLabMergeRequestMetadata.class",
}


def sha(path: Path) -> str:
    """计算文件摘要；不会输出文件内容。"""
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command: list[str], log: Path, cwd: Path, *, expected_failure: bool = False) -> str:
    """运行离线编译或测试并保存日志；只对原缺陷回归允许非零退出。"""
    result = subprocess.run(command, cwd=cwd, capture_output=True)
    data = result.stdout + result.stderr
    output = data.decode("utf-8", errors="replace").replace("\r\r\n", "\n").replace("\r\n", "\n")
    log.write_text(output, encoding="utf-8", newline="\n")
    if (result.returncode != 0) != expected_failure:
        raise RuntimeError(f"执行状态不符，请查日志：{log.name}")
    return output


def main() -> None:
    """校验输入身份，运行旧/新同一套回归，再只替换批准class生成私密JAR及交付说明。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-jar", type=Path, required=True)
    parser.add_argument("--legacy-source", type=Path, required=True)
    parser.add_argument("--jdk-home", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    contract = json.loads((HERE / "source-contract.json").read_text(encoding="utf-8"))
    baseline = args.baseline_jar.resolve()
    if sha(baseline) != contract["baseline_jar_sha256"]:
        raise ValueError("基线JAR不符合已批准会话包，停止构建")
    work = args.work_dir.resolve()
    if not work.is_relative_to(REPO / ".tmp"):
        raise ValueError("构建目录必须位于本工作树.tmp内")
    work.mkdir(parents=True, exist_ok=False)
    output = args.output_dir.resolve()
    if output.exists():
        raise ValueError("输出目录已存在，不覆盖已交付制品")
    original, libs, source, patched, tests = [work / name for name in
                                            ("original", "lib", "source", "patched", "tests")]
    for directory in (original, libs, source, patched, tests):
        directory.mkdir()
    for name, expected in contract["source_sha256"].items():
        src = args.legacy_source / name
        if sha(src) != expected:
            raise ValueError("老平台源文件与补丁基线不符：" + name)
        target = source / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(src.read_text(encoding="utf-8-sig"), encoding="utf-8", newline="\n")
    with zipfile.ZipFile(baseline) as jar:
        if len(jar.namelist()) != len(set(jar.namelist())):
            raise ValueError("原包存在重复条目")
        for name in jar.namelist():
            if name.startswith("BOOT-INF/classes/") and name.endswith(".class"):
                target = original / name.removeprefix("BOOT-INF/classes/")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(jar.read(name))
            elif name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                (libs / Path(name).name).write_bytes(jar.read(name))
    suffix = ".exe" if os.name == "nt" else ""
    javac = str(args.jdk_home / "bin" / ("javac" + suffix))
    java = str(args.jdk_home / "bin" / ("java" + suffix))
    cp = os.pathsep.join([str(original), str(libs / "*")])
    test_compile = [javac, "-J-Dfile.encoding=UTF-8", "--release", "9", "-proc:none", "-encoding", "UTF-8",
                    "-cp", cp, "-d", str(tests), str(HERE / "LegacyMrApiTest.java")]
    run(test_compile, work / "test-compile.log", REPO)
    def test_runner(classpath: str) -> list[str]:
        return [java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp",
                os.pathsep.join([str(tests), classpath]), "org.junit.runner.JUnitCore", "LegacyMrApiTest"]

    test_command = test_runner(cp)
    red = run(test_command, work / "red.log", REPO, expected_failure=True)
    if "org.junit.ComparisonFailure" not in red:
        raise RuntimeError("旧版未按业务断言复现，不接受环境失败代替")
    run(["git", "apply", "--unidiff-zero", "--directory=" + source.relative_to(REPO).as_posix(),
         str(HERE / "mr-api.patch")], work / "patch.log", REPO)
    sources = list(source.rglob("*.java")) + [HERE / "GitLabMergeRequestMetadata.java"]
    run([javac, "-J-Dfile.encoding=UTF-8", "--release", "9", "-encoding", "UTF-8", "-cp", cp,
         "-processorpath", str(libs / "lombok-1.18.32.jar"), "-sourcepath", str(work / "empty"),
         "-d", str(patched)] + [str(path) for path in sources], work / "compile.log", REPO)
    replacements = {path.relative_to(patched).as_posix(): path.read_bytes() for path in patched.rglob("*.class")}
    if replacements.keys() != EXPECTED_CLASSES:
        raise RuntimeError("编译输出超出批准class范围")
    if any(struct.unpack(">H", data[6:8])[0] != 53 for data in replacements.values()):
        raise RuntimeError("编译输出不兼容原Java 9字节码")
    patched_cp = os.pathsep.join([str(patched), cp])
    green = run(test_runner(patched_cp), work / "green.log", REPO)
    if "OK (11 tests)" not in green:
        raise RuntimeError("回归数量或结果异常")
    output.mkdir(parents=True, exist_ok=False)
    target_jar = output / baseline.name
    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(target_jar, "w", allowZip64=True) as after:
        after.comment = before.comment
        remaining = dict(replacements)
        for info in before.infolist():
            class_name = info.filename.removeprefix("BOOT-INF/classes/")
            data = remaining.pop(class_name) if class_name in remaining else before.read(info.filename)
            after.writestr(copy.copy(info), data)
        for name, data in sorted(remaining.items()):
            after.writestr("BOOT-INF/classes/" + name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(target_jar) as after:
        if after.testzip() is not None or len(after.namelist()) != len(set(after.namelist())):
            raise RuntimeError("新JAR条目或CRC不合法")
        changed = [n for n in before.namelist() if before.read(n) != after.read(n)]
        added = sorted(set(after.namelist()) - set(before.namelist()))
        allowed = {"BOOT-INF/classes/" + name for name in EXPECTED_CLASSES}
        if set(changed + added) != allowed:
            raise RuntimeError("新JAR差异超出批准范围")
        stop_class = "BOOT-INF/classes/com/huayun/service/impl/IssueServiceImpl.class"
        if before.read(stop_class) != after.read(stop_class):
            raise RuntimeError("延期停写class发生变化")
    digest = sha(target_jar)
    report = {"baseline_sha256": contract["baseline_jar_sha256"], "output_sha256": digest,
              "output_bytes": target_jar.stat().st_size, "changed_entries": changed, "added_entries": added,
              "all_other_entries_unchanged": True, "configs_and_credentials_unchanged": True,
              "old_delay_writeback_disabled_code_preserved": True, "java_class_major": 53,
              "tests": 11, "test_result": "PASS", "red_result": re.search(r"Tests run:.*", red).group(0),
              "runtime_started": False, "network_or_database_used_by_tests": False}
    (output / "VERIFICATION.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / (baseline.name + ".sha256")).write_text(digest + "  " + baseline.name + "\n", encoding="utf-8")
    (output / "README.txt").write_text(
        "老平台MR元数据API修复，2026-11-04\n"
        "基于前开发账号会话包，仅修MR项目名、状态、合并时间/合并人及扫描问题已说明标签的读取。\n"
        "所有配置/凭据、依赖、静态资源和延期标签停写class保持。\n"
        "停止旧Java进程并备份后，同名替换重启，只触发一次CC采集；结束并清理完成后再导入新平台。\n"
        "离线实际入口11项回归通过；未连接内网或数据库，生产全量结果由用户验证。\n"
        "核对24509：CC2026R2、MERGED、2025-10-18 08:53:48（服务器时区为Asia/Shanghai）、API合并人。\n"
        "旧任务取消、重叠与空结果替换行为不在本次修复范围。制品含既有凭据，仅受控交付。\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("output_directory=" + str(output))


if __name__ == "__main__":
    main()
