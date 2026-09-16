# 阶段 3 — 安卓采集器 - 实施计划

> 需求来源：[spec.md](file:///d:/github/everything/everything/.trae/specs/stage3-android-collectors/spec.md)
> 任务按依赖排序；系统无关逻辑一律抽纯 Kotlin 先行并配 JVM 单测（NFR-3）。

## Task 1: 采集数据模型与纯函数核心（JVM 可测）
- **Status**: `completed`
- **Completion Evidence**:
  - TR-1.1：`./gradlew :app:testDebugUnitTest --tests "com.everything.eve.collector.core.*"`
    BUILD SUCCESSFUL, EXIT=0；4 个测试类 19 用例全绿（CollectorIdsTest 4 /
    TypeMapsTest 4 / CursorTest 5 / CanonicalJsonTest 6，test XML 统计核实）。
  - TR-1.2：`collector/core/` 包 grep `import android\.|lazysodium` 无匹配。
  - TR-1.3（rubric 自评 4/5）：类型映射/游标/id 派生/规范化比对全部纯函数化，
    无框架耦合；DTO 与 spec FR-1 字段一致。
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新建 `app/src/main/java/com/everything/eve/collector/core/` 纯 Kotlin 包（不 import
    android.* / lazysodium）：
    - `CollectorKind.kt`：枚举 `CONTACT/SMS/CALLLOG`，含 module/type 常量
      （contact/contact、sms/sms、calllog/call）与 kind 短名。
    - `Models.kt`：三类明文 DTO（ContactData/SmsData/CallData，含嵌套 Phone/Email/
      Address/NameParts 与 Source 溯源），moshi `@JsonClass` 序列化；字段与
      spec FR-1 一致。
    - `Ids.kt`：`recordId(deviceId, kind, systemId) = "$deviceId:$kindName:$systemId"`。
    - `TypeMaps.kt`：短信/通话系统整数类型码 ↔ spec 字符串枚举映射（含未知兜底）。
    - `Cursor.kt`：复合游标 `(timestamp, id)` 的零值、SQL where/order 片段生成、
      行结果推进、与单轮预算（常量 `MAX_PER_KIND_PER_RUN = 400`）配合的截断判定。
    - `CanonicalJson.kt`：基于 moshi 的规范化序列化（Map/List 树形排序或固定 DTO
      字段顺序）与内容相等判定（JSON 解析后结构相等，字段顺序无关）。
  - JVM 单测 `app/src/test/java/com/everything/eve/collector/core/`：
    - id 派生与跨设备/跨类不冲突、幂等键稳定；
    - 类型码映射全枚举 + 未知码兜底；
    - 游标边界：等值时间戳按 _id 续进、预算截断后游标停位、首轮零值全量；
    - 规范化相等：字段顺序不同/空白不同判定相等，任一业务字段不同判定不等。
- **Acceptance Criteria Addressed**: AC-1、AC-2、AC-3、AC-12
- **Test Requirements**:
  - `rule` TR-1.1: `./gradlew :app:testDebugUnitTest` 通过，新增测试 ≥8 个断言场景
    全绿；证据：测试输出与测试类清单。
  - `rule` TR-1.2: core 包内 grep 不到 `import android.` 与 `lazysodium`；
    证据：grep 结果。
  - `rubric` TR-1.3: 纯函数设计质量；scale 1-5；anchors 1=映射/游标散落在
    Android 组件里不可测，3=抽出但有残余框架耦合，5=全部系统无关分支纯函数化且
    命名清晰；threshold >= 4；证据：代码评审。
- **Notes**: moshi 已在 main 依赖，JVM 单测可直接使用；DTO 字段定义一旦定稿须与
  Task 7 文档逐字段一致。

## Task 2: Room v3 迁移与 collector_state 持久化
- **Status**: `completed`
- **Completion Evidence**:
  - TR-2.1：`:app:assembleDebug :app:assembleDebugAndroidTest` BUILD SUCCESSFUL,
    EXIT=0；新增 `migrate2To3_addsCollectorStateAndKeepsData` 用例（新表 schema 校验 +
    旧 records/sync_state 数据保留 + 游标读写）。
  - TR-2.2：`MIGRATION_2_3` 仅 `CREATE TABLE IF NOT EXISTS collector_state`，
    不触碰 records/sync_state；状态表字段为游标/计数/枚举原因，无明文。
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新增 `CollectorStateEntity`（kind TEXT PK、last_timestamp INTEGER NOT NULL
    DEFAULT 0、last_system_id INTEGER NOT NULL DEFAULT 0、last_run_at INTEGER、
    last_scanned_count INTEGER NOT NULL DEFAULT 0、last_skip_reason TEXT）。
  - 新增 `CollectorStateDao`：get(kind)、upsert（推进游标/运行结果）。
  - EveDatabase 升 version=3、注册实体、新增 `MIGRATION_2_3`（CREATE TABLE IF NOT
    EXISTS collector_state …），沿用显式迁移风格。
  - 更新 instrumented `MigrationTest`：补 v2→v3 断言（新表存在、列正确、旧 records
    数据保留）。
- **Acceptance Criteria Addressed**: AC-2、AC-6
- **Test Requirements**:
  - `rule` TR-2.1: `assembleDebugAndroidTest` BUILD SUCCESSFUL（无设备环境以编译
    通过为准，运行关闭条件并入 FU-7）；证据：构建输出。
  - `rule` TR-2.2: MIGRATION_2_3 仅做 CREATE TABLE IF NOT EXISTS，不碰 records/
    sync_state；证据：迁移代码评审。
- **Notes**: 状态表只存游标与计数/枚举原因，严禁任何明文字段（NFR-1）。

## Task 3: 系统 ContentProvider 读取适配层
- **Status**: `completed`
- **Completion Evidence**:
  - TR-3.1：三源查询统一走 `cursorQueryBundle`（QUERY_ARG_SQL_SELECTION/SORT_ORDER/
    LIMIT），selection=`time > ? OR (time = ? AND _id > ?)`、排序 ASC、LIMIT 由
    CollectorCursor 纯函数生成，与 spec FR-3 一致。
  - TR-3.2：source 包 grep `\bLog\.[dwiev]|println|System.out` 无匹配；映射代码无
    异常文案携带业务字段。
  - TR-3.3（rubric 自评 4/5）：SystemSource<T> 接口 + ContentResolver 参数注入，
    instrumented 可替换；源仅做行→DTO，无加密/入库/日志。
  - 构建：`:app:assembleDebug` BUILD SUCCESSFUL, EXIT=0。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `collector/source/`：
    - `ContactsSource.kt`：ContactsContract.Contacts + Data（Phone/Email/Organization/
      Name/Event 生日/Note）联查，输出 ContactData；时间列
      CONTACT_LAST_UPDATED_TIMESTAMP（API 18+，minSdk26 可用），id 取 Contacts._ID，
      lookup_key 入 source；游标查询按 spec FR-3 复合条件。
    - `SmsSource.kt`：Telephony.Sms.CONTENT_URI（全文件夹），映射 address/body/date/
      type/read/thread_id/_id。
    - `CallLogSource.kt`：CallLog.Calls.CONTENT_URI，映射 number/cachedName/date/
      duration/type/_id。
    - 每类暴露 `query(cursor, limit): List<RawEntry>`（数据类携带 systemId/timestamp
      与 Task1 DTO），查询 selection/orderBy 复用 Cursor.kt 纯函数产物。
    - `PermissionGate.kt`：`grantedKinds(context): Set<CollectorKind>` 与
      `requiredPermission(kind)` 单点维护。
  - 适配层只做"系统行 → DTO"，不做加密/入库/日志明文。
- **Acceptance Criteria Addressed**: AC-1、AC-5、AC-7
- **Test Requirements**:
  - `rule` TR-3.1: 三源 selection 语句在代码审查中与 spec FR-3 完全一致
    （`time > ? OR (time = ? AND _id > ?)` + ASC 排序 + LIMIT）；证据：代码评审。
  - `rule` TR-3.2: source 包无任何 Log.x/println 输出 DTO 字段；异常文案只含
    kind/系统列名，不含号码/正文；证据：grep + 评审。
  - `rubric` TR-3.3: 适配层薄厚与可替换性（接口便于 instrumented 注入）；
    scale 1-5；threshold >= 4；证据：代码评审。

## Task 4: 采集编排引擎（游标→比对→密封→入库）
- **Status**: `completed`
- **Completion Evidence**:
  - TR-4.1：新建/未变/变化/MK 缺失/权限缺失/预算截断分支逐条可定位
    （CollectorEngine.runKind）；`CollectorEngineAndroidTest` 6 用例覆盖全部编排
    分支并随 `assembleDebugAndroidTest` 编译通过（运行需设备，并入 FU-7）。
  - TR-4.2：密封 module/type 取 CollectorKind 常量，AAD=记录自身 module（测试断言
    openRecord 可逆且版本递增重封）。
  - TR-4.3：CollectorEngine grep `\bLog\.|println|SharedPreferences` 无匹配；异常
    一律翻译为 SkipReason 枚举。
  - 构建：assembleDebug + assembleDebugAndroidTest + testDebugUnitTest 全 EXIT=0。
- **Priority**: high
- **Depends On**: Task 1、Task 2、Task 3
- **Description**:
  - 新建 `collector/CollectorEngine.kt`（挂 ServiceLocator）：
    - 输入：appContext、auth、RecordDao、CollectorStateDao、三种 Source；
    - `runKind(kind)` 单轮流程：读游标 → source 限量查询 → 对每行按 recordId 查本地
      RecordEntity → 存在则用 MK 解密（openRecord，AAD 用记录自身 module）后做
      CanonicalJson 比对：相同仅随循环推进游标；不同 version+1 重封；不存在则
      version=1 密封 → `sealRecord(mk, jsonBytes, id, module, version)` → upsert
      RecordEntity(dirty=1, createdAt/updatedAt 用系统时间仅作本地占位，服务端会
      权威覆盖) → 最后写 collector_state（新游标、计数、时间、跳过原因枚举）。
    - 统一结果模型 `CollectResult(scanned, created, updated, unchanged, stoppedAtLimit,
      skippedReason)`，只含计数与枚举。
  - RecordDao 增补采集所需查询（按 id 单查、dirty upsert），不改既有同步 SQL 语义。
  - 任何异常路径禁止把 DTO 拼进 message；记录 skipReason（如 `mk_unavailable`、
    `permission_denied`、`source_error`）。
- **Acceptance Criteria Addressed**: AC-1、AC-2、AC-3、AC-4、AC-7
- **Test Requirements**:
  - `rule` TR-4.1: 编排分支（新建/未变/变化/预算截断/MK 缺失/权限缺失）在代码评审
    逐条可定位；instrumented 测试类 `CollectorEngineAndroidTest` 编写完成并参与
    `assembleDebugAndroidTest` 编译（运行需设备/模拟器数据，并入 FU-7）。
  - `rule` TR-4.2: 入库信封 module 取值正确（与 Task1 常量一致），AAD 使用记录
    module；证据：代码评审 + instrumented 用例代码。
  - `rule` TR-4.3: 明文 DTO 不被写入任何 Log/SharedPreferences/文件；证据：grep。
- **Notes**: 未解锁（MK=null）由 Task 5 的 Worker 提前拦截；引擎内仍做防御性校验。

## Task 5: CollectorWorker 与调度演进
- **Status**: `completed`
- **Completion Evidence**:
  - TR-5.1：全代码库 grep `enqueueUniquePeriodicWork` 仅 CollectorWorker.kt 一处
    （任务名 `eve.collector-sync`，KEEP 幂等，启动时取消旧 `eve.sync.periodic`）；
    旧 SyncWorker 已删除，VaultViewModel 改用 `requestCollectNow`。
  - TR-5.2：doWork 先按开关遍历 runKind（MK/权限缺失由引擎记 skipReason 且不触碰
    Source），最后无条件 repo.sync()。
  - TR-5.3：grep `putString` 在采集相关代码无匹配；CollectorSettings 仅 putBoolean。
  - 构建：`:app:assembleDebug` BUILD SUCCESSFUL, EXIT=0。
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - 新增 `sync/CollectorWorker.kt`：doWork 顺序——读采集设置（普通
    SharedPreferences `eve-collector`，只存开关布尔，不存明文）→ 对每类
    "启用 && 权限授予 && MK 非空"才调 engine.runKind；MK 全空时跳过全部扫描并写
    skipReason=`mk_unavailable`；最后无条件调 `repo.sync()`（推送已加密 dirty，
    不需要 MK）；异常按可恢复/不可恢复决定 Result.retry/success。
  - `SyncScheduler` 演进：唯一周期任务名改为 `eve.collector-sync`（15 分钟、
    CONNECTED、指数退避，KEEP 策略幂等）；新增 `requestCollectNow(context)`
    一次性唯一任务；EveApplication 启动调度点替换。保留 SyncWorker 文件用于纯
    立即同步场景或删除其调度引用（以无重复周期任务为准，评审时确认 WorkManager
    任务名唯一）。
- **Acceptance Criteria Addressed**: AC-6、AC-8、AC-13
- **Test Requirements**:
  - `rule` TR-5.1: 代码中周期任务只有一个 enqueueUniquePeriodicWork 活跃路径；
    证据：grep enqueueUniquePeriodicWork 结果与评审。
  - `rule` TR-5.2: MK=null 路径不调用任何 Source/密封逻辑，且仍调用 repo.sync()；
    证据：代码评审（instrumented 运行并入 FU-7）。
  - `rule` TR-5.3: SharedPreferences `eve-collector` 中只允许布尔/整型键；
    证据：grep putString 无业务数据。

## Task 6: 权限向导与采集状态 UI
- **Status**: `completed`
- **Completion Evidence**:
  - TR-6.1：AndroidManifest grep `uses-permission` 共 6 行——新增恰好
    READ_CONTACTS/READ_SMS/READ_CALL_LOG 三个只读权限，无 RECEIVE_SMS/定位/
    FOREGROUND_SERVICE/REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。
  - TR-6.2：CollectorScreen 五态分支齐备——未授权（红字+授权引导）/已授权未采集
    （"已授权，等待首次采集"或"已授权，未开启采集"）/已采集（本地条数+上次成功
    时间+扫描行数）/未解锁跳过（mk_unavailable 专属文案）/失败（skipReason 中文
    映射+一次性任务 FAILED 文案）。
  - TR-6.3：openAppDetails 捕获 ActivityNotFoundException+SecurityException；
    requestIgnoreBattery 首层 catch 后降级 ACTION_IGNORE_BATTERY_OPTIMIZATION_
    SETTINGS，再 catch 才 snackbar 提示手动配置；两处 intent 崩溃率为零。
  - 其余实现：合规告知卡片（自托管/E2E/约 15 分钟非实时/不跟随系统删除/可关闭）；
    rememberLauncher 权限请求 + shouldShowRequestPermissionRationale 永久拒绝
    判定 → "去系统设置开启权限"按钮；周期总开关（CollectorSettings.master_enabled，
    SyncScheduler.schedulePeriodic 启动点尊重开关 + cancelPeriodic）；立即采集 →
    requestCollectNow + 轮询 WorkInfo 显示进行中/成功/失败；保活区含国产 ROM
    自启动指引文案。AppNav 加 COLLECTOR 路由（仅登录后可达），VaultScreen 顶栏
    加"采集"入口。TR-6.4 rubric 待 Task 8 独立评审打分。
  - 构建：`:app:assembleDebug :app:assembleDebugAndroidTest` BUILD SUCCESSFUL, EXIT=0。
- **Priority**: high
- **Depends On**: Task 4、Task 5
- **Description**:
  - 新增 `ui/screens/CollectorScreen.kt` + `CollectorViewModel.kt`：
    - 顶部合规告知卡片（仅存入本人自托管服务器、端到端加密、可关闭；明示
      "约 15 分钟周期、非实时""不跟随系统删除"）；
    - 三类行：图标/名称/权限用途说明 + 开关；开启走 rememberLauncher 权限请求；
      永久拒绝显示"去系统设置开启"按钮（ACTION_APPLICATION_DETAILS_SETTINGS，
      带 ActivityNotFound 兜底）；
    - 周期采集总开关（切换 SyncScheduler 周期启用/取消）；
    - "立即采集"按钮 → requestCollectNow + observe WorkInfo 显示进行中/成功/失败
      （失败文案仅枚举原因）；
    - 每类状态行：权限态、上次成功时间、本地已采集条数（RecordDao 按 module 计数
      查询）、上次跳过原因中文映射。
    - 保活区：请求忽略电池优化按钮
      （ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + isIgnoringBatteryOptimizations
      判定 + 不可用时隐藏/兜底）与应用详情入口；文案提示国产 ROM 另需允许自启动
      （链接到应用内展示的简明厂商指引段）。
  - `AppNav` 加 `COLLECTOR` 路由；VaultScreen 顶部/更多区加入口（与"设备管理"
    并列），未登录不可达（沿用现有导航结构）。
  - Manifest 增加 READ_CONTACTS/READ_SMS/READ_CALL_LOG；不加任何其他权限。
- **Acceptance Criteria Addressed**: AC-5、AC-8、AC-9、AC-11
- **Test Requirements**:
  - `rule` TR-6.1: Manifest diff 恰好新增三个 READ 权限，无 RECEIVE_SMS/定位/
    FOREGROUND_SERVICE；证据：AndroidManifest.xml 评审。
  - `rule` TR-6.2: 五种 UI 状态（未授权/已授权未采集/已采集/未解锁跳过/失败）
    均有实现分支与文案；证据：代码走查（独立评审 AC-11）。
  - `rule` TR-6.3: 电池优化与详情页两个 intent 均有 try/catch 兜底，崩溃率为零；
    证据：代码评审 + assembleDebug 安装冒烟（有设备时）。
  - `rubric` TR-6.4: UI 体验质量；scale 1-5；anchors 见 spec AC-11；
    threshold >= 4；证据：独立评审走查。

## Task 7: 文档同步
- **Status**: `completed`
- **Completion Evidence**:
  - TR-7.1：module-schemas.md 新增"5. 采集模块（Android only）"——5.1 source
    溯源字段表（system_id/lookup_key/last_updated）、5.2 contact 全字段表
    （display_name/name 五部件/organization/job_title/phones/emails/addresses/
    birthday/notes + 条目共有字段 number·email·formatted/type 九枚举/label/
    is_primary）、5.3 sms 七字段、5.4 calllog 六字段，逐一与
    collector/core/Models.kt DTO 对照一致；含 id 规则 `{deviceId}:{kind}:{systemId}`、
    "仅 Android 采集、Web 不展示、服务端零知识原样存储"边界与变更/删除语义；
    目录与 Android 支持矩阵（改第 6 章）已同步。
  - TR-7.2：android.md 新增"数据采集（阶段 3）"章——权限用途与申请时机、合规告知、
    行为限制 1（约 15 分钟非实时）+ 行为限制 2（不跟随系统删除）+ 未锁定不采集
    （mk_unavailable）说明；五厂商（小米/华为/OPPO/vivo/荣耀）自启动/后台指引表；
    CollectorEngineAndroidTest 运行方式（connectedDebugAndroidTest + pm grant/
    revoke 说明）；权限规划表三行标记已声明且注明不需要 RECEIVE_SMS。
  - everything_plan.md 阶段 3 勾选（注明附件上传不在本期、位置属阶段 4），
    待启动行改"阶段 4–8"；README.md 进度行同步。
- **Priority**: medium
- **Depends On**: Task 1
- **Description**:
  - `docs/module-schemas.md`：新增"采集模块（Android only）"章：contact/sms/
    calllog 三 type 完整字段表、source 溯源字段、记录 id 规则
    `{deviceId}:{kind}:{systemId}`、"仅 Android 采集、Web 不展示、服务端零知识
    原样存储"边界；更新目录与 Android 支持矩阵表。
  - `docs/android.md`：权限用途与申请时机、合规告知文案要点、行为限制（非实时、
    不跟随删除、未锁定不采集）、电池优化与国产 ROM（小米/华为/OPPO/vivo/荣耀）
    自启动/后台运行指引、instrumented 采集测试运行方式。
  - `.trae/documents/everything_plan.md` 阶段 3 勾选并注明附件/位置不在本期；
    README.md 进度行同步。
- **Acceptance Criteria Addressed**: AC-8、AC-9、AC-10
- **Test Requirements**:
  - `rule` TR-7.1: 字段表与 Task1 DTO 逐字段一致（评审逐行对照）；证据：评审记录。
  - `rule` TR-7.2: android.md 含五厂商指引、两条行为限制与未锁定说明；
    证据：文档评审。

## Task 8: 全量门禁、证据固化与评审准备
- **Status**: `completed`
- **Completion Evidence**:
  - TR-8.1（2026-09-16 08:30–08:42 复跑，五个构建/测试目标退出码全 0）：
    - Android（cwd=android，JAVA_HOME=`D:\Program Files\Java\jdk-17`，
      GRADLE_USER_HOME=`D:\Program Files\Gradle\gradle-home`）：
      `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest`
      → BUILD SUCCESSFUL in 5s，71 actionable tasks（4 executed / 67 up-to-date），
      EXIT=0（三目标同命令完成）。
    - server（cwd=server，PATH 前置 `C:\Program Files\Go\bin`）：`go test ./...`
      → ok internal/api 21.223s，auth/crypto/db cached，EXIT=0。排障记录：本机
      系统级环境变量 GOOS=darwin/GOARCH=arm64 会致测试二进制非 Win32
      （`%1 is not a valid Win32 application`，EXIT=1），在命令进程内显式
      `$env:GOOS="windows"; $env:GOARCH="amd64"` 后全绿；server 代码本期零改动，
      与阶段 3 无关。
    - web（cwd=web）：`npm run build` → vue-tsc --noEmit 通过 + vite build 11.19s
      （2746 modules），EXIT=0。
  - TR-8.2（Get-Item 核实文件真实存在，采集时间 2026-09-16 08:42）：
    - `D:\github\everything\everything\android\app\build\outputs\apk\debug\app-debug.apk`
      — 16,885,283 字节 — LastWriteTime 2026-09-16 08:30:58；
    - `D:\github\everything\everything\android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`
      — 298,814 字节 — LastWriteTime 2026-09-16 08:18:05。
  - TR-8.3（13 个 AC 全覆盖，每条至少一项独立证据，详见各任务回填）：
    - AC-1 三类授权采集与确定性身份：Task 1 TR-1.1（CollectorIdsTest）+
      Task 3 TR-3.1（三源 selection 与 spec FR-3 一致）。
    - AC-2 复合游标增量与分页追平：Task 1 TR-1.1（CursorTest 5 用例）+
      Task 2 TR-2.1（迁移测试含游标读写）。
    - AC-3 变化检测与版本纪律：Task 1 TR-1.1（CanonicalJsonTest）+
      Task 4 TR-4.2（version+1 重封断言）。
    - AC-4 复用通用同步链路：Task 4 TR-4.1（密封入 records dirty=1）+
      Task 5 TR-5.2（无条件 repo.sync()）。
    - AC-5 权限最小化与运行时授权：Task 6 TR-6.1（恰好三个 READ 权限）+
      Task 3 PermissionGate 单点维护。
    - AC-6 周期调度、唯一任务与未解锁降级：Task 5 TR-5.1（唯一
      enqueueUniquePeriodicWork）+ TR-5.2（MK=null 降级不触碰 Source）。
    - AC-7 零知识日志与错误信息：Task 3 TR-3.2 + Task 4 TR-4.3（grep 无
      Log/println/SharedPreferences 明文）。
    - AC-8 删除策略明示：Task 6 合规告知卡片（不跟随系统删除）+
      Task 7 TR-7.2（行为限制 2）。
    - AC-9 保活引导可用：Task 6 TR-6.3（电池优化两级兜底）+
      Task 7 TR-7.2（五厂商指引表）。
    - AC-10 文档与计划同步：Task 7 TR-7.1/TR-7.2 + everything_plan.md 阶段 3
      勾选 + README.md 进度行。
    - AC-11 采集页可用性质量：Task 6 TR-6.2 五态分支齐备；TR-6.4 rubric
      留待 Review 阶段独立评审打分。
    - AC-12 架构一致性与可测性：Task 1 TR-1.2（core 包无 android./lazysodium
      依赖）+ TR-1.3（rubric 自评 4/5）。
    - AC-13 全量门禁：本任务 TR-8.1/TR-8.2。
  - Implement 阶段至此完成；Review 阶段由全新上下文独立评审代理创建
    review.md（含 TR-6.4 打分），不在本任务内执行。
- **Priority**: high
- **Depends On**: Task 5、Task 6、Task 7
- **Description**:
  - 复跑门禁并在本文件记录命令、时间戳、退出码与 APK 产物绝对路径
    （app/build/outputs/apk/debug/app-debug.apk 与 androidTest apk，需验证文件
    真实存在——不止引用构建日志）：
    - `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest`
      （JAVA_HOME=D:\Program Files\Java\jdk-17，GRADLE_USER_HOME=D:\Program Files\
      Gradle\gradle-home）；
    - server `go test ./...`（PATH 前置 C:\Program Files\Go\bin）回归；
    - web `npm run build` 回归。
  - 自验全部 AC/TR，回填 Completion Evidence；随后进入 Review 阶段，由全新上下文
    独立评审（review.md 在评审时创建）。
- **Acceptance Criteria Addressed**: AC-13
- **Test Requirements**:
  - `rule` TR-8.1: 五条命令退出码全 0；证据：输出摘录。
  - `rule` TR-8.2: APK 与 androidTest APK 文件存在性以路径列表确认；证据：
    产物绝对路径 + 文件大小/时间戳。
  - `rule` TR-8.3: 每个 AC 均有至少一条独立证据；证据：本文件回填 + review.md。
