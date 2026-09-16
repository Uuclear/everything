# scripts/ci-local.* — 本地门禁脚本

## 背景

2026-09-16 用户决策：关闭 GitHub Actions CI，所有门禁改为本地执行。

## 使用

### PowerShell（Windows 默认）

```powershell
# 全门禁
powershell -NoProfile -File scripts/ci-local.ps1

# 单门禁
powershell -NoProfile -File scripts/ci-local.ps1 -Target web
powershell -NoProfile -File scripts/ci-local.ps1 -Target android
powershell -NoProfile -File scripts/ci-local.ps1 -Target server

# 清理构建产物
powershell -NoProfile -File scripts/ci-local.ps1 -Target clean
```

### Bash（Git Bash / WSL / macOS）

```bash
bash scripts/ci-local.sh all
bash scripts/ci-local.sh web
bash scripts/ci-local.sh android
bash scripts/ci-local.sh server
bash scripts/ci-local.sh clean
```

## 门禁内容

| 序号 | 内容 | 命令 |
|---|---|---|
| 1 | Go test + vet + cross-compile | `go test -race -count=1 ./...` + `go vet ./...` + 4 平台 build |
| 2 | Web vitest + vue-tsc + build | `npx vitest run` + `npx vue-tsc --noEmit` + `npm run build` |
| 3 | Android unit test + assembleDebug | `./gradlew :app:testDebugUnitTest --rerun-tasks` + `./gradlew :app:assembleDebug` |

## 重新启用 GitHub CI

```bash
# 还原 ci.yml（git 历史保留完整 workflow 定义）
git log --all --diff-filter=D --summary -- .github/workflows/ci.yml
git checkout <commit> -- .github/workflows/ci.yml
# 删除文件顶部 DISABLED 注释块并 push
```

## 已知差异

- 本机无 Go 工具链时 `server` 任务跳过 + 红色警告；用户决策承担。
- Android assembleDebug 需要 JDK 17 + Android SDK。
- Web 构建需要 Node 22+。