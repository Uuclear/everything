# 顾问建议池落地记录（Advisor Changelog）

> 每批开发除用户显性需求外，**至少采纳 2 条**顾问池（见各批次 plan「顾问主动建议池」）或等价改进，避免只补缺口。
> 本文件按时间倒序追加；未执行的项记入「顺延」便于下一批勾选。

---

## 2026-10-07 — 档案影像 + 地图 403 + OCR + P3 效率（阶段 2b 批次）

**用户显性目标（摘要）**：轨迹底图默认可用（同源瓦片代理）；证件/卡面影像归档；端侧 OCR 填表；证件墙与今日仪表盘、全局搜索。

### 本批主动落地（≥2）

| # | 顾问池来源 | 落地内容 | 证据 |
|---|------------|----------|------|
| 1 | **B. Web 财务 OCR 补齐**（文档债） | Web 小票/卡面/证件 OCR 纯函数 + 编辑器确认流，图像不出浏览器 | `web/src/ocr/*`、`FinanceTxEditor` / 证件编辑接入；Vitest fixture |
| 2 | **C. 档案感 — 证件墙 + 今日与我** | `/vault/home` 聚合提醒、`/vault/archive` 缩略图网格；Android 镜像页 | `VaultHomeView.vue`、`ArchiveWallView.vue`、`VaultHomeScreen.kt`、`ArchiveWallScreen.kt` |
| 3 | **D. 通用附件层一次抽象** | `vault/attachment` 统一 identity/finance/pass，避免三套块协议 | `web/src/vault/attachment.ts`、`AttachmentRules.kt`、crypto/module-schemas 2b 节 |
| 4 | **B. 命令式效率 — 全局搜索** | 解锁后本地解密搜索证件名/物品/财务备注/密码标题 | `useGlobalSearch.ts`、`GlobalSearchPanel.vue`、`vault/aggregate.ts` |

（上表 1–4 中任选两条即满足批次约定；本批实际交付 **4** 条。）

### 用户显性需求一并交付

- P0：`/api/v1/map/tiles/...` + Web 默认同源 URL + 失败 UI（`map-tiles-e2e.md`）。
- P1：证件正反面 / 扫描页附件 + 银行卡卡面（`stage2b-vault-attachments-e2e.md`）。
- P2：身份证/银行卡/小票 OCR 双端（ML Kit / Web WASM 路径，默认零知识不出网）。

### 顺延 / 未承诺

| 项 | 原因 |
|----|------|
| **统一同步中心**单页（上次成功时间、待推送条数） | 本批聚焦附件与地图；与阶段 8 备份/运维合并更顺 |
| **加密备份/恢复 MVP** | 顾问池 A-1，排下一批默认推进 |
| **Android 密码库完整 UI** | 阶段 2 原欠账，未在本批 scope |
| **AppShell 插件化路由** | 架构债，侧栏模块增至 7+ 后再动刀 |
| **真机 `connectedDebugAndroidTest` / FU-7 闹钟** | W6 仅 unit + assembleDebug；**未声称真机通过** |
| **附件 >50 MiB 分卷** | 仍属 finance v3 候选 |
| **CI 重新启用 GitHub Actions** | 仍走 `scripts/ci-local.ps1` 本地门禁 |

### 门禁（自动化，2026-10-07）

| 命令 | 结果 |
|------|------|
| `powershell -NoProfile -File scripts/ci-local.ps1 -Target all` | Web **PASS**（771）；Android **PASS**；Server 见跟进报告 |
| W6 Go 跟进（2026-10-07 22:30） | `ci-local -Target server/all` **exit 0** + `go test -count=1 ./...` **exit 0**；详见 `docs/smoke/gating-report-w6-go-followup-2026-10-07.md` |
| `ci-local` 内 `go test -race`（Win, CGO=0） | **FAIL**（脚本仍 exit 0；race 未跑） |
| 手动 smoke §签署 | **未填** — 见 `docs/smoke/stage2b-vault-attachments-e2e.md`、`map-tiles-e2e.md` |

---

## 模板（后续批次复制）

```markdown
## YYYY-MM-DD — 批次标题

### 本批主动落地（≥2）
...

### 顺延
...
```
