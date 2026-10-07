# 门禁报告 — 2026-10-07（自用优先：财务 + 物品）

## Wave 0 — 基线

| 栈 | 命令 | 结果 | 备注 |
|----|------|------|------|
| Go server | `go test -race ./...` | **SKIP** | 本机 PATH 无 `go` |
| Web | `npm ci` + `npx vitest run` | **PASS** 726/726 | 初跑 5 个 finance fixture SHA 失败，已同步常量 |
| Web | `npx vue-tsc --noEmit` | **PASS** | 已添加 `qrcode` 依赖 |
| Web | `npm run build` | （见批末） | |
| Android | `testDebugUnitTest --rerun-tasks` | **PASS** | 需 `local.properties` → `%LOCALAPPDATA%\Android\Sdk`（已 gitignore） |

## P0 — 财务稳定

- 修复：Web/Android 五组 `__fixtures__` 与 `FIXTURE_SHA256` / `EXPECTED_FIXTURE_SHA256` 失步（fixture 内容已更新，常量未改）。
- Android 全量 unit：**BUILD SUCCESSFUL**。

## P1 — 物品（stage5-items）

- T1–T3：文档 + Web/Android 纯函数 + fixture SHA `5c76b459…`
- T4：Room v11 `item` / `item_reminder_log` + `ItemsRepository`
- T5/T6/T9：ReminderScheduler `module=item`、Compose UI、AppNav、CollectorWorker 拉取
- T7/T8：Web `stores/items.ts`、路由 `/vault/items`、AppShell「物品」

## 自用最低验收（Web + Android 双端常用）

执行人：________  日期：2026-10-07

- [ ] 登录 → 解锁 MK
- [ ] **财务**：记一笔日常流水；v2 订阅或保单新建并在另一端同步可见
- [ ] **物品**：Web 新建物品 → Android 同步可见 → 任一端编辑 LWW
- [ ] 物品二维码扫码仅打开 id（通知文案无名称/价格）

## 显式 SKIP

- FU-7 真机保活
- Stage 6 Agent / Ollama
- smoke-4b 全量手册（日历未回归失败则不跑）

## Final gate（批末）

- Web vitest + vue-tsc + build：见上方
- Android `testDebugUnitTest`：**PASS**

---

## Goal 运行 — 2026-10-07 21:18+

| 项 | 结果 |
|----|------|
| Go 安装 | 可移植 **1.23.4** → `.tools/go`（`scripts/setup-go-local.ps1`） |
| `go test ./...` (CGO_ENABLED=0, 无 -race) | **PASS**（api/agent/auth/crypto/db/vault） |
| `go vet` + `go build ./cmd/eve` | **PASS** → `server/eve.exe` |
| 服务端运行 | `eve.exe -addr :8787` → `GET /api/v1/health` **ok** |
| `ci-local.ps1 -Target web` | **PASS** 726 tests |
| `ci-local.ps1 -Target android` | **PASS** unit + assembleDebug |
| `assembleDebugAndroidTest` | **PASS**（已修 MigrationTest FrameworkSQLiteDatabase） |
| `connectedDebugAndroidTest` | **SKIP**（无 adb 设备） |
| `go test -race` | **SKIP**（Windows + CGO_ENABLED=0 不支持 race） |

**本机继续用：**

```powershell
$env:PATH = "d:\github\cursor\everything\.tools\go\bin;" + $env:PATH
cd d:\github\cursor\everything\server
.\eve.exe -addr :8787 -data-dir .\data-goal-run
# Web 开发: cd web && npm run dev  → http://localhost:5173
# APK: android\app\build\outputs\apk\debug\app-debug.apk
```
