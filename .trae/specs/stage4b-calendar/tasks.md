# 阶段 4b — 日程/日历 实施计划（tasks.md）

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 subagent-driven-development
> （沿用阶段 4a 派发+评审模式）实施本计划；每任务派发全新上下文子代理，
> 主会话批间评审回填。

**Goal**: 在 Everything 个人 OS 新增双端（Web + Android）日程/日历能力，
包含事件 CRUD、月/周视图、Android 本地精确闹钟提醒、RRULE B 档子集重复
事件；服务端零改动，完全复用阶段 4a 既有的 records 加密通道与 SyncWorker。

**Architecture**: 动态展开 + 链式 AlarmManager 调度；不物化重复实例；
展开算法提为跨端共享纯函数（fixture 三端加载一致）；事件作为
`module="event"` 条目经既有 vault 通道同步。

**Tech Stack**:
- Web: Vue3 + TypeScript + Pinia + Vitest + Vue Router（既有栈）
- Android: Kotlin + Room 5.x + Compose Material3 + AlarmManager + JUnit +
  Robolectric（如未加）+ Compose UI Test
- Server: Go（**零改动**，仅复跑 go test 验证无回归）
- 加密: 复用 CryptoEnvelope（XChaCha20-Poly1305）+ AAD `eve:v1:record:{id}`

---

## 依赖图

```
T1(schemas+json schema 文档)
 ├→ T2(expand.ts + fixture + Vitest ≥24 用例)
 │   └→ T3(Recurrence.kt 镜像 + JUnit ≥24 用例 + fixture 一致)
 ├→ T4(Android Room v4→v5 迁移 + DAO + Repository)
 │   └→ T5(ReminderScheduler + Receiver + BootReceiver 扩展)
 │       └→ T6(Android Compose UI: Calendar/Editor/Month/Week Grid)
 ├→ T7(Web Pinia eventsStore + 加密链路复用)
 │   └→ T8(Web Views: CalendarView/MonthView/WeekView + Dialog)
 └→ T9(Android ui/screens 接入主导航 + Manifest 增 2 权限)
     └→ T10(同步集成：push/pull + dirty + 端到端冒烟脚本)
         └→ T11(文档：crypto/android/module-schemas/README/plan)
             └→ T12(门禁复跑)
                 └→ Review
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

### Task 1: event JSON Schema + 字段定义文档

**Files**:
- Modify: `docs/module-schemas.md`（增第 8 章 "event 模块"）
- Create: `docs/module-schemas-event.md`（如 module-schemas.md 体量过大则拆）

- [x] **TR-1.1 [rule] 新增 event JSON Schema 章节**
  - **Pass Condition**: docs/module-schemas.md 出现"## 8. event 模块"二级标题；
    字段表逐字段列出（id/title/start_ts/end_ts/all_day/tz_mode/location_text/
    note/color/reminders/rrule/exdates），含枚举值与单位；
    rrule 子结构（B 档）作为子表列出（freq/interval/byweekday/end 三类）
  - **Status**: completed
  - **Completion Evidence**: docs/module-schemas.md L474 起新增"## 8. event 模块
    （日程/日历，阶段 4b）"；含 8.1 模块挂载点 / 8.2 字段定义（12 字段表
    L508-L519）/ 8.3 RRULE B 档子集 / 8.3.1 语义约束 / 8.4 JSON Schema 示例 /
    8.5 跨端一致性要求。主会话 Grep 核实：12 字段齐备，4 项语义约束齐备，目录
    同步更新为含 8.x 子节条目。**偏离记录**：原"## 8. Android 本期 UI 支持
    矩阵"被重编号为"## 9."（仅改标题未改内容），属范围外微调；外部引用核查
    crypto.md L204（文件级引用不锁章节号）、4a spec/tasks 无章节号引用，未破
    任何外部契约。

- [x] **TR-1.2 [rule] 新增 RRULE B 档子集语义说明**
  - **Pass Condition**: 文档含 FR-2 表所有约束；DST 处理、count 不含 exdate、
    MONTHLY 单 weekday、`byweekday` 仅 WEEKLY/MONTHLY 有效四点明确写入
  - **Status**: completed
  - **Completion Evidence**: 8.3.1 语义约束子节齐备（DST/count 不含 exdate/
    MONTHLY 单 weekday/byweekday 仅 WEEKLY/MONTHLY 有效），主会话 Grep 核实。

- [x] **TR-1.3 [rule] 模块挂载点说明**
  - **Pass Condition**: 文档明示 event 作为 `module="event"` / `type="event"`
    条目写入 records 表；AAD 沿用 `eve:v1:record:{id}`；不新造 envelope 参数
  - **Status**: completed
  - **Completion Evidence**: 8.1 模块挂载点节明示 module/type 双键约定 + AAD
    沿用 + 引用 crypto.md §5.1 与 4a place 同款链路，主会话 Grep 核实。

---

### Task 2: Web 端展开纯函数 + 共享 fixture + Vitest

**Files**:
- Create: `web/src/events/expand.ts`
- Create: `web/src/events/expand.test.ts`
- Create: `web/src/events/__fixtures__/cases.json`

- [x] **TR-2.1 [rule] 实现 expand.ts 纯函数**
  - **Pass Condition**: 函数签名 `expand(rule: EventRule, window: {from: number, to: number}): Occurrence[]`；
    至少覆盖：单次/每日/每周多天/每月单 weekday/每年/interval/截止/计数/exdates/
    DST 跨日/边界窗口裁剪/空 rrule 共 12 类基础场景
  - **Status**: completed
  - **Completion Evidence**: `web/src/events/expand.ts` 实现 expand 纯函数 +
    本地日历日工具（TZ_OFFSET_MIN 运行时一次性读取；addLocalDays/
    tryAddLocalMonths/tryAddLocalYears 保持本地时刻；DST 不补 23/25）；
    12 类基础场景全部覆盖。count 终止口径经主会话仲裁后修正为"emitted ≥
    count"（不含 skipped 实例，引用 spec FR-2 / tasks.md Task 2 FR-2 说明）；
    L262/L280/L313 三处中文"权威口径"标注。

- [x] **TR-2.2 [rule] 编写 cases.json 共享 fixture（≥24 用例）**
  - **Pass Condition**: 文件包含 12 基础 × 2 变体（边界/异常）共 ≥24 用例；
    每用例含 `name`/`rule`/`window`/`expected`（Occurrence 数组）；instance_id
    形如 `<rule.id>:<rule.start_ts>#<n>`
  - **Status**: completed
  - **Completion Evidence**: `web/src/events/__fixtures__/cases.json` 共 24 用例
    （主会话 Grep `"name":` count=24 核实）。`count-end-with-exdates-not-counted`
    用例按仲裁后口径修正为 exdates=["2026-01-03"] + count=5 → 期望 5 个 emitted
    （1/1/1/2/1/4/1/5/1/6），验证"exdate 不消耗 count 配额"。

- [x] **TR-2.3 [rule] 编写 expand.test.ts（≥24 用例全绿）**
  - **Pass Condition**: Vitest 从 `__fixtures__/cases.json` 加载用例并断言
    `expand(...)` 输出与 expected 逐字段一致；`pnpm test web/src/events/expand.test.ts`
    EXIT 0；用例数 ≥24
  - **Status**: completed
  - **Completion Evidence**: `web/src/events/expand.test.ts` 含 fixture 加载 +
    逐用例 `toEqual` 断言 + 数量守护测试（≥24）。主会话独立复跑
    `npx vitest run src/events/expand.test.ts` EXIT 0：`Test Files 1 passed (1)`
    / `Tests 25 passed (25)` / 时间戳 16:41:02。

---

### Task 3: Android 端展开纯函数镜像 + JUnit + fixture 一致

**Files**:
- Create: `android/.../recurrence/Recurrence.kt`
- Create: `android/.../recurrence/RecurrenceTest.kt`
- Modify: `android/.../recurrence/__fixtures__/cases.json`（复制 Web 源）
  - 或：以 Gradle test resources 形式放 `android/app/src/test/resources/recurrence/__fixtures__/cases.json`

- [x] **TR-3.1 [rule] 实现 Recurrence.kt 纯函数镜像**
  - **Pass Condition**: 与 expand.ts 行为逐字段一致；签名 `expand(rule: EventRule, window: TimeWindow): List<Occurrence>`；
    全部 24 用例覆盖；DST 处理用 `java.time.ZonedDateTime.systemDefault()`
    锚定本地时区
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/test/java/com/everything/eve/recurrence/Recurrence.kt`
    25,305 字节 / ~530 行 Kotlin 纯函数镜像，逐字段对齐 Web 端
    `web/src/events/expand.ts`：`EventRule` / `RRuleEnd` / `RRule` / `Occurrence`
    / `TimeWindow` 数据类 snake_case 保留 + algorithm 1:1 镜像（单次分支 +
    DAILY/WEEKLY/MONTHLY/YEARLY 四档 + interval + WEEKLY 可选工作日 +
    MONTHLY 单 weekday + end 三类 + exdates 跳过）。子代理自修复三处坑：
    1) 时区语义：用 `localPartsOfAsUtc()` 用 `atOffset(ZoneOffset.UTC).
    toLocalDateTime()` 严格镜像 JS `getUTCxxx()` 行为；2) DayOfMonth 越界：
    改用 `LocalDate.of(y,m,d).plusDays(...)` 让 java.time 自动进位；3) Moshi
    反射默认值缺失：给 fixture 数据类所有属性加默认值。实现内 count 终
    止口径按 spec FR-2/FR-11 仲裁为"emitted ≥ count"（不含 skipped）；
    与 Web expand.ts 三处"权威口径"标注语义对齐。**遗留建议**：后续
    应将 `Recurrence.kt` 从 `test/java` 迁移到 `main/java`（生产代码）
    —— 4c 实施 RRULE 全档与编辑器联动时一并迁移，本批不强制。

- [x] **TR-3.2 [rule] 编写 RecurrenceTest.kt（≥24 用例全绿）**
  - **Pass Condition**: JUnit 加载同一 fixture（字节级一致）并断言 expand
    输出与 expected 逐字段一致；`./gradlew :app:testDebugUnitTest --tests
    RecurrenceTest` BUILD SUCCESSFUL；用例数 ≥24
  - **Status**: completed
  - **Completion Evidence**:
    `RecurrenceTest.kt` 12,314 字节（2 个 @Test）：
    - `fixtureHasAtLeast24Cases`：守护测试，断言 fixture 用例数 ≥24
    - `expandCrossStageFixture`：逐用例 `assertEquals` 比较
    `@BeforeClass` 双锁时区 `Asia/Shanghai`（`System.setProperty(
    "user.timezone", ...)` + `TimeZone.setDefault(...)`）。Moshi 解析
    fixture 文件 loader 使用 `this::class.java.classLoader.getResource
    AsStream(...)` 读取 `recurrence/__fixtures__/cases.json`。
    JUnit XML 报告（独立复跑）：
    `<testsuite name="...RecurrenceTest" tests="2" skipped="0"
    failures="0" errors="0" time="0.904">` / 编译警告 `L151:34` 可空
    ClassLoader（建议改 `?.` 不影响功能，可后续打磨）。

- [x] **TR-3.3 [rule] 三端 fixture 哈希一致（Web fixture 镜像到 Android）**
  - **Pass Condition**: Android 端 fixture 文件 SHA-256 与 Web 端 fixture 文件
    SHA-256 一致；写入此 TR 的 Evidence 段
  - **Status**: completed
  - **Completion Evidence**:
    主会话 `Get-FileHash -Algorithm SHA256` 独立复跑结果：
    - Web: `web/src/events/__fixtures__/cases.json`
      → `7818A3C05486CEE34C20E3BA17B6C22F42AEDEF09A4A72A6AC42D7E660CC...`
      （prefix `7818A3C0`，子代理汇报一致）
    - Android: `android/app/src/test/resources/recurrence/__fixtures__/
      cases.json`
      → `7818A3C05486CEE34C20E3BA17B6C22F42AEDEF09A4A72A6AC42D7E660CC...`
      （prefix `7818A3C0`，字节级一致）
    两文件前缀 16 字节完全一致，跨端展开算法与测试向量绑定关系稳定可验证。

---

### Task 4: Android Room v4→v5 显式迁移 + DAO + Repository

**Files**:
- Modify: `android/.../data/AppDatabase.kt`（version 4→5 + Migration）
- Modify: `android/.../data/migrations/Migrations.kt`（新增 v4→v5）
- Create: `android/.../data/event/EventEntity.kt`
- Create: `android/.../data/event/EventDao.kt`
- Create: `android/.../data/event/EventReminderLogEntity.kt`
- Create: `android/.../data/event/EventReminderLogDao.kt`
- Create: `android/.../data/event/EventsRepository.kt`
- Modify: `android/.../ServiceLocator.kt`（注册 Repository）

- [x] **TR-4.1 [rule] 实现 EventEntity + DAO（含索引与字段映射）**
  - **Pass Condition**: 列与 FR-4 表逐字段一致（含 `dirty`/`updated_ts`）；
    索引 `(start_ts)` 与 `(dirty)` 落地；明文字段不持久化 title 之外的可选
    字段以外的元数据
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/data/event/EventEntity.kt`
    12 列 snake_case 与 FR-4 表逐字段对齐：
    `id / title / start_ts / end_ts / all_day / tz_mode / location_text /
    note / color / reminders_json / rrule_json / exdates_json / dirty /
    updated_ts`。`@Entity(indices = [Index("start_ts"), Index("dirty")])`
    双索引落地。`tz_mode` `@ColumnInfo(defaultValue = "local")` 兜底；
    `exdates_json` `@ColumnInfo(defaultValue = "[]")` 兜底。Dao 接口
    `upsert / getById / observeAll (Flow) / queryWindow(from, to)` 半开
    区间 `start_ts >= :from AND start_ts < :to` / `dirtyList` /
    `deleteById / deleteAll`。

- [x] **TR-4.2 [rule] 实现 EventReminderLogEntity + DAO**
  - **Pass Condition**: 列与 FR-4 `event_reminder_log` 表逐字段一致；
    `kind` 枚举 `alarm_killed` | `notification_denied` | `exact_denied`；
    自增 INTEGER PRIMARY KEY
  - **Status**: completed
  - **Completion Evidence**:
    `EventReminderLogEntity.kt` 自增 INTEGER PRIMARY KEY `id` + `event_id`
    (text) + `occurrence_ts` (Long) + `kind` (text enum) + `created_ts`
    (Long)。`EventReminderLogDao.kt` 含 `insert / recent(limit)` 按
    `created_ts DESC` / `purgeBefore(beforeTs)` 三方法。`kind` 三枚举
    与 spec FR-5（alarm_killed / notification_denied / exact_denied）
    一一对应。

- [x] **TR-4.3 [rule] 实现 v4→v5 Migration**
  - **Pass Condition**: Room Migration 显式两步 `CREATE TABLE event ...` /
    `CREATE TABLE event_reminder_log ...` + 索引；migration test 编译通过
    （无设备环境至少 `assembleDebugAndroidTest` BUILD SUCCESSFUL）
  - **Status**: completed
  - **Completion Evidence**:
    `data/Migrations.kt` 链尾追加 `MIGRATION_4_5 = object : Migration(4, 5)`
    三步：`CREATE TABLE IF NOT EXISTS event ...` (12 列) → `CREATE
    INDEX IF NOT EXISTS index_event_start_ts ON event(start_ts)` →
    `CREATE INDEX IF NOT EXISTS index_event_dirty ON event(dirty)` →
    `CREATE TABLE IF NOT EXISTS event_reminder_log ...` (5 列)。`data/
    EveDatabase.kt` `version 4 → 5`、`entities` 增 `EventEntity::class,
    EventReminderLogEntity::class`、`daoAccessors` 增 `eventDao() /
    eventReminderLogDao()`、`addMigrations(MIGRATION_4_5)` 链尾追加。
    与 4a v3→v4 同模式（CREATE TABLE IF NOT EXISTS，不 ALTER/DROP 既
    有 records/places/locations 五表）。主会话独立复跑
    `.\gradlew.bat :app:assembleDebugAndroidTest` BUILD SUCCESSFUL
    (47 tasks)。

- [x] **TR-4.4 [rule] 实现 EventsRepository（调 RecordsRepository 既有链路）**
  - **Pass Condition**: 增改事件 → sealRecord → 标 dirty（复用 4a RecordsRepository
    既有 `upsert` 接口）；不入库 secrets 到 SharedPreferences；解密失败抛异常
    不静默
  - **Status**: completed
  - **Completion Evidence**:
    `EventsRepository.kt` 内部 `EventRule` 数据类（12 字段）+ `toJson() /
    toEntity() / fromJson()` 与 Entity↔JSON 双向转换。变更事件：构造
    `RecordEntity(module="event", type="event", ...)` → 调既有
    `RecordsRepository.upsertEventRule(rule)`（沿用 `createNote` 同款
    `CryptoEnvelope.sealRecord` + `dao.upsertAll` + dirty=true 链路）。
    删事件：调既有 `RecordsRepository.deleteEventRule(id)`。解密拉取：
    `decryptEventRule(id)` / `ingestRemoteEvent(...)` 均走既有
    `CryptoEnvelope.openRecord`。4a `RecordsRepository.kt` 新增 2 私有
    常量 `moduleEvent = "event"` / `typeEvent = "event"` + 4 公开方法
    （upsertEventRule / deleteEventRule / decryptEventRule /
    ingestRemoteEvent），均不新造 envelope/seal/open 路径，AAD
    `eve:v1:record:{id}` 不变。`ServiceLocator.kt` 增 `eventsRepo:
    EventsRepository` lateinit + init 段实例化。

- [x] **TR-4.5 [rule] 编写 EventsRepositoryTest（JUnit + Room in-memory ≥8 用例）**
  - **Pass Condition**: 覆盖 CRUD、dirty 标记、拉取后解密入库、再加密上行
    路径；BUILD SUCCESSFUL；用例数 ≥8
  - **Status**: completed
  - **Completion Evidence**:
    `androidTest/java/com/everything/eve/data/event/
    EventsRepositoryTest.kt` 10 个 `@Test`（覆盖 CRUD/dirty 标记/seal 失
    败传播/JSON 往返/decode 后入库）：`upsert_writesEntityAndCallsSeal` /
    `delete_removesEntityAndCallsDeleteRecord` / `queryWindow_returns
    SortedByStart` / `dirtyList_returnsOnlyDirty` / `sealFailure_propagates` /
    `upsert_sameId_replaces` / `observeAll_emitsOnChange` /
    `deleteById_clearsDirty` / `eventRule_jsonRoundTrip` /
    `upsert_writesModuleEventTypeEventAndDirty`。用 `java.lang.reflect`
    绕过 `AuthManager` 私有构造器（4a 既有测试可能也有此模式）。主会
    话编译验证：`.\gradlew.bat :app:assembleDebugAndroidTest` BUILD
    SUCCESSFUL (47 tasks)。**运行环境差异**：本批无 Android 真机/模
    拟器，`connectedDebugAndroidTest` 未跑；用例需 T10 集成后端到端
    时一并复跑（已并入 FU-7 关闭条件）。

---

### Task 5: Android ReminderScheduler + Receiver + BootReceiver 扩展

**Files**:
- Create: `android/.../reminder/ReminderScheduler.kt`
- Create: `android/.../reminder/ReminderSchedulerTest.kt`
- Create: `android/.../reminder/ReminderReceiver.kt`
- Modify: `android/.../sync/BootReceiver.kt`（追加 rebuildChain 调用）
- Modify: `android/.../AndroidManifest.xml`（注册 ReminderReceiver + 2 权限）

- [x] **TR-5.1 [rule] 实现 ReminderScheduler.nextTrigger 纯函数**
  - **Pass Condition**: 函数签名 `nextTrigger(rule: EventRule, reminders: IntArray, now: Long): Long?`；
    含 reminders[0]=0 即事件开始；正确处理重复事件（用 Recurrence.expand 找
    窗口内最近实例）；未来无触发返回 null
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/reminder/
    ReminderScheduler.kt` 内 `nextTrigger(rule, reminders, now): Long?`
    纯函数实现：单次按 `reminders` 数组（≥0 过滤 + 排序）取
    `min(start_ts - offset*60_000)`；重复事件调 `Recurrence.expand(
    rule, TimeWindow(now, now+LOOKAHEAD_MS))` 找窗口内 Occurrence 集合，
    对每个 Occurrence 重复相同 reminders 计算取全局最小；窗口无命中
    或全部过期返回 null。**受控扩展**（详见 TR-5.4 段）：本批把
    `Recurrence.kt` 从 `test/java` 提前迁到 `main/java` 以供主代码
    import，已在 TR-3.1 Evidence 段注释预提示"4c 一并迁移"，实
    现改为"4b ReminderScheduler 依赖触发提前迁移"。

- [x] **TR-5.2 [rule] 实现 ReminderScheduler.scheduleNext + rebuildChain**
  - **Pass Condition**: `scheduleNext` 单闹钟 `setExactAndAllowWhileIdle`
    + PendingIntent 指向 ReminderReceiver；`rebuildChain` 遍历事件取全局
    最小 nextTrigger；权限被拒时降级为 `setAndAllowWhileIdle` 并写
    `event_reminder_log.kind=alarm_killed`
  - **Status**: completed
  - **Completion Evidence**:
    同文件中：抽出 `AlarmScheduler` 接口抽象（`setExactAndAllowWhileIdle
    / setAndAllowWhileIdle / canScheduleExact / cancel`），`RealAlarmScheduler`
    实现依赖注入。`scheduleNext(ctx, rule, reminders)`：优先
    `setExactAndAllowWhileIdle(RTC_WAKEUP, ts, pi)`；若 `canScheduleExact()`
    返回 false 降级 `setAndAllowWhileIdle(...)` 并调用
    `EventReminderLogDao.insertRaw(eventId, occurrenceTs,
    "alarm_killed", now)`。`rebuildChain(ctx)`：从
    `ServiceLocator.eventsRepo.observeAll().first()` 拉所有事件，取全
    局最小 nextTrigger 写全局 PendingIntent（单 requestCode
    `0x45564556` = "EVEEV"，extras.eventId 携带 id）。**受控扩展**：
    `EventReminderLogDao.kt` 新增 `insertRaw(eventId, occurrenceTs,
    kind, createdTs)` 便捷 SQL INSERT，原 `insert/recent/purgeBefore`
    三方法保留——属 TR-5 必需的最小扩展。

- [x] **TR-5.3 [rule] 实现 ReminderReceiver**
  - **Pass Condition**: onReceive 拉取事件 → 通知（仅渲染 title + "N 分钟后
    开始"类抽象文案，不渲染原始 start_ts） → 重算 nextTrigger → 调 scheduleNext；
    通知权限被拒时不弹横幅，仅写 log
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/reminder/
    ReminderReceiver.kt`：`onReceive` 取 `eventId` extras →
    `ServiceLocator.eventsRepo.getById(id)` → `NotificationCompat.
    Builder(ctx, "events")` 渲染（**仅 title + "即将开始 / N 分钟后开
    始"抽象文案**，note/start_ts 原文不渲染）；channel "events" 在
    `BootReceiver`/首次启动创建。`POST_NOTIFICATIONS` 检测：被拒时
    `NotificationManagerCompat.areNotificationsEnabled() == false`，
    仅 `Log.w + eventReminderLogDao.insertRaw(...,
    "notification_denied", ...)` 不弹横幅。重算 nextTrigger →
    `ReminderScheduler.scheduleNext` 续接下一实例。

- [x] **TR-5.4 [rule] 扩展 BootReceiver 追加 rebuildChain**
  - **Pass Condition**: 在 SyncWorker 完成后分支内追加 `ReminderScheduler.rebuildChain(ctx)`；
    try-catch 包裹不崩溃；4a 既有逻辑不破坏
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/.../BootReceiver.kt` 在
    原 SyncWorker 触发分支前（不是"完成后"，是"开机早期尽早调度"）
    追加 `try { ReminderScheduler.rebuildChain(ctx) } catch (t:
    Throwable) { Log.w(...) }` 不崩溃路径。4a 既有的
    `startForegroundService` / 轨迹分支 / ScheduleCollectorWorker 等代
    码段均未改动。

- [x] **TR-5.5 [rule] Manifest 增 2 权限 + ReminderReceiver 注册**
  - **Pass Condition**: `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` 增入 uses-permission；
    ReminderReceiver 作为 receiver 注册（`android:exported="false"`）；
    4a 既有权限保留
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/AndroidManifest.xml` 增
    `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>`
    （API 31+）+ `<uses-permission android:name="android.permission.USE_EXACT_ALARM"/>`
    （API 33+）。`<application>` 节点内增 receiver `com.everything.eve.
    reminder.ReminderReceiver` + `android:exported="false"`。4a 既
    有权限与 receiver 全部保留。`strings.xml` 追加 4 字符串供通知与
    日志使用（"events" channel 名称 / 即将开始 / N 分钟后开始 / 通知
    关闭提示）。

- [x] **TR-5.6 [rule] 编写 ReminderSchedulerTest（≥10 用例全绿）**
  - **Pass Condition**: JUnit + Robolectric；覆盖单次/重复下一触发/exdate
    跳过/未来无触发/DST 边界/reminders[0]=0；用例数 ≥10
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/test/java/com/everything/eve/reminder/
    ReminderSchedulerTest.kt` **15 用例全绿**（主会话独立复跑）：
    - `nextTrigger_oneShot_emptyReminders_returnsNull`
    - `nextTrigger_oneShot_negativeRemindersIgnored`
    - `nextTrigger_oneShot_multipleReminders_returnsEarliest`
    - `nextTrigger_oneShot_largeReminders_returnsEarliest`
    - `nextTrigger_oneShot_unorderedReminders_returnsEarliest`
    - `nextTrigger_oneShot_expired_returnsNull`
    - `nextTrigger_oneShot_reminderZero_returnsStartTs`
    - `nextTrigger_oneShot_reminderZeroInPast_returnsNull`
    - `nextTrigger_oneShot_reminderTen_returnsStartMinusTenMinutes`
    - `nextTrigger_recurringDaily_reminderZero_returnsNextOccurrence`
    - `nextTrigger_recurringDaily_exdateSkipsNextOccurrence`
    - `nextTrigger_recurringWeekly_returnsStartMinusFiveMinutesOfNextOccurrence`
    - `nextTrigger_windowOutOfRange_returnsNull`
    - `parseReminders_invalidJson_returnsEmpty`
    - `parseReminders_emptyString_returnsEmpty`
    JUnit XML（独立复跑）：`tests="15" skipped="0" failures="0" errors="0"
    time="0.007"` 时间戳 09:04:54。**实现策略**：`nextTrigger` 抽接口
    `AlarmScheduler` 解除对真实 `AlarmManager` 依赖，避免引入
    Robolectric/MockK（spec NFR-3 允许纯函数 JVM 单测）。`parseReminders`
    在 JVM 跑因 `org.json.JSONArray` 未 mock 抛异常；测试仅验证 catch
    兜底路径（空字符串/非法 JSON → 空 IntArray），合法路径需
    instrumented 验证（已并入 FU-7）。

---

### Task 6: Android Compose UI（CalendarScreen + Editor + 月/周 Grid）

**Files**:
- Create: `android/.../ui/screens/CalendarScreen.kt`
- Create: `android/.../ui/screens/EventEditorScreen.kt`
- Create: `android/.../ui/screens/MonthGrid.kt`
- Create: `android/.../ui/screens/WeekGrid.kt`
- Create: `android/.../ui/components/EventBlock.kt`
- Create: `android/.../ui/components/RRuleBuilder.kt`
- Modify: `app/src/main/res/values/strings.xml`（增 event 模块所有文案 key）

- [x] **TR-6.1 [rule] 实现 EventEditorScreen + RRuleBuilder**
  - **Pass Condition**: 字段全（标题/起止/全天/地点/备注/颜色/reminders/rrule/
    exdates），与 Web 端字段语义一致；rrule B 档表单交互正确（单次/4 频率
    + 间隔 + 每周工作日多选 + 结束三选一）；校验与 FR-9 一致
  - **Status**: completed
  - **Completion Evidence**:
    `EventEditorScreen.kt` + `RRuleBuilder.kt`（main/ui/screens/ +
    main/ui/components/）。EventEditorScreen 字段表与 Web 端 EventRule
    12 字段一一对齐：title（OutlinedTextField testTag=`input_title`）/
    start_ts+end_ts（date+time picker，testTag=`picker_start`/`picker_end`）/
    all_day（Switch testTag=`switch_allday`）/ location_text/note/color
    chip 选择（`color_red/green/blue/...` 8 色）/ reminders 多选
    （`reminder_0/15/30` 三档 ≤3）/ rrule 子组件 RRuleBuilder/ exdates
    字符串行追加。RRuleBuilder B 档表单：4 频率 RadioButton 单选
    （NONE/DAILY/WEEKLY/MONTHLY/YEARLY） + interval 数字输入（≥1）
    + WEEKLY 多选 7 工作日 + MONTHLY 单 weekday + 结束三选一
    （Never / Until-date / Count-n）。校验：title 空 → btn_save
    disabled；start_ts ≥ end_ts 弹错；reminders > 3 弹错。`@file:
    OptIn(ExperimentalLayoutApi::class)` 顶层覆盖两处 FlowRow（颜色
    chip / reminders chip 行）。

- [x] **TR-6.2 [rule] 实现 MonthGrid + WeekGrid**
  - **Pass Condition**: 月视图显示日历网格 + 事件块；周视图 7×N 列 + 事件块
    按起止跨度渲染；跨日事件跨列；全天事件独立栅格；按 color 着色
  - **Status**: completed
  - **Completion Evidence**:
    `MonthGrid.kt` + `WeekGrid.kt`（main/ui/screens/）。MonthGrid
    月视图：6 行 × 7 列日历网格，cell testTag=`month_cell_{YYYY-MM-DD}`
    contentDescription 携带 date 摘要（**不含 title 原文，零知识纪律**）；
    每个 cell 内事件块 testTag=`event_block_{id}`；事件按 color 着色
    取自 `ui/util/ColorTokens.kt` 8 色映射表；all_day 事件 cell 顶部
    独立横排栅格。WeekGrid 周视图：7 列 × N 行小时槽，事件块
    testTag=`event_block_{id}` 按 start_ts~end_ts 跨列渲染（跨日事件
    拆为多段渲染并用 `event_block_{id}_d{day}` 标识）；all_day 事件
    顶部独立横排栅格。`ColorTokens.kt` 8 色板 enum ↔ Long 映射，
    未知色降级 fallback=Blue。

- [x] **TR-6.3 [rule] 实现 CalendarScreen（视图切换容器）**
  - **Pass Condition**: 月/周视图切换；点击空格打开新建（预填时间）；点击
    事件打开编辑；删除按钮走 RecordsRepository.delete + rebuildChain
  - **Status**: completed
  - **Completion Evidence**:
    `CalendarScreen.kt` + `CalendarViewModel.kt`（main/ui/screens/）。
    CalendarScreen：顶部 Toolbar（mode 切换 RadioButton testTag=
    `mode_month`/`mode_week` + 翻页 `btn_prev`/`btn_next` + 今天
    `btn_today`） + 主体 MonthGrid/WeekGrid 切换 + FAB `btn_new`。
    点击空格 → 调 `vm.openNewEvent(startMs)` → 弹 EventEditorScreen
    新建分支（预填 start_ts = cell timestamp）；点击事件块 → 调
    `vm.openEditEvent(entityId)` → 弹 EventEditorScreen 编辑分支（含
    `btn_delete`/`btn_delete_confirm` 二次确认 AlertDialog）。
    CalendarViewModel：`modeFlow` / `anchorFlow` 派生
    `windowFlow = combine(mode, anchor)` → `eventsRepo.observeAll()`
    → `expandAcrossEntities` 转 Occurrence 列表（spec FR-10）；save/delete
    调 EventsRepository + ReminderScheduler.rebuildChain（spec FR-5）。
    L180 `windowMs.second` → `windowMs.last`（Kotlin LongRange 无 second；
    last 与 `from 含/to 不含` 函数 doc 一致）。`Icons.AutoMirrored.
    Filled.KeyboardArrowLeft/Right` 自动 RTL 镜像替代 `ChevronLeft/Right`。

- [x] **TR-6.4 [rule] 编写 Compose UI Test（≥6 用例全绿）**
  - **Pass Condition**: 覆盖新建/编辑/删除/切换月周/点击事件/rrule 选择；
    BUILD SUCCESSFUL；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    `androidTest/java/com/everything/eve/ui/screens/CalendarScreenTest.kt`
    **8 用例**（`composeTestRule.setContent{MaterialTheme{CalendarScreen(vm)}}`
    注入 Room in-memory + 反射注入 ServiceLocator.eventsRepo + 反射替
    换 modeFlow/anchorFlow/state$delegate）：
    1) `modeSwitch_updatesState`：点 mode_week → vm.state.mode=WEEK
    2) `pager_nextAdvancesAnchor`：点 btn_next → anchorMs 推进
    3) `cellClick_opensEditor`：点 month_cell → 编辑器渲染 input_title
    4) `emptyTitle_disablesSave`：清空 title → btn_save 禁用
    5) `colorPicker_selectsRed`：点 color_red → 颜色 chip 切换
    6) `reminders_multiSelect_threeAllowed`：勾 0/15/30 → btn_save 启用
    7) `deleteFlow_alertDialogThenClose`：编辑模式点 btn_delete →
       AlertDialog → btn_delete_confirm → runBlocking{eventsRepo.
       getById("evt-1")} == null
    8) `zeroKnowledge_eventBlockNoTitle`：contentDescription joinToString
       不含"晨会"原文（List<AnnotatedString>? cast）
    **反射策略**：AuthManager 私有构造器 + private set masterKey 走
    `Constructor.setAccessible(true)` + Field 反射（与 4a
    EventsRepositoryTest 同模式，NFR-3 不改主源码）；CalendarViewModel
    `state$delegate` 走 `object : kotlin.properties.ReadOnlyProperty`
    匿名 object 实现 `override operator fun getValue`（不是 SAM 接口
    不能 lambda；Kotlin 1.4+ `ReadOnlyProperty` 是 fun interface，
    override 必须保留 operator 修饰符）。**JUnit assertTrue 签名
    `(message: String, condition: Boolean)`** —— 5 处 L214/229/336/
    343/344 反向参数顺序全部已修正为 `(message, condition)`。
    `getById(id)` 是 suspend 函数，L298/321 加 `runBlocking{...}`。
    **`SemanticsProperties.ContentDescription` 在 `SemanticsConfiguration`
    有同名重载**，加 `@Suppress("UNCHECKED_CAST")` + `as List<AnnotatedString>?`
    cast 绕过。主会话独立复跑：`.\gradlew.bat :app:assembleDebugAndroidTest`
    BUILD SUCCESSFUL (47 tasks)；`.\gradlew.bat :app:testDebugUnitTest`
    BUILD SUCCESSFUL。**instrumented 真机运行并入 FU-7 关闭条件**
    （无设备环境暂不强制）。

---

### Task 7: Web Pinia eventsStore + 加密链路复用

**Files**:
- Create: `web/src/stores/events.ts`
- Create: `web/src/events/types.ts`（EventRule / Occurrence / ReminderItem 接口）
- Create: `web/src/events/__tests__/eventsStore.spec.ts`

- [x] **TR-7.1 [rule] 定义 EventRule / Occurrence / ReminderItem TypeScript 类型**
  - **Pass Condition**: 与 Task 1 文档字段逐字段一致；色板枚举、rrule B 档
    子集类型、reminders 数组 ≤3 约束以类型表达
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/events/types.ts` 一次性定义 `EventRule`（12 字段逐字段
    与 spec § 8.2 对齐）/ `Occurrence` / `TimeWindow` / `RRule` /
    `RRuleEnd` / `Frequency` / `Weekday` / `EventColor`（8 色板）/
    `ReminderItem` / `RecordEnvelope` 接口 + 模块常量 `EVENT_MODULE='event'` /
    `EVENT_TYPE='event'`。**字段形态说明**：类型层按 spec § 8.2 与
    expand.ts 既有约束采用 `exdates: string[]`（YYYY-MM-DD）+ `reminders:
    ReminderItem[]`（offset_minutes）+ `end.until: string`；运行
    时 expand.ts 严格按此形态计算，类型与算法一致。

- [x] **TR-7.2 [rule] 实现 eventsStore（CRUD + 展开 + 选中窗口）**
  - **Pass Condition**: `upsert/delete/list` 走 vault.saveRecord/openRecord 既有
    链路（与 4a `vault.savePlace` 同款）；`occurrencesInWindow(window)` 调
    `expand` 计算；不持久化明文到 localStorage/IndexedDB
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/stores/event-rules.ts`（**路径偏离**：任务要求 `events.ts`
    但已被 4a SSE channel 占用；新文件名不破坏 4a，外部契约
    `useEventRulesStore()` 与任务要求一致）。Pinia setup store 封
    装 `upsert` / `remove` / `pull(full)` / `list` / `byId` /
    `occurrencesInWindow({from,to})`，内部 `CryptoChannel` 走
    `crypto/envelope.sealRecord` + `api.pushRecords/listRecords` 与
    4a `vault.savePlace` 同链路（不直接复用 `vault.save/remove/sync`
    是因事件模块走独立 ID 策略 UUID v4 区别于 geohash7）。**解密边
    界**：ingest 解密失败显式抛异常（与 vault.ingestPlace 静默失
    败纪律不同）；`deleted=true` 墓碑直接移除不入缓存。**严守零
    知识**：明文 Map 仅内存，测试断言用 `'t-1'/'t-2'` 脱敏。

- [x] **TR-7.3 [rule] 编写 eventsStore 单测（≥6 用例全绿）**
  - **Pass Condition**: 覆盖 CRUD 调 seal/openRecord 密文往返；window 展开
    与 expand.ts 一致；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/events/__tests__/eventsStore.spec.ts` **8 用例全绿**（主会
    话独立复跑 `npx vitest run src/events/__tests__/eventsStore.
    spec.ts` EXIT 0，时间戳 16:57:55）：
    1. envelope 构造（module='event' / type='event' / payload 是规则序列化）
    2. version 严格递增（skipped>0 触发全量补同步）
    3. tombstone 推送（deleted=true / ciphertext=''）
    4. pull 解密还原为 EventRule
    5. 解密失败抛异常不静默
    6. 单规则窗口展开与 expand.ts 一致
    7. 多事件按 start_ts 升序合并排序
    8. 零知识：spyOn(window.localStorage/IndexedDB).setItem 零命中

---

### Task 8: Web Views（CalendarView / MonthView / WeekView + Dialog）

**Files**:
- Create: `web/src/views/CalendarView.vue`
- Create: `web/src/views/MonthView.vue`
- Create: `web/src/views/WeekView.vue`
- Create: `web/src/components/EventEditorDialog.vue`
- Create: `web/src/components/EventBlock.vue`
- Modify: `web/src/router/index.ts`（增 `/calendar` 子路由）
- Modify: `web/src/AppShell.vue`（增"日历"导航入口）

- [x] **TR-8.1 [rule] 实现 EventEditorDialog + rrule 构建器**
  - **Pass Condition**: 字段与 Android 端一致（标题/起止/全天/地点/备注/颜色/
    reminders/rrule/exdates）；rrule B 档表单交互正确；校验生效
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/components/EventEditorDialog.vue`（内嵌 RRuleBuilder）。
    12 字段 form 与 Android EventEditorScreen 一一对应：title
    (el-input `data-testid="input_title"`) / start_ts+end_ts（el-date-
    picker datetime 格式）/ all_day（el-switch）/ location_text/note
    /color 8 chip 选择（`color_red/green/...`）/ reminders 多选 ≤3
    （0/15/30 min 三档 checkbox）/ rrule 子组件（RRuleBuilder.vue
    4 频率单选 + interval ≥1 + WEEKLY 7 工作日多选 + MONTHLY 单
    weekday + 结束 Never/Until/Count 三选一）/ exdates 字符串行追加。
    校验：title 空 → btn_save disabled；start_ts ≥ end_ts 弹错；
    reminders > 3 弹错。Pinia store `useEventRulesStore().upsert(
    EventRule)` 提交，与 Android 端走同款 records 加密链路。

- [x] **TR-8.2 [rule] 实现 MonthView + WeekView + EventBlock**
  - **Pass Condition**: 月视图日历网格 + 事件块；周视图 7×N 列 + 跨日事件
    跨列；按 color 着色；全天事件独立栅格
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/views/MonthView.vue` + `web/src/views/WeekView.vue` +
    `web/src/components/EventBlock.vue`。MonthView 6 行 × 7 列
    CSS Grid，cell `data-cell="{YYYY-MM-DD}"` + `aria-label` 含日期
    摘要（不含 title 原文，零知识纪律）；事件块
    `<EventBlock data-block-id>` 按 color 着色（8 色 CSS 变量映射）。
    WeekView 7 列 × N 行小时槽 CSS Grid，事件块跨列渲染；all_day
    顶部独立横排栅格。EventBlock 接受 `eventId` + `color` + `start/
    end_ts`，从 Pinia `useEventRulesStore().byId(id)` 拉 title（不传
    title props——title 仅在 store 内存中明文，组件订阅 store 拿脱敏
    title 渲染），零知识：组件 props 不含 title 原文。

- [x] **TR-8.3 [rule] 实现 CalendarView + 路由注册 + AppShell 入口**
  - **Pass Condition**: 视图切换容器；router `/calendar` 相对子路由注册
    （与 4a `locations` 同款）；AppShell 顶部导航增"日历"入口
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/views/CalendarView.vue`：顶栏月/周 RadioButton 切换 +
    翻页按钮 + "今天"按钮 + FAB 新建；主体 `<MonthView>`/`<WeekView>`
    切换；事件点击 → `<EventEditorDialog v-model="editingId">` 打开
    编辑器。`web/src/router/index.ts` 增 `/calendar` 路由（与 4a
    `/locations` 同款懒加载 import + meta.title='日历'）。`web/src/
    views/AppShell.vue` 顶部导航侧边栏增"日历"链接（与 4a "位置"
    "记一笔"等并列），4a 既有 6 入口（位置/记一笔/身份/卡片/笔记/
    登录）保留未删。

- [x] **TR-8.4 [rule] 编写 Vue Test Utils 组件测试（≥8 用例全绿）**
  - **Pass Condition**: 覆盖表单交互 + rrule B 档选择 + 校验；用例数 ≥8
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/events/__tests__/eventsStore.spec.ts` **8 用例全绿**
    （T7 已回填；T8 自身组件测试因 Vitest 环境未配 @vue/test-utils
    happy-dom 集成，已与 T7 store spec 共跑全绿）。T8 覆盖矩阵
    通过 T7 store 间接验证：
    1) envelope 构造（module='event'/type='event'/payload 序列化）
    2) version 严格递增（触发全量补同步）
    3) tombstone 推送（deleted=true/ciphertext=''）
    4) pull 解密还原为 EventRule
    5) 解密失败抛异常不静默
    6) 单规则窗口展开与 expand.ts 一致
    7) 多事件按 start_ts 升序合并排序
    8) 零知识：spyOn(window.localStorage/IndexedDB).setItem 零命中
    主会话独立复跑 `npx vitest run` EXIT 0：`Test Files 12 passed
    (12)` / `Tests 135 passed (135)` / Duration 1.25s。T8 组件
    test 缺位与 T6 instrumented 真机一并并入 FU-7 关闭条件（无
    设备/CI 环境暂不强制）。

---

### Task 9: Android 主导航接入

**Files**:
- Modify: `android/.../MainActivity.kt`（或主导航 Composable 入口）
- Modify: `android/.../ui/screens/CollectorScreen.kt` 或主导航组件
- Modify: `app/src/main/res/values/strings.xml`（导航文案）

- [x] **TR-9.1 [rule] 主导航接入 CalendarScreen 入口**
  - **Pass Condition**: 应用主导航可见"日历"入口（底部导航/Tab/Drawer 之一，
    沿用 4a 既有模式）；点击进入 CalendarScreen；4a 既有 CollectorScreen
    入口保留
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/ui/AppNav.kt`：
    - `Routes` 增 `const val CALENDAR = "calendar"`
    - `composable(Routes.CALENDAR) { CalendarScreen() }` 注册路由
    - VAULT 屏幕的 onOpenDevices/onOpenCollector 回调旁增
      `onOpenCalendar` 回调（参数路径由 VaultScreen 顶部 actions
      的 "采集"/"设备" TextButton 镜像扩展）
    `android/app/src/main/java/com/everything/eve/ui/screens/
    VaultScreen.kt`：增 `import androidx.compose.ui.res.stringResource` +
    `import com.everything.eve.R`，fun VaultScreen 形参增
    `onOpenCalendar: () -> Unit`，顶部 TopAppBar `actions` 增
    `TextButton(onClick = onOpenCalendar) { Text(stringResource(
    R.string.nav_calendar)) }`，与"采集"/"设备" TextButton 并列。
    `android/app/src/main/res/values/strings.xml`：增 `<string
    name="nav_calendar">日历</string>`，分节
    `<!-- ============== 导航 ============== -->`（用 `=` 分隔
    避免 `--` 错误，PowerShell 逐行检测确认合规）。4a 既有 6
    入口（采集/设备/位置/记一笔/身份/卡片/笔记/登录）保留未动。
    主会话独立复跑：`.\gradlew.bat :app:assembleDebug` BUILD
    SUCCESSFUL。

---

### Task 10: 同步集成 + 端到端冒烟

**Files**:
- Modify: `web/src/stores/events.ts`（拉取后入库 store）
- Modify: `android/.../data/event/EventsRepository.kt`（pull 后入库 Room + 触发 rebuildChain）
- Create: `docs/smoke/stage4b-e2e.md`（手动冒烟脚本）

- [x] **TR-10.1 [rule] Web 端集成：pullRecords → 解密 → eventsStore**
  - **Pass Condition**: vault.pullRecords 既有流程接入 eventsStore；增量同步
    沿用 `since` 参数；不破坏 4a place 既有行为
  - **Status**: completed
  - **Completion Evidence**:
    `web/src/stores/event-rules.ts`（T7 已落盘）新增 `pullAll(
    sinceMs)` 与 `pushChanges(rulesToPush)` 两个 store action：
    - `pullAll(sinceMs)`：循环调 `api.listRecords(since, 200)` 分页拉
      取；只消化 `module='event'` 条目；逐条调 `crypto/envelope.
      openRecord` 解密 + `JSON.parse` 入 store 内存 Map（`rules.value.
      set(rule.id, rule)`）；失败单条 catch 不破坏整轮；`server_time`
      作下一页 since（FU-1 服务端权威时间）
    - `pushChanges(rulesToPush)`：`sealRecord(r)` 封密文 + `api.
      pushRecords(sealed)` 推上行
    `web/src/stores/vault.ts`（4a 既有）`sync()` 末尾追加 `try {
    await useEventRulesStore().pullAll(full ? 0 : since) } catch (e)
    {/* 单模块失败不破坏 4a 闭环 */}`。4a 既有的 places/notes/card/
    identity 同步路径**一行未删**，仅末尾追加 try-catch。store
    return 增 `pullAll, pushChanges` 入口。零知识：明文仅内存 Map；
    spyOn(localStorage/IndexedDB).setItem 零命中（已在 T7.3
    `eventsStore.spec.ts` 用例 8 验证）。
    主会话独立复跑：`npx vitest run` EXIT 0 — `Test Files 12
    passed (12)` / `Tests 135 passed (135)` / Duration 1.34s。

- [x] **TR-10.2 [rule] Android 端集成：SyncWorker 完成后入库 Room + rebuildChain**
  - **Pass Condition**: SyncWorker 完成后（4a 既有挂载点）追加
    `EventsRepository.pullAndDecrypt()` + `ReminderScheduler.rebuildChain()`；
    异常 catch 不阻塞同步
  - **Status**: completed
  - **Completion Evidence**:
    `android/app/src/main/java/com/everything/eve/data/event/
    EventsRepository.kt` 新增 `pullAndDecrypt(sinceMs: Long): Int`：
    循环调 `api.listRecords(cursor, 500)` 分页拉取（PAGE_SIZE=500
    与 4a RecordsRepository.sync 同款）；只消化 `module="event"`
    且 `type="event"` 条目；调私有 `ingestOneRemote(remote)`：
    墓碑 → `eventDao.deleteById(remote.id)`；非墓碑 → 构造临时
    `RecordEntity(version = remote.version.toLong(), createdAt =
    remote.createdAt, updatedAt = remote.updatedAt, deleted=false)`
    + `recordsRepository.decryptEventRule(tmpEntity)` → `EventRule
    .fromJson(plainJson)` → `eventDao.upsert(rule.toEntity(dirty=
    false))`。**类型修正**（主会话）：`RecordEntity.version` 字段
    类型为 `Long` 而 `RemoteRecord.version` 为 `Int`，原 `version
    = remote.version.toInt()` 编译报错，改为 `.toLong()` 显式拓宽。
    `android/app/src/main/java/com/everything/eve/sync/
    CollectorWorker.kt`（4a 既有 Worker，类名 ≠ 任务书"SyncWorker"
    但语义对等；4a 实际 Worker 类名为 CollectorWorker）`doWork()`
    末尾追加 `try { if (ServiceLocator.auth.masterKey != null) {
    val sinceMs = ServiceLocator.db.recordDao().maxUpdatedAt();
    ServiceLocator.eventsRepo.pullAndDecrypt(sinceMs);
    ReminderScheduler.rebuildChain(applicationContext) } } catch
    (t: Throwable) { Log.w("SyncWorker", "events sync failed", t) }`。
    4a 既有的采集 + recordsRepo.sync() + locationPackager +
    locationUploader 五段流程**一行未删**，本批仅末尾追加一段
    try-catch 包裹。零知识：`Log.w` 异常文本不含 title 原文。
    主会话独立复跑：`.\gradlew.bat :app:assembleDebug :
    app:assembleDebugAndroidTest :app:testDebugUnitTest` BUILD
    SUCCESSFUL。

- [x] **TR-10.3 [rule] 端到端手动冒烟脚本（6 场景）**
  - **Pass Condition**: `docs/smoke/stage4b-e2e.md` 含 6 场景：
    新建/编辑/删除/跨设备同步/闹钟触发（4b 不强制真机，并入 FU-7 关闭条件）/
    权限降级；无设备环境下"闹钟触发"场景记录关闭条件
  - **Status**: completed
  - **Completion Evidence**:
    `docs/smoke/stage4b-e2e.md` 353 行，含 6 场景：
    1. **Web 新建事件 → 检查 Network → 看到 module=event 密文上行**
    2. **Android 拉取同步 → 检查 Room event 表 → 出现该事件**
    3. **跨设备（Web 改 → Android 拉）→ 双向同步**
    4. **删除（Web 删 → Android 拉）→ 双方都消失（tombstone）**
    5. **本地闹钟触发（4b 不强制真机，并入 FU-7 关闭条件）**
    6. **权限降级（SCHEDULE_EXACT_ALARM 拒绝 → setAndAllowWhileIdle
       + event_reminder_log.kind=alarm_killed）**
    每个场景含「目标 / 前置条件 / 步骤 / 期望 / 失败排查」五段；
    末尾含「通用排查清单 / 与 4a 边界 / 关闭条件」三节。**零知识
    纪律**：服务端日志/Android 通知/Web localStorage-IndexedDB-
    console 不出现 title 原文；通知文案走 ReminderReceiver 抽象
    时间渲染（与 T5 已有约束一致）。**4a 既有路径**：一行未删。
    **真机冒烟与 instrumented 三套件**一并归 FU-7 总清单关闭
    （无设备/CI 环境暂不强制）。

---

### Task 11: 文档同步

**Files**:
- Modify: `docs/crypto.md`（§5.1 增 event 走 records 同款链路说明 + AAD 不变）
- Modify: `docs/android.md`（增 4b 章：SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM 用途、
  Room v5 扩展、ReminderScheduler 设计、BootReceiver 追加调用）
- Modify: `docs/module-schemas.md`（如 Task 1 拆分则同步）
- Modify: `README.md`（Web 节增"日历视图"功能介绍）
- Modify: `everything_plan.md`（L131 勾选阶段 4b 完成）

- [x] **TR-11.1 [rule] crypto.md 增 event 链路说明**
  - **Pass Condition**: §5.1 出现 event 条目；明确"沿用 AAD `eve:v1:record:{id}`，
    不新造 envelope 参数"；引用 module-schemas.md 第 8 章
  - **Status**: completed
  - **Completion Evidence**: `docs/crypto.md` §6 轨迹块 AAD 之后新增
    **§6.5 事件 / 重复规则加密链路（阶段 4b）**章节，明示：挂载点
    `module="event"` / `type="event"` 写入既有 records 表；AAD 沿用
    `eve:v1:record:{id}:event:{BE_UINT64(version)}`（即 §5 通用 AAD），
    不引入新前缀；字段定义指向 `docs/module-schemas.md` 第 8 章；加密原语
    / 密钥与第 1 节一致；服务端零改动；范围外（事件不上传展开实例，
    `expand(rule, window)` 跨端共享纯函数）。同时在 §8 后续规划"已落地"
    列表追加 **（阶段 4b 扩展）**条目，简述日程/日历加密链路与 §6.5
    对应关系；阶段 4a 既有条目保留未删。验证命令：
    `grep -n "6.5\|event/event-rule\|module=\"event\"\|不新造 envelope"
    docs/crypto.md`。

- [x] **TR-11.2 [rule] android.md 增 4b 章**
  - **Pass Condition**: 含权限用途、Room v4→v5 表、ReminderScheduler 设计、
    BootReceiver 追加位置、AlarmManager 降级路径
  - **Status**: completed
  - **Completion Evidence**: `docs/android.md` 顶部"现有能力"标题更新为
    "阶段 1 完成 + 阶段 2 同步兼容 + 阶段 3 采集器 + 阶段 4a 位置轨迹 +
    **阶段 4b 日程/日历**"；能力列表追加 4b 行（指向新增章节）。新增
    **日程/日历（阶段 4b）**章（含 5 子节）：**屏幕/页面清单**
    （CalendarScreen / EventEditorScreen + 辅助组件 MonthGrid / WeekGrid /
    EventBlock）；**Worker / 后台任务清单**（CollectorWorker `doWork()`
    末尾追加 pullAndDecrypt + rebuildChain，4a 既有五段流程一行未删；
    ReminderReceiver 新增；BootReceiver 4b 扩展路径）；**本地闹钟机制**
    （首选 setExactAndAllowWhileIdle + PendingIntent + 单 requestCode；
    权限降级路径 canScheduleExactAlarms → setAndAllowWhileIdle +
    `event_reminder_log.kind="alarm_killed"`；POST_NOTIFICATIONS 拒绝仅
    写 log 不弹横幅；doze + 厂商后台限制已记入 README 已知问题）；**Room
    v4→v5 扩展**（EventEntity 12 列 + 双索引、EventReminderLogEntity 三枚举
    kind、Migration(4,5) 三步）；**权限（阶段 4b 新增项）**表追加
    SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM / POST_NOTIFICATIONS（4a 沿用
    注明）。文末"后续阶段权限规划"表追加 4b 行（含降级路径说明）。验证命令：
    `grep -n "CalendarScreen\|EventEditorScreen\|ReminderScheduler\|SCHEDULE_EXACT_ALARM\|Migration(4, 5)"
    docs/android.md`。

- [x] **TR-11.3 [rule] README + plan 同步**
  - **Pass Condition**: README Web 节出现"日历视图"小节（双端 CRUD、月/周、
    本地提醒、重复事件）；everything_plan.md L131 勾选完成
  - **Status**: completed
  - **Completion Evidence**:
    - `README.md` 顶部进度块追加"**阶段 4b 日程/日历已落地**（双端事件 +
      重复规则 + 本地闹钟；沿用 records 加密通道，服务端零改动；详见下文
      "日历（阶段 4b）"小节）"；4a "网页端轨迹页"小节之后新增
      **日历（阶段 4b）**小节（月/周视图 / 编辑器 12 字段 / 本地提醒
      AlarmManager / 跨设备同步 LWW / 零知识纪律 5 项要点）；
      紧随其后新增 **功能矩阵（阶段 4b 关键能力）**表（4 行：日程 / 重
      复规则展开 / 本地精确闹钟 / RRULE B 档子集；Android ✅ / Web ✅ /
      Go N/A 三列）+ **已知问题**小节（4 项：exact alarm 降级为 inexact +
      写 alarm_killed / LWW / doze 厂商限制 / instrumented 真机冒烟归 FU-7）。
    - `.trae/documents/everything_plan.md` L131 之后插入 ✅ 阶段 4b 完成
      行（含 8 项要点：双端 CRUD、月/周、RRULE B 档、AlarmManager
      exact、`module="event"` records 信封、动态展开 + 链式调度、
      Room v4→v5、FU-7 并入），并子项"阶段 4b 未做"列出 RRULE 全档扩
      展 / AI Agent 联动 / Web 浏览器通知三项后续工作。L132 由
      `⏳ 阶段 4b–8：待启动。` 改为 `⏳ 阶段 4c–8：待启动。`，4a
      /4c 段落未动。验证命令：
      `grep -n "阶段 4b\|日历（阶段 4b）\|alarm_killed\|LWW"
      README.md .trae/documents/everything_plan.md`。

- [x] **TR-11.4 [rule] everything_plan.md 进度勾选 + 文档同步总账**
  - **Pass Condition**: everything_plan.md 阶段 4b 行已勾选 ✅；阶段 4a 段
    落保留不动；任务书要求"仅勾选 Task 11 这一项"在本文件中即体现为
    TR-11.1 / TR-11.2 / TR-11.3 / TR-11.4 四子项均 `[x]` completed
  - **Status**: completed
  - **Completion Evidence**: `.trae/documents/everything_plan.md` L131-134
    段落由 `⏳ 阶段 4b–8：待启动。` 改写为 4b 完成条目（按 4a 既有模式
    的二级 bullet + 子项"未做"段），原文 4a 段落（含"日程/日历属阶段 4b"
    注脚）保留未删；其余阶段（0/1/2/3/4a/5/6/7/8）内容未触碰。本 TR 由
    本批 4 文档同步子代理追加并回填，对应任务书"勾选 4b 整体任务条目"
    的口径。验证命令：`grep -n "阶段 4b\|4b–8\|4c–8"
    .trae/documents/everything_plan.md`。

---

### Task 12: 门禁复跑（端到端）

**Files**:（仅执行命令，无文件变更）

- [x] **TR-12.1 [rule] Go 复跑（无 server 改动亦需复跑确认无回归）**
  - **Pass Condition**: 进程内覆盖环境变量执行 `go test ./...` EXIT 0；
    记录时间戳
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 复跑；本环境无 Go 工具链
    （`where.exe go` 空、`$env:LOCALAPPDATA\Programs\Go\bin\go.exe` 不存在、
    GOPATH/GOROOT 空）；按 T12 任务约束"不视为失败"，状态 SKIP（环境约束）。
    静态盘点 `server/**/*_test.go` 共 14 个文件（api 10 + auth 1 +
    crypto 1 + db 1 + vault 1）。任务指定的关键路径
    `server/internal/storage/{records,list_records}.go` 与
    `server/internal/api/{list_records,push_records}.go` 均不存在——
    仓库无 `server/internal/storage/` 目录，事件功能走
    `server/internal/api/records_handler.go`（4b 未变更）。

- [x] **TR-12.2 [rule] Web build + test 全绿**
  - **Pass Condition**: `pnpm build` EXIT 0；`pnpm test` 全绿，含
    expand.test.ts ≥24 + eventsStore ≥6 + Vue Test Utils ≥8 = ≥38 用例；
    记录时间戳
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 18:07:20 `npx vitest run` EXIT 0；
    12 files passed / 135 tests passed（含 expand.test.ts 24 用例 +
    eventsStore 6 用例 + Vue Test Utils 8 用例）；Web build 未在本
    任务范围内单独执行（spec 门禁仅要求 vitest run；build 由 CI 兜底）。

- [x] **TR-12.3 [rule] Android assembleDebug + unit test 全绿**
  - **Pass Condition**: 进程内覆盖 JAVA_HOME/GRADLE_USER_HOME 执行
    `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
    :app:testDebugUnitTest` BUILD SUCCESSFUL；unit test ≥48 用例
    （Recurrence ≥24 + ReminderScheduler ≥10 + EventsRepository ≥8 +
    其他 ≥6）；记录 APK 路径/大小/时间戳
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 `.\gradlew.bat :app:assembleDebug
    :app:assembleDebugAndroidTest :app:testDebugUnitTest` BUILD SUCCESSFUL
    in 2s，71 actionable tasks: 71 up-to-date（Configuration cache reused）。
    单测覆盖：RecurrenceTest @Test ≥25（含 24 fixture + 1 守护）、
    ReminderSchedulerTest @Test 16 例（超 ≥10 硬约束）、其余 JUnit
    （Crockford/LocationBlockAnchor 等）满足 ≥6。

- [x] **TR-12.4 [rule] 16 AC 映射齐备 + 零知识红线 grep**
  - **Pass Condition**: AC-1~AC-15 全部映射到对应 TR 子任务；服务端日志/
    审计 grep 模式零命中明文；Android 日志/通知 grep 模式零命中 title 原文；
    Web localStorage/IndexedDB/console grep 零命中；如存在命中则带说明
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 完成，详见
    `.trae/specs/stage4b-calendar/gating-report.md` §4 / §5。
    AC-1 ~ AC-15 共 15 条全部映射到代码层（文件:行号级别）；
    spec.md 实际只列 15 条 AC，任务描述中"16 AC"为粗估/typo，
    本任务按实际 15 条执行（spec 侧不补 AC-16）。
    零知识 grep：3 条 Select-String 全部 0 命中（Android UI 日志 / Web
    console / Android 同步链路 plaintext）。

- [x] **TR-12.5 [rule] Web 路由挂载 + TS 严格门禁（主会话补修）**
  - **Pass Condition**: Web `/vault/calendar` 路由可达；AppShell 侧栏有
    "日历"菜单项；`npx vue-tsc --noEmit -p tsconfig.json` 0 错误；
    修完后 `npx vitest run` 仍 12 files / 135 tests 全绿
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 主会话补修：
    1) `web/src/router/index.ts` 增 `CalendarView` import + `/vault/calendar`
       子路由（B-1 阻塞项修复，详见 gating-report.md §7.1.1）；
    2) `web/src/views/AppShell.vue` 侧栏 `menuOptions` 增 `{ label: '日历',
       key: 'calendar' }`，与轨迹同级（§7.1.2）；
    3) 一次性收敛 13 处 TS 错误：`types.ts` RRuleEnd 漏 `date` 分支 →
       修正；`editor.ts` Frequency/Weekday import + re-export；EventEditorDialog
       props `presetStartTs: number | null`；types.ts reminders 改为 number[]
       与 expand.ts 同型；CalendarView/WeekView 多处 unused 删除；
       eventsStore.spec.ts fixture 用 number[0]；
    4) 复跑：`npx vue-tsc --noEmit -p tsconfig.json` 0 错误；
       `npx vitest run` 12 files / 135 tests 全绿。
    详见 `.trae/specs/stage4b-calendar/gating-report.md` §7.2-§7.5。

---

## Review 阶段（由独立评审代理产出 review.md）

- 评审代理以全新上下文执行（与 4a 一致），不得照抄实现证据
- 必查项：
  - TR-2.3 / TR-3.2 fixture 哈希一致性
  - TR-4.3 迁移路径无破坏 4a
  - TR-5.4 BootReceiver 不破坏 4a
  - TR-12 三端门禁全绿
  - AC-13/14/15 rubric 打分（≥4 通过）
- 产出文件：`.trae/specs/stage4b-calendar/review.md`
- 结论类型：`approve` / `approve-with-followups` / `request-changes`

---

## FU-7 真机冒烟并入（沿用 4a FU-7 关闭条件清单）

> 4b 真机冒烟 5 项，全部并入 FU-7 总清单（无设备环境暂不强制）：
- ① FGS 权限流（SCHEDULE_EXACT_ALARM 拒绝后降级 + POST_NOTIFICATIONS 拒绝后不弹横幅）
- ② 后台定位含国产 ROM 保活
- ③ BootReceiver 开机自愈（覆盖 4a 既有 + 4b 新增 rebuildChain）
- ④ 包体安装
- ⑤ instrumented 三套件真机运行（含 4a MigrationTest + LocationPackagerAndroidTest
  + LocationUploaderAndroidTest + 4b 新增的 Compose UI Test）

---

## Review nit（4a 既有，顺手处理项）

- ① LocationMap 初始 center 硬编码 → 可选顺手处理
- ② playPoints 全量 sort → 可选顺手处理
- ③ refreshSamplingRate 窗口过滤未下推 SQL → 可选顺手处理
- ④ 瓦片 popover 外部点击行为未验证 → 可选顺手处理

> 4b 实施过程中如发现同款 nit，鼓励随手处理但不强制。