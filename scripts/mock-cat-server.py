#!/usr/bin/env python3
"""本地演示用的假 CAT 服务端。

按 `docs/bi-dashboard/集成测试平台接口使用手册.md` 提供四个接口，供 BI 单元测试/集成测试页面在
外网本地看到真实的图表样式与数据展示。**它产出的是演示数据，与任何真实 CAT 数据无关**，只用于
本地开发与验收；BI 页面本身不直连 CAT，数据经真实镜像链路（配置 -> 目录同步 -> 阶段映射 ->
阶段同步 -> 原子发布）落库后才被页面读取。

用法：

    python scripts/mock-cat-server.py --port 18899

随后在另一个终端执行 `python scripts/seed-local-bi-cat-demo.py` 驱动真实链路。

数据设计（全部确定性生成，无随机、无外部依赖）：

- 1 个默认项目 -> 1 个版本（名称与平台产品版本 business key 一致，默认 `CC2026R4`）-> 两个测试阶段
  （`单元测试`、`集成测试`）。只放唯一候选，使系统设置页的映射建议能被唯一预填。
- 14 个业务模块、共 79 个功能；每个模块的功能通过率覆盖高/中/低三档，并包含恰好 95.00 的边界值。
- 模块的达标/未达标功能数与模块通过率按“功能通过率 >= 95% 视为达标”反推，整体通过率同样由达标
  功能数与统计功能数汇总，保证演示数据自洽。手册未固定 `testPassRate` 的具体聚合公式，本演示取
  “达标功能数 / 统计功能数”只是为让计数、模块通过率与达标规则互相不矛盾，不构成业务口径声明。
- 单元测试阶段整体达标（96.20%）、集成测试阶段整体未达标（87.34%），使两种状态样式都能被看到；
  该分化是演示设计，不是任何业务结论。
"""

from __future__ import annotations

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# 模块与功能：同一批功能在单元测试与集成测试中复用，只有通过率不同。
MODULES: list[tuple[str, list[str]]] = [
    ("草图模块", ["草图绘制", "草图约束", "草图编辑", "参考几何", "草图诊断", "草图重定位"]),
    (
        "零件与特征模块",
        ["拉伸凸台/基体", "旋转凸台", "扫描凸台", "放样凸台", "圆角", "倒角", "抽壳", "拔模", "阵列"],
    ),
    ("装配模块", ["零部件插入", "配合关系", "干涉检查", "装配爆炸", "装配体镜像", "子装配管理"]),
    ("工程图模块", ["视图投影", "剖视图", "尺寸标注", "公差标注", "明细表", "图框模板", "焊接符号"]),
    ("钣金模块", ["基体法兰", "边线法兰", "展开/折叠", "折弯系数", "成形工具", "钣金切口"]),
    ("曲面模块", ["拉伸曲面", "边界曲面", "曲面缝合", "曲面剪裁", "曲面加厚", "曲面延伸"]),
    ("焊件模块", ["结构构件", "剪裁/延伸", "角撑板", "顶端盖", "焊缝符号"]),
    ("模具模块", ["分型面", "型芯型腔分割", "拔模分析", "底切检查", "浇注系统"]),
    ("仿真模块", ["材料赋定", "网格划分", "载荷约束", "求解与后处理", "结果云图导出"]),
    ("数据管理模块", ["文档检入/检出", "版本历史", "属性映射", "权限校验", "生命周期状态", "BOM 同步"]),
    ("参数化约束模块", ["全局变量", "方程式驱动", "设计表", "参数联动更新"]),
    ("导入导出模块", ["STEP 导入", "IGES 导出", "DWG/DXF 交互", "Parasolid 导入", "中立格式校验"]),
    ("渲染与显示模块", ["材质外观", "环境光照", "显示样式切换", "大装配轻量化显示"]),
    ("图纸标准化模块", ["企业模板校验", "图层规范", "标注样式统一", "图框属性填充", "批量打印"]),
]

# 每个模块中达标功能数占功能总数的比例；未列出的模块全部达标。
UNIT_ATTAINED_RATIO: dict[str, float] = {
    "零件与特征模块": 0.89,
    "模具模块": 0.80,
    "图纸标准化模块": 0.80,
}
INTEGRATION_ATTAINED_RATIO: dict[str, float] = {
    "草图模块": 0.83,
    "零件与特征模块": 0.89,
    "工程图模块": 0.86,
    "钣金模块": 0.83,
    "曲面模块": 0.83,
    "焊件模块": 0.80,
    "模具模块": 0.80,
    "数据管理模块": 0.83,
    "导入导出模块": 0.80,
    "图纸标准化模块": 0.80,
}


def attained_count(module_name: str, total: int, ratios: dict[str, float]) -> int:
    """按阶段比例算出该模块的达标功能数；未配置比例的模块视为全部达标。"""
    ratio = ratios.get(module_name, 1.0)
    return min(total, int(total * ratio + 0.5))


def achieved_rate(index: int) -> float:
    """达标功能通过率：覆盖 95.00 边界并向上分布。"""
    return round(95.0 + (index % 5) * 1.1, 1)


def failed_rate(index: int) -> float:
    """未达标功能通过率：全部低于 95，最高逼近 94.9。"""
    return round(min(62.0 + (index % 7) * 4.7, 94.9), 1)


class DemoDataset:
    """一次生成、只读复用：项目目录与两个测试阶段的模块/功能统计。"""

    def __init__(self, version_name: str, project_id: str, version_id: str,
                 unit_phase_id: str, integration_phase_id: str) -> None:
        self.version_name = version_name
        self.project_id = project_id
        self.version_id = version_id
        self.phases = {
            "UNIT_TEST": unit_phase_id,
            "INTEGRATION_TEST": integration_phase_id,
        }
        self.stages: dict[str, dict[str, object]] = {
            "UNIT_TEST": self._build_stage(UNIT_ATTAINED_RATIO),
            "INTEGRATION_TEST": self._build_stage(INTEGRATION_ATTAINED_RATIO),
        }

    def _build_stage(self, ratios: dict[str, float]) -> dict[str, object]:
        modules: list[dict[str, object]] = []
        features: dict[str, list[dict[str, object]]] = {}
        attained_total = 0
        function_total = 0
        for module_index, (module_name, function_names) in enumerate(MODULES, start=1):
            module_id = f"module-{module_index:02d}"
            total = len(function_names)
            attained = attained_count(module_name, total, ratios)
            attained_total += attained
            function_total += total
            module_features: list[dict[str, object]] = []
            for function_index, function_name in enumerate(function_names):
                function_id = f"{module_id}-feature-{function_index + 1:02d}"
                rate = (
                    achieved_rate(function_index)
                    if function_index < attained
                    else failed_rate(function_index)
                )
                module_features.append(
                    {
                        "id": function_id,
                        "name": function_name,
                        "featureUniqueId": function_id,
                        "featureLabel": "核心功能" if function_index == 0 else "基础功能",
                        "testPassRate": rate,
                    }
                )
            features[module_id] = module_features
            modules.append(
                {
                    "id": module_id,
                    "moduleName": module_name,
                    "name": module_name,
                    "passFeatureCount": attained,
                    "notPassFeatureCount": total - attained,
                    "testPassRate": round(attained / total * 100, 2),
                }
            )
        return {
            "modules": modules,
            "features": features,
            "overallRate": round(attained_total / function_total * 100, 2),
        }

    def projects_payload(self) -> dict[str, object]:
        return {
            "id": self.project_id,
            "name": "CrownCAD 集成测试（本地演示）",
            "note": "本地演示替身数据，与真实 CAT 数据无关",
            "createTime": "2025-01-06T02:00:00Z",
            "createUserId": "demo-local",
            "defaultProject": True,
        }

    def phase_tree_payload(self) -> dict[str, object]:
        return {
            "id": self.version_id,
            "name": self.version_name,
            "createTime": "2025-01-06T02:05:00Z",
            "children": [
                {
                    "id": self.phases["UNIT_TEST"],
                    "name": "单元测试",
                    "createTime": "2025-01-06T02:10:00Z",
                    "children": None,
                    "note": "单元测试阶段",
                    "group_id": self.version_id,
                    "endTime": "2025-06-30T00:00:00Z",
                    "curVersion": None,
                    "projectId": self.project_id,
                    "versionId": self.version_id,
                    "disabled": None,
                    "defaultProject": None,
                },
                {
                    "id": self.phases["INTEGRATION_TEST"],
                    "name": "集成测试",
                    "createTime": "2025-01-06T02:15:00Z",
                    "children": None,
                    "note": "系统集成测试阶段",
                    "group_id": self.version_id,
                    "endTime": "2025-07-31T00:00:00Z",
                    "curVersion": None,
                    "projectId": self.project_id,
                    "versionId": self.version_id,
                    "disabled": None,
                    "defaultProject": None,
                },
            ],
            "note": None,
            "group_id": "0",
            "endTime": None,
            "curVersion": True,
            "projectId": None,
            "versionId": None,
            "disabled": True,
            "defaultProject": True,
        }

    def stage_for_phase(self, phase_id: str) -> dict[str, object] | None:
        for stage, mapped_phase in self.phases.items():
            if mapped_phase == phase_id:
                return self.stages[stage]
        return None

    def statistics_payload(self, phase_id: str) -> dict[str, object] | None:
        stage = self.stage_for_phase(phase_id)
        if stage is None:
            return None
        return {
            "result": stage["modules"],
            "passRate": stage["overallRate"],
        }

    def features_payload(self, phase_id: str, module_id: str) -> dict[str, object] | None:
        stage = self.stage_for_phase(phase_id)
        if stage is None:
            return None
        module_features = stage["features"].get(module_id)
        if module_features is None:
            return None
        return {
            "statisticsInfoList": module_features,
            "page": None,
            "pageSize": None,
            "totalCount": len(module_features),
            "sumPageCount": None,
        }


def envelope(data: object, code: int = 200, message: str = "success") -> bytes:
    return json.dumps(
        {"code": code, "message": message, "data": data}, ensure_ascii=False
    ).encode("utf-8")


class DemoHandler(BaseHTTPRequestHandler):
    dataset: DemoDataset
    server_version = "MockCatDemo/1.0"

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 命名约定
        if urlparse(self.path).path == "/healthz":
            self._write(200, envelope({"status": "ok", "demo": True}))
            return
        self._write(405, envelope(None, 405, "本地演示替身只接受 POST"))

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 命名约定
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        body = self._read_body()
        path = parsed.path

        if path.endswith("/getAllProject"):
            self._write(200, envelope([self.dataset.projects_payload()]))
            return
        if path.endswith("/getAllByProjectId"):
            project_id = (query.get("projectId") or [self.dataset.project_id])[0]
            if project_id != self.dataset.project_id:
                self._write(500, envelope(None, 500, "未找到项目：" + project_id))
                return
            self._write(200, envelope([self.dataset.phase_tree_payload()]))
            return
        if path.endswith("/getStatisticsInfoByTPId"):
            phase_id = (query.get("testingPhaseId") or [""])[0]
            payload = self.dataset.statistics_payload(phase_id)
            if payload is None:
                self._write(500, envelope(None, 500, "未找到测试阶段：" + phase_id))
                return
            self._write(200, envelope(payload))
            return
        if path.endswith("/getFeatureInfoByModuleId"):
            try:
                request = json.loads(body.decode("utf-8")) if body else {}
            except json.JSONDecodeError:
                self._write(500, envelope(None, 500, "请求体不是合法 JSON"))
                return
            payload = self.dataset.features_payload(
                str(request.get("testingPhaseId", "")), str(request.get("moduleId", ""))
            )
            if payload is None:
                self._write(500, envelope(None, 500, "未找到模块或测试阶段"))
                return
            self._write(200, envelope(payload))
            return
        self._write(404, envelope(None, 404, "未知接口：" + path))

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def _write(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        sys.stderr.write("[mock-cat] " + format % args + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="本地演示用假 CAT 服务端（非真实 CAT 数据）。")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址，默认 127.0.0.1。")
    parser.add_argument("--port", type=int, default=18899, help="监听端口，默认 18899。")
    parser.add_argument("--project-id", default="demo-cat-project", help="CAT 项目 ID。")
    parser.add_argument("--version-id", default="demo-cat-cc2026r4", help="CAT 版本 ID。")
    parser.add_argument(
        "--version-name",
        default="CC2026R4",
        help="CAT 版本名称；与平台产品版本 business key 一致才能被映射建议唯一命中。",
    )
    parser.add_argument("--unit-phase-id", default="demo-cat-phase-ut", help="单元测试阶段 ID。")
    parser.add_argument(
        "--integration-phase-id", default="demo-cat-phase-it", help="集成测试阶段 ID。"
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    DemoHandler.dataset = DemoDataset(
        args.version_name,
        args.project_id,
        args.version_id,
        args.unit_phase_id,
        args.integration_phase_id,
    )
    server = ThreadingHTTPServer((args.host, args.port), DemoHandler)
    print(f"[mock-cat] 监听 http://{args.host}:{args.port}（本地演示替身，数据与真实 CAT 无关）")
    print(f"[mock-cat] 项目={args.project_id} 版本={args.version_id}({args.version_name}) "
          f"单元测试阶段={args.unit_phase_id} 集成测试阶段={args.integration_phase_id}")
    for stage, payload in DemoHandler.dataset.stages.items():
        modules = payload["modules"]
        attained_modules = sum(
            1 for module in modules if module["testPassRate"] >= 95  # type: ignore[index]
        )
        print(f"[mock-cat] {stage}: {len(modules)} 个模块，整体通过率 {payload['overallRate']}%，"
              f"其中 {attained_modules} 个模块达标")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock-cat] 已停止")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
