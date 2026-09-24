# 安卓端说明

## 环境要求

- JDK 17
- Android SDK：compileSdk/targetSdk 35，minSdk 26（Android 8.0）
- Gradle 8.11.1（仓库已带 wrapper，无需本机安装 Gradle）

## 构建

```bash
cd android
./gradlew :app:assembleDebug      # APK：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug       # 连接真机/模拟器后直接安装
```

> 中国大陆网络可在 `~/.gradle/init.gradle.kts` 配置 Maven 镜像（阿里云/腾讯云）。

## 连接服务端

- Android 模拟器访问宿主机：默认 `http://10.0.2.2:8787`（登录页可改）。
- 真机：填电脑局域网 IP，如 `http://192.168.1.10:8787`，确保防火墙放行。
- 当前允许明文 HTTP（`usesCleartextTraffic=true`）仅用于局域网调试；
  正式使用请走 HTTPS 域名并关闭该选项。

## 现有能力（阶段 1 完成 + 阶段 2 同步兼容 + 阶段 3 采集器 + 阶段 4a 位置轨迹 + 阶段 4b 日程/日历）

- 注册 / 登录解锁（lazysodium Native API：Argon2id、XChaCha20-Poly1305 信封，与 Go/Web 逐字节互通）
- 登录三态分流：已批准直入 / 待审批轮询配对码 / 需 TOTP 二步验证
- 注册强制备份恢复密钥（Crockford Base32）；恢复向导可重置主密码并轮换恢复码
- 设备审批端：查看待审批配对（批准并端到端下发主密钥 / 拒绝）、吊销设备
- 加密笔记：Room 离线存储（显式迁移 `Migration(1,2)`/`Migration(2,3)`）→ 推送 → 增量拉取，记录上次同步时间
- 令牌存于 EncryptedSharedPreferences（含设备 X25519 seed）；主密钥仅存内存，重启需重新解锁
- 401 单飞刷新：访问令牌过期自动刷新重试，刷新失败回登录页
- 周期任务：唯一周期任务 `eve.collector-sync` 每 15 分钟"采集 + 同步"（联网约束 + 指数退避），本地变更立即触发一次
- 数据采集（阶段 3）：通讯录/短信/通话记录只读采集——复合游标增量、结构比对变化检测（version+1 重封）、采集页权限向导与五态状态行（详见下章）
- 位置轨迹（阶段 4a）：前台定位服务持续采集 → 本机缓冲封块加密 → 密文块经 `/api/v1/locations/batch` 上行（详见"位置轨迹采集（阶段 4a）"章）
- 日程/日历（阶段 4b）：事件作为 `module="event"` 记录走既有 records 信封通道；月/周视图、本地 AlarmManager exact 闹钟（权限降级见下章）、RRULE B 档子集；详见"日程/日历（阶段 4b）"章
- 财务 AI 联动记账（阶段 5 v2 B8）：OCR 小票扫描（CameraX 1.4.2 + ML Kit text-recognition 16.0.1 自包含 AAR）+ 语音记账（系统 `SpeechRecognizer` on-device），均**仅作为编辑器预填 hint**，用户手动确认才入既有加密链路；详见"AI 联动记账（OCR + 语音；阶段 5 v2 B8）"章

## 数据采集（阶段 3）

### 权限用途与申请时机

- Manifest 仅声明三个**只读**权限：`READ_CONTACTS` / `READ_SMS` / `READ_CALL_LOG`（无任何写权限）。
- 运行时申请发生在采集页**逐类开启开关**时：只申请该类对应的那一个权限；
  永久拒绝（不再询问）后显示"去系统设置开启权限"按钮。
- 未授权的类别由引擎记跳过原因后略过，不影响其他类别与同步推送。

### 合规告知与行为限制

采集页顶部常驻合规告知卡片，要点（与应用内文案一致）：

- 数据在本机**端到端加密后仅存入用户自己部署的服务器**，服务端无法读取明文；
- **行为限制 1——非实时**：采集约每 15 分钟执行一次（WorkManager 周期下限），并非实时同步；
- **行为限制 2——不跟随系统删除**：在系统中删除短信/联系人/通话记录，不会删除已采集的加密副本；
- **未锁定不采集**：主密钥仅存内存，应用未解锁（MK 不在内存）时跳过全部采集扫描
  （记 `mk_unavailable`），但仍照常推送已加密的本地记录；
- 每类采集可随时单独关闭，另有周期采集总开关。

### 电池优化与国产 ROM 保活指引

采集页"保持后台采集"区提供：请求忽略电池优化（带降级兜底）与应用详情设置入口。
各厂商还需手动允许自启动/后台运行：

| 厂商 | 设置路径（供参考，不同版本文案略有差异） |
|---|---|
| 小米（MIUI/澎湃） | 设置 → 应用设置 → 应用管理 → 本应用 → 省电策略「无限制」+ 自启动开启 |
| 华为（EMUI/鸿蒙） | 设置 → 应用和服务 → 应用启动管理 → 本应用 → 关闭自动管理并允许自启动/后台活动 |
| OPPO（ColorOS） | 设置 → 电池 → 后台耗电管理 → 本应用 → 允许后台运行；手机管家 → 自启动管理开启 |
| vivo（OriginOS/Funtouch） | 设置 → 电池 → 后台耗电管理 → 本应用 → 允许后台高耗电；i 管家 → 自启动开启 |
| 荣耀（MagicOS） | 设置 → 应用 → 应用启动管理 → 本应用 → 关闭自动管理并允许自启动/后台活动 |
| （阶段 4a 补充）后台定位 | 定位权限须选「始终允许」：设置 → 应用 → 本应用 → 权限 → 位置信息 → 始终允许；勿在任务管理中划掉本应用（前台服务被杀即停采） |

### 采集相关测试

```bash
# instrumented 测试需真机/模拟器（含 CollectorEngineAndroidTest）：
./gradlew :app:connectedDebugAndroidTest
# 引擎测试自建内存 Fake 数据源，不依赖设备真实短信/联系人；
# 权限用例经 UIAutomator shell 执行 pm grant/revoke，无需手工预授权。
```

## 位置轨迹采集（阶段 4a）

### 权限用途与申请时机

Manifest 恰声明六项定位相关权限（`AndroidManifest.xml`，无 `ACTIVITY_RECOGNITION` 等）：

| 权限 | 用途 | 申请时机 |
|---|---|---|
| `ACCESS_FINE_LOCATION` | 精确轨迹点采集（GPS / 网络 provider） | 开启轨迹采集开关时运行时申请；运行中被撤销即停采（记 `fine_denied`） |
| `ACCESS_COARSE_LOCATION` | 与 FINE 成对声明（系统要求） | 随 FINE 一并申请 |
| `ACCESS_BACKGROUND_LOCATION` | 应用退后台后持续采集 | **仅 Manifest 声明，不伪造运行时弹窗**：API 29+ 须由用户去系统设置页将定位权限选为「始终允许」（未选则记 `background_denied` 停采） |
| `FOREGROUND_SERVICE` | 前台服务基础声明 | Manifest 声明即可 |
| `FOREGROUND_SERVICE_LOCATION` | 定位类前台服务类型声明（API 34+ 必需） | Manifest 声明即可 |
| `POST_NOTIFICATIONS` | 前台服务常驻通知可见 | Android 13+ 运行时申请 |

未授权即停采，不影响其他采集类别与同步推送；停止原因持久化为枚举 wireName 供 UI 展示。

### 前台服务行为

- **启动前置四分支**（`TrackStartCheck.blockedReason` 纯函数）：轨迹开关开 && MK 在内存
  && FINE 已授权 && 后台定位「始终允许」；任一不满足记枚举原因后 `stopSelf`，不弹窗打扰。
- **常驻通知**：channel `location_tracking`（LOW 重要性，不发声不震动），文案仅
  "轨迹采集中 · 今日 N 点"（今日计数随收点刷新），**不含任何坐标**；点击回主界面。
- **provider 选择**：GPS 优先，GPS 不可用时 NETWORK 兜底；两者都不可用则挂起等待
  `onProviderEnabled` 回调自动恢复。
- **采样参数与静止降频**：`minDistance` 恒 25m；`minTime` 常规 60s，10 分钟滚动窗口内
  最大两两位移 < 50m 判定静止后降为 300s；每次收点后重算，档位变化时重新注册监听。
- **精度过滤**：无精度或精度半径 >100m 的点直接丢弃，不入库。

### 缓冲与过期纪律

- 明文坐标唯一驻留点是 Room `location_points` 表；`location_outbox` 只存密文块。
- **封块即删**：块加密封存成功后，按块覆盖的最大 ts 上界批量删除对应明文行。
- **24h 过期**：超过 24 小时未封块的点强制删除（滞留明文红线兜底）。
- 坐标明文禁入日志 / 通知文案 / SharedPreferences / 异常消息；全链路结果模型
  只含计数与枚举原因。

### MK 不可用停采

- 启动前置检查即含 MK 分支：MK 不在内存（未解锁）时服务记 `mk_unavailable` 后自行退出。
- 运行中监听解锁态：MK 变 null（锁定 / 退出登录）即注销定位监听、退出前台并停止服务。
- 解锁恢复挂钩：MainActivity 解锁成功后再次拉起服务。

### BootReceiver 开机自愈与降级

- 开机广播（`BOOT_COMPLETED`）后若轨迹开关开，尝试拉起前台服务；
  四分支前置检查由服务自身 `onStartCommand` 复核（MK 未解锁时服务记原因后自行退出，
  等待解锁恢复挂钩再次拉起）。
- **降级约定**：API 31+ 后台启动前台服务限制（`BackgroundServiceStartNotAllowedException`）
  与低版本同语义 `IllegalStateException` 两路异常**只吞不抛**，静默降级为
  "等待下次解锁 / 启动"——开机广播中崩溃会导致系统弹窗，必须避免。
- 阶段 3 的周期采集任务由 WorkManager（KEEP 策略）注册，系统重启后自动恢复，
  不经本接收器。

### 轨迹相关保活说明

轨迹采集沿用阶段 3 五厂商保活表（自启动 / 后台运行 + 忽略电池优化），该表已追加
"后台定位"行：定位权限须在系统设置页选「始终允许」，且勿在任务管理中划掉本应用。
前台服务被杀即停采；轨迹开关开启状态下，重启手机经 BootReceiver 自愈，
重启应用 / 解锁经恢复挂钩拉起。

## 日程/日历（阶段 4b）

阶段 4b 在 Android 端落地"日历 + 编辑器 + 本地精确闹钟"全链路，沿用 records 加密
信封（同款 `sealRecord` / `openRecord`，`module="event"`），服务端零改动。

### 屏幕/页面清单

- **CalendarScreen**（`ui/screens/CalendarScreen.kt`）：视图切换容器，月/周
  RadioButton 切换 + 翻页/今天/FAB；主体 `MonthGrid` / `WeekGrid` 二选一；
  点击空格调 `vm.openNewEvent(startMs)` 打开 `EventEditorScreen` 新建分支，
  点击事件块调 `vm.openEditEvent(entityId)` 打开编辑分支（含二次确认删除
  AlertDialog）。
- **EventEditorScreen**（`ui/screens/EventEditorScreen.kt`）：事件编辑器，
  字段与 Web `EventEditorDialog` 12 字段一一对齐（标题 / 起止 / 全天 / 地点 /
  备注 / 颜色 8 色 / reminders ≤3 / rrule B 档 / exdates）；内嵌
  `RRuleBuilder`（NONE / DAILY / WEEKLY / MONTHLY / YEARLY 五档单选 +
  interval ≥1 + WEEKLY 7 工作日多选 + MONTHLY 单 weekday + 结束 Never /
  Until-date / Count-n 三选一）；校验（title 空 → btn_save disabled；
  start_ts ≥ end_ts 弹错；reminders > 3 弹错）。
- 辅助组件：`MonthGrid`（6×7 日历格）/ `WeekGrid`（7 列 × N 小时槽）/ 
  `EventBlock`（事件块按 `color` 着色，contentDescription 不含 title 原文，
  严守零知识纪律）。

### Worker / 后台任务清单

- **CollectorWorker**（4a 既有，4b 扩展）：`doWork()` 末尾追加一段
  `try { if (ServiceLocator.auth.masterKey != null) { 
  val sinceMs = ServiceLocator.db.recordDao().maxUpdatedAt();
  ServiceLocator.eventsRepo.pullAndDecrypt(sinceMs);
  ReminderScheduler.rebuildChain(applicationContext) } } catch (t:
  Throwable) { Log.w(...) }`。4a 既有"采集 + recordsRepo.sync() +
  locationPackager + locationUploader"五段流程一行未删，本批仅末尾追加。
- **ReminderReceiver**（4b 新增）：收到全局 PendingIntent 后拉事件
  → `NotificationCompat.Builder(ctx, "events")` 渲染（**仅 title +
  "即将开始 / N 分钟后开始"抽象文案**，note / start_ts 原文不渲染）
  → 重算 `nextTrigger` → 续接下一实例。
- **BootReceiver**（4a 既有，4b 扩展）：开机广播后除原有轨迹 / 同步分支外，
  在同步任务触发分支前追加 `try { ReminderScheduler.rebuildChain(ctx) }
  catch (t: Throwable) { Log.w(...) }` 路径，4a 既有逻辑不破坏。

### 本地闹钟机制

- **首选路径**：`AlarmScheduler.setExactAndAllowWhileIdle(RTC_WAKEUP, ts,
  pi)`，由 `ReminderScheduler.scheduleNext` 在权限可用时调用，PendingIntent
  指向 `ReminderReceiver`；单 requestCode `0x45564556` = "EVEEV"，
  extras.eventId 携带事件 id。
- **权限降级路径**：先 `AlarmManager.canScheduleExactAlarms()` 检测，被拒时
  降级 `setAndAllowWhileIdle(...)`（inexact，仍走 wakeup 路径）并写
  `event_reminder_log.kind = "alarm_killed"` 留可观测痕迹；通知权限
  `POST_NOTIFICATIONS` 被拒时不弹横幅，仅 `Log.w + 
  eventReminderLogDao.insertRaw(..., "notification_denied", ...)` 兜底。
- **链式续接**：`ReminderReceiver.onReceive` 触发后立刻重算 nextTrigger
  并 `scheduleNext` 续接下一实例；重复事件通过 `Recurrence.expand` 在
  `LOOKAHEAD_MS` 窗口内滚动展开，不物化重复实例。
- **doze + 厂商后台限制**：Android 12+ `BackgroundServiceStartNotAllowedException`
  与 doze / 各厂商后台限制已记入 README "已知问题"清单；本批不修复，4b 仅做
  降级 + 留痕。

### Room v4→v5 扩展

- `EventEntity`（12 列 snake_case：`id / title / start_ts / end_ts / 
all_day / tz_mode / location_text / note / color / reminders_json / 
rrule_json / exdates_json / dirty / updated_ts`）+ 双索引
`Index("start_ts")` / `Index("dirty")`；`tz_mode` `@ColumnInfo(defaultValue=
"local")`、`exdates_json` `@ColumnInfo(defaultValue="[]")` 兜底。
- `EventReminderLogEntity`（自增 INTEGER PRIMARY KEY `id` + `event_id` +
  `occurrence_ts` + `kind` + `created_ts`），`kind` 三枚举 `alarm_killed` /
  `notification_denied` / `exact_denied` 与 spec FR-5 对齐。
- `Migration(4, 5)`：显式两步 `CREATE TABLE IF NOT EXISTS event ...` →
  `CREATE INDEX IF NOT EXISTS index_event_start_ts ON event(start_ts)` →
  `CREATE INDEX IF NOT EXISTS index_event_dirty ON event(dirty)` →
  `CREATE TABLE IF NOT EXISTS event_reminder_log ...`；与 4a v3→v4 同模式
  （CREATE TABLE IF NOT EXISTS，不 ALTER / DROP 既有 records / places /
  locations 五表）。

### 权限（阶段 4b 新增项）

| 权限 | 用途 | 申请时机 |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` | Android 12+（API 31+）精确闹钟（用户可在系统设置页授权/拒绝） | Manifest 声明；运行时由 `canScheduleExactAlarms()` 判定；用户拒绝即降级为 inexact |
| `USE_EXACT_ALARM` | Android 13+（API 33+）应用类别允许的精确闹钟声明 | Manifest 声明即可 |
| `POST_NOTIFICATIONS` | Android 13+（API 33+）通知可见性（已声明，阶段 4a 沿用） | 运行时申请；被拒时仅写 `event_reminder_log.kind="notification_denied"`，不弹横幅 |

`POST_NOTIFICATIONS` 在阶段 4a 已声明，4b 沿用不再重复登记。

## 财务模块（阶段 5）

阶段 5 财务 v1 在 Android 端落地"账户 / 银行卡 / 日常记账 + 账单日 / 还款日
提醒"全链路。沿用 records 加密信封（同款 `sealRecord` / `openRecord`，
`module="finance"`），服务端零改动；新增 Room v5→v6 显式迁移 + 单闹钟链式
调度复用 + FinanceRepository 主链路。本节为阶段 5 实施落地说明，独立模块
文档（聚合规则 / 月报 / 资产看板 / Luhn / 提醒触发 / v2 钩子）见
[`finance.md`](finance.md)。

### 屏幕 / 页面清单

- **FinanceScreen**（`ui/screens/FinanceScreen.kt`）：财务总览容器，顶栏 4 个
  Tab（仪表盘 / 账户 / 银行卡 / 流水）；账户 / 卡 / 流水列表均按 `updated_at`
  升序 / `occurred_at` 降序展示；点击空位调 `vm.openNewAccount/Card/Tx()`
  打开 `AccountEditorDialog` / `CardEditorDialog` / `TxEditorDialog`；
  点击条目打开 `Routes.FINANCE` 编辑器（含删除 AlertDialog）。
- **AccountEditorDialog**：11 字段编辑器（name / kind 五选一 / currency / 
  balance / note / icon / color / archived / created_at / updated_at），
  与 Web `AccountEditorDialog.vue` 逐字段一致；校验（name 空 → btn_save 
  disabled；balance 非 decimal-as-string 弹错）。
- **CardEditorDialog**：21 字段编辑器（含 credit_limit / used_limit /
  billing_day / due_day / brand / expiry_month / expiry_year / holder /
  last4 等），UI 录入完整卡号 → Luhn 校验 → 仅保留后四位（详见下方
  "Luhn 校验 + 仅后四位入库纪律"小节）。
- **TxEditorDialog**：18 字段编辑器（含 account_id / card_id / transfer_to_ 
  account_id 三组关联字段）；`kind` 三选一（income / expense / transfer）；
  transfer 时强制 `transfer_to_account_id != account_id`；amount 一律正数。

### FinanceRepository 接入

[`FinanceRepository`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/data/finance/FinanceRepository.kt)
作为财务模块的领域仓库，**双写**本地明文 + records 密文（与 4a `createNote`
+ 4b `upsertEventRule` 同款模式）：

- **入口**：
  - UI / ViewModel：`upsertAccount/Card/Tx` + `deleteAccount/Card/Tx`（墓碑删除）+ 
    `observeAccounts/Cards/Txs()`；
  - CollectorWorker / SyncWorker：`pullAndDecrypt(moduleRecords)` 下行解密
    入库 + dirty 翻 false；
  - ReminderScheduler.rebuildChain：`observeCards().first()` 拉全局最小
    nextTrigger（详见 [`ReminderScheduler.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/reminder/ReminderScheduler.kt)
    `rebuildChain` 阶段 5 扩展段）。
- **明文 + 密文双写**：`upsertAccount` 等方法**同步执行两步**：
  1) 写本地明文 `finance_account` / `finance_card` / `finance_tx` 表；
  2) 调 `recordsRepository.upsertFinanceAccount/Card/Tx(id, plaintextJson)`
     把同一 id 的密文落 `records` 表，模块=finance，类型=account/card/tx，
     dirty=true。
- **墓碑语义**：删除走 tombstone 软删（`deleted=true` + dirty=true）；
  关联账户 / 卡被删除后历史流水的 `account_id` / `card_id` **保留**原引用
  （删除与保留红线）。
- **Luhn 校验 + 仅后四位入库纪律**：完整卡号**不入** Room / ciphertext
  载荷；入参 entity 仅含 `last4`（编辑器契约）。详见 [`Luhn.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/Luhn.kt)
  与 [`finance.md`](finance.md) §5。

### Room v5→v6 迁移

[`EveDatabase.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/data/EveDatabase.kt)
`version = 6`，companion object 内嵌 `MIGRATION_5_6` 显式迁移，与 4a v3→v4 /
4b v4→v5 同模式：仅 `CREATE TABLE IF NOT EXISTS` + 索引，**不 ALTER / DROP**
既有七表（records / sync_state / collector_state / location_points /
location_outbox / event / event_reminder_log），保证既有数据零影响。

四表 schema 与索引：

#### finance_account（14 列）

- `id TEXT NOT NULL PRIMARY KEY`（UUID）；
- `name TEXT NOT NULL` / `kind TEXT NOT NULL`（5 枚举：cash / deposit / 
  stock / wallet / other）/ `currency TEXT NOT NULL`；
- `balance TEXT NOT NULL`（decimal-as-string）/ `note TEXT` / `icon TEXT` / 
  `color TEXT`；
- `archived INTEGER NOT NULL DEFAULT 0` / `created_at INTEGER NOT NULL` / 
  `updated_at INTEGER NOT NULL`；
- `schema_version INTEGER NOT NULL DEFAULT 1` / `module TEXT NOT NULL 
  DEFAULT 'finance'` / `type TEXT NOT NULL DEFAULT 'account'` / 
  `dirty INTEGER NOT NULL DEFAULT 1` / `deleted INTEGER NOT NULL DEFAULT 0`。
- 索引：(updated_at) 服务增量同步游标；(dirty) 服务同步推送对账。

#### finance_card（21 列）

- 主键：`id TEXT NOT NULL PRIMARY KEY`；
- 业务字段：`name` / `kind`（debit / credit 2 枚举）/ `issuer` / `last4` 
  （仅后四位数字串，**完整 PAN 不入库**）/ `currency` / `credit_limit` 
  / `used_limit` / `billing_day` / `due_day` / `brand`（BIN 段推断：visa 
  / master / unionpay / amex / jcb / discover / unknown）/ `expiry_month` 
  / `expiry_year` / `holder` / `note` / `icon` / `color`；
- 状态：`archived INTEGER NOT NULL DEFAULT 0`；
- 时间戳：`created_at INTEGER NOT NULL` / `updated_at INTEGER NOT NULL`；
- 系统字段：`schema_version INTEGER NOT NULL DEFAULT 1` / `module TEXT NOT 
  NULL DEFAULT 'finance'` / `type TEXT NOT NULL DEFAULT 'card'` / 
  `dirty INTEGER NOT NULL DEFAULT 1` / `deleted INTEGER NOT NULL DEFAULT 0`。
- 索引：(updated_at) 服务增量同步游标；(dirty) 服务同步推送对账。

#### finance_tx（18 列）

- 主键：`id TEXT NOT NULL PRIMARY KEY`；
- 业务字段：`account_id TEXT NOT NULL`（外键到 account.id；账户删除后保留
  引用，UI 标注"账户已删除"）/ `card_id TEXT`（可选）/ `kind TEXT NOT NULL` 
  （income / expense / transfer 三枚举）/ `amount TEXT NOT NULL`（decimal-as-
  string，正数）/ `currency TEXT NOT NULL` / `category TEXT NOT NULL` / 
  `occurred_at INTEGER NOT NULL` / `note TEXT` / `icon TEXT` / `color TEXT` / 
  `transfer_to_account_id TEXT`（transfer 时必填，不能等于 account_id）；
- 时间戳：`created_at INTEGER NOT NULL` / `updated_at INTEGER NOT NULL`；
- 系统字段：`schema_version INTEGER NOT NULL DEFAULT 1` / `module TEXT NOT 
  NULL DEFAULT 'finance'` / `type TEXT NOT NULL DEFAULT 'tx'` / 
  `dirty INTEGER NOT NULL DEFAULT 1` / `deleted INTEGER NOT NULL DEFAULT 0`。
- 索引：(updated_at) 服务增量同步游标；(occurred_at) 服务按时间排序与
  月报聚合；(account_id) 服务按账户过滤；(dirty) 服务同步推送对账。

#### finance_reminder_log（4 列 + 自增主键）

- `id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`（自增主键）；
- `ref_id TEXT NOT NULL`（finance 条目 id，v1 即 card.id）；
- `ref_kind TEXT NOT NULL`（`card_statement_due` / `card_payment_due` 
  v1 启用；v2 占位后三类：`subscription_renewal` / `policy_expiry` / 
  `loan_due`）；
- `fire_at INTEGER NOT NULL`（触发时刻 Unix 毫秒）；
- `delivered INTEGER NOT NULL DEFAULT 0`（0/1；通知是否成功投递；
  POST_NOTIFICATIONS 拒绝时 = 0）。
- 索引：(fire_at) 服务按时间排序；(ref_id, ref_kind) 服务按引用查询。
- **零知识纪律**：严禁写 title / amount / last4 等明文（NFR-1 红线）。

### Scheduler 复用与 module 路由

财务提醒**完全复用**阶段 4b `ReminderScheduler`，**不新建**第二个 Scheduler。
实现位于 [`ReminderScheduler.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/reminder/ReminderScheduler.kt)，
关键扩展点：

- **单闹钟 requestCode** `0x45564556` = "EVEEV" hex，event + finance 共用
  （spec NFR-4 约束）；
- `rebuildChain(ctx)` 阶段 5 扩展：合并事件 + 财务两类触发，取全局最小
  `nextTrigger` 写**单闹钟**；**严禁**新建第二条调度链路；
- Intent extras 新增 `EXTRA_MODULE` / `EXTRA_REF_KIND` / `REF_ID` 三字段：
  - `MODULE_EVENT = "event"` / `MODULE_FINANCE = "finance"`；
  - `REF_KIND_CARD_STATEMENT_DUE = "card_statement_due"` / 
    `REF_KIND_CARD_PAYMENT_DUE = "card_payment_due"`；
- `LOOKAHEAD_MS = 14 天` / `MAX_MONTH_LOOKAHEAD = 24 个月`（与 4b 同款）；
- `REMINDER_CHANNEL_ID = "events"`（与事件共用，不新建 channel）。

`ReminderReceiver.onReceive` 按 `module` 字段路由：

| module | DAO | 通知文案 |
|---|---|---|
| `"event"`（或缺失，4b 兼容） | `EventDao` | title + "即将开始 / N 分钟后开始" 抽象文案 |
| `"finance"` | `FinanceCardDao` | 抽象文案（账单 / 还款），**不渲染金额 / 卡号后四位 / 具体日期数字** |

**零知识红线**：通知文案**绝不**渲染金额 / 卡号后四位 / 具体日期数字。
通知 id 用 `cardId.hashCode()`（同一卡片覆盖，不同卡片并行）。
channelId = `"events"`（与事件共用，不新建 channel）。

通知文案模板（v1 启用）：

| `ref_kind` | 文案模板 |
|---|---|
| `card_statement_due` | "💳 信用卡账单已生成" |
| `card_payment_due` | "💳 信用卡还款临近" |

跳转路由携带 `record_id` + `record_kind`，路由至 `Routes.FINANCE` 编辑器。

### Worker / 后台任务清单

- **CollectorWorker**（4a 既有，4b 扩展，5 沿用）：`doWork()` 末尾追加
  `financeRepo.pullAndDecrypt(moduleRecords)`（与 eventsRepo.pullAndDecrypt 
  同款骨架，但仅处理 module="finance" 的 records）；4a 既有"采集 + 
  recordsRepo.sync() + locationPackager + locationUploader"五段流程一行未删，
  本批仅末尾追加。
- **ReminderReceiver**（4b 新增，5 扩展）：收到全局 PendingIntent 后按
  `module` 字段分支：
  - `module="event"` → 4b 既有路径（拉 EventDao → 渲染 title + 抽象文案
    → 重算 nextTrigger → 续接下一实例）；
  - `module="finance"` → 拉 FinanceCardDao → 按 `ref_kind` 渲染抽象通知
    → 重算 nextCardFiring → 续接下一实例。
  通知文案**绝不**渲染金额 / 卡号后四位 / 具体日期数字（NFR-1 红线）。
- **BootReceiver**（4a 既有，4b 扩展，5 无新增改动）：开机广播后除原有轨迹
  / 同步分支外，在轨迹分支前追加 `try { ReminderScheduler.rebuildChain(ctx) }
  catch (t: Throwable) { Log.w(...) }` 路径——`rebuildChain` 内部已合并
  event + finance 两类触发，**5 阶段无额外改动**。4a 既有"BackgroundServiceStart
  NotAllowedException / IllegalStateException 两路异常只吞不抛"约定与 4b
  既有 try-catch 降级一并沿用。

### 权限（阶段 5 沿用既有 + 阶段 5 v2 B8 扩展）

| 权限 | 用途 | 申请时机 |
|---|---|---|
| `POST_NOTIFICATIONS` | Android 13+（API 33+）通知可见性（已声明，4a 沿用） | 运行时申请；被拒时仅写 `finance_reminder_log.delivered = 0`，不弹横幅 |
| `CAMERA`（B8 新增） | OCR 小票扫描取流（CameraX + ML Kit text-recognition） | OCR Sheet `LaunchedEffect` 自动申请；首次拒绝隐藏入口 + 引导文案，不阻断核心记账 |
| `RECORD_AUDIO`（B8 新增） | 语音记账本地识别（系统 `SpeechRecognizer` on-device） | 语音 Sheet 进入时申请；未授权显示 `permission_blocked` testTag + 「麦克风权限不足」文案 |
| `android.hardware.camera.any`（feature，B8 新增） | 声明可选硬件能力 | `required=false`，无相机设备仍可安装，仅 OCR 入口不可用 |

阶段 5 **不新申请**任何运行时权限；账单 / 还款日通知文案仅含抽象描述，
不暴露金额 / 卡号后四位 / 具体日期数字。

B8 沿用此纪律——OCR / 语音识别文案仅含金额 / 日期 / 商家（OCR）/ 分类（语音）
四类字段，**绝不**扩散原文 / 识别结果到通知或日志。

## AI 联动记账（OCR + 语音；阶段 5 v2 B8）

阶段 5 v2 B8 在 Android 端**新增** OCR 小票扫描 + 语音记账两条辅助录入
路径，**仅作为编辑器预填 hint**，用户**必须**在 `FinanceEditor` 中手动
确认才落入既有加密链路。Web 端**明确不做** OCR / 语音联动，本节为
Android-only 描述。

### 架构四层

| 层 | 组成 | 职责 |
|---|---|---|
| ① 纯函数层 | [`OcrParser.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/OcrParser.kt) / [`SpeechParser.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/SpeechParser.kt) | 文本 → 结构化 hint（金额 / 日期 / 商家 / 分类），纯函数零依赖，JUnit 直接覆盖 |
| ② 引擎层 | [`OcrScannerEngine.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/OcrScannerEngine.kt) / [`SpeechRecorderEngine.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/SpeechRecorderEngine.kt) | 硬件 / 系统能力封装：CameraX + ML Kit、SpeechRecognizer lifecycle |
| ③ Compose Sheet | [`OcrScannerSheet.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/ui/finance/OcrScannerSheet.kt) / [`SpeechRecorderSheet.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/ui/finance/SpeechRecorderSheet.kt) | ModalBottomSheet 状态机：`Idle / Recognizing / Result / NoResult / Error` |
| ④ ViewModel | [`FinanceViewModel.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/ui/finance/FinanceViewModel.kt) | 独立 `AiHintState` StateFlow（**不进** 14 路 combine 主 state），`applyReceiptHintToBuffer` / `applySpeechHintToBuffer` 两 action |

### 新增依赖（libs.versions.toml + build.gradle.kts）

| 依赖 | 版本 | 用途 | 范围 |
|---|---|---|---|
| `androidx.camera:camera-core` | 1.4.2 | CameraX 核心 | implementation |
| `androidx.camera:camera-camera2` | 1.4.2 | CameraX Camera2 后端 | implementation |
| `androidx.camera:camera-lifecycle` | 1.4.2 | CameraX 生命周期绑定 | implementation |
| `androidx.camera:camera-view` | 1.4.2 | `PreviewView` Compose 适配 | implementation |
| `com.google.mlkit:text-recognition` | 16.0.1 | ML Kit 中文识别器（自包含 AAR） | implementation |
| `org.robolectric:robolectric` | 4.14.1 | JVM Compose 测试 | testImplementation |

**纪律**：ML Kit **不引入** GMS / Firebase / 第三方云 SDK；SpeechRecognizer
**不引入** Google 应用内语音服务；OCR / 语音识别全程设备本地。

### AndroidManifest 变更

`android/app/src/main/AndroidManifest.xml` 追加：

```xml
<!-- B8 AI 联动记账：OCR + 语音 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature
    android:name="android.hardware.camera.any"
    android:required="false" />
```

**权限合规**：CAMERA / RECORD_AUDIO 走 Compose
`rememberLauncherForActivityResult` + `LaunchedEffect` 运行时申请；用户
拒绝仅隐藏入口 / 显示说明，**不阻断**核心记账（仍可手动录入）。

### Robolectric 落地关键经验

B8 在测试基建层沉淀了四条关键经验，已作为后续 Android UI 测试模板：

1. **不开 `includeAndroidResources`**：工程默认未启用；测试类统一
   `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [33], manifest = Config.NONE)`；
2. **必须覆盖空 Application**：在 `@Config(application = TestOnlyApplication::class)`
   指定 `class TestOnlyApplication : Application()`（**不重写 onCreate**）；
   否则 Robolectric 按合并清单创建真实 `EveApplication`，其 onCreate 初始化
   `ServiceLocator` / `AndroidKeyStore`，JVM 沙箱无该 Provider 抛 `KeyStoreException`；
3. **ModalBottomSheet 触摸注入**：Robolectric 下 `performClick()` 的真实
   触摸注入无法分发到独立 Dialog 窗口（节点显示但 onClick 不触发）；
   改用扩展 `performSemanticsAction(SemanticsActions.OnClick)` 触发真实
   onClick；
4. **节点可见性断言改 `assertExists()`**：Robolectric 下节点布局边界可能
   始终位于可视区域外（与第 3 条相伴），组件可见性断言统一改用
   `assertExists()`，放弃 `assertIsDisplayed()`（节点已存在于语义树即视为
   可访问）。

### ImageProxy / ImageInfo 桩要点（CameraX 1.4.x）

CameraX 1.4.x 中 `ImageProxy` 为**抽象类**（`Unsafe.allocateInstance` 对
抽象类抛 `InstantiationException`），单元测试必须实现全部抽象方法：

- `ImageProxy` 抽象成员：`close / getWidth / getHeight / getCropRect /
  setCropRect / getImageInfo / getImage / getFormat / getPlanes`；
- `ImageInfo` 抽象成员：`getRotationDegrees / getTimestamp(Long) /
  getTagBundle / populateExifData(ExifData.Builder)`；
- `PlaneProxy` 是嵌套类型 `ImageProxy.PlaneProxy`（**不能**从顶层包 import）。

源码参考：
[`OcrScannerSheetTest.kt`](file:///d:/github/everything/everything/android/app/src/test/java/com/everything/eve/ui/finance/OcrScannerSheetTest.kt)
的 `fakeImageProxy()` 私有方法。

### 关键源文件清单

| 文件 | 行数 | 备注 |
|---|---|---|
| `android/app/src/main/java/com/everything/eve/finance/OcrParser.kt` | — | 金额启发式（关键词行优先、BigDecimal 转分、千分位、电话排除）、多格式日期、商家 |
| `android/app/src/main/java/com/everything/eve/finance/SpeechParser.kt` | — | 中文金额 `chineseNumberToBigDecimal`、中文分类映射、昨天 / 前天时间语义 |
| `android/app/src/main/java/com/everything/eve/finance/OcrScannerEngine.kt` | — | ML Kit `TextRecognizer`（中文）生命周期 |
| `android/app/src/main/java/com/everything/eve/finance/SpeechRecorderEngine.kt` | — | `SpeechRecognizer` lifecycle + `RecognitionListener` 包装 |
| `android/app/src/main/java/com/everything/eve/ui/finance/OcrScannerSheet.kt` | ~410 | 状态机 `Idle / Recognizing / Result / NoResult`、CAMERA 运行时权限、`runCatching` 兜底无日志 |
| `android/app/src/main/java/com/everything/eve/ui/finance/SpeechRecorderSheet.kt` | ~330 | 状态机 `Idle / Listening / Result / NoResult / Error(code)`、`!capable→unsupported`、`onError` code 9 → 权限引导 |
| `android/app/src/main/java/com/everything/eve/ui/finance/FinanceEditor.kt` | — | 仅 TX kind 显示 OCR / 语音按钮（testTag `tx_editor_ocr_entry` / `tx_editor_speech_entry`） |
| `android/app/src/main/java/com/everything/eve/ui/finance/FinanceViewModel.kt` | — | `AiHintState` 独立 StateFlow；`applyReceiptHintToBuffer` / `applySpeechHintToBuffer`；apply 后立即清空 hint |
| `android/app/src/main/res/values/strings.xml` | — | 追加 24 条 `finance_ai_` 前缀 string resource |

### 测试覆盖（48 条新增）

| 文件 | 用例数 | 范围 |
|---|---|---|
| `OcrParserTest.kt` | 10 | 多格式金额 / 日期 / 商家启发式（纯 JVM） |
| `SpeechParserTest.kt` | 10 | 中文金额解析 / 分类映射 / 时间语义（纯 JVM） |
| `OcrScannerSheetTest.kt` | 8 | Robolectric 下权限 / 拍照识别 / 重试 / 预填 / 关闭 |
| `SpeechRecorderSheetTest.kt` | 11 | Robolectric 下设备能力 / 权限 / 识别 / 重试 / 预填 / 错误码 |
| `FinanceViewModelAiTest.kt` | 9 | 纯 JVM；amountMinor ↔ 元映射、merchant 仅空 note 时写入、apply 后清空 hint |

**最终门禁**：`./gradlew.bat :app:testDebugUnitTest` 全量 **481/481 通过**
（0 failure / 0 error；含 B8 新增 48 条用例与既有 433 条），其中
`compileDebugKotlin` 0 error。

### 决策与纪律记录

- **不提交 `test_config.properties`**：Robolectric 沙箱资源解析需
  `android_merged_manifest` / `android_resource_apk` 等本机绝对路径，提交会
  致其它机器 / CI 上资源解析失败；主代理已 `DeleteFile` 不提交，本纪律在
  `.trae/specs/stage5-finance-v2/tasks.md` Completion Evidence 与本节同步
  记录；
- **不引入 GMS / Firebase**：OCR / 语音全程设备本地；不接入任何云 SDK；
- **零知识文案**：UI 预览仅渲染金额 / 日期 / 商家（OCR）或金额 / 分类
  （语音）四类字段，**绝不**扩散原文 / 识别结果到通知 / 日志 / 通知文案
  （AC-V2F-19）；
- **临时调试脚本**：`tmp_calculate_times.ps1`、`.trae/parse-junit.ps1`、
  `android/.kotlin/` **永不提交**。

### 真机冒烟

详见 [`docs/smoke/finance-v2-ai-manual.md`](smoke/finance-v2-ai-manual.md)
（SMOKE-V2-AI-S1~S6 ≥6 真机冒烟场景，含零知识核查与失败上报模板）。

## 测试

```bash
./gradlew :app:testDebugUnitTest        # JVM 单测（Crockford Base32、采集纯函数核心等）
./gradlew :app:connectedDebugAndroidTest # Room 迁移/采集引擎等 instrumented 测试，需设备/模拟器
```

## 后续阶段权限规划

| 能力 | 权限 | 阶段 | 保活要点 |
|---|---|---|---|
| 通讯录 | `READ_CONTACTS`（已声明，阶段 3） | 3 ✅ | 增量游标同步，采集页申请 |
| 短信 | `READ_SMS`（已声明，阶段 3；**不需要** `RECEIVE_SMS`） | 3 ✅ | 高危权限，仅自建 APK 分发，用途说明页 |
| 通话记录 | `READ_CALL_LOG`（已声明，阶段 3） | 3 ✅ | 同上 |
| 后台轨迹 | `ACCESS_FINE_LOCATION` `ACCESS_BACKGROUND_LOCATION`（均已声明，阶段 4a） | 4a ✅ | 前台服务 + 引导关闭电池优化 + 自启动；后台定位须设置页「始终允许」 |
| 通知 | `POST_NOTIFICATIONS`（已声明，阶段 4a） | 4a ✅ | Android 13+ 运行时申请 |
| 开机自启 | `RECEIVE_BOOT_COMPLETED`（已声明） | 4a ✅ | 轨迹分支经 BootReceiver 拉起前台服务；周期任务由 WorkManager 自动恢复 |
| 日程精确闹钟 | `SCHEDULE_EXACT_ALARM`（API 31+） + `USE_EXACT_ALARM`（API 33+）（均已声明，阶段 4b） | 4b ✅ | AlarmManager `setExactAndAllowWhileIdle`；用户拒绝 `SCHEDULE_EXACT_ALARM` 即降级 `setAndAllowWhileIdle` 并写 `event_reminder_log.kind="alarm_killed"`，doze + 厂商后台限制已记入 README 已知问题 |
| OCR 相机 | `CAMERA` + `uses-feature camera.any`（均已声明，阶段 5 v2 B8） | B8 ✅ | CameraX 1.4.2 + ML Kit text-recognition 16.0.1 自包含 AAR；OCR Sheet 运行时申请；首次拒绝仅隐藏入口 |
| 语音麦克风 | `RECORD_AUDIO`（已声明，阶段 5 v2 B8） | B8 ✅ | 系统 `SpeechRecognizer` on-device；语音 Sheet 进入时申请；未授权走 `permission_blocked` testTag |

## 分发策略

短信/通话/后台定位属 Google Play 敏感权限，政策上难以过审，**默认自建 APK 分发**；
后续会提供签名 release APK 的下载通道与升级检查。
