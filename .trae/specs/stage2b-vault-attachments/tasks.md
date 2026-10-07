# 阶段 2b — 档案影像附件 - 实施计划

> 需求来源：[spec.md](./spec.md)
> 与产品计划波次对齐：W0 文档 → W2 Web → W3 Android → W6 门禁与 smoke。
> **优先级**：P0 必做 / P1 建议。

## 波次总览

| 波次 | Task | 内容 | 状态 |
|---|---|---|---|
| W0 | T0 | spec + module-schemas §3 + crypto §6.7.8 | `completed`（本文档批次） |
| W2 | T1 | Web `vault/attachment.ts` 泛化 + store 接入 | `pending` |
| W2 | T2 | Web identity 编辑器影像 UI + AttachmentViewer 组件化 | `pending` |
| W2 | T3 | Web FinanceCard / pass card 可选卡面图 | `pending` |
| W3 | T4 | Android AttachmentRepository 泛化 + Room 迁移 | `pending` |
| W3 | T5 | Android 证件编辑相机/相册 | `pending` |
| W6 | T6 | smoke `docs/smoke/stage2b-vault-attachments-e2e.md` + ci-local | `pending` |

---

## Task 0: 契约与文档（W0 / P0）

- **Status**: `completed`
- **Depends On**: stage5-finance-v2 附件已落地
- **Pass Condition**:
  - `.trae/specs/stage2b-vault-attachments/spec.md` 锁定 module/type、parent_ref_id、块 AAD 复用、identity 三字段。
  - `docs/module-schemas.md` §3 含 `front_attachment_id` / `back_attachment_id` / `scan_attachment_ids`。
  - `docs/crypto.md` §6.7.8 说明 vault 级 L1 AAD（按父 module）与 L2 共用前缀。
- **Description**:
  - 仅文档；不改 Web/Android 代码。
- **TR 列表**:
  - TR-0.1 spec.md PRD
  - TR-0.2 tasks.md 波次划分
  - TR-0.3 module-schemas + crypto 同步

---

## Task 1: Web 泛化附件通道（W2 / P0）

- **Status**: `pending`
- **Depends On**: T0
- **Pass Condition**:
  - 新建 `web/src/vault/attachment.ts`：`parentModule: 'identity' | 'finance' | 'pass'`。
  - L1 `sealRecord(module=parentModule, type='attachment', …)`；L2 调既有 `sealAttachmentBlock`。
  - `web/src/finance/attachment.ts` 改为 re-export 或薄包装，finance 单测零回归。
  - Vitest：identity 模块 AAD 段为 `identity`；块 AAD 与 finance 固定向量一致。
- **TR 列表**:
  - TR-1.1 抽取 vault attachment 纯函数
  - TR-1.2 finance 兼容层
  - TR-1.3 vault store / pull 按 parent_ref_id 索引

---

## Task 2: Web 证件影像 UI（W2 / P0）

- **Status**: `pending`
- **Depends On**: T1
- **Pass Condition**:
  - `RecordEditor` / `RecordViewer`：`kind=identity` 时正反面 + 多页扫描 UI。
  - `IdentityData`（`web/src/types/vault.ts`）扩展三字段；校验 attachment id 为 UUID。
  - 预览复用组件化 `AttachmentViewer`（从 finance 视图抽出）。
- **TR 列表**:
  - TR-2.1 types + 校验
  - TR-2.2 编辑器上传/删除/预览
  - TR-2.3 列表不脱敏文件名（仅本地解密后显示）

---

## Task 3: Web 银行卡卡面（W2 / P1）

- **Status**: `pending`
- **Depends On**: T1
- **Pass Condition**:
  - `FinanceCardEditor` / pass `CardData` 可选 `card_face_attachment_id`（若 §2 字段已入 schema）。
  - `parent_ref_id` = 卡记录 id；`module` 分别为 `finance` / `pass`。
- **TR 列表**:
  - TR-3.1 module-schemas §2 / §9 卡面字段（若 W0 未写则本 task 补文档）
  - TR-3.2 编辑器 UI

---

## Task 4: Android Repository 泛化（W3 / P0）

- **Status**: `pending`
- **Depends On**: T0
- **Pass Condition**:
  - Room：`vault_attachment` + `vault_attachment_block` **或** finance 表加 `parent_module`（与 spec 已决项一致）。
  - `AttachmentRepository`（或等价）支持 `module=identity` 元数据密封与块上行。
  - `MigrationTest` v(n)→v(n+1) 全绿。
- **TR 列表**:
  - TR-4.1 DDL + 迁移
  - TR-4.2 seal/open 与 Web 字节一致
  - TR-4.3 RecordsRepository 同步 attachment 墓碑

---

## Task 5: Android 证件相机（W3 / P0）

- **Status**: `pending`
- **Depends On**: T4
- **Pass Condition**:
  - 证件编辑 Compose：相册 + CameraX（复用 OcrScannerSheet 管线可选）。
  - Identity 实体/JSON 含三 attachment 字段。
- **TR 列表**:
  - TR-5.1 UI
  - TR-5.2 单测（sha256 / 50MB）

---

## Task 6: 冒烟与门禁（W6 / P0）

- **Status**: `pending`
- **Depends On**: T1–T5
- **Pass Condition**:
  - `docs/smoke/stage2b-vault-attachments-e2e.md` 可手工执行。
  - `ci-local` 全绿；更新 `.trae/documents/everything_plan.md` 阶段 2b 状态。
- **TR 列表**:
  - TR-6.1 smoke 文档
  - TR-6.2 plan 同步

---

## W2 开工前待决项（来自 W0）

1. **Android Room 表策略**：`vault_*` 统一表 vs 暂用 `finance_attachment` + 后续迁移（见 spec「客户端存储」）；**未决则 T4 阻塞**。
2. **pass 卡面字段名**：`card_face_attachment_id` 是否与本波一起进 §2（T3 可仅 finance，pass 延后）。
3. **块 API module 校验**：若服务端 handler 硬编码 `finance`，W2 需同波次 Go 小改（查 `records_handler` / attachment block handler）。
