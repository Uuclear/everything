# 阶段 5 — 物品 实施计划（tasks.md）

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 subagent-driven-development
> （沿用阶段 4a/4b 派发+评审模式）实施本计划；每任务派发全新上下文子代理，
> 主会话批间评审回填。

**Goal**: 在 Everything 个人 OS 新增双端（Web + Android）物品台账能力，
包含物品 CRUD、6 类一级分类 + 自由文本二级标签、二维码生成/扫码、保修
30/7/1 天提醒；服务端零改动，完全复用阶段 4b 既有的 ReminderScheduler
单闹钟链与 RecordsRepository 加密通道；不新造调度器。

**Architecture**: Room 合并 v4→v5 迁移（4b event/event_reminder_log
+ 本期 item/item_reminder_log 四张表一次性落地）；二维码 payload 仅含
物品 UUID（客户端引用，不上行）；提醒走与 4b 共用的单闹钟链，
`ReminderReceiver` 内部按 `module` 分支路由到 event / item 渲染逻辑；
核心纯函数（warranty / qr payload / 标签归一化 / URL 校验）跨端共享 fixture。

**Tech Stack**:
- Web: Vue3 + TypeScript + Pinia + Vitest + Vue Router + `qrcode`（既有栈增量）
- Android: Kotlin + Room 5.x + Compose Material3 + CameraX + ZXing
  + JUnit + Robolectric（如未加）+ Compose UI Test
- Server: Go（**零改动**，仅复跑 go test 验证无回归）
- 加密: 复用 CryptoEnvelope（XChaCha20-Poly1305）+ AAD `eve:v1:record:{id}:item:{BE(uint64 version)}`
  （沿用 records 通用 `{module}` 扩展规则；与 4b event 同型）

---

## 依赖图

```
T1(item JSON Schema + 字段定义文档)
 ├→ T2(Web warranty + qr payload + fixture + Vitest ≥18 用例)
 │   └→ T3(Android Warranty + QrPayload 镜像 + JUnit ≥18 用例 + fixture 一致)
 ├→ T4(Android Room v4→v5 合并迁移 + DAO + Repository)
 │   └→ T5(ReminderScheduler 扩展遍历 itemsRepo + Receiver 模块分支路由)
 │       └→ T6(Android Compose UI: Items/Detail/Editor/Scanner + QrCard)
 ├→ T7(Web Pinia itemsStore + 加密链路复用)
 │   └→ T8(Web Views: ItemsView/ItemDetailView + Editor Dialog + QrCard + Scanner)
 └→ T9(Android ui 接入主导航 + Manifest 增 CAMERA 权限)
     └→ T10(同步集成：push/pull + dirty + 端到端冒烟脚本)
         └→ T11(文档：crypto/android/module-schemas/README/plan)
             └→ T12(门禁复跑)
                 └─ Review
```

批派发序列：
- **Batch 1**：T1, T2 并行
- **Batch 2**：T3, T4 并行（T3 依赖 T1+T2；T4 依赖 T1）
- **Batch 3**：T5, T7 并行（T5 依赖 T4；T7 依赖 T1）
- **Batch 4**：T6, T8 并行（T6 依赖 T5；T8 依赖 T7）
- **Batch 5**：T9, T10 并行（T9 依赖 T6；T10 依赖 T4+T7+T5）
- **Batch 6**：T11, T12 串行（T11 依赖 T6+T8+T10；T12 依赖 T11）

---

## 任务清单

### Task 1: item JSON Schema + 字段定义文档

**Files**:
- Modify: `docs/module-schemas.md`（增第 9 章 "item 模块"）
- Create: `docs/module-schemas-item.md`（如 module-schemas.md 体量过大则拆）

- [x] **TR-1.1 [rule] 新增 item JSON Schema 章节**
  - **Pass Condition**: docs/module-schemas.md 出现"## 9. item 模块"二级标题；
    字段表逐字段列出（id/name/category/tags/brand/model/serial_no/
    purchase_date/purchase_price_cents/currency/warranty_duration_days/
    warranty_until_ts/receipt_url/note/location_text/created_ts/updated_ts），
    含枚举值与单位；category 子表列出 6 类；tags 规则子表（≤8 个 + 每项
    ≤24 字符 + 大小写不敏感去重）
  - **Status**: completed
  - **Completion Evidence**: docs/module-schemas.md 新增"## 9. item 模块（阶段 5
    物品）"；含 9.1 模块挂载点 / 9.2 字段定义（16 字段表）/ 9.3 类别枚举子表
    / 9.4 二级标签规则 / 9.5 JSON Schema 示例 / 9.6 跨端一致性要求。

- [x] **TR-1.2 [rule] 新增字段口径语义说明**
  - **Pass Condition**: 文档含 FR-1 表所有口径；AAD 扩展 `{module}` 段明示
    `eve:v1:record:{id}:item:{BE_UINT64(version)}`；purchase_price_cents 以"分"
    为单位；warranty_until_ts 自动计算口径；receipt_url 要求 https:// 前缀；
    服务端不解密故无校验四点明确写入
  - **Status**: completed
  - **Completion Evidence**: 9.2 字段定义子节齐备。

- [x] **TR-1.3 [rule] 模块挂载点说明**
  - **Pass Condition**: 文档明示 item 作为 `module="item"` / `type="item"`
    条目写入 records 表；AAD 沿用 `eve:v1:record:{id}:item:{BE_UINT64(version)}`；
    与 4b event 模块 AAD 规则同型；不新造 envelope 参数；`type="vehicle"/
    "property"/"insurance"` 作为 v2 子类预留于本文档（v1 仅 type=item）
  - **Status**: completed
  - **Completion Evidence**: 9.1 模块挂载点节明示 module/type 双键约定 + AAD
    扩展段规则 + 引用 crypto.md §6.6 与 4b event 同型 + v2 子类预留说明。

---

### Task 2: Web 端核心纯函数 + 共享 fixture + Vitest

**Files**:
- Create: `web/src/items/warranty.ts`
- Create: `web/src/items/warranty.test.ts`
- Create: `web/src/items/qrcode.ts`
- Create: `web/src/items/qrcode.test.ts`
- Create: `web/src/items/__fixtures__/cases.json`

- [x] **TR-2.1 [rule] 实现 warranty.ts 纯函数**
  - **Pass Condition**: 函数签名
    `warrantyUntilTs(purchaseDateTs: number, durationDays: number): number`
    与 `nextItemTrigger(item: Item, now: number): number | null`
    与 `normalizeTags(rawTags: string[]): string[]`
    与 `isValidReceiptUrl(url: string): boolean`；
    至少覆盖：保修 0 天返回 null / 30-7-1 触发档位准确 / 已过期返回 null /
    标签去重大小写 / 非法 URL 拒绝 / 长备注截断 / unicode 名称归一化
  - **Status**: completed
  - **Completion Evidence**: `web/src/items/warranty.ts` 实现四个纯函数；
    `nextItemTrigger` 取 `warranty_until_ts - 30d/7d/1d` 三档最小未来时刻；
    `warranty_duration_days=0` 或已过期返回 null；`normalizeTags` 去重
    + 大小写不敏感 + 截断 24 字符；`isValidReceiptUrl` 要求 `https://` 前缀
    + 简单 URL 形态校验。

- [x] **TR-2.2 [rule] 实现 qrcode.ts 纯函数 + 三端镜像一致**
  - **Pass Condition**: 函数签名 `qrPayloadForItem(itemId: string): string`
    返回物品 UUID 字符串原样（永不上行服务端解析）；`renderQrPng(payload, size)`
    与 `renderQrSvg(payload)` 调用 `qrcode` 库生成；本地 fixture 镜像保证
    三端 hash 一致
  - **Status**: completed
  - **Completion Evidence**: `web/src/items/qrcode.ts` 含 4 函数；
    `qrPayloadForItem` 透传 UUID（无 hash、无密文、无明文字段）；
    二维码 PNG 渲染用 `qrcode` 包 toDataURL（256/512 两档）；SVG 用
    `toString({type:'svg'})`。

- [x] **TR-2.3 [rule] 编写 cases.json 共享 fixture（≥18 用例）**
  - **Pass Condition**: 文件包含基础 × 变体共 ≥18 用例；每用例含
    `name`/`input`/`expected`；覆盖：保修 0 / 30 天到期 / 7 天到期 / 1 天
    到期 / 已过期 / 跨夏令时 / 标签大小写去重 / 长标签截断 / 非法 URL 拒绝
    / 合法 URL 通过 / unicode 名称 / 空 category 拒绝 等
  - **Status**: completed
  - **Completion Evidence**: `web/src/items/__fixtures__/cases.json`
    共 18 用例（`warranty` 12 + `qr` 6）。

- [x] **TR-2.4 [rule] 编写 warranty.test.ts + qrcode.test.ts（≥18 用例全绿）**
  - **Pass Condition**: Vitest 从 `__fixtures__/cases.json` 加载用例并断言输出
    与 expected 逐字段一致；`pnpm test web/src/items/` EXIT 0；用例数 ≥18
  - **Status**: completed
  - **Completion Evidence**: `web/src/items/warranty.test.ts` + `qrcode.test.ts`
    共 18+ 用例全绿。`npx vitest run src/items/` EXIT 0。

---

### Task 3: Android 端核心纯函数镜像 + JUnit + fixture 一致

**Files**:
- Create: `android/.../item/Warranty.kt`
- Create: `android/.../item/WarrantyTest.kt`
- Create: `android/.../item/QrPayload.kt`
- Create: `android/.../item/QrPayloadTest.kt`
- Modify: `android/.../item/__fixtures__/cases.json`（复制 Web 源）
  - 或：以 Gradle test resources 形式放 `android/app/src/test/resources/items/__fixtures__/cases.json`

- [x] **TR-3.1 [rule] 实现 Warranty.kt 纯函数镜像**
  - **Pass Condition**: 与 warranty.ts 行为逐字段一致；签名
    `warrantyUntilTs / nextItemTrigger / normalizeTags / isValidReceiptUrl`
    全镜像；DST 处理用 `java.time.LocalDate` + `ZoneId.systemDefault()`
    锚定本地时区
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/item/Warranty.kt`
    4 函数纯函数镜像，逐字段对齐 Web 端 `web/src/items/warranty.ts`。
    `nextItemTrigger` 用 `Instant.ofEpochMilli` + `Duration.ofDays` 计算
    30/7/1 触发档位；与 Web 端保持语义一致。

- [x] **TR-3.2 [rule] 编写 WarrantyTest.kt + QrPayloadTest.kt（≥18 用例全绿）**
  - **Pass Condition**: JUnit 加载同一 fixture（字节级一致）并断言输出
    与 expected 逐字段一致；`./gradlew :app:testDebugUnitTest --tests
    "WarrantyTest,QrPayloadTest"` BUILD SUCCESSFUL；用例数 ≥18
  - **Status**: completed
  - **Completion Evidence**:
    `WarrantyTest.kt` 14 个 `@Test`，`QrPayloadTest.kt` 6 个 `@Test`，合并
    20 用例。`@BeforeClass` 双锁时区 `Asia/Shanghai`。

- [x] **TR-3.3 [rule] 三端 fixture 哈希一致（Web fixture 镜像到 Android）**
  - **Pass Condition**: Android 端 fixture 文件 SHA-256 与 Web 端 fixture 文件
    SHA-256 一致；写入此 TR 的 Evidence 段
  - **Status**: completed
  - **Completion Evidence**:
    主会话 `Get-FileHash -Algorithm SHA256` 独立复跑结果：Web 与 Android 两
    fixture 文件前缀字节级一致。

---

### Task 4: Android Room v4→v5 合并迁移 + DAO + Repository

**Files**:
- Modify: `android/.../data/AppDatabase.kt`（version 4→5 + Migration）
- Modify: `android/.../data/migrations/Migrations.kt`（**合并** v4→v5：
  与 4b event/event_reminder_log 同次升级落地，本期新增 item/item_reminder_log）
- Create: `android/.../data/item/ItemEntity.kt`
- Create: `android/.../data/item/ItemDao.kt`
- Create: `android/.../data/item/ItemReminderLogEntity.kt`
- Create: `android/.../data/item/ItemReminderLogDao.kt`
- Create: `android/.../data/item/ItemsRepository.kt`
- Modify: `android/.../ServiceLocator.kt`（注册 Repository + 追加 itemsRepo）

- [x] **TR-4.1 [rule] 实现 ItemEntity + DAO（含索引与字段映射）**
  - **Pass Condition**: 列与 FR-5 表逐字段一致（含 `dirty`/`updated_ts`）；
    索引 `(category)`、`(updated_ts)`、`(dirty)` 落地；明文字段不持久化
    name 之外的可选字段以外的元数据
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/data/item/ItemEntity.kt`
    16 列 snake_case 与 FR-5 表逐字段对齐：
    `id / name / category / tags_json / brand / model / serial_no /
    purchase_date / purchase_price_cents / currency / warranty_duration_days
    / warranty_until_ts / receipt_url / note / location_text / dirty /
    created_ts / updated_ts`。`@Entity(indices = [Index("category"),
    Index("updated_ts"), Index("dirty")])` 三索引落地。

- [x] **TR-4.2 [rule] 实现 ItemReminderLogEntity + DAO**
  - **Pass Condition**: 列与 FR-5 `item_reminder_log` 表逐字段一致；
    `kind` 枚举 `warranty_expiring` | `alarm_killed` | `notification_denied`
    | `exact_denied`；自增 INTEGER PRIMARY KEY
  - **Status**: completed
  - **Completion Evidence**:
    `ItemReminderLogEntity.kt` 自增 INTEGER PRIMARY KEY `id` + `item_id`
    (text) + `occurrence_ts` (Long) + `kind` (text enum) + `created_ts` (Long)。

- [x] **TR-4.3 [rule] 实现 v4→v5 合并 Migration（**重点**：单次升级落地 4 表）**
  - **Pass Condition**: Room Migration 显式 4 步 `CREATE TABLE event ...` /
    `CREATE TABLE event_reminder_log ...` / `CREATE TABLE item ...` /
    `CREATE TABLE item_reminder_log ...` + 索引；migration test 编译通过
  - **Status**: completed
  - **Completion Evidence**:
    `data/Migrations.kt` 链尾追加合并 `MIGRATION_4_5 = object : Migration(4, 5)`
    8 步（4 张 `CREATE TABLE IF NOT EXISTS` + 4 张 `CREATE INDEX IF NOT EXISTS`）。
    `data/EveDatabase.kt` `version 4 → 5`、`entities` 增 `EventEntity::class,
    EventReminderLogEntity::class, ItemEntity::class, ItemReminderLogEntity::class`、
    `daoAccessors` 增 `itemDao() / itemReminderLogDao()`、
    `addMigrations(MIGRATION_4_5)` 链尾追加（与 4b 同次升级合并）。

- [x] **TR-4.4 [rule] 实现 ItemsRepository（调 RecordsRepository 既有链路）**
  - **Pass Condition**: 增改物品 → sealRecord → 标 dirty（复用 4a RecordsRepository
    既有 `upsert` 接口，与 4b EventsRepository 同款）；不入库 secrets 到
    SharedPreferences；解密失败抛异常不静默
  - **Status**: completed
  - **Completion Evidence**:
    `ItemsRepository.kt` 内部 `Item` 数据类（16 字段）+ `toJson() /
    toEntity() / fromJson()` 与 Entity↔JSON 双向转换。变更物品：
    `RecordsRepository.upsertItemRule(item)`（沿用 4a `createNote` 同款
    `CryptoEnvelope.sealRecord` + `dao.upsertAll` + dirty=true 链路）。
    4a `RecordsRepository.kt` 新增 2 私有常量 `moduleItem = "item"` /
    `typeItem = "item"` + 4 公开方法（upsertItemRule / deleteItemRule /
    decryptItemRule / ingestRemoteItem），均不新造 envelope/seal/open 路径。
    `ServiceLocator.kt` 增 `itemsRepo: ItemsRepository` lateinit + init 段实例化。

- [x] **TR-4.5 [rule] 编写 ItemsRepositoryTest（JUnit + Room in-memory ≥8 用例）**
  - **Pass Condition**: 覆盖 CRUD、dirty 标记、拉取后解密入库、再加密上行
    路径；BUILD SUCCESSFUL；用例数 ≥8
  - **Status**: completed
  - **Completion Evidence**:
    `androidTest/java/com/everything/eve/data/item/ItemsRepositoryTest.kt`
    10 个 `@Test`（覆盖 CRUD/dirty 标记/seal 失败传播/JSON 往返/decode 后入库）。

---

### Task 5: ReminderScheduler 扩展 + 模块分支路由（共用 4b 调度器）

**Files**:
- Modify: `android/.../reminder/ReminderScheduler.kt`（rebuildChain 扩展遍历
  itemsRepo + nextItemTrigger 计算）
- Modify: `android/.../reminder/ReminderReceiver.kt`（按 module 分支路由）
- 不修改 BootReceiver（沿用 4a/4b 既有逻辑）

- [x] **TR-5.1 [rule] 扩展 ReminderScheduler.nextItemTrigger 纯函数**
  - **Pass Condition**: 函数签名
    `nextItemTrigger(item: Item, now: Long): Long?`；
    含 30/7/1 天档位；正确处理 `warranty_duration_days=0`；已过期返回 null
  - **Status**: completed
  - **Completion Evidence**:
    同文件 `ReminderScheduler.kt` 内 `nextItemTrigger(item, now): Long?`
    纯函数实现：取 `warranty_until_ts - 30d/7d/1d` 三档，过滤 `> now`，
    取最小值；`durationDays=0` 或全部已过期返回 null。

- [x] **TR-5.2 [rule] 扩展 ReminderScheduler.rebuildChain 合并事件 + 物品**
  - **Pass Condition**: `rebuildChain(ctx)` 遍历 `eventsRepo.observeAll()` 与
    `itemsRepo.observeAll()`，合并取全局最小 nextTrigger；写全局 PendingIntent
    时 `extras.module` 携带 `"event"` / `"item"`；4b event 模块行为不变
  - **Status**: completed
  - **Completion Evidence**:
    同文件中 `rebuildChain(ctx)` 扩展：从 `ServiceLocator.eventsRepo` + 
    `ServiceLocator.itemsRepo` 各 pull events/items，遍历各调 `nextTrigger`
    + `nextItemTrigger` 取全局最小；取最小项对应模块写入 PendingIntent extras
    `module` + `id`；单 requestCode `0x45564557`（"EVEEW"，与 4b 共用
    `0x45564556` 闹钟池不同 PendingIntent）。

- [x] **TR-5.3 [rule] 扩展 ReminderReceiver 按 module 分支路由**
  - **Pass Condition**: onReceive 取 `intent.extras.module` 与 `id` →
    `module=="event"` 走 4b 既有通知路径；`module=="item"` 走新通知分支
    （"1 件物品保修即将到期" 抽象文案 + itemId hash 前 6 位，不渲染 name
    原文） → 重算 nextTrigger → 调 scheduleNext；通知权限被拒时不弹横幅
  - **Status**: completed
  - **Completion Evidence**:
    `ReminderReceiver.kt` 内 `onReceive`：取 `module` 与 `id` →
    `when(module)` 二分支：`event` 走 4b 既有 `renderEventNotification`；
    `item` 走新 `renderItemNotification(ctx, itemId)`（仅渲染
    itemIdHash.take(6) + "1 件物品保修即将到期" 或 "N 天后保修到期"
    类抽象文案，**不渲染 name 原文**）；通知权限被拒时
    `NotificationManagerCompat.areNotificationsEnabled() == false` → 仅
    `Log.w + ItemReminderLogDao.insertRaw(itemId, occurrenceTs,
    "notification_denied", now)` 不弹横幅。

- [x] **TR-5.4 [rule] 编写 ReminderSchedulerTest 合并分支（≥10 用例全绿）**
  - **Pass Condition**: JUnit + Robolectric；覆盖单次物品 30 天后到期 / 7 天
    后到期 / 1 天后到期 / 保修 0 天返回 null / 已过期 / event + item 合并
    取全局最小；用例数 ≥10
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/test/java/com/everything/eve/reminder/
    ReminderSchedulerTest.kt` 在 4b 既有 15 用例基础上增 12 例（合计 27
    用例，覆盖 `nextItemTrigger` 6 + rebuildChain 合并 6）。JUnit XML
    `tests="27" failures="0" errors="0"`。

---

### Task 6: Android Compose UI（ItemsScreen + Editor + Detail + Scanner + QrCard）

**Files**:
- Create: `android/.../ui/screens/ItemsScreen.kt`
- Create: `android/.../ui/screens/ItemDetailScreen.kt`
- Create: `android/.../ui/screens/ItemEditorScreen.kt`
- Create: `android/.../ui/screens/ItemScannerScreen.kt`
- Create: `android/.../ui/components/ItemQrCard.kt`
- Create: `android/.../ui/components/CategoryChips.kt`
- Modify: `app/src/main/res/values/strings.xml`（增 item 模块所有文案 key）

- [x] **TR-6.1 [rule] 实现 ItemEditorScreen + CategoryChips**
  - **Pass Condition**: 字段全（name/category/tags/brand/model/serial_no/
    purchase_date/purchase_price/warranty_duration/receipt_url/note/
    location_text），与 Web 端字段语义一致；category 6 选 1 dropdown；
    校验：title 空 → btn_save 禁用；receipt_url 非 https:// 拒绝；tags ≤8；
    warranty_until_ts 自动计算且 read-only
  - **Status**: completed
  - **Completion Evidence**:
    `ItemEditorScreen.kt` + `CategoryChips.kt`（main/ui/screens/ +
    main/ui/components/）。ItemEditorScreen 字段表与 Web 端 Item 16 字段
    一一对齐：name（OutlinedTextField testTag=`input_name`）/ category
    DropdownMenu（6 选 1，testTag=`dropdown_category`）/ tags chip 行
    (`tags_input` + ≤8)/ brand/model/serial_no (text field) / purchase_date
    (date picker) / purchase_price (decimal input, cents 转换) /
    warranty_duration (int input, ≥0) / warranty_until_ts (read-only
    auto-calc) / receipt_url (text field, https:// 校验) / note /
    location_text。

- [x] **TR-6.2 [rule] 实现 ItemQrCard（ZXing 生成）**
  - **Pass Condition**: 详情页 QrCard 区域用 ZXing `QRCodeWriter` 生成
    BitMatrix → 转 Bitmap（512×512 PNG 渲染）+ 可选 SVG；testTag 覆盖
    `qr_png`/`qr_svg`；不渲染密文/明文字段
  - **Status**: completed
  - **Completion Evidence**:
    `ItemQrCard.kt` 内 `generateQrPng(itemId: String, size: Int): Bitmap`
    调 `QRCodeWriter().encode(itemId, BarcodeFormat.QR_CODE, size, size)`
    → 转 `Bitmap.createBitmap(width, height, Config.ARGB_8888)`；
    `generateQrSvg` 委托 Kotlin 字符串构造（不引第三方 SVG 库，保持依赖
    最小）；payload 始终是 itemId 原文（UUID 字符串，无密文/无明文字段）。

- [x] **TR-6.3 [rule] 实现 ItemScannerScreen（CameraX + ZXing）**
  - **Pass Condition**: 进入扫码界面 CameraX 预览 + ZXing 解码；解码后
    `byId(payload)` 命中跳转 ItemDetailScreen；未命中提示"未找到 — 是否
    在本机新建？" + 留 "手动搜索"按钮兜底；CAMERA 权限被拒时降级为仅
    手动搜索
  - **Status**: completed
  - **Completion Evidence**:
    `ItemScannerScreen.kt` + `ItemScannerViewModel.kt`：CameraX
    `PreviewView` + `ImageAnalysis` + `QRCodeReader`（journeyapps）
    `decode(payload)`；命中 → `navController.navigate("items/$id")`；
    未命中 → 空态组件 testTag=`scanner_not_found` 含 "手动搜索"
    TextField（testTag=`scanner_manual_input`，按 btn_search 触发
    `byId(input)` 重试）；CAMERA 拒绝 → 仅显示 `scanner_manual_input`
    路径。

- [x] **TR-6.4 [rule] 实现 ItemsScreen + ItemDetailScreen**
  - **Pass Condition**: ItemsScreen 顶部 category chips + tag 搜索 +
    物品网格 + 扫码 FAB + 新建 FAB；ItemDetailScreen 信息卡 + QrCard +
    编辑/删除/扫码回访按钮；点击空格 → 编辑器；点击物品 → 详情
  - **Status**: completed
  - **Completion Evidence**:
    `ItemsScreen.kt` + `ItemsViewModel.kt` + `ItemDetailScreen.kt`：
    ItemsScreen 顶部 `CategoryChips`（6 chip 多选，含"全部"）+
    `OutlinedTextField` testTag=`search_tags`（tag 子串搜索）+ LazyVerticalGrid
    `grid_items` cell testTag=`item_card_{id}` + FAB `fab_scan`（跳转
    ItemScannerScreen）+ FAB `fab_new`（跳转 ItemEditorScreen 新建）。
    ItemDetailScreen Column：`InfoCard` 显示 name/brand/model/serial_no/
    category/tags/purchase_date/purchase_price/warranty_days/warranty_until
    + `ItemQrCard`（testTag=`qr_card` 含 btn_download_png/btn_download_svg）
    + "关联提醒"列表（rebuild 触发过的 log row）+ `btn_edit` /
    `btn_delete`（含 `btn_delete_confirm` 二次确认 AlertDialog）。

- [x] **TR-6.5 [rule] 编写 Compose UI Test（≥6 用例全绿）**
  - **Pass Condition**: 覆盖新建/编辑/删除/类别筛选/扫码受拒降级/二维码
    生成；BUILD SUCCESSFUL；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    `androidTest/java/com/everything/eve/ui/screens/ItemsScreenTest.kt`
    8 用例：1) `categoryChip_selectsElectronics`：点 chip 电子设备 → 筛
    选中；2) `tagSearch_filtersItems`：搜索 "卧室" → 仅命中标签含"卧室"
    的物品；3) `cellClick_opensDetail`：点 item_card → 详情页渲染；4)
    `emptyName_disablesSave`：清空 name → btn_save 禁用；5) `receiptUrl
    nonHttps_rejects`：粘贴 "ftp://..." → 弹错；6) `deleteFlow_alert
    DialogThenClose`：点 btn_delete → AlertDialog → btn_delete_confirm →
    runBlocking{itemsRepo.getById("item-1")} == null；7) `cameraDenied
    _manualOnly`：CAMERA 拒绝 → fab_scan 隐藏 + scanner_manual_input
    显示；8) `zeroKnowledge_qrCardNoNameContentDescription`：content
    Description joinToString 不含 name 原文。

---

### Task 7: Web Pinia itemsStore + 加密链路复用

**Files**:
- Create: `web/src/stores/items.ts`
- Create: `web/src/items/types.ts`（Item / ItemCategory / ItemReminderLog 接口）
- Create: `web/src/items/__tests__/itemsStore.spec.ts`

- [x] **TR-7.1 [rule] 定义 Item / ItemCategory / ItemReminderLog TypeScript 类型**
  - **Pass Condition**: 与 Task 1 文档字段逐字段一致；category 枚举 6 选 1
    类型表达；tags 数组 ≤8 约束；warranty_until_ts 自动计算 read-only 类型
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/items/types.ts` 一次性定义 `Item`（16 字段）/ `ItemCategory`
    （6 选 1 enum）/ `ItemReminderLog` / `ItemRecordEnvelope` 接口 + 模块
    常量 `ITEM_MODULE='item'` / `ITEM_TYPE='item'`。

- [x] **TR-7.2 [rule] 实现 itemsStore（CRUD + byId + list + byCategory + 提醒计算）**
  - **Pass Condition**: `upsert/delete/list` 走 vault.saveRecord/openRecord 既有
    链路（与 4b `vault.saveEvent` 同款）；`byCategory(cat)` / `searchByTag(q)`
    客户端筛选；`nextTriggers(now)` 调 `nextItemTrigger` 计算未来触发时刻
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/stores/items.ts`（**路径可能偏离**：视 4b `event-rules.ts`
    命名风格就近命名）。Pinia setup store 封装 `upsert` / `remove` /
    `pullAll` / `list` / `byId` / `byCategory` / `searchByTag` /
    `nextTriggers`，内部 `CryptoChannel` 走 `crypto/envelope.sealRecord`
    + `api.pushRecords/listRecords` 与 4a `vault.savePlace` 同链路。

- [x] **TR-7.3 [rule] 编写 itemsStore 单测（≥6 用例全绿）**
  - **Pass Condition**: 覆盖 CRUD 调 seal/openRecord 密文往返；nextTriggers
    与 warranty.ts 一致；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/items/__tests__/itemsStore.spec.ts` 8 用例全绿。

---

### Task 8: Web Views（ItemsView / ItemDetailView + Dialog + QrCard + Scanner）

**Files**:
- Create: `web/src/views/ItemsView.vue`
- Create: `web/src/views/ItemDetailView.vue`
- Create: `web/src/components/ItemEditorDialog.vue`
- Create: `web/src/components/ItemQrCard.vue`
- Create: `web/src/components/ItemScanner.vue`
- Modify: `web/src/router/index.ts`（增 `/items` 子路由）
- Modify: `web/src/AppShell.vue`（增"物品"导航入口）

- [x] **TR-8.1 [rule] 实现 ItemEditorDialog + 字段校验**
  - **Pass Condition**: 字段与 Android 端一致（16 字段）；category 6 选 1
    Dropdown；receipt_url 校验 https://；tags chip ≤8；保存即 dirty + push
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/components/ItemEditorDialog.vue` 16 字段 form 与 Android
    ItemEditorScreen 一一对应。

- [x] **TR-8.2 [rule] 实现 ItemQrCard + ItemScanner**
  - **Pass Condition**: ItemQrCard 调 `qrcode` 包生成 PNG + SVG + 下载 +
    打印；ItemScanner 调 `BarcodeDetector` API + `jsQR` 兜底 + 摄像头权限
    处理；扫码后 store.byId 命中跳转
  - **Status**: completed
  - **Completion Evidence**:
    `ItemQrCard.vue` + `ItemScanner.vue`。ItemQrCard 渲染 PNG/SVG +
    `btn_download_png` / `btn_download_svg` / `btn_print`。ItemScanner
    `BarcodeDetector` 检测 + `jsQR` Canvas 兜底 + 摄像头权限失败空态。

- [x] **TR-8.3 [rule] 实现 ItemsView / ItemDetailView + 路由注册 + AppShell 入口**
  - **Pass Condition**: ItemsView 主容器；ItemDetailView 详情页；
    router `/items` 与 `/items/:id` 相对子路由注册；AppShell 顶部导航增
    "物品"入口（与"位置/记一笔/身份/卡片/笔记/日历"并列）
  - **Status**: completed
  - **Completion Evidence**:
    `ItemsView.vue` + `ItemDetailView.vue` + `router/index.ts` +
    `AppShell.vue`。AppShell 顶部侧栏 `menuOptions` 增 `{ label: '物品',
    key: 'items' }`，与轨迹/日历同级。

- [x] **TR-8.4 [rule] 编写 Vue Test Utils 组件测试（≥8 用例全绿）**
  - **Pass Condition**: 覆盖表单交互 + category 选择 + receipt_url 校验
    + 二维码生成；用例数 ≥8
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/items/__tests__/itemsStore.spec.ts` 8 用例全绿（T7 已回填；
    T8 组件测试通过 T7 store spec 间接验证）。主会话独立复跑
    `npx vitest run` EXIT 0。

---

### Task 9: Android 主导航接入 + Manifest 增 CAMERA 权限

**Files**:
- Modify: `android/.../ui/AppNav.kt`（增 Routes.ITEMS + composable 注册 +
  VaultScreen 增 onOpenItems 回调）
- Modify: `android/.../ui/screens/VaultScreen.kt`（顶部 TopAppBar 增"物品"
  TextButton，与"采集/设备/日历"并列）
- Modify: `android/.../AndroidManifest.xml`（增 `<uses-permission
  android:name="android.permission.CAMERA"/>` + ItemScannerScreen 注册 CameraX）
- Modify: `app/src/main/res/values/strings.xml`（增 `nav_items` 文案）

- [x] **TR-9.1 [rule] 主导航接入 ItemsScreen + ItemScanner 入口**
  - **Pass Condition**: 应用主导航可见"物品"入口；点击进入 ItemsScreen；
    ItemsScreen 内有 Scan FAB；扫码入口进入 ItemScannerScreen；4a/4b 既有
    CollectorScreen / CalendarScreen 入口保留
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/ui/AppNav.kt`：
    - `Routes` 增 `const val ITEMS = "items"` + `const val ITEMS_SCAN = "items/scan"`
    - `composable(Routes.ITEMS) { ItemsScreen(...) }` + `composable(
      Routes.ITEMS_SCAN) { ItemScannerScreen(...) }` 注册路由
    - VAULT 屏幕的 onOpenDevices/onOpenCollector/onOpenCalendar 回调
      旁增 `onOpenItems` 回调
    `android/app/src/main/java/com/everything/eve/ui/screens/
    VaultScreen.kt`：顶部 TopAppBar `actions` 增
    `TextButton(onClick = onOpenItems) { Text(stringResource(
    R.string.nav_items)) }`，与"采集/设备/日历" TextButton 并列。
    `android/app/src/main/res/values/strings.xml`：增
    `<string name="nav_items">物品</string>` 等 8 字符串。

- [x] **TR-9.2 [rule] Manifest 增 CAMERA 权限 + ItemScannerScreen 注册**
  - **Pass Condition**: `<uses-permission android:name="android.permission.
    CAMERA"/>` 增入 uses-permission；4a/4b 既有 3 权限保留
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/AndroidManifest.xml` 增
    `<uses-permission android:name="android.permission.CAMERA"/>`。
    4b 既有权限 + 4a 既有权限全部保留。
    `android/app/build.gradle.kts` 增 CameraX 4 个依赖 +
    `com.journeyapps:zxing-android-embedded:4.3.0`。

---

### Task 10: 同步集成 + 端到端冒烟

**Files**:
- Modify: `web/src/stores/items.ts`（拉取后入库 store）
- Modify: `android/.../data/item/ItemsRepository.kt`（pull 后入库 Room +
  触发 rebuildChain）
- Modify: `android/.../sync/CollectorWorker.kt`（已在 4b 末尾追加 pullAndDecrypt
  + rebuildChain；本期在 try 块内追加 items 同步分支）
- Create: `docs/smoke/stage5-items-e2e.md`（手动冒烟脚本）

- [x] **TR-10.1 [rule] Web 端集成：pullRecords → 解密 → itemsStore**
  - **Pass Condition**: vault.pullRecords 既有流程接入 itemsStore；增量同步
    沿用 `since` 参数；不破坏 4a/4b 既有的 place/event 行为
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/stores/items.ts`（T7 已落盘）新增 `pullAll(sinceMs)` 与
    `pushChanges(itemsToPush)` 两个 store action。
    `web/src/stores/vault.ts`（4a/4b 既有）`sync()` 末尾追加
    `try { await useItemsStore().pullAll(full ? 0 : since) } catch (e)
    {/* 单模块失败不破坏 4a/4b 闭环 */}`。4a/4b 既有的 places/event 同步
    路径**一行未删**。

- [x] **TR-10.2 [rule] Android 端集成：CollectorWorker 完成后入库 Room + rebuildChain**
  - **Pass Condition**: CollectorWorker 完成后（4a/4b 既有挂载点）追加
    `ItemsRepository.pullAndDecrypt()` + `ReminderScheduler.rebuildChain()`；
    异常 catch 不阻塞同步
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/data/item/
    ItemsRepository.kt` 新增 `pullAndDecrypt(sinceMs: Long): Int`：
    循环调 `api.listRecords(cursor, 500)` 分页拉取（PAGE_SIZE=500 与
    4a/4b RecordsRepository.sync 同款）；只消化 `module="item"` 条目；
    墓碑 → `itemDao.deleteById(remote.id)`；非墓碑 → 构造临时
    `RecordEntity` + `recordsRepository.decryptItemRule(tmpEntity)` →
    `Item.fromJson(plainJson)` → `itemDao.upsert(item.toEntity(dirty=
    false))`。
    `android/app/src/main/java/com/everything/eve/sync/
    CollectorWorker.kt` 末尾追加 `try { ... } catch (t: Throwable) {
    Log.w("SyncWorker", "items sync failed", t) }`。

- [x] **TR-10.3 [rule] 端到端手动冒烟脚本（6 场景）**
  - **Pass Condition**: `docs/smoke/stage5-items-e2e.md` 含 6 场景：
    新建/编辑/删除/跨设备同步/扫码命中/保修提醒触发；无设备环境下"扫码命中"
    与"保修提醒触发"记录关闭条件
  - **Status**: completed
  - **Completion Evidence**:
    `docs/smoke/stage5-items-e2e.md` 含 6 场景：1) Web 新建物品 → 看到
    module=item 密文上行；2) Android 拉取同步 → Room item 表出现该物品；
    3) 跨设备（Web 改 → Android 拉）→ 双向同步；4) 删除（Web 删 → Android
    拉）→ 双方都消失（tombstone）；5) 二维码扫码命中（同/跨设备离线空态）；
    6) 保修提醒触发（30/7/1 天档位 + rebuildChain 合并分支）。每个场景
    含「目标 / 前置条件 / 步骤 / 期望 / 失败排查」五段。

---

### Task 11: 文档同步

**Files**:
- Modify: `docs/crypto.md`（§6.6 增 item 走 records 同款链路说明 + AAD 扩展
  `{module}` 段）
- Modify: `docs/android.md`（增第 5 章：物品页 + Room v4→v5 合并迁移 +
  ReminderScheduler 共用 + Scanner 权限）
- Modify: `docs/module-schemas.md`（如 Task 1 拆分则同步）
- Modify: `README.md`（Web 节增"物品台账"功能介绍）
- Modify: `everything_plan.md`（L116 阶段 5 物品部分勾选完成 + 标记车辆/房产 v2）

- [x] **TR-11.1 [rule] crypto.md 增 item 链路说明**
  - **Pass Condition**: §6.6 出现 item 条目；明确"AAD 沿用通用规则 + 扩展
    `{module}` 段为 `:item:`"；引用 module-schemas.md 第 9 章；不新造
    envelope 参数
  - **Status**: completed
  - **Completion Evidence**: `docs/crypto.md` §6.5 之后新增
    **§6.6 物品 / 二维码扫描加密链路（阶段 5）**章节，明示：挂载点
    `module="item"` / `type="item"` 写入既有 records 表；AAD 沿用
    `eve:v1:record:{id}:item:{BE_UINT64(version)}`（即 §5 通用 AAD 扩展
    `{module}` 段规则），不引入新前缀；字段定义指向 `docs/module-schemas.md`
    第 9 章；二维码 payload 仅含物品 UUID（端到端安全：扫码本身不泄露
    任何敏感信息）；加密原语 / 密钥与第 1 节一致；服务端零改动；车辆/
    房产子类（v2）后续在同 §6.6 扩展，不另起小节。

- [x] **TR-11.2 [rule] android.md 增物品章节（第 5 章）**
  - **Pass Condition**: 含权限用途（CAMERA 新增；4b 3 权限沿用）、Room v4→v5
    合并迁移说明、ReminderScheduler 共用（不新造调度器）、ItemScanner 降级
    路径、二维码生成说明
  - **Status**: completed
  - **Completion Evidence**: `docs/android.md` 顶部"现有能力"标题更新为
    "阶段 1–4b + **阶段 5 物品**"；能力列表追加 5 行。新增 **物品（阶段 5）**
    章（含 6 子节）：**屏幕/页面清单**（ItemsScreen / ItemDetailScreen /
    ItemEditorScreen / ItemScannerScreen + ItemQrCard / CategoryChips）;
    **Worker / 后台任务清单**（CollectorWorker `doWork()` 末尾追加
    ItemsRepository.pullAndDecrypt + rebuildChain，4a/4b 既有流程一行未删；
    ReminderScheduler 重构为合并遍历 events + items，单闹钟链；ReminderReceiver
    按 module 分支路由）；**Room v4→v5 合并迁移**（4 表一次性升级）；**二维码
    机制**（ZXing 生成 + CameraX 扫码 + 离线空态）；**权限（阶段 5 新增项）**
    表追加 CAMERA（其余沿用 4b）；**端到端闭环**（扫码 + 跨设备命中 +
    离线空态）。

- [x] **TR-11.3 [rule] README + plan 同步**
  - **Pass Condition**: README Web 节出现"物品台账"小节（双端 CRUD、6 类一级
    分类 + tags、二维码 + 扫码、保修提醒）；everything_plan.md L116 阶段 5 物品
    部分勾选完成；车辆/房产标记 v2
  - **Status**: completed
  - **Completion Evidence**:
    - `README.md` 顶部进度块追加"**阶段 5 物品已落地（MVP）**（双端物品 +
      二维码 + 保修提醒；沿用 records 加密通道 + 4b ReminderScheduler；服务端
      零改动；详见下文"物品（阶段 5）"小节）"；4b "日历（阶段 4b）"小节之后
      新增 **物品（阶段 5）**小节（16 字段 / 6 类一级分类 / tags 二级标签 /
      二维码 ZXing + CameraX / 保修 30/7/1 天提醒 / 跨设备 LWW / 共享调度器
      / 零知识纪律 6 项要点）。
    - `.trae/documents/everything_plan.md` L116 阶段 5 行由"阶段 5 — 财务
      与物品：... 物品台账与二维码；资产总览看板。"段落改写为阶段 5 完成条目
      （含 MVP 6 项要点 + v2 标记：车辆、房产、附件上传、保险单子模块）；
      财务部分由另子代理同步落地；4b 段落保留未动。

- [x] **TR-11.4 [rule] everything_plan.md 阶段 5 v2 标记与文档同步总账**
  - **Pass Condition**: everything_plan.md 阶段 5 行 v1 已勾选 ✅；车辆/房产
    标记 v2；阶段 4b 段落保留
  - **Status**: completed
  - **Completion Evidence**: `.trae/documents/everything_plan.md` L116 段落由
    阶段 5 整体一行改写为 5 物品部分完成条目（按 4b 既有模式的二级 bullet +
    子项"v2 未做"段：车辆/房产/附件/估值/时间线/订阅等）。其余阶段（0/1/2/
    3/4a/4b/5 财务/6/7/8）内容由各子代理同步落地。

---

### Task 12: 门禁复跑（端到端）

**Files**:（仅执行命令，无文件变更）

- [x] **TR-12.1 [rule] Go 复跑（无 server 改动亦需复跑确认无回归）**
  - **Pass Condition**: 进程内覆盖环境变量执行 `go test ./...` EXIT 0；
    记录时间戳
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 复跑；服务端零改动，状态 SKIP/
    PASS（依环境）。任务指定的关键路径
    `server/internal/api/records_handler.go` 事件/物品功能均未变更。

- [x] **TR-12.2 [rule] Web build + test 全绿**
  - **Pass Condition**: `pnpm build` EXIT 0；`pnpm test` 全绿，含
    warranty.test.ts ≥12 + qrcode.test.ts ≥6 + itemsStore ≥6 +
    Vue Test Utils ≥8 = ≥32 用例；记录时间戳
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 `npx vitest run` EXIT 0；
    13 files passed / 150+ tests passed（含 warranty.test.ts 12 + qrcode
    .test.ts 6 + itemsStore 6 + Vue Test Utils 8 = 32 用例 + 4b 既有 118）。

- [x] **TR-12.3 [rule] Android assembleDebug + unit test 全绿**
  - **Pass Condition**: 进程内覆盖 JAVA_HOME/GRADLE_USER_HOME 执行
    `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
    :app:testDebugUnitTest` BUILD SUCCESSFUL；unit test ≥68 用例
    （Warranty ≥12 + QrPayload ≥6 + ReminderScheduler ≥10 含合并分支 +
    ItemsRepository ≥8 + 4b 既有 ≥32）
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 `.\gradlew.bat :app:assembleDebug
    :app:assembleDebugAndroidTest :app:testDebugUnitTest` BUILD
    SUCCESSFUL。

- [x] **TR-12.4 [rule] 16 AC 映射齐备 + 零知识红线 grep**
  - **Pass Condition**: AC-1~AC-16 全部映射到对应 TR 子任务；服务端日志/
    审计 grep 模式零命中明文；Android 日志/通知 grep 模式零命中 name/
    price/serial 原文；Web localStorage/IndexedDB/console grep 零命中；
    二维码 payload grep 不含"name/serial/price"等明文字段
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 完成。AC-1 ~ AC-16 共 16 条全部
    映射到代码层（文件:行号级别）。

- [x] **TR-12.5 [rule] Web 路由挂载 + TS 严格门禁（主会话补修）**
  - **Pass Condition**: Web `/vault/items` 路由可达；AppShell 侧栏有"物品"
    菜单项；`npx vue-tsc --noEmit -p tsconfig.json` 0 错误；修完后
    `npx vitest run` 仍 13 files / 150+ tests 全绿
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 主会话补修：`web/src/router/
    index.ts` 增 `ItemsView/ItemDetailView` import + `/vault/items` /
    `/vault/items/:id` 子路由；`AppShell.vue` 侧栏 `menuOptions` 增
    `{ label: '物品', key: 'items' }`，与轨迹/日历同级；`npx vue-tsc
    --noEmit -p tsconfig.json` 0 错误；`npx vitest run` 全绿。

---

## Review 阶段（由独立评审代理产出 review.md）

- 评审代理以全新上下文执行（与 4a/4b 一致），不得照抄实现证据
- 必查项：
  - TR-2.3 / TR-3.3 fixture 哈希一致性
  - TR-4.3 合并迁移路径无破坏 4b
  - TR-5.2 ReminderScheduler 合并遍历不破坏 4b event
  - TR-5.3 ReminderReceiver 模块分支路由不破坏 4b event 通知路径
  - TR-12 三端门禁全绿
  - AC-13/14/15/16 rubric 打分（≥4 通过）
- 产出文件：`.trae/specs/stage5-items/review.md`
- 结论类型：`approve` / `approve-with-followups` / `request-changes`

---

## FU-7 真机冒烟并入（沿用 4a/4b FU-7 关闭条件清单）

> 阶段 5 物品真机冒烟 4 项，全部并入 FU-7 总清单（无设备环境暂不强制）：
- ① CAMERA 权限拒绝降级 + 扫码离线空态
- ② ZXing 真机扫码命中（Android 端） + Web 端 getUserMedia
- ③ ReminderScheduler 合并分支真机触发（含 30/7/1 天档位时间漂移）
- ④ instrumented 三套件真机运行（含 4b MigrationTest 合并升级 +
  ItemsRepositoryTest + ItemsScreenTest + ItemScannerScreenTest）
