# 阶段 4b — 日程/日历端到端手动冒烟脚本

> 本文档对应 `.trae/specs/stage4b-calendar/tasks.md` 中 **Task 10 / TR-10.3** 的
> Pass Condition：覆盖 6 场景——新建/编辑/删除/跨设备同步/本地闹钟/权限降级，
> 其中"本地闹钟触发"在当前无真机环境下并入 **FU-7 关闭条件**（沿用 4a FU-7
> 总清单，不在本批冒烟范围内强制）。
>
> 路径约定：
> - **Web** 仓库根 `web/`（Vue3 + Pinia + Vue Router）；
> - **Android** 仓库根 `android/`（Kotlin Compose + Room + AlarmManager）。
>
> 复跑命令（与 docs 中其它冒烟脚本同款）：
> ```bash
> # Web 端（主会话复跑 gate）
> cd web && npx vitest run
>
> # Android 端（主会话复跑 gate；无真机则仅跑 unit test + instrumented 编译）
> cd android && .\gradlew.bat :app:compileDebugUnitTestKotlin :app:assembleDebugAndroidTest
>
> # Go 服务端无回归复跑
> cd server && go test ./...
> ```
>
> 零知识纪律（贯穿所有场景）：
> - 服务端 Go 日志 / 审计 grep 不出现 title 原文；
> - Android 日志 / 通知文案 / SharedPreferences 不出现 title 原文；
>   通知文案仅渲染 title + "N 分钟后开始"类抽象时间；
> - Web `localStorage` / `IndexedDB` / `console.log` 不出现 title 原文。
>
> 同步链路总览（TR-10.1 / TR-10.2 装配后）：
> - **Web → Server**：`vault.sync` 后追加 `useEventRulesStore().pullAll(since)`
>   → 远端 records → 调 `crypto/envelope.openRecord` 解密 → 入 eventsStore；
> - **Server → Android**：`CollectorWorker.doWork` 末尾 try-catch 包裹追加
>   `eventsRepo.pullAndDecrypt(sinceMs)` → 调 `CryptoEnvelope.openRecord`
>   解密 → upsert 到 event 表；然后 `ReminderScheduler.rebuildChain(ctx)`
>   重注册闹钟链头；
> - **冲突策略**：LWW（last-write-wins），与既有 records 一致；
> - **删除语义**：删规则 → 删 records 条目（tombstone `deleted=true`）。
>
> 引用：
> - `web/src/stores/event-rules.ts`（T7 / TR-7.2 落盘，T10 暴露 `pullAll` /
>   `pushChanges` 入口）
> - `web/src/stores/vault.ts`（4a 已有 sync，本批追加 eventsStore.pullAll 挂载点）
> - `android/app/src/main/java/com/everything/eve/data/event/EventsRepository.kt`
>   （T4 / TR-4.4 落盘，本批新增 `pullAndDecrypt`）
> - `android/app/src/main/java/com/everything/.../sync/CollectorWorker.kt`
>   （本批在 4a `doWork` 末尾追加 try-catch 包裹的事件模块拉取 + 闹钟重建）

---

## 场景 1：Web 新建事件 → 检查 Network → 看到 `module=event` 密文上行

**目标**：验证 Web 端通过 `eventsStore.upsert` 走 `crypto/envelope.sealRecord`
+ `api.pushRecords` 链路，把一条事件以密文上行到服务端 `/records/batch`。

**前置条件**：
- Web 已登录且资料库已解锁（master key 在内存）。
- 浏览器开发者工具 Network + Console 已打开。
- 后端 Go 服务在 `localhost:8080`（或用户配置的服务端地址）。

**步骤**：
1. 在 Web 端访问 `/calendar` 路由（顶部导航"日历"入口）。
2. 点击 FAB"新建事件"，在 `EventEditorDialog.vue` 表单填写：
   - 标题：`晨会`（脱敏用文案，非必填）。
   - 开始：明天 09:00；结束：明天 09:30。
   - all_day：off；颜色：blue；reminders：勾选 `15` 与 `0` 两档。
   - rrule 折叠面板保持"单次"。
3. 点击"保存"。
4. 在浏览器开发者工具 Network 面板过滤 `records/batch`，找到刚才保存时的 POST 请求。

**期望**：
- 请求 path = `POST /api/v1/records/batch`。
- 请求 body 含一条记录且 `module="event"`、`type="event"`、`id` 为
  UUID v4 字符串、`version=1`、`ciphertext` 是 Base64 密文（不可肉眼读出原文）。
- 请求 body 的 title / start_ts 等明文字段**不在**任何服务端可观测位置
  （不出现于 `ciphertext` 之外）。
- 响应 `server_time` 字段非 0；UI 日历视图立即可见该事件（version 严格
  递增即时写本地缓存，与 vault.savePlace 同款即时性）。

**失败排查**：
- `module` 不等于 `"event"` → 检查 `EVENT_MODULE` / `EVENT_TYPE` 常量；
  回退到 4a place 链路。
- `ciphertext` 为空 → `sealRecord` 失败；多半是 `auth.sodium` / `auth.masterKey`
  在未解锁时为 null（应先 unlock 再 open Editor）。
- 不见 records/batch 请求 → `useEventRulesStore().upsert` 未被调用；
  检查 `EventEditorDialog.vue` 的 save handler 是否真调到 store。
- Network 面板出现 401 → 检查 access token 刷新链路（4a 已修过的；
  与本批无关，跳过本次排障）。

---

## 场景 2：Android 拉取同步 → 检查 Room event 表 → 出现该事件

**目标**：验证 `CollectorWorker` 在 4a 同步流程完成后追加调
`eventsRepo.pullAndDecrypt` → 远端 event 密文 → `CryptoEnvelope.openRecord`
→ 入库 Room `event` 表。

**前置条件**：
- Android 真机或模拟器一台，已配对且解锁到主界面（Master Key 已就位）。
- 上一场景中在 Web 端已成功上行一条事件。
- adb 已连接，开发者选项开启 USB 调试。

**步骤**：
1. 在 Android 端打开应用，等待 15 分钟周期 `CollectorWorker` 触发（或
   手动 `adb shell cmd jobscheduler run -f com.everything.eve 999`
   / WorkManager 调试命令触发即时单次任务）。
2. 应用主界面顶部 banner 显示"上次同步 HH:mm"已更新到刚才触发时间。
3. 在 adb shell 进入根目录：
   ```bash
   adb shell run-as com.everything.eve \
     sqlite3 databases/eve.db \
     'SELECT id, title, start_ts, end_ts, color, reminders_json, dirty
        FROM event ORDER BY start_ts;'
   ```
4. 同步触发前/后分别看一次 banner 时间戳与上述 SQL 输出。

**期望**：
- `event` 表出现一行：id 为 UUID v4（与 Web 端 records 表 id 一致）；
  title 列就是 `晨会`；`reminders_json` 含 `[0,15]` 数组；
  `dirty=0`（入库时显式标 false，与 4a RecordsRepository.sync 流程保持一致）。
- 应用主界面"日历"Tab → 该事件按月/周视图显示在正确位置（按 start_ts）。
- `last_sync_state.key=last_successful_sync` 时间戳已更新。
- WorkManager / logcat 不出现 4a 既有同步相关异常（place/locations 上行
  照常），仅有 `Log.w("SyncWorker", "events sync failed", t)` 兜底日志即视为成功
  （若 events 同步全程无异常，则无该日志，理想态）。

**失败排查**：
- `event` 表无新行 → `pullAndDecrypt` 未触发；检查 logcat 中的 `SyncWorker` tag。
- `event` 表有行但 `dirty=1` → ingestOneRemote 未显式传 dirty=false；
  检查 `EventsRepository.ingestOneRemote`。
- 应用崩溃在主界面 → 多半是 `ServiceLocator.eventsRepo` 为 lateinit
  未就位（极早启动场景）；本批已经把 ReminderScheduler.rebuildChain 异常
  catch 但 pullAndDecrypt 在 try-catch 兜底，需要专门看 logcat trace。
- `reminders_json` 是 `"null"` 或 `""` → JSONArray(reminders) 序列化失败；
  排查 EventEditorDialog 的 reminders 字段是否传 Int[]。

---

## 场景 3：跨设备（Web 改 → Android 拉）→ 双向同步

**目标**：Web → server → Android 的 record 信封链路在事件模块畅通无阻；
且 web 二次编辑后 Android 在下一轮 WorkManager 周期能看到变更。

**前置条件**：
- 场景 2 刚跑完（Android 端已有该事件）。
- Web 端登录态保持。

**步骤**：
1. Web 端进入 /calendar，点击已存在事件（点进编辑）。
2. 将标题改为 `晨间站会`；start_ts 推迟 30 分钟；color 切到 green。
3. 保存。
4. Android 端：
   - 触发即时同步（`adb shell cmd jobscheduler run -f ...`）后，
     再次跑场景 2 的 sqlite3 查询。
   - 同时在应用日历视图切到对应日期，看事件标题/颜色是否更新。

**期望**：
- Web Network 面板 `POST /api/v1/records/batch` 的请求 body 中 version=2
  （version 严格递增）、ciphertext 是新密文（旧密文已被远端版本竞争覆盖）。
- Android `event` 表对应 id 行：title = `晨间站会`；color = green；
  start_ts = 推迟后的值；dirty=0；updated_ts 取服务端权威 `updated_at`。
- 应用日历视图 title / 颜色无须刷新即刻反映（Flow 订阅 respond）。
- server `records` 表（`sqlite3 server.db 'SELECT id, version, updated_at
  FROM records WHERE id = "<uuid>";'`——若开发机有 sqlite3 可选）version=2，
  deleted=0。

**失败排查**：
- title 未更新 → EventBlock 取的是 `byId(id).data.title` 但订阅没生效；
  检查 v3 `useEventRulesStore().upsert` 后是否写回 reactive Map。
- version 仍是 1 → `existing?.version ?? 0` 取值错（首轮可能是 0）；
  排查 store upsert 函数 L260 附近。
- Android 端没拉到 v2 增量 → next 周期未触发；`maxUpdatedAt()` 还在 v1
  值（FU-1 已经覆盖）；手动 run Worker 验证。
- 应用视图出错：`Cannot read property 'data' of undefined` → tombstone
  走 `ingest` 删除条目后 `byId(id)` 返回 undefined；View 层应容错。

---

## 场景 4：删除（Web 删 → Android 拉）→ 双方都消失（tombstone）

**目标**：删除走 tombstone（`deleted=true`、ciphertext 置空），
与现有 records 通道一致；Android 在下轮拉取同步后本地 event 表行被删除。

**前置条件**：
- 场景 3 跑完（事件版本为 v2）。

**步骤**：
1. Web 端进入 /calendar，点击事件块打开编辑器。
2. 点击删除按钮（二次确认弹窗确认）。
3. Web 端 Network 面板观察 `POST /api/v1/records/batch`，body 包含一条
   `deleted=true`、`ciphertext=""`、`version=3` 的记录。
4. Web 端日历视图应立即移除该事件块（store.remove 调用 `pull()` 增量回拉
   成功后本地 Map.delete）。
5. Android 端触发即时同步（同场景 2 的 adb 命令），再跑 sqlite3 查询。

**期望**：
- Web Network 请求 body `module="event" type="event" deleted=true`。
- Web 日历视图已无该事件。
- Android `event` 表对应 id 行消失（`sqlite3 ... 'SELECT ...'` 返回 0 行）。
- Android Room v5 数据库结构不变（迁移已落盘，event 表 schema 仍 12 列）。
- server `records` 表对应 id 仍存在但 `deleted=1`、`version=3`、`ciphertext=""`。
- logcat 不出现异常；`SyncWorker` tag 无 `events sync failed`（若出现→tombstone
  ingest 删除后没调 eventDao.deleteById）。

**失败排查**：
- Android event 表行仍在 → `ingestOneRemote` 没把 `remote.deleted` 分支
  走到 `eventDao.deleteById`；回查 EventsRepository.kt 相关函数。
- Web 视图仍能看见事件块 → `rules.delete(id)` 未生效；多半是 `remove`
  里 `res.skipped === 0` 条件不满足（远端跳过）；检查返回值类型。
- 删除后 archive 仍能搜到：4a 没有任何 archive 概念，跳过本条。

---

## 场景 5：本地闹钟触发（**4b 不强制真机**，并入 FU-7 关闭条件）

**目标**：Android 端本地 AlarmManager 在 events 周期窗口内触发通知；
通知文案仅渲染 title + "N 分钟后开始"，不渲染原始 start_ts。

**前置条件（本场景关闭条件）**：
> 本场景在无真机/无模拟器环境**不强制执行**；并入 `.trae/specs/stage4b-calendar/
> tasks.md` 的 **FU-7 真机冒烟并入** 总清单与 4a FU-7 共同关闭条件。
>
> 真机可用时再做本场景；目标机型：Android 13+ 国产 ROM（如 MIUI / EMUI /
> OriginOS）以覆盖 `SCHEDULE_EXACT_ALARM` 权限收紧与杀后台场景。

**步骤**（真机/模拟器可用时执行）：
1. 创建一条事件：开始 = 当前 +90 秒；end = 当前 +5400 秒；reminders = `[0]`。
2. 不切后台等待，应用保持前台可见。
3. 到点后看通知栏是否弹出 banner，文案为 `<title>` + `即将开始` 摘要。

**期望**：
- 通知 channel "events" 触发；标题 = 事件 title；正文 = `即将开始` 或
  `N 分钟后开始`（**不出现** start_ts 数字、`YYYY-MM-DD HH:mm` 等原文、
  不出现坐标/备注）。
- logcat `ReminderReceiver` tag 不出现 title 原文；允许出现 `EXTRA_EVENT_ID`
  / `EXTRA_OCCURRENCE_TS` 等元数据。
- 触发后系统自调度下一闹钟：`adb shell dumpsys alarm | grep ReminderReceiver`
  应见一条新的 `RTC_WAKEUP` 行（request_code = `0x45564556`）。

**关闭条件（合并入 FU-7）**：
- 当前开发机无 Android 真机/模拟器；该场景与 4a 既有 FGS / 闹钟 /
  BootReceiver 等真机冒烟一并归 4b FU-7 总清单关闭。
- unit test 层 `ReminderSchedulerTest`（15 用例） + `EventsRepositoryTest`
  （10 用例）已覆盖核心纯函数与 Room 单元逻辑，JVM 内跑可。
- instrumented 层 `CalendarScreenTest`（8 用例）编译通过；真机跑待设备
  到位后由主会话评测端跑一次。

**失败排查**（真机可用时）：
- 通知未弹：检查 `POST_NOTIFICATIONS` 是否被允许（API 33+）。
- `Log.w SyncWorker events sync failed` 出现：`pullAndDecrypt` 或
  `rebuildChain` 异常；查 trace。
- 通知文案泄露 start_ts：`ReminderReceiver.kt` 文案构造逻辑回退到
  `Log.d(title, ...)` 模式。

---

## 场景 6：权限降级（SCHEDULE_EXACT_ALARM 拒绝 → 走 setAndAllowWhileIdle 写 event_reminder_log.kind=alarm_killed）

**目标**：API 31+ 用户拒绝 `SCHEDULE_EXACT_ALARM`（或 API 33+ 关闭
`USE_EXACT_ALARM`）后，调度降级为 `setAndAllowWhileIdle`；同时写入
一条 `event_reminder_log` 表，`kind=alarm_killed`，作为设置页 banner
"可能被系统限制"的依据。

**前置条件（本场景关闭条件）**：
- 同场景 5：当前无真机，并入 FU-7；具体真机步骤沿用 4a FU-7 关闭条件
  中"① FGS 权限流"列。
- 真机可用时：Android 12+ 设备；先在"系统设置 → 应用 → Everything →
  闹钟与提醒"关闭精确闹钟权限。

**步骤**（真机可用时）：
1. 创建一条事件：开始 = 当前 +120 秒；reminders = `[0]`。
2. 锁屏等待触发（或前台静默 120 秒）。
3. 检查通知是否降级（精度约 15 分钟，可能不弹准时 banner）。
4. 触发后查 `event_reminder_log` 表：
   ```bash
   adb shell run-as com.everything.eve \
     sqlite3 databases/eve.db \
     'SELECT id, event_id, occurrence_ts, kind, created_ts
        FROM event_reminder_log ORDER BY created_ts DESC LIMIT 5;'
   ```

**期望**：
- `event_reminder_log` 出现一行：`kind='alarm_killed'`、`event_id` =
  该事件 UUID、`occurrence_ts` = 触发时刻（约 start_ts - 0）；不出现
  title 原文。
- 在指定精确闹钟权限前（或权限被关后）：logcat `ReminderScheduler`
  tag 应无 `Log.w("SyncWorker", ...)`（事件链头单闹钟成功注册）；
  alarm_killed 仅来自权限被关路径。
- 通知功能降级：`setAndAllowWhileIdle` + 不弹横幅；用户进入应用看到
  "日历"设置页一次性 banner "可能被系统限制"。
- 关闭权限重新打开后再次创建事件：logcat `ReminderScheduler` tag 出现
  `setExactAndAllowWhileIdle` 调用（精度恢复），`event_reminder_log`
  不再写入 `alarm_killed`。

**关闭条件（同 FU-7）**：
- 当前无真机/模拟器。
- unit test 层 `ReminderSchedulerTest` 已覆盖 15 用例（含 reminders
  排序、零点边界、过期返回 null、Recurrence 重复下一触发、解析失败
  兜底）。

**失败排查**（真机可用时）：
- `event_reminder_log` 无新行 → `EventReminderLogDao.insertRaw` 漏调；
  回查 `RealAlarmScheduler.scheduleExact` 返回 false 的分支。
- 通知未降级但闹钟仍尝试精确 → `Build.VERSION.SDK_INT >= S` 判断缺失；
  检查分支条件。
- 通知弹了横幅 → `POST_NOTIFICATIONS` 被允许；本场景未拒绝；与降级
  无关，跳过排查。

---

## 通用排查清单

| 现象 | 可能原因 | 排查位置 |
|---|---|---|
| Web 端新建事件后服务端 records 表无对应行 | `eventsStore.upsert` 未推上行 | `web/src/stores/event-rules.ts` → upsert |
| Web 端新建事件后本地可见但 Android 拉不到 | WorkManager 未触发 / `pullAndDecrypt` 异常 | `android/app/.../sync/CollectorWorker.kt` |
| Android 应用启动后 calendar Tab 空白 | `eventsRepo` 注入失败 / Room v5 迁移未跑 | `ServiceLocator.init` + `EveDatabase` version 4→5 |
| 触发通知时崩溃 `ClassCastException`（NotificationChannel 未建） | minSdk=26 兜底未生效 | `ReminderScheduler` channel 创建时机 |
| 真机不弹通知 | `POST_NOTIFICATIONS` 权限被拒（API 33+） | `AndroidManifest.xml` + 应用首次启动 channel 注册 |
| 数据出现漂移（时区） | tz_mode=local 在跨时区旅行后展开漂移 | 接受行为，已文档化 |
| 服务端审计 grep 命中 title 原文 | sealRecord 参数错位 / 忘记走 records 通道 | `RecordsRepository.upsertEventRule` |
| Web localStorage 命中 event 明文 | `eventsStore` 误用 `localStorage.setItem` | NFR-1 红线，0 容忍；当前 store 仅内存 |

---

## 与 4a 既有冒烟的边界

- 不引入新的采集类别（4a `place` / `pass` / 轨迹 / location block 等独立）；
  events 走既有 `records` 通道。
- 不破坏 4a `CollectorWorker` 主路径：4a 既有的采集 + recordsRepo.sync()
  + locationPackager + locationUploader 五段流程**一行未删**，本批仅在
  末尾追加一段 try-catch 包裹的事件模块拉取 + 闹钟重建。
- 不破坏 Web `vault.sync`：4a 既有的 places/notes/card/identity 同步路径
  **一行未删**，本批仅在末尾追加一段 try-catch 包裹的
  `useEventRulesStore().pullAll(...)`。

---

## 关闭条件（沿用 4a FU-7 总清单 + 本批新增）

无真机/模拟器情况下，以下场景一并归入 `.trae/specs/stage4b-calendar/tasks.md`
`## FU-7 真机冒烟并入` 总清单，由 4b 与 4a 联合推进时关闭：

1. 场景 5（本地闹钟触发真机验证）；
2. 场景 6（SCHEDULE_EXACT_ALARM 拒绝后降级真机验证）；
3. 4b instrumented 三套件真机运行（`RecoveryEnvelopeVectorTest` /
   `MigrationTest` / `CollectorEngineAndroidTest` / `LocationPackagerAndroidTest`
   / `LocationUploaderAndroidTest` + 4b 新增的 `EventsRepositoryTest` /
   `CalendarScreenTest`）；
4. 包体安装 / 4a FGS 流 / BootReceiver 开机自愈覆盖 4b 新增 `rebuildChain`。

主会话可在真机/模拟器到位后由评测端拉一次全量 `connectedDebugAndroidTest`
+ 真机走查 5+6 关闭本清单。
