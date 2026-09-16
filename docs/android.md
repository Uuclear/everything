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

## 分发策略

短信/通话/后台定位属 Google Play 敏感权限，政策上难以过审，**默认自建 APK 分发**；
后续会提供签名 release APK 的下载通道与升级检查。
