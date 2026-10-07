# W6 门禁跟进 — Go 可发现性（2026-10-07 22:30+）

> 关闭阶段 2b W6 阻塞：`ci-local.ps1` 此前因 **PATH 无 `go`** 跳过 Server 段。
> 未改 git config；未提交。手动 smoke 签署仍由用户/设备完成（**未声称通过**）。

## 可移植 Go

| 项 | 结果 |
|----|------|
| `scripts/setup-go-local.ps1` | **exit 0** — 已存在 `D:\github\cursor\everything\.tools\go`（Go **1.23.4**） |

## 推荐命令（进程内 PATH，无需全局安装）

在仓库根目录 PowerShell 中：

```powershell
# 一次性：确保 .tools/go 存在
powershell -NoProfile -File scripts/setup-go-local.ps1

# 门禁（任选其一）
$env:PATH = "$PWD\.tools\go\bin;" + $env:PATH
powershell -NoProfile -File scripts/ci-local.ps1 -Target server

powershell -NoProfile -File scripts/ci-local.ps1 -Target all
```

自本跟进起，`ci-local.ps1` 的 `Gate-Server` 会在 PATH 无 `go` 时**自动** prepend `<repo>\.tools\go\bin`（若 `go.exe` 存在），无需手动 `$env:PATH` 亦可跑 `-Target server` / `all`（仍须先 `setup-go-local.ps1` 装一次）。

完整日志：

- `docs/smoke/ci-local-server-w6-followup.log`
- `docs/smoke/ci-local-all-w6-followup.log`

## 复跑结果

| 命令 | 退出码 | Server | Web | Android | 备注 |
|------|--------|--------|-----|---------|------|
| `ci-local.ps1 -Target server`（PATH 含 `.tools\go\bin`） | **0** | 见下 | — | — | |
| `ci-local.ps1 -Target all`（同上） | **0** | 见下 | **PASS** 771 | **PASS** | |
| 显式 `go test -count=1 ./...`（`server/`，CGO=0） | **0** | **PASS** | — | — | 权威 Windows 单测 |
| 显式 `go vet ./...` | **0** | **PASS** | — | — | |
| 显式 `go build ./cmd/eve`（windows/amd64） | **0** | **PASS** | — | — | |
| `ci-local` 内 `go test -race`（CGO=0） | — | **FAIL** | — | — | 见「剩余阻塞」 |

### Server 段明细（`-Target all` 与 `-Target server` 一致）

1. `go test -race -count=1 ./...` → 立即失败：`go: -race requires cgo; enable cgo by setting CGO_ENABLED=1`（**未执行任何包测试**）
2. `go vet ./...` → **PASS**
3. 四目标 `go build ./cmd/eve` → **PASS**
4. 脚本仍打印 `V Go gate passed`（**未检查** `go test -race` 退出码——脚本既有行为）

### Web / Android（`-Target all`）

- Vitest **771/771**、vue-tsc、build → **PASS**
- `testDebugUnitTest --rerun-tasks`、`assembleDebug` → **PASS**

## 剩余阻塞 / 非自动化项

| 项 | 说明 |
|----|------|
| **`go test -race` on Windows** | `ci-local` 固定 `CGO_ENABLED=0` 与 `-race` 互斥；在 Win 上 race 步骤**不绿**，但脚本 exit 0。Linux/macOS CI 或本机 CGO+编译器环境方可真正跑 race。 |
| **权威 Server 单测（本机 Win）** | 以 `CGO_ENABLED=0 go test -count=1 ./...` **exit 0** 为准（见上表）。 |
| **手动 smoke** | `docs/smoke/stage2b-vault-attachments-e2e.md`、`map-tiles-e2e.md` 文末签署表 → **用户操作**，未填。 |
| **`connectedDebugAndroidTest`** | 无 adb 设备 → **未跑**。 |

## 与主报告关系

- 主报告：`docs/smoke/gating-report-2026-10-07.md`（早期 Wave 0 仍记 Go SKIP；以**本跟进**为 W6 Go 关闭状态）。
