# 阶段 4b — 日程/日历 - 产品需求文档（PRD）

## Overview

- **Summary**：在"人生操作系统"新增个人日程/日历能力——Web（Vue3+TS）与
  Android（Kotlin Compose）双端均提供事件 CRUD、月视图与周视图；Android
  端通过本地 AlarmManager 单闹钟链式调度本地提醒（不接受服务端推送）。
  事件规则沿用既有 records 加密通道同步（与阶段 4a `place` 模块同款
  信封链路），服务端**零改动**；重复事件以纯函数动态展开（不物化实例），
  展开算法在三端共享同一组 fixture 与测试向量，确保跨端一致。
- **Purpose**：把个人日程纳入加密个人库，形成可被未来 AI 助理使用的
  时间档案，同时维持服务端零知识边界（服务端只见密文与元数据），
  提醒走端侧本地闹钟（不依赖 FCM/服务端可见的触发时刻）。
- **Target Users**：自托管 Everything 服务、使用 Android 主机的单一用户本人
  （不合规场景：他人代为创建/推送；产品内不引入服务端推送通道）。

## Goals

- 端到端 CRUD：Web 与 Android 均可新建/编辑/删除日程事件，双端经 records
  通道加密同步。
- 视图可用：月视图 + 周视图切换；事件块按 `color` 着色；全天事件独立栅格；
- 提醒可靠：Android 端 AlarmManager 本地精确闹钟（USE_EXACT_ALARM 声明），
  单闹钟链式调度；开机/应用更新/系统重启后自动重建链头。
- 重复事件：覆盖 RRULE B 档简化子集（每日/每周/每月/每年 + 间隔 + 每周可选
  工作日 + 结束条件 + 例外日期），重复事件按本地时区展开，不补时区漂移校正。
- 可验证：展开算法提为纯函数，三端共享 fixture（≥24 用例），JVM 单测 +
  Vitest 双锁定；端到端冒烟 6 场景全过。
- 不破零知识：服务端只见到密文 records；明文事件明文仅存 Android Room
  （Vault DB 容器内）与 Web 浏览器内存，**不进** localStorage/IndexedDB/
  日志/崩溃消息。

## Non-Goals

- **不做**任务（todo）模块（独立阶段 4c）。
- **不做**服务端推送：提醒仅 Android 本地闹钟；服务端不存触发时刻明文；
  不引入 FCM/任何服务端通道。
- **不做**时区独立存储：仅 `tz_mode=local`（字段保留以便未来扩展，不暴露 UI）；
  跨时区旅行时重复事件随设备本地时区展开（接受历史漂移）。
- **不做**单次实例覆盖编辑：修改只整序列；如需改一次，等价于"在 exdates
  跳过该次 + 新建单发事件"。
- **不做**日视图、独立 agenda 列表视图：周视图点当天即够（YAGNI）。
- **不做**跨设备闹钟同步：每个设备按本地时区独立调度。
- **不做**富文本、参与人、邀请、附件、位置关联、导入导出 iCal。
- **不做** iOS 端、Web 端提醒（仅 Android）。
- **不做**服务端对事件内容的任何校验或索引：不解密、不解析 rrule、不缓存
  明文起始时刻。

## Future Enhancements

> 以下能力**并非明确不做**，本期因范围控制先记录在案，后续阶段逐一完善。
> 各项均须继承本期零知识纪律（服务端只见密文，分析/调度在客户端或经用户
> 明示的本地能力完成）。

- 任务（todo）模块：与日程共享 reminders 通道。
- 服务端哑调度 + FCM 唤醒：跨设备提醒可达，但触发时刻明文落服务端属新增
  元数据暴露，需先评估隐私影响。
- 时区独立存储：`tz_mode=tz` + 浮动规则展开。
- 单次实例覆盖编辑：需 detached instance 建模 + 三端编辑分叉 UI。
- 日视图与 agenda 列表视图。
- iCal 导入导出（导入在客户端解密 → 校验 → 加密入库；导出在客户端解密 → 生成）。
- 富文本 note、附件、参与人/协作、位置关联（与 place 模块打通）。
- Android 端日历桌面 widget。
- Web 端提醒（Web Notification API 需用户授权浏览器通知，可作未来轻量补强）。

## Background & Context

- 现有加密通道：阶段 0–3 已落地 `CryptoEnvelope.sealRecord/openRecord`
  （XChaCha20-Poly1305，AAD `eve:v1:record:{id}`），事件模块**直接复用**
  不新造原语（4a 已验证三端逐字节一致）。
- 现有同步链路：Android `RecordsRepository`（Room v4 显式迁移）+ 15 分钟
  周期 `SyncWorker`（4a 复用）；Web `vault.ts` 的 `sealRecord/pushRecords`
  + `pullRecords/openRecord`；服务端 Chi `approved` 分组下 `/records/batch`
  已承载批量幂等。事件模块**直接复用**现有 SyncWorker/接口，不新造。
- 现有 manifest 权限：4a 已声明 `POST_NOTIFICATIONS`（API 33+），本期复用；
  本期仅新增 `SCHEDULE_EXACT_ALARM`（API 31+）+ `USE_EXACT_ALARM`（API 33+ 自动
  授予但显式声明，向用户说明意图）。
- 现有组件复用：阶段 4a `BootReceiver` 已处理开机拉起 SyncWorker；
  本期在同一 receiver 内追加"重建闹钟链头"逻辑（仅 SyncWorker 完成后调用）。
  **不新建**第二个 receiver。
- 现有 Android 调度模式：阶段 3 已落地 `CollectorWorker`（WorkManager），
  本期不用 WorkManager 调度闹钟（精度受 Doze 限制），改用 AlarmManager
  `setExactAndAllowWhileIdle`（API 31+ 需 SCHEDULE_EXACT_ALARM）。
- 现有 Web 月历渲染：阶段 4a `web/src/locations/month.ts` 抽出了
  `CalendarCell` 月历格子纯函数（带数据高亮/选中态），可直接借鉴思路
  但**不复用**（轨迹月视图与日程月视图交互差异显著：日程需事件块排版、
  跨日事件、点击事件进入编辑器）。
- 模块扩展约定（`docs/development.md`）：新增业务模块**不改 records 表**——
  定义模块 JSON Schema + 客户端表单即可；时间戳 Unix 毫秒 int64；
  ID 客户端 UUID 字符串。事件模块严格遵循。
- 全库代码现状：全库无 calendar/event/schedule/reminder 实现，
  本期为**绿地构建**，但锚定既有 envelope/records/SyncWorker/Room/Compose/
  Pinia/Chi/audit 既有模式。
- 时间纪律：FU-1 已落地服务端权威时间；事件 `start_ts/end_ts/exdates` 全部
  取设备本地时区语义（不显式带时区），落 records 走加密信封，服务端不解析。

## Functional Requirements

### FR-1 事件数据模型

事件作为一条 records 记录写入 `records` 表（`module="event"`、`type="event"`），
明文 JSON Schema 进 `docs/module-schemas.md` 第 8 章：

```json
{
  "id": "uuid-string",
  "title": "字符串（≤200字符，必填）",
  "start_ts": 1735689600000,
  "end_ts":   1735693200000,
  "all_day": false,
  "tz_mode": "local",
  "location_text": "纯文本，可选",
  "note": "纯文本，可选",
  "color": "blue|green|red|amber|violet|pink|cyan|slate",
  "reminders": [0, 15, 1440],
  "rrule": null | {
    "freq": "DAILY|WEEKLY|MONTHLY|YEARLY",
    "interval": 1,
    "byweekday": ["MO","WE","FR"],
    "end": { "kind": "never" }
         | { "kind": "date",  "until": "2027-12-31" }
         | { "kind": "count", "count": 12 }
  },
  "exdates": ["2026-01-01", "2026-02-17"]
}
```

字段口径：

- `start_ts`/`end_ts`：Unix 毫秒；`all_day=true` 时 `end_ts` 含结束日 00:00；
- `tz_mode`：本期固定 `"local"`，字段保留以便未来扩展，UI 不暴露；
- `reminders`：提前分钟数组，≤3 个；`0` 表示事件开始时刻；
- `exdates`：日期数组 `YYYY-MM-DD`，与 `tz_mode=local` 联动按本地日历日匹配；
- `color`：8 色板预设，事件→实例继承（同一事件所有实例同色）。

### FR-2 RRULE B 档子集语义

`rrule` 为 `null` 时 = 单次事件；非空时为简化子集：

| 约束 | 说明 |
|---|---|
| `freq ∈ {DAILY, WEEKLY, MONTHLY, YEARLY}` | 四档频率 |
| `interval ≥ 1` | UI 默认 1 |
| `byweekday` 仅 WEEKLY/MONTHLY 有效 | MONTHLY 时取"该月第 N 个 `byweekday[0]`"，**仅支持单 weekday**（不支持 MO+TU 同时） |
| `end.kind ∈ { never | date | count }` | `never` 永不复；`date.until` 为日期（本地日历日）到该日 24:00 后不再展开；`count` 累计展开次数（不含已被 exdate 跳过的实例） |
| DST 处理 | 跨夏令时切换日，`WEEKLY` 维持同一本地时刻（如周一 09:00 → 周一 09:00），不补 23/25 小时校正 |
| 字段不可识别 | schema 校验失败 → 阻止保存（前端表单防住为首选；服务端不解密故无法校验） |

### FR-3 同步链路（沿用 4a 既有 records 通道）

| 端 | 入库路径 | 触发 | 出库路径 |
|---|---|---|---|
| Web | `vault.saveEvent` → `sealRecord` → `pushRecords` | 编辑器保存即 dirty | `pullRecords` → `openRecord` → 内存态 |
| Android | `EventsRepository.upsert` → `RecordsRepository` dirty | 编辑器保存即 dirty | `SyncWorker`（4a 既有）pull/push |
| Server | — | — | 仅做认证 + 中转 + 元数据索引（**零改动**） |

冲突策略：LWW（last-write-wins，与现有 records 一致）。

删除语义：删规则 → 删 records 条目；exdate 不删实例（保持 idempotent）。

增量同步：`since` 参数沿用 4a 现有 SyncWorker 接口，无需新增。

### FR-4 Android 端 Room 扩展

Room v4 → v5 显式迁移（4a 既有 Room v3 → v4 模式复用）：

- 新增 `event` 表（明文字段，Vault DB 容器内加密）：
  - `id TEXT PRIMARY KEY`
  - `title TEXT NOT NULL`
  - `start_ts INTEGER NOT NULL`
  - `end_ts INTEGER NOT NULL`
  - `all_day INTEGER NOT NULL`
  - `tz_mode TEXT NOT NULL DEFAULT 'local'`
  - `location_text TEXT`
  - `note TEXT`
  - `color TEXT NOT NULL`
  - `reminders_json TEXT NOT NULL`
  - `rrule_json TEXT`
  - `exdates_json TEXT NOT NULL DEFAULT '[]'`
  - `dirty INTEGER NOT NULL DEFAULT 0`
  - `updated_ts INTEGER NOT NULL`
  - 索引 `(start_ts)`、`(dirty)`
- 新增 `event_reminder_log` 表（降级事件记录，供设置页/通知中心展示）：
  - `id INTEGER PRIMARY KEY AUTOINCREMENT`
  - `event_id TEXT NOT NULL`
  - `occurrence_ts INTEGER NOT NULL`
  - `kind TEXT NOT NULL`（`alarm_killed` | `notification_denied` | `exact_denied`）
  - `created_ts INTEGER NOT NULL`

明文驻留：仅 Android Room 与 Web 浏览器内存；不进 SharedPreferences/日志/
崩溃消息/通知文案（通知文案仅渲染 title，不渲染坐标——4a 已验证纪律）。

### FR-5 Android 端提醒调度（链式 AlarmManager）

`ReminderScheduler` 计算下一触发点 + 调度单一闹钟：

- `nextTrigger(rule, reminders, now)`：在规则 + reminders 下找
  `now` 之后最近的触发时刻（含 reminders[0]=0 即事件开始时刻本身）；
  若未来无触发返回 `null`。
- 调度接口：AlarmManager `setExactAndAllowWhileIdle`（API 31+），
  配合 `PendingIntent` 指向 `ReminderReceiver`。
- 链式：触发后 `ReminderReceiver` 展示通知 → 重新计算 `nextTrigger` →
  调 `setExactAndAllowWhileIdle`；仅注册**单闹钟**，规避国产 ROM 配额/
  滥用检测。
- 重建触发点：
  - `BootReceiver`（4a 既有）开机/应用更新后 SyncWorker 完成后调
    `ReminderScheduler.rebuildChain()`；
  - 编辑器保存事件（含新建/编辑/删除）后立即调 `rebuildChain()`；
  - 应用冷启动进入主界面后调一次 `rebuildChain()`（4a 启动挂钩复用）。
- 触发语义：
  - 到点 → 通知（`POST_NOTIFICATIONS` 已声明）；
  - 若 `reminders` 含 0 → 事件开始时刻触发；
  - 若 `reminders` 含 15 → 事件开始前 15 分钟触发；以此类推。
- 通知文案：仅渲染 title + 距开始分钟数（如"3 分钟后开始"），不渲染坐标；
  不渲染 `start_ts/end_ts` 原始数字（防止日志抓取暴露时刻）。

### FR-6 权限与降级

Android Manifest 新增：

- `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>`（API 31+）
- `<uses-permission android:name="android.permission.USE_EXACT_ALARM"/>`（API 33+ 自动授予）

Android 13+ 用户拒绝 `SCHEDULE_EXACT_ALARM` 或 `POST_NOTIFICATIONS`：

- 调度器降级为 `setAndAllowWhileIdle`（约 15 分钟精度）；
- 通知降级为不弹横幅（仅通知中心落一条）；
- 写一条 `event_reminder_log` 记录（`kind=alarm_killed` 或
  `notification_denied`），设置页展示一次性 banner "可能被系统限制"，**不阻塞**
  事件保存/查看。
- Android 12 及以下：用户手动在系统设置关闭精确闹钟后，应用通过
  `AlarmManager.canScheduleExactAlarms()` 检测并落入同降级路径。

### FR-7 BootReceiver 扩展

4a `BootReceiver` 在 SyncWorker 完成后追加：

```kotlin
if (syncSucceeded) {
    ReminderScheduler.rebuildChain(applicationContext)
}
```

- 不新建第二个 receiver；
- 不阻塞开机启动流程（rebuildChain 同步执行 O(事件数)，单用户量级可接受）；
- 异常 catch 后仅记录降级日志，不崩溃。

### FR-8 Web 端 UI（Vue3 + Pinia + Vue Router）

- 新增路由 `/calendar`（AppShell children 内，4a 既有风格，相对子路由）；
- AppShell 顶部导航增"日历"入口；
- 组件树：
  - `views/CalendarView.vue`：月/周视图切换容器；
  - `views/MonthView.vue`：月视图（复用 4a `month.ts` 网格布局思路；
    新增事件块排版，跨日事件跨列渲染；点击空格→新建；点击事件→打开
    `EventEditorDialog`）；
  - `views/WeekView.vue`：周视图（7 × N 列；事件块按 `start_ts-end_ts`
    跨度渲染；点击当天空格→新建；点击事件→编辑）；
  - `components/EventEditorDialog.vue`：表单组件；
  - `components/EventBlock.vue`：事件块展示（颜色+标题+时间，按 all_day
    区分渲染样式）；
  - `stores/events.ts`：Pinia store（CRUD + 展开计算 + 选中窗口）；
  - `events/expand.ts`：纯函数展开（跨端共享 fixture）。

### FR-9 Web 端事件编辑器

`EventEditorDialog.vue` 表单字段：

- 标题（必填，≤200 字符）
- 开始/结束（日期 + 时间；`all_day=true` 时仅日期选择）
- `all_day` 开关
- 地点（纯文本，可选）
- 备注（纯文本，可选）
- 颜色（8 色板）
- 提醒（多选下拉：0/5/15/30/60/1440 分钟，≤3 个）
- 重复（折叠面板，单次/每日/每周/每月/每年 + 间隔 + 每周工作日 +
  结束条件 + 例外日期）
- 例外日期（日期列表，加号添加）

校验：

- 标题非空
- 结束 ≥ 开始（`all_day=true` 时结束日 ≥ 开始日）
- reminders ≤3 个、值在预设档位
- rrule 字段合法性（前端表单防住）

保存：调 `eventsStore.upsert(rule)` → `vault.saveEvent` → `sealRecord` →
`pushRecords`（4a `savePlace` 同款链路）。

### FR-10 Android 端 UI（Kotlin Compose）

- `CalendarScreen`：月/周视图切换容器；
- `MonthGrid.kt`：月视图（Compose Canvas 绘制网格 + 事件块）；
- `WeekGrid.kt`：周视图（Compose LazyColumn 或 Canvas）；
- `EventEditorScreen`：表单组件（与 Web 同字段同校验）；
- `EventBlock`：组件（按 `all_day` 区分样式）；
- 入口：4a `CollectorScreen` 之后新增"日历"卡片 / 主导航 Tab。

### FR-11 跨端展开纯函数 + 共享 fixture

`web/src/events/expand.ts` 与 `android/.../recurrence/Recurrence.kt` 同源：

```ts
function expand(rule: EventRule, window: {from: number, to: number}): Occurrence[]
```

`Occurrence` 字段：

- `instance_id`：`<rule.id>:<rule.start_ts>#<n>`（n 从 0 开始，跳过 exdate 后递增）
- `rule_id`：原 rule.id
- `start_ts`：实例起始 Unix 毫秒
- `end_ts`：实例结束 Unix 毫秒
- `all_day`、`color`、`title` 等继承字段
- `original_start_ts`：起始 ts（用于 `recurrence-id` 对齐，按 RFC 5545 简化命名）

展开算法要点：

- 窗口裁剪：实例 `start_ts < window.from` 或 `> window.to` 即丢；
- exdate 应用：实例本地日历日命中 exdate 列表即丢（不递增 n）；
- count 结束：累计展开次数（含已被 exdate 跳过的实例？不含，FR-2 已定）；
- date 结束：实例本地日历日 > `until` 即停；
- DST：`WEEKLY` 维持同一本地时刻，跨 DST 日不补 23/25 小时；
- `MONTHLY` + `byweekday`：取该月第 N 个 `byweekday[0]`（如"每月第二个周二"）；
  若该月无第 N 个则跳过该月。

跨端测试向量：

- `recurrence/__fixtures__/cases.json`（Web 与 Android 镜像加载）：
  - 单次/每日/每周多天/每月单 weekday/每年/interval/截止/计数/exdates/
    DST 跨日/边界窗口裁剪/空 rrule，最少 24 用例。
- 三端测试都断言 `expand(...)` 输出**逐字段一致**（含 `instance_id`）。

### FR-12 文档

- `docs/module-schemas.md` 第 8 章：event JSON Schema + RRULE B 档子集定义；
- `docs/crypto.md`：§5.1 增 event 走 records 同款链路说明 + AAD 不变说明；
- `docs/android.md` 4b 章：权限、Room 表、Receiver 扩展、Scheduler 设计；
- `README.md` Web 节：增"日历视图"功能介绍；
- `everything_plan.md` L131：勾选阶段 4b 完成。

## Non-Functional Requirements

- **NFR-1 零知识红线**：服务端持久化与日志中不得出现事件明文
  （标题/备注/地点/rrule/exdates/reminders）；Android 明文仅存 Room
  且有界（与 4a records 一致）；Web 明文仅驻留浏览器内存，**不写**
  localStorage/IndexedDB/日志/崩溃消息/通知文案。
- **NFR-2 权限最小化**：恰申请 FR-6 所列两项权限（`SCHEDULE_EXACT_ALARM` +
  `USE_EXACT_ALARM`），`POST_NOTIFICATIONS` 沿用 4a 既有声明；不申请
  RECEIVE_BOOT_COMPLETED（4a 已声明）、不导出 ReminderReceiver 之外的组件。
- **NFR-3 可测性**：展开算法、提醒 nextTrigger、rrule 构建、字段校验
  抽为不依赖 Android Framework / 浏览器 API 的纯函数，由 JVM（Kotlin）
  与 Vitest（TS）单测覆盖；跨端共享 fixture 双锁定；Room v4→v5 走
  显式迁移并有 instrumented 迁移测试（无设备环境至少编译通过）。
- **NFR-4 闹钟配额**：仅注册单闹钟（链式），规避国产 ROM AlarmManager
  配额与滥用检测；rebuildChain O(事件数) 单用户量级 < 100ms。
- **NFR-5 兼容性**：minSdk 26 / targetSdk 35；零 GMS 依赖；国产 ROM
  杀后台/权限收紧场景降级不崩溃。
- **NFR-6 一致性**：Android 沿用 ServiceLocator 手动注入/Room 显式迁移/
  Compose Material3/中文详细注释；服务端零改动；Web 沿用 stores/crypto
  (envelope)/router 分层；不新造平行链路。

## Constraints

- **Technical**：
  - 加密复用 CryptoEnvelope 同参数（Argon2id/XChaCha20-Poly1305 与 AAD 规则
    不得新造）；事件 AAD 沿用 `eve:v1:record:{id}`（与现有 records 逐字节
    一致）。
  - 服务端零知识：不解密、不校验 rrule、不缓存明文起始时刻；不引入新表/列/
    接口；events 与 place 同走 `/records/batch`。
  - 时间戳一律 Unix 毫秒 int64；ID 一律客户端 UUID 字符串。
  - 模块扩展：新增业务模块不改 records 表——定义模块 JSON Schema + 客户端
    表单即可；事件模块严格遵循。
- **Business**：仅本人自托管场景；应用内不做提醒滥用检测（单用户单账号）；
  通知文案避免渲染坐标/原始时刻数字。
- **Dependencies**：Android 不新增第三方库（AlarmManager 为框架 API）；
  Web 无新增依赖（Pinia/Vue Router/Vitest 既有）；服务端预计无新增依赖。
  Android 引入 Robolectric（如未加）以单测 ReminderScheduler。

## Assumptions

- 单用户单账号；多设备间事件经 records 通道 LWW 同步。
- 设备系统时间大致准确（事件 `start_ts` 取系统本地时区语义）；异常时钟
  导致的事件漂移由用户在编辑/删除下自行处理，不做服务端矫正。
- 设备本地时区作为唯一时区锚点；跨时区旅行时历史事件随设备本地时区
  漂移（接受该行为并文档化）。
- 国产 ROM 杀后台场景下闹钟可能被系统清除；rebuildChain 在 BootReceiver
  /应用启动/事件保存后重建，可恢复大部分场景；深度杀后台场景接受降级。
- Web 端提醒本期不做（仅 Android）；未来如做需 Web Notification API 用户授权。
- 单用户事件量假设：千级以下；展开与 rebuildChain 性能均按此设计。

## Acceptance Criteria

### AC-1: 事件数据模型与 RRULE B 档
- **Type**: `rule`
- **Given**: 任意前端表单输入
- **When**: 提交事件（单次/每日/每周多天/每月单 weekday/每年/interval/截止/计数）
- **Then**: 落 records 表的密文对应明文符合 FR-1/FR-2；超出 B 档的 rrule 字段
  被前端校验拒绝；服务端不解密故无法校验（文档明示）
- **Pass Condition**: 三端字段定义逐字段一致（Grep module-schemas.md 第 8 章 +
  Android EventEntity + Web types）
- **Evidence**: 文档 diff + 代码行

### AC-2: 跨端展开纯函数一致性
- **Type**: `rule`
- **Given**: `recurrence/__fixtures__/cases.json` ≥24 用例
- **When**: 在 Web Vitest 与 Android JUnit 中分别加载 fixture 调 expand
- **Then**: 三端输出 `Occurrence[]` 逐字段一致（含 instance_id 派生）
- **Pass Condition**: Web expand.test.ts ≥24 用例全绿；Android RecurrenceTest.kt
  ≥24 用例全绿；fixture 文件哈希三端一致
- **Evidence**: 单测输出 + fixture 文件哈希

### AC-3: 同步链路复用 records 通道
- **Type**: `rule`
- **Given**: Web 端新建一个事件
- **When**: 保存 + 触发 pullRecords（Android SyncWorker 周期窗口）
- **Then**: 服务端 `/records/batch` 收到密文（无明文可验）；Android 解密后
  Room `event` 表出现该规则；编辑另一端再保存后 pullRecords 可见更新
- **Pass Condition**: 服务端 Go 测试无回归；Android SyncWorker 测试通过；
  Web pushRecords/pullRecords 代码审查确认复用 4a 链路
- **Evidence**: go test 输出 + Android 测试 + 代码行

### AC-4: Android Room v4→v5 显式迁移
- **Type**: `rule`
- **Given**: 4a 后的 Room v4 数据库
- **When**: 应用升级到 v5（新增 event/event_reminder_log 两表）
- **Then**: 迁移脚本执行成功，旧数据保留；新增表索引符合 FR-4
- **Pass Condition**: MigrationTest instrumented 编译通过（无设备环境至少编译）；
  Grep Room Migration 调用链
- **Evidence**: MigrationTest 代码 + Gradle 输出

### AC-5: 链式闹钟与 nextTrigger 调度
- **Type**: `rule`
- **Given**: 多个事件（含重复）+ reminders 列表
- **When**: 调 ReminderScheduler.nextTrigger(rule, reminders, now)
- **Then**: 返回 `now` 之后最近的触发时刻（含 reminders[0]=0 即事件开始时刻
  本身）；未来无触发返回 null；触发后链式调度下一闹钟
- **Pass Condition**: ReminderSchedulerTest JUnit + Robolectric ≥10 用例全绿
  （含重复事件下一触发/exdate 跳过/未来无触发/DST 边界）
- **Evidence**: 单测输出 + Scheduler 代码

### AC-6: 权限降级路径
- **Type**: `rule`
- **Given**: Android 13+ 设备拒绝 SCHEDULE_EXACT_ALARM 或 POST_NOTIFICATIONS
- **When**: 触发一次闹钟或事件保存
- **Then**: 调度降级为 setAndAllowWhileIdle + 不弹横幅；event_reminder_log
  写入对应 kind；设置页展示一次性 banner；事件保存/查看不阻塞
- **Pass Condition**: 代码审查确认降级分支；reminder_log 表索引查询通过
- **Evidence**: Scheduler 代码 + log 表 Schema

### AC-7: BootReceiver 重建链头
- **Type**: `rule`
- **Given**: 设备重启或应用更新
- **When**: BootReceiver 触发 + SyncWorker 完成
- **Then**: 在同一 receiver 内追加调用 rebuildChain；单闹钟重新注册到下一个
  触发时刻；异常 catch 不崩溃
- **Pass Condition**: 代码审查确认追加位置；真机冒烟记录于 tasks.md
  （无设备则记录关闭条件，合并入 FU-7）
- **Evidence**: BootReceiver 代码 + 冒烟记录

### AC-8: Web 月视图与周视图
- **Type**: `rule`
- **Given**: Room/Store 中有若干事件（含重复 + 跨日 + 全天）
- **When**: 切换月/周视图、点击空格新建、点击事件编辑、翻月/翻周
- **Then**: 事件块按 color 着色；跨日事件跨列渲染；全天事件独立栅格；
  新建/编辑/删除闭环顺畅；空态/加载/错误态清晰
- **Pass Condition**: Vitest 组件测试 + 代码审查
- **Evidence**: 单测输出 + 组件代码

### AC-9: Web 事件编辑器与 rrule 构建器
- **Type**: `rule`
- **When**: 输入标题/起止/全天/地点/备注/颜色/reminders/rrule/exdates
- **Then**: 字段校验正确；rrule B 档子集表单交互正确；保存即 dirty + push
- **Pass Condition**: Vitest + Vue Test Utils ≥8 用例全绿
- **Evidence**: 单测输出 + 组件代码

### AC-10: Android Compose 编辑器与 rrule 构建器
- **Type**: `rule`
- **When**: 输入同上
- **Then**: 同 AC-9（字段语义一致）
- **Pass Condition**: Compose UI Test ≥6 用例全绿
- **Evidence**: 单测输出 + 组件代码

### AC-11: 零知识红线全链路
- **Type**: `rule`
- **Given**: 任意场景下事件明文流转
- **When**: 检查服务端日志/审计、Android 日志/通知/SharedPreferences、
  Web localStorage/IndexedDB/控制台
- **Then**: 服务端仅见密文与 records 元数据；Android 日志/通知无 title
  明文（仅"3 分钟后开始"类抽象文案）；Web 无明文持久化路径
- **Pass Condition**: grep 模式（Log[.dwiev]、putString、通知文案、localStorage、
  sessionStorage、IndexedDB、document.cookie）零命中或带说明
- **Evidence**: grep 检查记录

### AC-12: 文档与计划同步
- **Type**: `rule`
- **When**: 实现完成
- **Then**: module-schemas.md 第 8 章含 event JSON Schema + RRULE B 档定义；
  crypto.md §5.1 增 event 链路说明；android.md 4b 章含权限/Receiver 扩展/
  Scheduler 设计；README Web 节增"日历视图"；plan 阶段 4b 进度同步
- **Pass Condition**: 文档评审通过且字段与代码/DTO 一致
- **Evidence**: 文档 diff

### AC-13: Web 日历视图体验质量
- **Type**: `rubric`
- **Dimension**: 日历页（月/周 + 编辑器 + 切换 + 同步闭环）的用户体验质量
- **Scale**: 1-5
- **Anchors**: 1 = 视图错乱/解密失败无提示；3 = 功能可用但空态/加载/错误态
  粗略；5 = 装载-切换-新建-编辑-删除闭环顺畅，月/周联动自然，跨日/全天事件
  视觉清晰
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对日历页走查（含空日/大量事件/跨日事件/重复事件四态）

### AC-14: 架构一致性与可测性
- **Type**: `rubric`
- **Dimension**: 新增代码与既有架构的契合度及纯函数可测性
- **Scale**: 1-5
- **Anchors**: 1 = 新造平行存储/网络/加密路径，逻辑耦合在 Activity/组件不可测；
  3 = 复用主通道但展开/调度逻辑散落、部分单测；5 = 纯函数核心 + 薄平台适配层，
  完全复用信封/Room/Worker/Chi 分组/audit/BootReceiver，单测覆盖全部平台无关
  分支
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对模块边界与单测覆盖的走查

### AC-15: 闹钟可靠性（rubric）
- **Type**: `rubric`
- **Dimension**: Android 端闹钟在国产 ROM 与权限收紧下的可靠性
- **Scale**: 1-5
- **Anchors**: 1 = 频繁漏触发/无降级；3 = 主线可达但杀后台后失效率高；
  5 = 链式调度 + BootReceiver 重建 + 权限降级 + 降级日志全链路闭环，真机冒烟
  通过
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对 ReminderScheduler/BootReceiver/log 表的走查 +
  真机冒烟记录（无设备则并入 FU-7 关闭条件）

## 交付物清单

### 代码层

- `docs/module-schemas.md` 第 8 章（新增）
- `docs/crypto.md` §5.1（更新交叉引用）
- `docs/android.md` 4b 章（新增）
- `web/src/events/expand.ts` + `expand.test.ts`（新增）
- `web/src/events/__fixtures__/cases.json`（新增，Android 镜像加载）
- `web/src/events/__tests__/rruleBuilder.spec.ts`（新增）
- `web/src/stores/events.ts`（新增）
- `web/src/components/EventEditorDialog.vue`（新增）
- `web/src/components/EventBlock.vue`（新增）
- `web/src/views/CalendarView.vue`、`MonthView.vue`、`WeekView.vue`（新增）
- `web/src/router/index.ts`（更新：增 `/calendar` 路由）
- `web/src/AppShell.vue`（更新：增"日历"入口）
- `android/.../data/event/EventEntity.kt`、`EventReminderLogEntity.kt`、
  `EventDao.kt`、`EventReminderLogDao.kt`、`EventsRepository.kt`（新增）
- `android/.../recurrence/Recurrence.kt`、`RecurrenceTest.kt`（新增）
- `android/.../reminder/ReminderScheduler.kt`、`ReminderSchedulerTest.kt`、
  `ReminderReceiver.kt`（新增）
- `android/.../sync/BootReceiver.kt`（更新：追加 rebuildChain 调用）
- `android/.../ui/screens/CalendarScreen.kt`、`EventEditorScreen.kt`、
  `MonthGrid.kt`、`WeekGrid.kt`（新增）
- `android/.../AndroidManifest.xml`（更新：增 2 权限 + ReminderReceiver 注册）
- `app/src/main/res/values/strings.xml`（更新：增 event 模块所有文案）
- `android/app/build.gradle.kts`（更新：增 Robolectric 依赖，如未加）

### 文档/约定层

- `README.md` Web 节（增"日历视图"功能介绍）
- `everything_plan.md` L131（勾选阶段 4b 完成）
- `.trae/specs/stage4b-calendar/spec.md`（本文件）
- `.trae/specs/stage4b-calendar/tasks.md`（writing-plans 产出）
- `.trae/specs/stage4b-calendar/review.md`（独立评审产出）

### 不交付（明确划界）

- 不动 server/任何 Go 代码
- 不动 records 表 schema / SyncWorker 接口
- 不动 4a 既有测试、Android Manifest 已有权限（仅增权限/注册）
- 不新增 FCM / 服务端推送通道
- 不产出 iCal 导入导出
- 不做任务（todo）模块（留 4c）
- 不做 Web 端提醒
- 不做日视图、独立 agenda 列表视图

## 门禁（不可跳过）

- Go `go test ./...` 全 0（无 server 改动亦需复跑）
- Web `pnpm build` + `pnpm test` 全 0（含 expand.test.ts ≥24 用例）
- Android `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` BUILD SUCCESSFUL
- Android unit test 全绿（RecurrenceTest ≥24 + ReminderSchedulerTest ≥10 + EventsRepository ≥8）
- Android instrumented 编译通过（无设备环境仅编译）
- Web `recurrence/__fixtures__/cases.json` 与 Android 镜像文件哈希一致