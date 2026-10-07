# 阶段 2b — 档案影像附件（Vault Attachments）- 产品需求文档（PRD）

## Overview

- **Summary**：在零知识 records 通道上，把阶段 5 v2 已验证的**财务附件**
  （元数据 L1 + 256 KiB 块 L2）泛化为 **vault 级影像能力**，覆盖证件
  （`module=identity`）、密码库银行卡卡面（`module=pass` / `type=card`）、
  以及既有财务附件（`module=finance`）。不新开服务端 blob 表；块密文 AAD
  **完全复用** `eve:v1:attachment-block:{attachment_id}:{offset}`。
- **Purpose**：补齐「证件可拍照归档、银行卡可选卡面图、合同/保单 PDF」的产品
  体感；为阶段 2b 后续 OCR（P2）、证件墙（P3）提供统一附件 id 与解密预览
  链路；维持服务端只见密文与块索引。
- **Target Users**：自托管 Everything、Web 管理端 + Android 同步端的单一用户本人。

## Goals

- **G-1**：附件元数据记录 `module` 与**父业务记录同模块**，`type=attachment`；
  `parent_ref_id` 指向父记录 `id`（证件 / 银行卡 / 合同等）。
- **G-2**：identity 明文扩展：`front_attachment_id` / `back_attachment_id`（身份证
  正反面）；`scan_attachment_ids[]`（护照等多页扫描）。
- **G-3**：Web 抽取 `web/src/vault/attachment.ts`（从 finance 泛化 `parentModule`），
  证件编辑器接拍照/选图/预览/删除；财务与 pass 卡面复用同一通道。
- **G-4**：Android 镜像 Web 语义：共用或扩展 `AttachmentRepository`，`parent_module`
  与 finance v2 对齐；证件编辑页相机/相册（W3）。
- **G-5**：双端同步后 Web 可 `blob:` 内存预览解密图，**不落** localStorage /
  IndexedDB；日志/通知不含证件号、文件名明文路径。
- **G-6**：单文件 ≤ 50 MiB、上传/下载 sha256 校验、允许 MIME：
  `image/jpeg` / `image/png` / `image/webp` / `application/pdf`（与 finance
  一致并扩展 webp）。
- **G-7**：零回归：finance v2 既有附件单测与 AAD 跨端向量保持绿。

## Non-Goals

- **不做**服务端 OCR / 云图床 / 新 envelope 原语 / 新块 AAD 前缀。
- **不做**本阶段（W0）任何 UI 或代码实现——仅契约与文档（W2/W3 实施）。
- **不做**证件墙、今日仪表盘、全局搜索（P3 / W5）。
- **不做**完整 PAN / 身份证号持久化扩展（OCR 预填仍遵守既有 last4 / 密文字段纪律，P2）。
- **不做**附件去重合并（同 sha256 可存在多条独立 attachment 记录，与 finance v2 一致）。
- **不做**iOS 端。

## 已决架构（W0 锁定，W2 须遵循）

### 记录类型

| 维度 | 取值 | 说明 |
|---|---|---|
| `module` | `identity` / `finance` / `pass` | 与**父记录**相同，不写死 `finance` |
| `type` | `attachment` | 与 finance v2 统一，**不**引入 `document_attachment` |
| L1 元数据 AAD | `eve:v1:record:{attachment_id}:{module}:{BE_UINT64(version)}` | `module` 段随父模块变化 |
| L2 块 AAD | `eve:v1:attachment-block:{attachment_id}:{offset}` | 与 §6.7.5 逐字节一致，**跨模块共用** |
| 块大小 / 上限 | 256 KiB / ≤ 50 MiB | 三端与 finance v2 相同 |

### 元数据明文 JSON（L1，密封前）

与 [module-schemas.md](../../../docs/module-schemas.md) §9.18 对齐，并显式包含 `parent_ref_id`：

```json
{
  "id": "uuid",
  "name": "front.jpg",
  "mime": "image/jpeg",
  "size": 1048576,
  "sha256": "64-hex",
  "parent_ref_id": "parent-identity-uuid",
  "created_at": 1759000000000
}
```

- `schema_version`：附件记录走 records 信封时，客户端 `type=attachment` 条目与 finance v2 一致使用 **`2`**（仅 attachment 子类型，不强制抬高 identity 父记录 schema_version）。
- `parent_ref_id`：必填于业务逻辑（上传时写入）；拉取孤儿附件（父已删）时客户端按墓碑策略清理或隐藏。

### identity 父记录扩展字段

见 [module-schemas.md](../../../docs/module-schemas.md) §3；均为可选，旧记录无字段即无影像。

| 字段 | 类型 | 用途 |
|---|---|---|
| `front_attachment_id` | UUID 字符串 | 身份证正面（`kind=id_card` 时 UI 优先展示） |
| `back_attachment_id` | UUID 字符串 | 身份证反面 |
| `scan_attachment_ids` | UUID 字符串数组 | 其它证件多页；顺序即 UI 页序 |

**互斥约定（UI 层，非协议硬约束）**：

- `id_card`：推荐 `front` + `back`；若用户仅上传多页扫描，可退化到 `scan_attachment_ids`。
- `passport` / `driver_license` / `generic`：默认仅用 `scan_attachment_ids`；允许额外设置 front/back 但不强制。

### pass / finance 引用（扩展，非 identity 专属）

- `pass` / `type=card`：可选 `card_face_attachment_id`（列入 module-schemas §2 后续增量，W2 与 FinanceCard 并行）。
- `finance`：既有 `contract.attachment_id`、`tx` 附件列表、`card` 卡面（若产品启用）继续走 `module=finance` + `parent_ref_id`。

### 客户端存储（Android Room，W3 决策预览）

**推荐（spec 倾向）**：将 `finance_attachment` / `finance_attachment_block` 演进为
`vault_attachment` / `vault_attachment_block`，增加 `parent_module TEXT NOT NULL`
列，避免 identity 与 finance 双表；迁移走显式 `MIGRATION_n_n+1`，**严禁** destructive。

**备选（若 W2 求快）**：Web 仅 records 通道、无本地 attachment 缓存表；Android 暂复用
finance 表且 `module` 列写 `identity`——W3 必须补迁移，否则双端索引不一致。

> W0 不改 Room；上表供 W2 主会话在开工前二选一，避免并行 agent 分叉。

### 零知识纪律

- 预览 URL 仅 `blob:` / 内存 `Uint8Array`；页面卸载即失效。
- 错误提示：「附件无法打开」类通用文案，不带 `name` / `sha256` / 尺寸。
- 服务端：`attachment_blocks`（或等价块 API）仍只见密文；与 finance v2 相同路由。

## Functional Requirements

### FR-1 上传

- 客户端校验 MIME 白名单与 50 MiB 上限；计算 `sha256(原始字节)`。
- 分块密封 L2 → 上行块索引；L1 元数据 `sealRecord(module=父模块, type=attachment)`。
- 更新父记录对应 `*_attachment_id` 字段并 `version+1` 重封父记录。

### FR-2 下载与预览

- `openRecord` 解 L1 → 按 `id` 拉块 → `openAttachmentBlock` 拼接 → sha256 校验。
- 解码为 `Blob` / `ImageBitmap` 供 UI；PDF 走既有 AttachmentViewer 逻辑。

### FR-3 删除

- 删除父记录或用户移除影像：附件记录标墓碑 + 块密文按 finance v2 策略 GC（客户端发起，服务端幂等）。
- 父记录字段清空对应 id； dangling 附件由「按 parent_ref_id 反查」清理任务处理（W2 实现细节）。

### FR-4 同步

- 附件 records 与块走既有 `RecordsRepository` / Web `pullRecords` + 块 batch API，无新服务端接口（除非块 API 已泛化 module 校验——须与 finance 行为一致）。

## Acceptance（产品级）

- 证件正反面保存 → Android 同步 → Web 解密预览。
- grep 审计：通知/日志无证件号、无附件明文路径。
- Vitest + Android 单测：sha256、超 50MB 拒绝、AAD 前缀与 finance 块向量一致。
- finance v2 附件回归全绿。

## 关联文档

- [docs/crypto.md](../../../docs/crypto.md) §6.7 / §6.7.8（vault 附件密封）
- [docs/module-schemas.md](../../../docs/module-schemas.md) §3（identity 影像字段）
- [web/src/finance/attachment.ts](../../../web/src/finance/attachment.ts)（泛化前参考实现）
- 产品批次计划：`.cursor/plans/档案影像与地图修复_ba476c7e.plan.md`（P1 / W0–W3）

## Future Enhancements

- 缩略图端侧缓存（加密 blob 子块，非 W2 范围）。
- `pass` 登录项附件、物品 `item` 发票图（复用本 spec 同一 `type=attachment`）。
- 加密备份包包含 attachment 块密文（顾问池「备份 MVP」）。
