# 运行产物和日志治理

本项目根目录只保留源码、配置、文档和明确入口。运行日志、临时调试文件和构建产物不得纳入版本管理。

## 目录约定

- 后端运行日志：`backend/logs/`
- 前端构建产物：`frontend/dist/`
- 前端依赖：`frontend/node_modules/`
- 后端构建产物：`backend/target/`
- 临时调试文件：`.tmp/`
- 本地临时日志：`.tmp-logs/`
- 本地工具链：`tools/`

## 提交前检查

提交前运行：

```powershell
python scripts/check_worktree_artifacts.py
python scripts/check_runtime_artifact_locations.py
python scripts/check_text_whitespace.py
git diff --check
```

`scripts/check_worktree_artifacts.py` 会拦截已经被 Git 跟踪的日志、临时文件和构建产物。未跟踪的本地日志可以按需删除，但不应进入提交。
`scripts/check_runtime_artifact_locations.py` 会额外拦截根目录下的本地日志和临时文件，避免运行产物污染项目根目录的判断。
