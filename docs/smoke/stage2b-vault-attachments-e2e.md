# 阶段 2b — 档案影像附件端到端手动冒烟脚本

> 对应 `.trae/specs/stage2b-vault-attachments/spec.md` 与产品批次 W6 门禁。
> 覆盖 **证件正反面 / 多页扫描**、**财务银行卡卡面图**、records 通道同步、
> 内存 `blob:` 预览、sha256 验签、**≤ 50 MiB** 拒绝，以及零知识 grep 纪律。
>
> 路径约定：
> - **Web** `web/`（`web/src/vault/attachment.ts`、`IdentityAttachmentFields.vue`、
>   `useVaultThumb.ts`、`ArchiveWallView.vue`）；
> - **Android** `android/`（`AttachmentRepository`、`VaultImageSlot`、`IdentitiesScreen`、
>   `IdentityEditorScreen`、`AttachmentRules.kt`）；
> - **Go 服务端** 仍 **零接触** 附件明文（仅 records 密文与块索引，与 finance v2 相同）。
>
> 自动化复跑（与 `scripts/ci-local.ps1` 对齐）：
>
> ```powershell
> # 全门禁（Windows）
> powershell -NoProfile -File scripts/ci-local.ps1 -Target all
>
> # 分栈
> cd web && npx vitest run
> cd web && npx vue-tsc --noEmit -p tsconfig.json
> cd android && .\gradlew.bat :app:testDebugUnitTest --rerun-tasks
>
> # Go（本机无 PATH 时先 scripts/setup-go-local.ps1，再）
> $env:PATH = "<repo>\.tools\go\bin;" + $env:PATH
> cd server && go test -count=1 ./...
> ```
>
> 相关单测锚点：
> - `web/src/vault/__tests__/attachment.spec.ts`
> - `web/src/vault/__tests__/aggregate.spec.ts`
> - `android/app/src/test/java/com/everything/eve/vault/AttachmentRulesTest.kt`
> - `android/app/src/test/java/com/everything/eve/data/finance/AttachmentRepositoryTest.kt`
>
> **显式 SKIP（本脚本不冒充已测）**：`connectedDebugAndroidTest`、真机 CameraX 画质、
> 跨网弱网大文件断点续传。

---

## §A 通用前置

1. **环境**
   - 启动服务端：`server` 构建产物或 `go run ./cmd/eve`，监听如 `:8787`；
   - Web 经 embed 或 `cd web && npm run dev` 代理到同一 origin（附件与 records API 同源）；
   - Android 模拟器/真机配置 `baseUrl` 指向同一服务端（与阶段 1 登录一致）。
2. **账号与解锁**
   - 完成注册/登录，设备状态为 **approved**；
   - Web/Android 均 **解锁资料库（MK 在内存）**；未解锁时附件列表应为空或禁用上传。
3. **密文通道**
   - 附件元数据：`module` = 父记录模块（`identity` / `finance` / `pass`），`type=attachment`；
   - 块 AAD：`eve:v1:attachment-block:{attachment_id}:{offset}`（与 finance v2 逐字节一致）。
4. **零知识 grep（贯穿）**
   - 服务端日志 / 审计：不出现完整身份证号、完整 PAN、`attachment` 解密路径、用户文件名明文；
   - Web：`localStorage` / `IndexedDB` **不得**存解密图像字节或 `blob:` URL 持久化键；
   - Android：`Logcat` / 通知不出现证件号、卡号、附件本地绝对路径；
   - 浏览器 DevTools **Console**：上传/预览流程不 `console.log` 证件号或 PAN。

---

## §B 场景列表

### 场景 1：Web — 身份证正面影像上传与内存预览

- **前置**：`kind=id_card` 证件已存在或新建。
- **步骤**：
  1. 打开证件编辑 →「正面」选择一张 **< 5 MiB** 的 JPEG；
  2. 保存证件 → 触发 records push（含 `front_attachment_id`）；
  3. 刷新页面或重进编辑页 → 缩略图可见。
- **期望**：
  - Network：`PUT/POST` records 与附件块均为密文载荷；
  - 预览 URL 为 `blob:`，页面卸载或关闭预览后 `URL.revokeObjectURL`（见 `useVaultThumb`）；
  - Application 面板无新增存明文图像的 IndexedDB 键。
- **失败降级**：sha256 与元数据不一致 → UI 标红「附件校验失败」，不展示图。

### 场景 2：Web — 身份证反面 + `expires_on` 字段共存

- **步骤**：上传反面图 → 手动填写有效期 → 保存。
- **期望**：`back_attachment_id` 与日期字段同一父记录版本递增；列表/证件墙显示到期色条（若已过期为红/橙）。

### 场景 3：Web → Android 同步预览

- **步骤**：Web 完成场景 1–2 → Android 触发同步（WorkManager 或设置内「立即同步」）→ 打开同一证件。
- **期望**：Android `VaultImageSlot` 可解密显示；两端 `version` LWW 一致。
- **反向**：Android 仅改标题 → Web 拉取后标题更新，附件 id 不变。

### 场景 4：Android — 相册选图上传证件

- **步骤**：证件编辑 → 正面/反面从相册选择 PNG/WebP（白名单内）。
- **期望**：`validateAttachmentUpload` 通过；拒绝非 `image/jpeg|png|webp`（若 UI 允许选 PDF，应提示仅影像）。

### 场景 5：财务银行卡 — 卡面图（last4 纪律）

- **前置**：财务模块已有银行卡，PAN 仅 **last4** 入库。
- **步骤**：Web `FinanceCardEditor` 上传卡面 JPEG → 保存。
- **期望**：
  - 卡面 `parent_ref_id` 指向卡记录 id，`module=finance`；
  - UI 与通知不出现完整卡号；OCR 若预填 PAN 仅在内存，保存后仍仅 last4。

### 场景 6：证件墙缩略图聚合

- **步骤**：至少 2 个证件带正面图 → 打开 `/vault/archive`（证件墙）。
- **期望**：网格展示解密缩略图 + 类型标签；无图证件显示占位，不崩溃。

### 场景 7：删除附件与孤儿处理

- **步骤**：编辑证件 → 删除正面图 → 保存 → 对端同步。
- **期望**：`front_attachment_id` 清空；附件记录软删或客户端隐藏；证件墙不再显示该缩略图。

### 场景 8：sha256 验签失败（故意篡改）

- **步骤**（仅测试环境）：上传成功后，在另一端把本地缓存块改一字节或 mock 错误 hash 再预览。
- **期望**：下载/解密流程拒绝展示，UI 提示校验失败；**不**将脏数据写入持久层。

### 场景 9：超过 50 MiB 拒绝

- **步骤**：选择 **> 52428800 字节** 文件（可用本地生成占位文件，**勿**提交仓库）。
- **期望**：
  - Web：选择后立即提示「仅支持 ≤ 50 MiB」类文案，**不**发起块上传；
  - Android：`AttachmentValidation.Rejected` 对应提示；
  - 单测：`AttachmentRulesTest` / `web/src/vault/__tests__/attachment.spec.ts` 已覆盖上限常量。
- **记录**：本场景为客户端校验，服务端仍不见明文大小以外的业务含义。

### 场景 10：零知识抽检（手动）

- **步骤**：完成场景 1–5 后：
  1. 服务端终端滚动日志 30 秒；
  2. Web Console 清空后重放上传；
  3. Android `adb logcat` 过滤应用包名。
- **期望**：无 18 位身份证模式、无 13–19 位连续卡号、无用户真实姓名+证号同屏日志。

---

## §C 签署

| 项 | 执行人 | 日期 | 结果 |
|----|--------|------|------|
| Web 场景 1–3、5–9 | | | ☐ PASS / ☐ FAIL |
| Android 场景 3–4 | | | ☐ PASS / ☐ SKIP（无设备） |
| 零知识场景 10 | | | ☐ PASS |

自动化门禁（2026-10-07 W6）：Web vitest **771**、vue-tsc、build **PASS**；Android `testDebugUnitTest` + `assembleDebug` **PASS**（无 `connected` 设备）。
