# 阶段 5 — 物品 - 产品需求文档（PRD）

## Overview

- **Summary**：在 Everything 个人 OS 新增"物品"台账能力——Web（Vue3+TS）与
  Android（Kotlin Compose）双端均提供物品 CRUD、类别筛选、二维码生成/下载/扫码
  管理、保修/保单/到期提醒；服务端**零改动**，全部数据经既有 records 加密通道
  （阶段 1 信封）同步。模块编号 `module="item"`（待定；spec 内确认见
  FR-1）；与车辆、房产平级但 MVP 仅含个人物品台账 + 二维码 + 保修提醒三能力。
- **Purpose**：把零散物品信息（购买日期/价格/序列号/发票/保修）纳入加密个人库，
  形成可被未来 AI 助理（"我的相机什么时候过保？""我卧室那台 NAS 的序列号？"）
  引用的资产档案，同时维持服务端零知识边界（服务端只见密文与元数据），
  提醒走端侧本地闹钟（与阶段 4b `event` 模块共用 ReminderScheduler，
  不新造调度器，规避国产 ROM AlarmManager 配额）。
- **Target Users**：自托管 Everything 服务、使用 Android 主机的单一用户本人
  （不合规场景：他人代为登记/扫描；不引入服务端推送通道）。

## Goals

- 端到端 CRUD：Web 与 Android 均可新建/编辑/删除物品条目，双端经 records
  通道加密同步。
- 类别体系：内置一级分类（电子设备 / 家具 / 服饰 / 工具 / 书籍 / 其他 6 类）
  + 用户自由文本二级标签；UI 提供筛选 chips。
- 二维码机制：每条物品生成一张含 `itemId` 的二维码（UUID 客户端字符串），
  支持 PNG/SVG 下载与打印贴标签；双端扫码后跳转到对应物品详情页。
- 保修提醒：保修期 = `purchase_date + warranty_duration`；到期前 30/7/1 天
  触发本地通知；与 4b `event` 模块**共用** ReminderScheduler 的同一闹钟链，
  通过 `module+id` 区分。
- 可验证：核心逻辑（到期日计算、二维码 payload 构造、类别归一化）抽为纯函数，
  Web Vitest + Android JUnit 双锁定。
- 不破零知识：服务端只见到密文 records；明文物品信息仅存 Android Room
  （Vault DB 容器内）与 Web 浏览器内存，**不进** localStorage/IndexedDB/
  日志/崩溃消息/通知文案。

## Non-Goals

- **不做**车辆管理（加油 / 保养 / 违章 / 年检）——列入 v2，本期 MVP 不含
  （plan §五 §7 子类，决策依据见 Future Enhancements）。
- **不做**房产租约与物业信息——列入 v2。
- **不做**附件上传（发票 PDF、照片）——与财务 spec 同步决策：v1 仅支持
  "外部链接 + 文本备注"；附件上传等阶段 5 后批再评估。
- **不做**服务端推送：提醒仅 Android 本地闹钟；服务端不存触发时刻明文；
  不引入 FCM/任何服务端通道。
- **不做**资产总览/估值：物品价格不入汇总（避免服务端累计推断资产规模）。
- **不做**物品转让/借用登记：v1 仅为"个人台账"。
- **不做**保险单管理（属于"保单/订阅"，归阶段 5 财务 spec）。
- **不做**物品照片墙/时间线：v1 不采集图像（仅外部链接）。
- **不做**多用户协作/家人共享物品台账。
- **不做**iOS 端、Web 端本地提醒（仅 Android）。
- **不做**服务端对物品内容的任何校验或索引：不解密、不解析 receipt_url、
  不缓存明文价格/序列号。

## Future Enhancements

> 以下能力**并非明确不做**，本期因范围控制先记录在案，后续阶段逐一完善。
> 各项均须继承本期零知识纪律（服务端只见密文，分析/调度在客户端或经用户
> 明示的本地能力完成）。

- **车辆管理**：加油记录（里程/油量/金额/油站）/ 保养记录（项目/工时费/
  4S 店）/ 违章记录（罚款/扣分/状态）/ 年检提醒 / 保险记录。复用本模块
  records 信封与 ReminderScheduler，新建子类 `module="vehicle"`。
- **房产租约**：地址/租金/合同期/押金/物业联系人/水电气账号与缴费日。
  复用本模块结构；单独记账模块走财务 spec。
- **附件上传**：发票 PDF、合同扫描件、物品照片（决策待阶段 5 财务 spec
  落地后统一评估存储与端到端加密策略）。
- **物品照片墙**：每个 item 关联多张图片（端到端加密图床）。
- **资产估值/总览**：客户端解密后聚合，所有计算均在客户端。
- **保修/到期多档通知**：30/14/7/1 天 + 自定义档位；与 4b event reminders
  通道合并。
- **保险单模块**：保单类型/投保公司/保费/到期续保（与 4b 提醒通道合并）。
- **物品时间线**：物品生命周期事件（购买/维修/转让/报废）以子记录存储。
- **订阅/自动扣费清单**：归阶段 5 财务 spec。
- **iOS 端**：本期不做。
- **Web 端本地提醒**：依赖浏览器 Notification API 授权。

## Background & Context

- 现有加密通道：阶段 1 已落地 `CryptoEnvelope.sealRecord/openRecord`
  （XChaCha20-Poly1305，AAD `eve:v1:record:{id}`）。物品模块**直接复用**
  不新造原语；AAD 字段扩展为 `eve:v1:record:{id}:item:{BE(uint64 version)}`
  （与 4a 位置模块同款命名，与 4b event 模块命名同型；AAD 沿用 records
  通用规则，详见 FR-1 确认项）。
- 现有同步链路：Android `RecordsRepository`（Room v4 显式迁移）+ 15 分钟
  周期 `CollectorWorker`（3 + 4a 复用）；Web `vault.ts` 的 `sealRecord/
  pushRecords` + `pullRecords/openRecord`；服务端 Chi `approved` 分组下
  `/records/batch` 已承载批量幂等。物品模块**直接复用**现有 Worker/接口，
  不新造。
- **模块命名约定**（`docs/development.md`）：新增业务模块不改 records
  表——定义模块 JSON Schema + 客户端表单即可；时间戳 Unix 毫秒 int64；
  ID 客户端 UUID 字符串。物品模块 `module="item"` / `type="item"` 与 3
  个未来子类（`type="vehicle"` / `type="property"` / `type="insurance"`）
  共用 `module="item"`（同一信封装载），待 v2 落地后讨论是否拆 module。
- 现有 manifest 权限：4a 已声明 `POST_NOTIFICATIONS`（API 33+）；
  4b 已新增 `SCHEDULE_EXACT_ALARM`（API 31+）+ `USE_EXACT_ALARM`
  （API 33+ 自动授予）。物品模块**完全复用 4b 既有权限**，不新增。
- 现有组件复用：阶段 4b `ReminderScheduler`（链式 AlarmManager 单闹钟
  + `BootReceiver` 触发 `rebuildChain`）已落地；本期在同一调度器内
  追加 `ItemReminderScheduler` 入口（薄适配层），共用同一 `PendingIntent`
  requestCode 桶，通过 `module+id` 在 receiver 内分支路由。**不新造**
  第二个调度器。
- 现有 Web 月历/月视图：阶段 4a `web/src/locations/month.ts` 与
  4b `web/src/events/expand.ts` 抽出月历格子/展开纯函数；物品列表**借
  鉴思路**但不复用（交互形态显著不同：物品是网格卡片 + 类别筛选，
  非日历月视图）。
- 现有 Android UI：阶段 4b `CalendarScreen` 已落地 Compose Material3
  + `Routes.CALENDAR` 路由 + `R.string.nav_calendar` 入口；物品顶层
  `Routes.ITEMS` 与 `R.string.nav_items` 镜像扩展。
- 时间纪律：FU-1 已落地服务端权威时间；`created_ts / updated_ts / purchase_date
  / warranty_until` 全部取设备本地时区语义（落 records 走加密信封，服务端
  不解析）。
- 模块扩展约定：
  - **服务端零改动**：不引入新表、新接口；所有数据走 records envelope。
  - **零知识纪律**：明文价格/序列号/地址/外部链接不入日志、不入 console、
    不入 plaintext 字段。
  - **三端对齐**：Web + Android 端 UI 一致；服务端无业务逻辑。
  - **跨设备同步**：复用 since + 增量同步机制。
  - **本地提醒**：复用 4b ReminderScheduler。

## Functional Requirements

### FR-1 物品数据模型

物品作为一条 records 记录写入 `records` 表（`module="item"`、
`type="item"`），明文 JSON Schema 进 `docs/module-schemas.md` 第 9 章：

```json
{
  "id":          "uuid-string",
  "name":        "字符串（≤120字符，必填）",
  "category":    "electronics|furniture|apparel|tools|books|other",
  "tags":        ["自由文本二级标签数组，≤8 个，每项 ≤24字符"],
  "brand":       "字符串，可选（≤80字符）",
  "model":       "字符串，可选（≤80字符）",
  "serial_no":   "字符串，可选（≤120字符）",
  "purchase_date": 1735689600000,
  "purchase_price_cents": 1299000,
  "currency":    "CNY",
  "warranty_duration_days": 730,
  "warranty_until_ts":     1737072000000,
  "receipt_url": "https://...（外部链接，可选）",
  "note":        "纯文本，可选（≤2000字符）",
  "location_text": "纯文本，可选（≤120字符）",
  "created_ts":  1737072000000,
  "updated_ts":  1737072000000
}
```

字段口径：

- `id`：客户端 UUID v4 字符串（也是二维码负载）；永不上行服务端解析。
- `category`：内置 6 选 1 枚举；UI 提供 dropdown 选择。
- `tags`：自由文本二级标签（用户自命名，如"卧室""主相机""专业设备"）；
  服务端只见密文，不校验取值。
- `purchase_price_cents`：以"分"为单位的 int64；避免浮点精度漂移。
- `currency`：ISO 4217 三字母代码（MVP 默认 `CNY`，UI 不暴露切换）。
- `warranty_duration_days`：整数天数；0 = 无保修。
- `warranty_until_ts`：Unix 毫秒 = `purchase_date + warranty_duration_days *
  86400000`；前端编辑器自动计算；服务端不见不校验。
- `receipt_url`：可选外部链接（用户粘贴 https URL）；**v1 不上传附件**；
  服务端只见密文，不访问也不抓取。
- `created_ts` / `updated_ts`：客户端本地时钟写入，服务端 Z 检验时分复用
  `records.z` 字段；冲突策略 LWW。
- AAD 沿用通用 records AAD 规则 `eve:v1:record:{id}:item:{BE(uint64 version)}`
  （与 4a place 同款 `{module}` 扩展；与 4b event 同型），确认后写入
  docs/crypto.md §6.6。

### FR-2 类别体系

- 一级 `category` 枚举 6 类：`electronics` / `furniture` / `apparel` /
  `tools` / `books` / `other`；新增 `category` 需在 spec 内追加并通过前端
  校验防住（非枚举值拒绝保存）。
- 二级 `tags[]`：用户自由文本数组，每项 ≤24字符；≤8 个；去重大小写不敏感；
  服务端只见密文，不解析为分类键。
- 筛选：UI 提供类别 chips 多选 + tag 子串搜索；服务端不解密故无法预筛，
  全量拉取后客户端过滤。

### FR-3 二维码机制

每条物品生成一张二维码：

- **编码内容**：物品 `id`（客户端 UUID 字符串，纯本地引用，永不上行）。
- **生成**：客户端生成 PNG（256×256 / 512×512 两档）与 SVG（矢量打印用）；
  Web 用 `qrcode`（纯 JS 库）或 `qrcode-svg`；Android 用 `ZXing`
  （`com.google.zxing.core`，**注意**：零 GMS 依赖约束见 Constraints 节）。
- **存储**：二维码 PNG/SVG **不入库**，仅在客户端按需生成；详情页提供
  "下载 PNG / 下载 SVG / 打印"三按钮 + "粘贴标签"建议（Avery 模板）。
- **扫码**：
  - **Android**：CameraX + ZXing 解码（`BarcodeFormat.QR_CODE`）；
    权限 `CAMERA`（API 23+ 运行时）；扫码后从 URL 路径中取 `id` 字符串
    （payload 本身即物品 UUID）→ 从 RecordsRepository 拉密文解密 →
    跳转 ItemDetailScreen。
  - **Web**：`BarcodeDetector` API（Chrome 83+/Edge）兜底 `jsQR`
    （Canvas 拍照解码）；调用浏览器摄像头 `getUserMedia`；扫码后
    在物品 store 内 `byId(payload)` 命中即跳详情，未命中提示"未找到
    物品，是否在本机新建？"。
- **关联语义**：扫码 = "用物品 ID 查询 vault"。跨设备场景：Web 端扫到
  Android 端生成的二维码后，需经 records 同步链路把密文拉到 Web store
  后才能命中；无网络时给空态提示。
- **同步**：物品 ID 是 client-side UUID，跨设备扫码后通过同步链路拉取
  密文；服务端只见模块字段 `module="item"`，不解 `id` 内容。

### FR-4 同步链路（沿用 4a/4b 既有 records 通道）

| 端 | 入库路径 | 触发 | 出库路径 |
|---|---|---|---|
| Web | `vault.saveItem` → `sealRecord` → `pushRecords` | 编辑器保存即 dirty | `pullRecords` → `openRecord` → 内存态 |
| Android | `ItemsRepository.upsert` → `RecordsRepository` dirty | 编辑器保存即 dirty | `CollectorWorker`（3 + 4a + 4b 既有）pull/push |
| Server | — | — | 仅做认证 + 中转 + 元数据索引（**零改动**） |

冲突策略：LWW（last-write-wins，与现有 records 一致）；与 4b event 同款。

删除语义：删物品 → 删 records 条目（tombstone，`deleted=true, ciphertext=""`）；
任何与该物品关联的提醒通过 rebuildChain 自然消散（无未来 nextTrigger）。

增量同步：`since` 参数沿用 4a 既有 CollectorWorker 接口，无需新增。

### FR-5 Android 端 Room 扩展

Room v4 → v5 显式迁移（**注**：与 4b event 模块 v4→v5 升级**同时**进行；
合并 migration：v4 → v5 = 4b event/event_reminder_log 两表 + 本期 item 两表
一次性落地，避免 v5 反复迁移）：

- 新增 `item` 表（明文字段，Vault DB 容器内加密）：
  - `id TEXT PRIMARY KEY`
  - `name TEXT NOT NULL`
  - `category TEXT NOT NULL`
  - `tags_json TEXT NOT NULL DEFAULT '[]'`
  - `brand TEXT` / `model TEXT` / `serial_no TEXT`
  - `purchase_date INTEGER NOT NULL DEFAULT 0`
  - `purchase_price_cents INTEGER NOT NULL DEFAULT 0`
  - `currency TEXT NOT NULL DEFAULT 'CNY'`
  - `warranty_duration_days INTEGER NOT NULL DEFAULT 0`
  - `warranty_until_ts INTEGER NOT NULL DEFAULT 0`
  - `receipt_url TEXT` / `note TEXT` / `location_text TEXT`
  - `dirty INTEGER NOT NULL DEFAULT 0`
  - `created_ts INTEGER NOT NULL`
  - `updated_ts INTEGER NOT NULL`
  - 索引 `(category)`、`(updated_ts)`、`(dirty)`
- 新增 `item_reminder_log` 表（4b `event_reminder_log` 同款模式，降级事件记录）：
  - `id INTEGER PRIMARY KEY AUTOINCREMENT`
  - `item_id TEXT NOT NULL`
  - `occurrence_ts INTEGER NOT NULL`
  - `kind TEXT NOT NULL`（`warranty_expiring` | `alarm_killed` |
    `notification_denied` | `exact_denied`）
  - `created_ts INTEGER NOT NULL`

明文驻留：仅 Android Room 与 Web 浏览器内存；不进 SharedPreferences/日志/
崩溃消息/通知文案（通知文案仅渲染抽象短语如"X 物品保修即将到期"，不渲染
名称/价格/序列号原文——4a/4b 已验证纪律）。

### FR-6 Android 端提醒调度（与 4b ReminderScheduler 共用）

物品保修提醒**完全复用**阶段 4b `ReminderScheduler`（不新造调度器）：

- **入口**：在 4b `ReminderReceiver` 内根据 `intent.extras.module == "item"` 分支路由；
  在 `ReminderScheduler.rebuildChain()` 中遍历 `itemsRepo.observeAll()` 与
  `eventsRepo.observeAll()`，合并取全局最小 nextTrigger。
- **触发逻辑**：
  - `nextItemTrigger(item, now)`：纯函数，计算物品未来最近的触发时刻——
    `warranty_until_ts - 30 * 86400000`（30 天前）/ `warranty_until_ts - 7 *
    86400000`（7 天前）/ `warranty_until_ts - 86400000`（1 天前），取 `> now`
    的最小值；已过期或 `warranty_duration_days=0` 返回 null。
- **链路**：触发 → `ReminderReceiver` 分支路由 → 通知（"X 物品 N 天后保修到期"，
  不渲染 name 原文，仅渲染 itemId hash 前 6 位作为去标识符 + 抽象短语）
    → `Log.w + ItemReminderLogDao.insertRaw(...)` → 重新计算 `nextItemTrigger`
    并 `scheduleNext`。
- **共享 `PendingIntent` requestCode**：
  - 4b event 用 `0x45564556`（"EVEEV"）；
  - 本期物品用 `0x45564557`（"EVEEW"，+1 后缀）；共用同一闹钟链全局 nextTrigger，
    `extras` 携带 `module` + `itemId/eventId` 在 receiver 内分支。
- **重建触发点**：
  - `BootReceiver`（4a/4b 既有）开机/应用更新后 SyncWorker 完成后调
    `ReminderScheduler.rebuildChain(applicationContext)`；4b 既有逻辑保留，
    本期不修改 BootReceiver。
  - 编辑器保存物品（含新建/编辑/删除）后立即调 `rebuildChain()`；
  - 应用冷启动进入主界面后调一次 `rebuildChain()`（4b 启动挂钩复用）。
- **权限**：复用 4b 已声明的 `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM`
  + `POST_NOTIFICATIONS`；**本期不新增 Android 权限**。
- **降级**：与 4b 同款——
  - 拒绝 SCHEDULE_EXACT_ALARM：降级 `setAndAllowWhileIdle` + 写
    `item_reminder_log.kind="alarm_killed"`；
  - 拒绝 POST_NOTIFICATIONS：仅写 log 不弹横幅；
  - 详细降级路径见 4b spec FR-6。

### FR-7 权限与降级

**Web 端**：

- `getUserMedia({video:true})` 扫码：浏览器弹权限提示，用户拒绝后
  UI 展示"扫码不可用，请录入物品 ID 手动搜索"。
- `Notification API`：本期 Web 端**不做**本地通知（仅 Android），避免
  浏览器授权摩擦。

**Android 端**：

- 新增 `CAMERA` 权限（API 23+ 运行时申请）；扫码界面进入前申请；用户拒绝
  后落地降级——隐藏底部"扫码"按钮，仅提供"手动搜索物品 ID"。
- 复用 4b 已有 `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` / `POST_NOTIFICATIONS`
  权限；不再追加 Android 权限。
- 详情见 FR-6 降级路径。

### FR-8 BootReceiver 复用

4b `BootReceiver` 在 SyncWorker 完成后已调 `rebuildChain`；本期**不修改**
BootReceiver；只让 `rebuildChain` 内部扩展为同时遍历 `eventsRepo` 与
`itemsRepo`，合并 nextTrigger 取全局最小。

### FR-9 Web 端 UI（Vue3 + Pinia + Vue Router）

- 新增路由 `/items`（AppShell children 内，沿用 4a/4b 风格，相对子路由）；
- AppShell 顶部导航（侧栏）增"物品"入口（与"位置/记一笔/身份/卡片/笔记/
  日历"并列）；
- 组件树：
  - `views/ItemsView.vue`：主容器（侧栏筛选项 + 物品网格/列表切换 +
    类别 chips + tag 搜索 + 扫码 FAB + 新建 FAB）；
  - `views/ItemDetailView.vue`：详情页（基本信息卡片 + 二维码下载区 +
    关联提醒列表 + 编辑/删除/扫描回访按钮）；
  - `components/ItemEditorDialog.vue`：表单组件（name / category / tags /
    brand / model / serial_no / purchase_date / purchase_price /
    warranty_duration / receipt_url / note / location_text）；
  - `components/ItemQrCard.vue`：二维码展示（PNG / SVG / 打印 / 复制
    物品 ID）；
  - `components/ItemScanner.vue`：摄像头扫码（BarcodeDetector / jsQR 兜底）；
  - `stores/items.ts`：Pinia store（CRUD + byId + list + byCategory + 提醒
    计算 nextTrigger）；
  - `items/qrcode.ts`：纯函数二维码 payload 构造（仅返回 UUID 字符串，
    不渲染）；
  - `items/warranty.ts`：纯函数保修到期日 + nextTrigger 计算。

### FR-10 Web 端物品编辑器

`ItemEditorDialog.vue` 表单字段（与 FR-1 字段口径逐字段一致）：

- 名称（必填，≤120 字符）
- 类别（6 选 1 dropdown + 自定义提示："如需自定义类别，请在 tags 二级
  标签中标注"）
- 二级标签（chip 行，≤8 个，每项 ≤24 字符，逗号/回车添加；去重大小写不
  敏感）
- 品牌（可选，≤80 字符）
- 型号（可选，≤80 字符）
- 序列号（可选，≤120 字符）
- 购买日期（date picker，仅日期）
- 购买价格（数字 + 货币单位，默认 CNY）
- 保修天数（整数，0 = 无保修）
- 保修到期（read-only，按 purchase_date + warranty_duration_days 自动计算）
- 发票链接（可选 https URL，前后端校验 https:// 前缀；非 https 拒绝保存）
- 备注（可选，≤2000 字符，多行 textarea）
- 存放位置（可选，≤120 字符）

校验：

- 名称非空
- 类别 ∈ 6 选 1 枚举（前端防住）
- 标签 chip ≤8 个 + 每项 ≤24 字符
- 保修天数 ≥0
- 发票链接 startsWith `https://`（前端防住）
- 备注 ≤2000 字符

保存：调 `itemsStore.upsert(item)` → `vault.saveItem` → `sealRecord` →
`pushRecords`（与 4b `saveEvent` 同款链路）。

### FR-11 Android 端 UI（Kotlin Compose）

- `ItemsScreen`：主容器（顶部类别 chips + 标签搜索 + 物品网格 +
  扫码 FAB + 新建 FAB）；
- `ItemDetailScreen`：详情页（基本信息 + 二维码渲染 + 关联提醒列表 +
  编辑/删除按钮）；
- `ItemEditorScreen`：表单组件（与 Web 字段同字段同校验）；
- `ItemQrCard`：二维码展示（ZXing 生成 Bitmap）；
- `ItemScannerScreen`：CameraX + ZXing 扫码；
- 入口：4b `Routes.CALENDAR` 旁边新增 `Routes.ITEMS = "items"` + 主导航
  TopAppBar 增"物品" TextButton（与"采集/设备/日历"并列）。

### FR-12 核心纯函数（共享 fixture 风格可借鉴 4b）

`web/src/items/warranty.ts` 与 `android/.../item/Warranty.kt` 同源：

```ts
function warrantyUntilTs(purchaseDateTs: number, durationDays: number): number
function nextItemTrigger(item: Item, now: number): number | null
function normalizeTags(rawTags: string[]): string[]   // 去重 + 转小写 + 截断
function isValidReceiptUrl(url: string): boolean       // https:// prefix + URL 形态
```

跨端测试：

- `items/__fixtures__/cases.json`（Web 与 Android 镜像加载）：覆盖边界
  场景——保修 0 天返回 null / 30-7-1 触发档位准确 / 已过期返回 null /
  跨夏令时（接受与 4b 同款漂移）/ 标签去重大小写 / 非法 URL 拒绝。
- Web expand.test.ts Vitest ≥12 用例；Android WarrantyTest JUnit ≥12 用例。

### FR-13 二维码库依赖

**Web**：

- `qrcode`（[npm qrcode](https://www.npmjs.com/package/qrcode)，纯 JS，零依赖，
  长期维护，Apache-2.0）或 `qrcode-svg`（仅 SVG）二选一；不引第三方
  react/vue 二维码组件（保持栈一致）。

**Android**：

- **ZXing 替代方案（推荐）**：使用 `journeyapps:zxing-android-embedded`
  （Apache-2.0，仅含 `core` 与 `zxing-android-embedded`，无 GMS 依赖）
  做扫码；或纯解码端 `zxing/core`（不引 CameraX 包装）。
- **生成端**：Android 自带 `BarcodeEncoder` 不开放；用 ZXing `QRCodeWriter`
  生成 `BitMatrix` → 转 `Bitmap`（PNG）/ SVG（手写路径或 `zxing/core` 渲染）；
  不引 `zxing-android-embedded` 的"生成"封装（仅用其扫码）。
- **零 GMS**：journeyapps 包验证无 `com.google.android.gms` 传递依赖即可。

### FR-14 文档

- `docs/module-schemas.md` 第 9 章：item JSON Schema + 字段表 + 类别枚举 +
  标签规则 + 保修/二维码口径；
- `docs/crypto.md`：§6.6 增 item 走 records 同款链路说明 + AAD 规则扩展
  `{module}` 段；
- `docs/android.md` 第 5 章：物品页章节、Room v4→v5 合并 migration、
  ItemScanner 权限、ReminderScheduler 共用说明；
- `README.md` Web 节：增"物品台账"功能介绍；
- `everything_plan.md` L116：勾选阶段 5 物品部分完成。

## Non-Functional Requirements

- **NFR-1 零知识红线**：服务端持久化与日志中不得出现物品明文
  （名称/品牌/型号/序列号/价格/发票链接/备注/存放位置）；Android 明文仅
  存 Room 且有界（与 4a records 一致）；Web 明文仅驻留浏览器内存，**不写**
  localStorage/IndexedDB/日志/崩溃消息/通知文案。
- **NFR-2 权限最小化**：新增仅 `CAMERA`（API 23+ 运行时），其余复用 4b 既有
  3 权限；`POST_NOTIFICATIONS` / `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`
  沿用 4b 既有声明。
- **NFR-3 可测性**：保修到期 / nextItemTrigger / 标签归一化 / URL 校验
  抽为不依赖 Android Framework / 浏览器 API 的纯函数，由 JVM（Kotlin）
  与 Vitest（TS）单测覆盖；跨端共享 fixture 双锁定；Room v4→v5 走
  显式合并迁移并有 instrumented 迁移测试（无设备环境至少编译通过）。
- **NFR-4 闹钟配额**：复用 4b 单闹钟链式调度；物品不增闹钟数；rebuildChain
  O(事件数 + 物品数) 单用户量级 < 150ms。
- **NFR-5 兼容性**：minSdk 26 / targetSdk 35；零 GMS 依赖；国产 ROM 杀后台/
  权限收紧场景降级不崩溃。
- **NFR-6 一致性**：Android 沿用 ServiceLocator 手动注入/Room 显式迁移/
  Compose Material3/中文详细注释；服务端零改动；Web 沿用 stores/crypto
  (envelope)/router 分层；不新造平行链路。
- **NFR-7 二维码局部化**：物品 ID 是 client-side UUID，不依赖网络/服务端；
  离线扫码后提示"未找到 — 是否在本机新建？"，避免"扫到错设备"误判。

## Constraints

- **Technical**：
  - 加密复用 CryptoEnvelope 同参数（Argon2id/XChaCha20-Poly1305 与 AAD 规则
    不得新造）；物品 AAD 沿用 `eve:v1:record:{id}:item:{BE_UINT64(version)}`。
  - 服务端零知识：不解密、不校验 category、不缓存明文价格/序列号；不引入
    新表/列/接口；item 与 place/event 同走 `/records/batch`。
  - 时间戳一律 Unix 毫秒 int64；ID 一律客户端 UUID 字符串。
  - 模块扩展：新增业务模块不改 records 表——定义模块 JSON Schema + 客户端
    表单即可；item 模块严格遵循。
  - Room migration：合并 4b v4→v5 + 本期 item 到单次 v5 升级；不允许 v5→v6
    再迁一次（避免升级回环）。
- **Business**：仅本人自托管场景；应用内不做滥用检测（单用户单账号）；
  通知文案避免渲染物品名称/价格/序列号原文。
- **Dependencies**：
  - Android 新增 `androidx.camera:camera-core` + `camera-camera2` +
    `camera-lifecycle` + `camera-view`（CameraX 扫码用，Apache-2.0）；
  - Android 新增 `com.journeyapps:zxing-android-embedded:4.3.0`（扫码，
    Apache-2.0，零 GMS 验证）；
  - Web 新增 `qrcode`（生成）二选一，不引组件库；
  - 服务端预计无新增依赖。

## Assumptions

- 单用户单账号；多设备间物品经 records 通道 LWW 同步。
- 设备系统时间大致准确（物品 `purchase_date` 取系统本地时区语义）；异常
  时钟导致的保修漂移由用户在编辑/删除下自行处理，不做服务端矫正。
- 二维码含物品 UUID（不含密文/未加密），故扫码本身**不泄露**任何敏感
  信息——解密仍需本地 MK；离线扫码未命中属预期，给空态提示即可。
- 物品数量假设：千级以下；nextTrigger 计算 O(数量)，单用户量级 < 50ms；
  rebuildChain 合并 events + items 后 < 150ms。
- Web 端提醒本期不做（仅 Android）。
- "MVP 仅含个人物品台账 + 二维码 + 保修提醒"是 spec 决策；车辆 + 房产 =
  v2 入 Future Enhancements，spec 内不承诺 v1 交付。

## Acceptance Criteria

### AC-1: 物品数据模型与字段口径
- **Type**: `rule`
- **Given**: 任意前端表单输入
- **When**: 提交物品（含购买日期/价格/保修/序列号/发票链接/二级标签）
- **Then**: 落 records 表的密文对应明文符合 FR-1；category 枚举由前端校验
  防住；服务端不解密故无法校验（文档明示）
- **Pass Condition**: 三端字段定义逐字段一致（Grep module-schemas.md 第 9 章 +
  Android ItemEntity + Web `items/types.ts`）
- **Evidence**: 文档 diff + 代码行

### AC-2: 跨端纯函数一致性
- **Type**: `rule`
- **Given**: `items/__fixtures__/cases.json` ≥12 用例
- **When**: 在 Web Vitest 与 Android JUnit 中分别加载 fixture 调 warranty/
  nextItemTrigger/normalizeTags/isValidReceiptUrl
- **Then**: 三端输出逐字段一致
- **Pass Condition**: Web warranty.test.ts ≥12 用例全绿；Android
  WarrantyTest.kt ≥12 用例全绿；fixture 文件 SHA-256 三端一致
- **Evidence**: 单测输出 + fixture 文件哈希

### AC-3: 同步链路复用 records 通道
- **Type**: `rule`
- **Given**: Web 端新建一个物品
- **When**: 保存 + 触发 pullRecords（Android CollectorWorker 周期窗口）
- **Then**: 服务端 `/records/batch` 收到密文（无明文可验）；Android 解密后
  Room `item` 表出现该物品；编辑另一端再保存后 pullRecords 可见更新
- **Pass Condition**: 服务端 Go 测试无回归；Android CollectorWorker 测试
  通过；Web pushRecords/pullRecords 代码审查确认复用 4a 链路
- **Evidence**: go test 输出 + Android 测试 + 代码行

### AC-4: Android Room v4→v5 合并迁移
- **Type**: `rule`
- **Given**: 4b 后的 Room v4 数据库
- **When**: 应用升级到 v5（一次性合并 4b event/event_reminder_log + 本期
  item/item_reminder_log 四张表）
- **Then**: 迁移脚本执行成功，旧数据保留；新增 4 表 + 索引符合 FR-5
- **Pass Condition**: MigrationTest instrumented 编译通过（无设备环境至少
  编译）；Grep Room Migration 调用链确认合并单次升级
- **Evidence**: MigrationTest 代码 + Gradle 输出

### AC-5: 共享 ReminderScheduler 调度（不新造调度器）
- **Type**: `rule`
- **Given**: 多个物品（含不同保修天数）+ 多个 4b event
- **When**: 调 ReminderScheduler.rebuildChain() 合并两类 nextTrigger
- **Then**: 返回全局最小 nextTrigger（含 `reminders[0]=0` 与 30/7/1 保修档
  位）；未来无触发返回 null；触发后分支路由到 event / item receiver
- **Pass Condition**: ReminderSchedulerTest JUnit ≥10 用例全绿（含合并
  event + item + 类别排序）
- **Evidence**: 单测输出 + Scheduler 代码

### AC-6: 权限降级路径（CAMERA + 4b 既有 3 权限）
- **Type**: `rule`
- **Given**: Android 13+ 设备拒绝 CAMERA / SCHEDULE_EXACT_ALARM /
  POST_NOTIFICATIONS
- **When**: 进入扫码界面或触发物品闹钟
- **Then**: 扫码：底部按钮隐藏，仅"手动搜索物品 ID"路径开启；闹钟：
  复用 4b 降级路径 + 写 `item_reminder_log`；事件保存/查看不阻塞
- **Pass Condition**: 代码审查确认降级分支；item_reminder_log 表索引查询
  通过
- **Evidence**: Scanner 代码 + log 表 Schema

### AC-7: 二维码生成 + 扫码 + 跨设备命中
- **Type**: `rule`
- **Given**: 一个物品 UUID
- **When**: 在详情页点击"下载 PNG/SVG"生成二维码；用同款扫码入口扫描
- **Then**: PNG/SVG 渲染含 UUID；扫码后 store.byId(uuid) 命中并跳转
  ItemDetailScreen；未命中提示"未找到 — 是否在本机新建？"
- **Pass Condition**: Vitest + Compose UI Test 覆盖生成/扫码；离线场景
  给出空态
- **Evidence**: 单测输出 + 真机冒烟（无设备并入 FU-7）

### AC-8: Web 物品列表与详情
- **Type**: `rule`
- **Given**: vault/store 中有若干物品（不同类别 + tags）
- **When**: 在 ItemsView 切换类别 chips + tags 搜索 + 详情页点击编辑
- **Then**: 类别筛选命中；tag 子串搜索命中；详情页字段完整（含二维码区
  + 关联保修提醒列表）；新建/编辑/删除闭环顺畅
- **Pass Condition**: Vitest 组件测试 + 代码审查
- **Evidence**: 单测输出 + 组件代码

### AC-9: Web 物品编辑器与字段校验
- **Type**: `rule`
- **When**: 输入名称/类别/标签/品牌/型号/序列号/购买日期/价格/保修天数/
  发票链接/备注/位置
- **Then**: 字段校验正确；category ∈ 6 选 1 防住；发票链接要求
  `https://` 前缀；保存即 dirty + push
- **Pass Condition**: Vitest ≥8 用例全绿
- **Evidence**: 单测输出 + 组件代码

### AC-10: Android Compose 编辑器与字段校验
- **Type**: `rule`
- **When**: 输入同上
- **Then**: 同 AC-9（字段语义一致）
- **Pass Condition**: Compose UI Test ≥6 用例全绿
- **Evidence**: 单测输出 + 组件代码

### AC-11: 零知识红线全链路
- **Type**: `rule`
- **Given**: 任意场景下物品明文流转
- **When**: 检查服务端日志/审计、Android 日志/通知/SharedPreferences、
  Web localStorage/IndexedDB/控制台、二维码 SVG/PNG payload
- **Then**: 服务端仅见密文与 records 元数据；Android 日志/通知无 name
  原文（仅 itemId hash 前 6 位 + 抽象短语如"1 件物品保修即将到期"）；
  Web 无明文持久化路径；二维码 payload 仅含 UUID（不含密文/明文）
- **Pass Condition**: grep 模式（Log[.dwiev]、putString、通知文案、
  localStorage、sessionStorage、IndexedDB、document.cookie）零命中或带
  说明；二维码 payload 字符串内不含"name/serial"等明文字段
- **Evidence**: grep 检查记录

### AC-12: 文档与计划同步
- **Type**: `rule`
- **When**: 实现完成
- **Then**: module-schemas.md 第 9 章含 item JSON Schema + 类别枚举 +
  标签规则；crypto.md §6.6 增 item 链路说明；android.md 第 5 章含权限/
  Room v5 合并迁移/共享 ReminderScheduler；README Web 节增"物品台账"；
  plan 阶段 5 物品部分同步
- **Pass Condition**: 文档评审通过且字段与代码/DTO 一致
- **Evidence**: 文档 diff

### AC-13: Web 物品视图体验质量
- **Type**: `rubric`
- **Dimension**: 物品页（列表/详情/编辑器/扫码）的用户体验质量
- **Scale**: 1-5
- **Anchors**: 1 = 视图错乱/解密失败无提示；3 = 功能可用但空态/加载/错误态
  粗略；5 = 装载-新建-编辑-删除-扫码-二维码下载闭环顺畅，类别筛选+标签搜索
  体验自然
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对物品页走查（含空态/大量物品/含长名称/含 unicode
  名称四态）

### AC-14: 架构一致性与可测性
- **Type**: `rubric`
- **Dimension**: 新增代码与既有架构的契合度及纯函数可测性
- **Scale**: 1-5
- **Anchors**: 1 = 新造平行存储/网络/加密路径，逻辑耦合在 Activity/组件
  不可测；3 = 复用主通道但提醒/扫码逻辑散落、部分单测；5 = 纯函数核心 +
  薄平台适配层，完全复用信封/Room/Worker/Chi 分组/audit/4b
  ReminderScheduler，单测覆盖全部平台无关分支
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对模块边界与单测覆盖的走查

### AC-15: 二维码可用性（rubric）
- **Type**: `rubric`
- **Dimension**: 双端二维码生成/扫码/打印的可用性
- **Scale**: 1-5
- **Anchors**: 1 = 二维码扫不出/扫码按钮永远灰；3 = 主线可达但离线/多设备
  空态粗糙、UX 含糊；5 = PNG+SVG 双格式下载 + 打印支持 + 扫码离线空态
  清晰 + 跨设备命中流程闭环 + 真机冒烟通过
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对二维码组件与扫码入口的走查 + 真机冒烟记录
  （无设备则并入 FU-7 关闭条件）

### AC-16: 物品扫码权限降级（rubric）
- **Type**: `rubric`
- **Dimension**: Android 端权限收紧下的扫码可用性
- **Scale**: 1-5
- **Anchors**: 1 = 权限拒绝即崩溃/无法恢复；3 = 拒绝后降级但路径含糊；5 =
  CAMERA/SCHEDULE_EXACT_ALARM/POST_NOTIFICATIONS 三权限任一拒绝均有清晰
  降级路径 + 入口提示 + 手动搜索兜底
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对 ScannerScreen + Receiver 降级分支的走查

## 交付物清单

### 代码层

- `docs/module-schemas.md` 第 9 章（新增）
- `docs/crypto.md` §6.6（更新交叉引用：item 信封 + AAD 扩展 `{module}` 段）
- `docs/android.md` 第 5 章（新增：物品页 + Room v5 合并迁移 + Scanner
  权限 + ReminderScheduler 共用）
- `web/src/items/warranty.ts` + `warranty.test.ts`（新增）
- `web/src/items/qrcode.ts` + `qrcode.test.ts`（新增）
- `web/src/items/types.ts` + `__fixtures__/cases.json`（新增，Android 镜像加载）
- `web/src/stores/items.ts`（新增）
- `web/src/components/ItemEditorDialog.vue`（新增）
- `web/src/components/ItemQrCard.vue`（新增）
- `web/src/components/ItemScanner.vue`（新增）
- `web/src/views/ItemsView.vue`、`ItemDetailView.vue`（新增）
- `web/src/router/index.ts`（更新：增 `/items` 路由）
- `web/src/AppShell.vue`（更新：增"物品"导航入口）
- `android/.../data/item/ItemEntity.kt`、`ItemReminderLogEntity.kt`、
  `ItemDao.kt`、`ItemReminderLogDao.kt`、`ItemsRepository.kt`（新增）
- `android/.../item/Warranty.kt`、`WarrantyTest.kt`（新增）
- `android/.../item/QrPayload.kt`、`QrPayloadTest.kt`（新增）
- `android/.../sync/BootReceiver.kt`（不修改；4a/4b 既有逻辑保留）
- `android/.../reminder/ReminderScheduler.kt`（更新：扩展 rebuildChain
  遍历 itemsRepo + event/item 分支路由）
- `android/.../reminder/ReminderReceiver.kt`（更新：模块分支路由）
- `android/.../ui/screens/ItemsScreen.kt`、`ItemDetailScreen.kt`、
  `ItemEditorScreen.kt`、`ItemScannerScreen.kt`（新增）
- `android/.../ui/components/ItemQrCard.kt`（新增 ZXing 生成）
- `android/.../AndroidManifest.xml`（更新：增 1 权限 `CAMERA`）
- `app/src/main/res/values/strings.xml`（更新：增 item 模块所有文案）
- `android/app/build.gradle.kts`（更新：增 CameraX + ZXing 依赖）

### 文档/约定层

- `README.md` Web 节（增"物品台账"功能介绍）
- `everything_plan.md` L116（勾选阶段 5 物品部分完成 + 标记车辆/房产 v2）
- `.trae/specs/stage5-items/spec.md`（本文件）
- `.trae/specs/stage5-items/tasks.md`（writing-plans 产出）
- `.trae/specs/stage5-items/review.md`（独立评审产出）

### 不交付（明确划界）

- 不动 server/任何 Go 代码
- 不动 records 表 schema / CollectorWorker 接口
- 不新造调度器（与 4b ReminderScheduler 共用）
- 不动 4a/4b 既有 BootReceiver（仅在 ReminderScheduler 内部扩展遍历）
- 不动 4b 既有权限（仅增 1 权限 `CAMERA`）
- 不新增 FCM / 服务端推送通道
- 不产出车辆/房产子类（v2 留 Future Enhancements）
- 不产出附件上传（v2 与财务 spec 同步评估）
- 不做 iOS 端、Web 端本地通知

## 门禁（不可跳过）

- Go `go test ./...` 全 0（无 server 改动亦需复跑）
- Web `pnpm build` + `pnpm test` 全 0（含 warranty.test.ts ≥12 + qrcode.test.ts
  ≥6 + itemsStore ≥6 + Vue Test Utils ≥8 = ≥32 用例）
- Android `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  :app:testDebugUnitTest` BUILD SUCCESSFUL
- Android unit test 全绿（WarrantyTest ≥12 + QrPayloadTest ≥6 +
  ReminderSchedulerTest ≥10 含合并分支 + ItemsRepository ≥8 + 其他 ≥6）
- Android instrumented 编译通过（无设备环境仅编译）
- Web `items/__fixtures__/cases.json` 与 Android 镜像文件 SHA-256 一致
