from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
POM = ROOT / "backend/pom.xml"
RUN_BACKEND = ROOT / "backend/run-backend.ps1"
TEST_RESOURCES = ROOT / "backend/src/test/resources"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def pom_plugins() -> list[ET.Element]:
    root = ET.fromstring(text(POM))
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    return root.findall(".//m:build/m:plugins/m:plugin", namespace)


def child_text(element: ET.Element, name: str) -> str:
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    child = element.find(f"{namespace}{name}")
    return "" if child is None or child.text is None else child.text.strip()


def plugin_by_artifact(artifact_id: str) -> ET.Element | None:
    for plugin in pom_plugins():
        if child_text(plugin, "artifactId") == artifact_id:
            return plugin
    return None


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def main() -> int:
    failures: list[str] = []
    surefire = plugin_by_artifact("maven-surefire-plugin")
    dependency = plugin_by_artifact("maven-dependency-plugin")
    pom = text(POM)
    run_backend = text(RUN_BACKEND)
    test_application = text(TEST_RESOURCES / "application.yml")
    flyway_application = text(TEST_RESOURCES / "application-flyway-test.yml")
    logback_test = TEST_RESOURCES / "logback-test.xml"

    require(surefire is not None, "backend/pom.xml must configure maven-surefire-plugin", failures)
    require(
        "-javaagent:${mockito.agent.path}" in pom,
        "maven-surefire-plugin must preload Mockito as a javaagent",
        failures,
    )
    require("-Xshare:off" in pom, "maven-surefire-plugin should suppress JVM sharing warnings in tests", failures)
    require("<debug>false</debug>" in pom, "maven-surefire-plugin must isolate tests from DEBUG env vars", failures)
    require("-Ddebug=false" in run_backend, "run-backend.ps1 must isolate backend startup from DEBUG env vars", failures)
    require(dependency is not None, "backend/pom.xml must configure maven-dependency-plugin", failures)
    require(
        "<artifactId>mockito-core</artifactId>" in pom and "<destFileName>mockito-core.jar</destFileName>" in pom,
        "maven-dependency-plugin must copy mockito-core.jar for the test javaagent",
        failures,
    )
    require(logback_test.exists(), "backend/src/test/resources/logback-test.xml is required", failures)
    if logback_test.exists():
        logback = text(logback_test)
        require("JSON_FILE" not in logback, "logback-test.xml must not write JSON_FILE logs", failures)
        require('<root level="WARN">' in logback, "logback-test.xml should keep test output at WARN by default", failures)
    for label, content in [
        ("application.yml", test_application),
        ("application-flyway-test.yml", flyway_application),
    ]:
        require("banner-mode: off" in content, f"{label} must disable the Spring banner", failures)
        require("banner: false" in content, f"{label} must disable the MyBatis-Plus banner", failures)

    if failures:
        print("Backend test hygiene check failed:")
        for failure in failures:
            print(f"  {failure}")
        return 1
    print("Backend test hygiene check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
