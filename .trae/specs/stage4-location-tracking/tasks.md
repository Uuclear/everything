# 阶段 4a — 位置轨迹 - 实施计划

> 需求来源：[spec.md](file:///d:/github/everything/everything/.trae/specs/stage4-location-tracking/spec.md)
> 任务按依赖排序；系统无关逻辑一律抽纯函数先行并配单测（NFR-3：Android JVM /
> Web Vitest）。服务端先行（Task 1-2）确立块存储契约，Android（3-7）与
> Web（8-10）可并行推进，文档与门禁收尾。

## Task 1: 服务端 locations 存储层与迁移 0004

- **Status**: `completed`
- **Completion Evidence**:
  - TR-1.1：`go test ./...`（进程内 GOOS=windows/GOARCH=amd64 显式覆盖）
    EXIT=0——`ok internal/vault 4.954s`，新增 7 个测试场景全绿
    （TestMonthTableBoundary / TestUpsertBlocksAcrossMonths /
    TestUpsertBlocksIdempotent / TestListRangeAcrossMonthsSorted /
    TestListRangeEmptyDB / TestDeleteRangeEmptyDB / TestDeleteRangeAcrossMonths），
    既有测试（api/auth/crypto/db）全部不回归；`go build ./...` 通过。
  - TR-1.2：`location_store.go` 月表 DDL 与 spec FR-6 逐字段一致
    （id/user_id/device_id/start_ts/end_ts/point_count/cipher/created_at +
    `(user_id, start_ts)` 索引）；`0004_location_blocks.sql` 登记性迁移
    （`SELECT 1;` 占位）注释记录同口径；`migrate_test.go` 版本号 3→4
    为新增迁移的必要适配。附带修正：Description 中 LIKE 模式下划线数
    6→7（见行内批注）。
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新建 `server/internal/vault/location_store.go`（与 store.go 同包，复用
    `*sql.DB` 注入模式）：
    - `LocationBlock` DTO：`ID/UserID/DeviceID string`、`StartTs/EndTs int64`、
      `PointCount int`、`Cipher []byte`（API 层 base64）、`CreatedAt int64`；
      json tag 与 Task 2 API 对齐（`id/device_id/start_ts/end_ts/point_count/
      cipher/created_at`）。
    - `LocationStore struct{ db *sql.DB }` + `NewLocationStore(database *sql.DB)`。
    - 月表名纯函数 `monthTable(startTs int64) string`：按
      `time.UnixMilli(startTs).UTC().Format("200601")` 拼 `locations_YYYYMM`。
    - DDL 常量模板（`fmt.Sprintf` 表名）：`CREATE TABLE IF NOT EXISTS %s (
      id TEXT PRIMARY KEY, user_id TEXT NOT NULL, device_id TEXT NOT NULL,
      start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL,
      point_count INTEGER NOT NULL, cipher BLOB NOT NULL,
      created_at INTEGER NOT NULL)` 与索引 `CREATE INDEX IF NOT EXISTS
      idx_%[1]s_user_ts ON %[1]s(user_id, start_ts)`。
    - `UpsertBlocks(userID string, blocks []LocationBlock, now int64)
      (applied, skipped int, err error)`：逐块 EnsureMonth（CREATE IF NOT EXISTS）
      后 `INSERT OR IGNORE`，`RowsAffected=0` 计 skipped（同 id 幂等）；
      `created_at` 一律用入参 `now`（服务端权威，客户端值忽略）。
    - `ListRange(userID string, from, to int64) ([]LocationBlock, error)`：
      先 `SELECT name FROM sqlite_master WHERE type='table' AND name LIKE
      'locations_______'`（7 个下划线：`_YYYYMM` 共 7 字符；原文 6 个系
      off-by-one 笔误，实现已按语义修正并实测验证）取已建月表集合，
      枚举 from→to 覆盖的 UTC 月份（含两端），
      仅对已建表执行 `SELECT ... WHERE user_id=? AND start_ts BETWEEN ? AND ?
      ORDER BY start_ts ASC` 并拼接。
    - `DeleteRange(userID string, from, to int64) (int, error)`：同月表枚举
      执行 `DELETE WHERE user_id=? AND start_ts BETWEEN ? AND ?`，累计返回删除数。
  - 新建迁移 `server/internal/db/migrations/0004_location_blocks.sql`：登记性
    迁移——注释说明月表 DDL 常量、"写入时 CREATE TABLE IF NOT EXISTS 动态建表"
    约定与 user_id 隔离口径；若迁移框架要求可执行语句，以 `SELECT 1;` 占位。
  - 单测 `server/internal/vault/location_store_test.go`（对齐 db 包测试方式：
    `modernc.org/sqlite` 驱动 + `t.TempDir()` 临时库，无 CGO）：
    - 跨两月写入 → 两张月表建成、各表行数正确；
    - 同 id 重复 UpsertBlocks → applied/skipped 正确、行数不增（幂等）；
    - ListRange 跨月按 start_ts 升序、月归属边界（块 start_ts 恰为月末
      23:59:59.999 UTC 与次月 00:00:00.000 各归其月）；
    - 空库 ListRange/DeleteRange 不报错（月表不存在路径）；
    - DeleteRange 跨月删除并返回正确计数。
- **Acceptance Criteria Addressed**: AC-5、AC-6
- **Test Requirements**:
  - `rule` TR-1.1: `go test ./internal/vault/` 全绿，新增 ≥5 个测试场景；
    证据：测试输出。
  - `rule` TR-1.2: 月表 DDL 与 spec FR-6 列定义逐字段一致（含 user_id 与
    `(user_id, start_ts)` 索引）；证据：代码评审 + 0004 文件内容。
- **Notes**: store 层不接触明文、不解密；月表名只允许本包 `monthTable` 生成，
  严禁拼接外部输入（防注入）。

## Task 2: 服务端 locations API 与审计

- **Status**: `completed`
- **Completion Evidence**:
  - TR-2.1：`go test ./...`（GOOS/GOARCH 显式覆盖）EXIT=0，ok 5 包 / FAIL 0
    （`ok internal/api 29.800s`，Task 1 及既有测试零回归）；`go build`/`go vet`/
    `gofmt -l` 全干净。新增 5 个测试函数全 PASS：TestLocationUploadValidation
    （10 个 400 + 恰 50 块/恰 256KB 边界 200）、TestLocationUploadGetDeleteFlow
    （幂等重提 skipped=全部、跨月升序、device_id claims 覆盖、63 天 400/恰 62 天
    200、DELETE 计数递减）、TestLocationAuditEvents（三事件 detail 精确全等 +
    明文标记串泄漏断言）、TestLocationUploadBroadcastsChange（SSE 收到
    locations_changed）、TestLocationEndpointsRequireApprovedScope（401/403）。
  - TR-2.2：三审计事件 detail 仅计数与范围（`blocks=n applied=m skipped=k` /
    `from=… to=… blocks=n` / `from=… to=… deleted=n`）；测试以明文标记串及其
    base64、块 id 标记做泄漏断言，三事件 detail 均不含；主会话 grep 核实
    claims.UserID/DeviceID 覆盖与 hub.Publish 落实。
  - 合理偏离记录：批量端点用自定义 20MB MaxBytesReader（通用 decodeJSON 4MB
    上限会误杀 50×256KB×4/3≈17MB 合法批量）；cipher 显式 base64 解码以区分
    格式错/超限两类 400；空密文块补下界 400；audit_coverage_test.go 未注册
    新事件（该文件为 FR-25 认证事件清单，location.* 由独立测试精确断言）。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `server/internal/api/locations_handler.go`（模式对齐
    records_handler.go：writeJSON/writeError、`s.vault`/`s.hub`/`s.audit`）：
    - `POST /api/v1/locations/batch`：body `{blocks:[{id,start_ts,end_ts,
      point_count,cipher(base64)}]}`；校验——块数 1..50（超限 400）、id 非空、
      `0 < start_ts <= end_ts`、`point_count > 0`、cipher base64 可解且解码后
      ≤256*1024 字节；`UserID/DeviceID` 以 token claims 覆盖（不信任客户端）；
      调 `LocationStore.UpsertBlocks(claims.UserID, blocks, now)`；审计
      `location.upload`（detail 仅计数：`blocks=n applied=m skipped=k`）；
      `s.hub.Publish(claims.UserID, sync.Event{Type: "locations_changed"})`；
      返回 `{applied, skipped}`。
    - `GET /api/v1/locations?from&to`：from/to 为 UTC 毫秒整数；解析失败、
      from≤0、to<from、跨度 >62*24h 均 400；`ListRange` 后返回 `{blocks:[...]}`
      （cipher 转 base64）；审计 `location.download`（范围与块数）。
    - `DELETE /api/v1/locations?from&to`：参数同 GET；`DeleteRange` 返回
      `{deleted}`；审计 `location.delete`（范围与删除块数）。
  - 修改 `server/internal/api/server.go`：approved 分组（requireAccessToken +
    requireScope(auth.ScopeApproved)）内新增三行——
    `r.Post("/locations/batch", s.uploadLocationBlocks)`、
    `r.Get("/locations", s.listLocationBlocks)`、
    `r.Delete("/locations", s.deleteLocationBlocks)`。
  - 单测 `server/internal/api/locations_handler_test.go`（对齐 e2e_test.go /
    helpers_test.go 既有测试基建）：
    - 51 块 → 400；cipher 超 256KB → 400；缺 id/非法 ts → 400；
    - 正常批量 → 200 且 applied 正确；同批重提 → skipped=全部；
    - GET 跨度 63 天 → 400；合法跨月 GET → 升序返回；
    - DELETE 返回删除数；三端点各产生对应审计事件（对齐 audit_coverage_test.go
      的审计断言模式）；
    - 未授权（无 token/scope 不符）→ 401/403。
- **Acceptance Criteria Addressed**: AC-5、AC-6
- **Test Requirements**:
  - `rule` TR-2.1: `go test ./internal/api/` 全绿（含既有用例回归）；
    证据：测试输出。
  - `rule` TR-2.2: 三审计事件 detail 只含计数/范围，无任何坐标/密文字段；
    证据：代码评审 + 审计断言。
- **Notes**: 明文坐标永不出现——handler 只透传密文与元数据（NFR-1）；
  `locations_changed` 事件仅作 Live 模式预留，Web 本期不订阅。

## Task 3: Android 轨迹纯函数核心（JVM 可测）

- **Status**: `completed`
- **Completion Evidence**:
  - TR-3.1：`./gradlew :app:assembleDebug :app:testDebugUnitTest`
    BUILD SUCCESSFUL, EXIT=0；4 个测试类 23 用例全绿（PointFilterTest 4 /
    RateDeciderTest 6 / GeoMathTest 4 / BlockPackerTest 9，test XML 统计
    failures=0 errors=0），≥10 断言场景达标。
  - TR-3.2：`collector/location/core/` 包 grep `import android\.|lazysodium`
    无匹配（执行代理亲验）。
  - TR-3.3（rubric 自评 5/5）：常量集中于 LocationParams 命名与 tasks.md
    常量表逐字一致；过滤/降频/haversine/分块/块 id 派生全部纯函数化零框架
    耦合；pack 对乱序输入先按 ts 排序保证输出只依赖集合内容；逐函数中文
    KDoc 完整。
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新建 `app/src/main/java/com/everything/eve/collector/location/core/` 纯
    Kotlin 包（不 import android.* / lazysodium）：
    - `LocationParams.kt`：常量——`MIN_TIME_MS = 60_000L`、
      `MIN_DISTANCE_M = 25f`、`MAX_ACCURACY_M = 100f`、
      `STILL_WINDOW_MS = 10 * 60_000L`、`STILL_DISPLACEMENT_M = 50.0`、
      `STILL_INTERVAL_MS = 300_000L`、`MAX_POINTS_PER_BLOCK = 100`、
      `MAX_BLOCK_SPAN_MS = 60 * 60_000L`、`POINT_EXPIRY_MS = 24 * 60 * 60_000L`。
    - `TrackPoint.kt`：数据类 `TrackPoint(ts: Long, lat: Double, lon: Double,
      acc: Float, speed: Float?, bearing: Float?, altitude: Double?,
      provider: String?)`；块明文 DTO `LocationBlockJson(device_id: String,
      start_ts: Long, end_ts: Long, points: List<TrackPoint>)`，moshi
      `@JsonClass`。
    - `GeoMath.kt`：`haversineM(lat1, lon1, lat2, lon2): Double`（WGS84 半径
      6371000m）。
    - `PointFilter.kt`：`accept(acc: Float?): Boolean` = acc 非空且
      ≤MAX_ACCURACY_M（无精度信息视为不可用，保守丢弃）。
    - `RateDecider.kt`：`decideInterval(recent: List<TrackPoint>, now: Long):
      Long`——取窗口 `[now-STILL_WINDOW_MS, now]` 内已收点，最大两两位移
      <STILL_DISPLACEMENT_M（且点数 ≥2）→ STILL_INTERVAL_MS，否则 MIN_TIME_MS；
      窗口内 0/1 个点时维持 MIN_TIME_MS。
    - `BlockPacker.kt`：`pack(deviceId: String, points: List<TrackPoint>):
      List<LocationBlockJson>`——按 ts 升序遍历，块内点数达
      MAX_POINTS_PER_BLOCK 或 `ts - 块首ts >= MAX_BLOCK_SPAN_MS` 即结算；
      块 id 纯函数 `blockId(deviceId, startTs, endTs) = "$deviceId:$startTs:$endTs"`。
  - JVM 单测 `app/src/test/java/com/everything/eve/collector/location/core/`：
    - 过滤边界：acc=100 接受、100.5/101 丢弃、null 丢弃；
    - 降频：窗口内 10 分钟位移<50m → 300s；任一相邻位移 ≥50m → 60s；
      窗口不足 2 点 → 60s；窗口滑动（旧点出窗后恢复 60s）；
    - haversine 已知对照（北京→上海约 1067km，容差 1%）；零距离 =0；
    - 分块：恰好 100 点封块、101 点两块（1+100）；1 小时边界（末点-首点
      =3_599_999 同块 / =3_600_000 开新块）；空输入零块；单点成块；
      同输入两次 pack 块 id 完全一致（幂等）；跨设备 id 前缀不同。
- **Acceptance Criteria Addressed**: AC-1、AC-3、AC-12（haversine 与 Web 同算法
  互证）
- **Test Requirements**:
  - `rule` TR-3.1: `./gradlew :app:testDebugUnitTest` 通过，新增 ≥10 个断言场景
    全绿；证据：测试输出与测试类清单。
  - `rule` TR-3.2: core 包 grep `import android\.|lazysodium` 无匹配；
    证据：grep 结果。
  - `rubric` TR-3.3: 纯函数设计质量；scale 1-5；anchors 1=降频/分块散落在
    Service 不可测，3=抽出但有残余框架耦合，5=全部系统无关分支纯函数化且常量
    命名清晰；threshold >= 4；证据：代码评审。
- **Notes**: 块明文 JSON 字段名（snake_case）即 docs/module-schemas.md 与
  Web decode.ts 的契约，定稿后三方逐字段一致。

## Task 4: Android Room v4 迁移（location_points + location_outbox）

- **Status**: `completed`
- **Completion Evidence**:
  - TR-4.1：`:app:assembleDebug :app:assembleDebugAndroidTest
    :app:testDebugUnitTest` BUILD SUCCESSFUL, EXIT=0；JVM 51 tests / 0 failures
    不回归；新增 `migrate3To4_addsLocationTablesAndKeepsData` instrumented 用例
    （两新表列/PK/可空性逐列断言、旧三表数据保留、points/outbox 读写、
    DEFAULT 0 与 INSERT OR IGNORE 幂等），instrumented 编译参与（真机冒烟
    并入 FU-7 待设备环境）。
  - TR-4.2：MIGRATION_3_4 仅 3 条 CREATE（location_points + ts 索引 +
    location_outbox），主会话 grep 核实零 ALTER/DROP 既有表；KSP 生成
    `EveDatabase_Impl` 建表语句与迁移 SQL 逐列核对一致。
  - 合理偏离记录：upsertIgnore 因 Room 限制（INSERT 查询方法仅允许
    void/long 返回）改为 `@Insert(onConflict = IGNORE): Long` + 接口默认方法
    翻译为影响行数（1/0），对外"INSERT OR IGNORE 幂等判定"语义不变。
- **Priority**: high
- **Depends On**: Task 3
- **Description**:
  - 新建 `collector/location/db/`：
    - `LocationPointEntity`：`id INTEGER PK AUTOINCREMENT`、`ts INTEGER NOT NULL`、
      `lat/lon REAL NOT NULL`、`acc REAL NOT NULL`、`speed/bearing REAL NULL`、
      `altitude REAL NULL`、`provider TEXT NULL`、`created_at INTEGER NOT NULL`；
      索引 `(ts)`。
    - `LocationOutboxEntity`：`block_id TEXT PK`、`start_ts/end_ts INTEGER
      NOT NULL`、`point_count INTEGER NOT NULL`、`cipher BLOB NOT NULL`、
      `created_at INTEGER NOT NULL`、`attempts INTEGER NOT NULL DEFAULT 0`。
    - `LocationDao`：points——insert、oldestFirst()（按 ts ASC 全取）、
      deleteUpToTs(ts)（删除已封块段）、deleteExpired(beforeTs)（24h 过期）、
      countSince(dayStartTs)（今日点数）；outbox——upsertIgnore（
      `INSERT OR IGNORE`，返回影响行数供幂等判断）、pending()（按 created_at
      ASC）、delete(blockIds)、count()。
  - `EveDatabase` 升 version=4、注册两实体与 DAO、新增 `MIGRATION_3_4`
    （仅 `CREATE TABLE IF NOT EXISTS location_points / location_outbox` +
    索引，不触碰既有三表）。
  - instrumented `MigrationTest` 增补 v3→v4 断言：两新表列/PK 正确、旧
    records/sync_state/collector_state 数据保留、points/outbox 基本读写。
- **Acceptance Criteria Addressed**: AC-3、AC-4
- **Test Requirements**:
  - `rule` TR-4.1: `:app:assembleDebug :app:assembleDebugAndroidTest` BUILD
    SUCCESSFUL；新增 v3→v4 迁移用例；证据：构建输出 + 测试代码。
  - `rule` TR-4.2: MIGRATION_3_4 仅 CREATE TABLE/INDEX，不 ALTER/DROP 既有表；
    证据：迁移代码评审。
- **Notes**: location_points 是全库唯一明文坐标驻留点，其生命周期（封块即删/
  24h 过期）由 Task 5 保证；outbox 只存密文，last_error 类字段一律枚举原因。

## Task 5: Android 封块密封编排（明文→密文 outbox）

- **Status**: `completed`
- **Completion Evidence**:
  - TR-5.1：CryptoEnvelope.kt 142-151 行增补 `locationBlockAAD`/
    `sealLocationBlock`/`openLocationBlock`，与 tasks.md 代码块逐字一致
    （AAD=`eve:v1:location-block:`+blockId，复用 private aeadSeal/aeadOpen，
    主会话 Read 核实）；`:app:assembleDebug :app:assembleDebugAndroidTest
    :app:testDebugUnitTest` BUILD SUCCESSFUL, EXIT=0；instrumented
    LocationPackagerAndroidTest 5 用例编译参与（真机冒烟并入 FU-7）。
  - TR-5.2：密封成功后 `deleteUpToTs(块覆盖最大 ts)` 删明文（上界删除
    安全性：pack 输入即全量 oldestFirst，天然成立）；grep 实测——
    `Log\.[dwiev]` @ collector/location/ 无匹配；异常路径仅
    `throw ce`（取消异常原样上抛无消息构造）；LocationPackager 零日志、
    异常一律翻译 PackSkipReason 枚举。
  - 跨端锚点（Task 8 输入）：`LocationBlockAnchorJvmTest`（纯 JVM，
    SodiumAndroid 直接映射路线 + mingw 运行时预加载 + Assume 环境缺失跳过）
    直调线上 sealLocationBlock 产出固定向量——MK=hex 000102…1e1f、
    blockId=`dev-fixed-1:1700000000000:1700000060000`，明文 JSON 与密文
    hex 完整记录于测试与该任务主会话汇报（随机 nonce 一次性样例，
    Web 侧做 open 可逆断言）；测试内含长度/可逆/AAD 绑定三段断言全过。
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - `CryptoEnvelope.kt` 增补（复用既有 private aeadSeal/aeadOpen，参数不新造）：
    ```kotlin
    /** 轨迹块 AAD 域："eve:v1:location-block:" + blockId（块不可变，无版本号）。 */
    private fun locationBlockAAD(blockId: String): ByteArray =
        ("eve:v1:location-block:$blockId").toByteArray(Charsets.UTF_8)

    fun sealLocationBlock(key: ByteArray, plaintext: ByteArray, blockId: String): ByteArray =
        aeadSeal(plaintext, locationBlockAAD(blockId), key)

    fun openLocationBlock(key: ByteArray, sealed: ByteArray, blockId: String): ByteArray =
        aeadOpen(sealed, locationBlockAAD(blockId), key)
    ```
  - 新建 `collector/location/LocationPackager.kt`（挂 ServiceLocator，输入
    appContext/auth/LocationDao/moshi）：
    - `packPending(): PackResult(sealedBlocks, expiredDropped, skippedReason?)`：
      MK=null 立即返回 `skippedReason="mk_unavailable"`（防御，调用方已拦截）；
      先 `deleteExpired(now - POINT_EXPIRY_MS)` 记 expiredDropped；再
      oldestFirst() 全取 → `BlockPacker.pack(deviceId, points)` → 逐块 moshi
      序列化（UTF-8）→ `sealLocationBlock` → outbox `upsertIgnore`（行数=0
      说明同 id 已在队，幂等）→ 成功后 `deleteUpToTs(最后已封点ts)` 删除明文；
      任何异常只记枚举原因，坐标明文禁入日志/异常消息。
    - PackResult 只含计数与枚举（NFR-1）。
  - instrumented `LocationPackagerAndroidTest`（编译参与，运行需设备）：
    密封后 openLocationBlock 可逆、outbox 落行、明文行已删、重复 pack 幂等、
    过期点清除。
- **Acceptance Criteria Addressed**: AC-3、AC-4
- **Test Requirements**:
  - `rule` TR-5.1: AAD 域为 `eve:v1:location-block:` + blockId，与
    docs/crypto.md（Task 11 登记）逐字节一致；证据：代码评审 + instrumented
    可逆用例。
  - `rule` TR-5.2: 密封成功后对应明文行删除；grep 证明坐标不流向
    Log/异常 message；证据：grep + 评审。
- **Notes**: 删除明文用"块覆盖的最大 ts"上界批量删，须保证该 ts 之前的点全部
  已入块（pack 输入即全量 oldestFirst，天然成立）。

## Task 6: Android 前台定位服务、权限模型与生命周期

- **Status**: `completed`
- **Completion Evidence**:
  - TR-6.1：主会话 Grep 核实 AndroidManifest.xml——L15-21 恰六权限
    （ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION /
    ACCESS_BACKGROUND_LOCATION / FOREGROUND_SERVICE /
    FOREGROUND_SERVICE_LOCATION / POST_NOTIFICATIONS，无
    ACTIVITY_RECOGNITION 等多余项）；L46 非导出
    `LocationTrackingService`（foregroundServiceType="location"）；
    L52 BootReceiver（exported=true，系统广播必需）。
  - TR-6.2：前置检查抽为纯函数 `core/TrackStartCheck.kt`
    `blockedReason` 四分支（disabled / mk_unavailable / fine_denied /
    background_denied），逐条可定位；`TrackStartCheckTest` 7 用例 JVM
    全绿；通知 channel `location_tracking`（LOW），文案"轨迹采集中 ·
    今日 N 点"无坐标。
  - TR-6.3：MK StateFlow 监听变 false → removeUpdates +
    stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf；BootReceiver
    捕获 BackgroundServiceStartNotAllowedException（API31+）与
    IllegalStateException 双路降级不崩溃；本机无设备，真机冒烟并入
    FU-7。
  - 门禁：`:app:assembleDebug :app:assembleDebugAndroidTest
    :app:testDebugUnitTest` BUILD SUCCESSFUL, EXIT=0。
  - 偏离记录（子代理申报，主会话逐条核实认可）：
    1. tasks.md 假设"BootReceiver 已有"，实际仓库仅有
       RECEIVE_BOOT_COMPLETED 权限声明、无 Receiver 类 → 新建
       BootReceiver.kt 并注册 Manifest；
    2. FR-8"解锁后自动恢复"无既有拉起点 → MainActivity 增补最小挂钩
       （collect isUnlocked 变 true 且开关/canTrack 复核 →
       startForegroundService，IllegalStateException 降级）；
    3. onLocationChanged 中 tryUpload() 暂不调用，以注释锚定挂载点，
       上行实现归 Task 7；
    4. 后台定位系统设置引导 UI 归 Task 7 采集页卡片（本任务仅
       TrackStartCheck 纯函数判定 + LocationPermissionGate）。
- **Priority**: high
- **Depends On**: Task 5
- **Description**:
  - 新建 `collector/location/LocationTrackingService.kt`（
    `foregroundServiceType="location"`，START_STICKY）：
    - onStartCommand 前置检查：轨迹开关开 && MK 非空 && FINE 已授权 &&
      后台定位"始终允许"；任一不满足 → stopSelf（记枚举原因，不弹窗打扰）。
    - 启动后：创建/复用通知 channel（`location_tracking`，LOW 重要性），
      常驻通知文案仅"轨迹采集中 · 今日 N 点"（无坐标）；注册 LocationManager
      ——GPS_PROVIDER 优先，`isProviderEnabled`  false 时 NETWORK_PROVIDER 兜底
      （两者都不可用则挂起等待 provider 回调）；请求参数取自
      `RateDecider.decideInterval` 输出（每次 onLocationChanged 后重算，档位
      变化时 removeUpdates + 重新 requestLocationUpdates，minDistance 恒 25m）。
    - onLocationChanged：`PointFilter.accept` 通过 → 写 location_points →
      调 `LocationPackager.packPending()` → 调 Task 7 `LocationUploader.
      tryUpload()`（在线即传）；通知计数刷新。
    - MK 监听：AuthManager 暴露的解锁态变化回调（或轮询兜底）——MK 变 null 即
      removeUpdates、stopForeground(STOP_FOREGROUND_REMOVE)、stopSelf
      （mk_unavailable 语义，沿用阶段 3）。
    - onDestroy 释放 listener；异常一律翻译为枚举原因。
  - 新建 `collector/location/LocationPermissionGate.kt`（对齐阶段 3
    PermissionGate 单点风格）：`fineGranted(context)`、
    `backgroundGranted(context)`（API29+ 检查 ACCESS_BACKGROUND_LOCATION）、
    `notificationsGranted(context)`（API33+）、`canTrack(context)` 组合判定。
  - 修改 `AndroidManifest.xml`：恰新增六权限（ACCESS_FINE_LOCATION /
    ACCESS_COARSE_LOCATION / ACCESS_BACKGROUND_LOCATION / FOREGROUND_SERVICE /
    FOREGROUND_SERVICE_LOCATION / POST_NOTIFICATIONS）+ `<service
    android:name=".collector.location.LocationTrackingService"
    android:foregroundServiceType="location" android:exported="false"/>`。
  - `BootReceiver`（已有 RECEIVE_BOOT_COMPLETED）增补：轨迹开关开则
    `ContextCompat.startForegroundService` 启动服务，捕获
    `BackgroundServiceStartNotAllowedException`（API31+）与
    `IllegalStateException` 降级为等待下次解锁/启动，不崩溃。
- **Acceptance Criteria Addressed**: AC-1、AC-2、AC-7
- **Test Requirements**:
  - `rule` TR-6.1: Manifest diff 恰新增六权限 + 一个非导出 service（无
    ACTIVITY_RECOGNITION 等）；证据：AndroidManifest.xml 评审。
  - `rule` TR-6.2: 前置检查（开关/MK/FINE/后台定位）四分支在代码评审逐条
    可定位；通知文案无坐标；证据：代码评审。
  - `rule` TR-6.3: MK=null 路径 removeUpdates 并退出前台；BootReceiver 两路
    异常有 catch 降级；证据：代码评审（真机冒烟记录于 tasks.md，无设备并入
    FU-7）。
- **Notes**: 服务内不写日志明文；`RateDecider` 输入用 Room 最近窗口点
  （oldestFirst 取 ts ≥ now-10min），服务重启后档位判定自然延续。

## Task 7: Android 上行链路与采集页第 4 卡片

- **Status**: `completed`
- **Completion Evidence**:
  - TR-7.1：`LocationUploader.kt` MAX_BATCH_SIZE=50（L84）+ `chunked`
    切片（L108）；单批成功→delete 该批 blockIds；4xx→attempts+1 记
    REJECTED_4XX 留队并继续下一批；网络/5xx→attempts+1 记
    NETWORK_ERROR 留队并中止本轮（防好块 attempts 误推上限）；
    attempts≥MAX_ATTEMPTS=8 坏块跳过记 give_up 不删不丢（L100-102）；
    UploadOutcome 只含计数/枚举（主会话 Grep 核实 L28-169）。
    instrumented `LocationUploaderAndroidTest` 6 用例编译参与（真机
    冒烟并入 FU-7）。
  - TR-7.2：`CollectorScreen.kt` 第 4 卡片（L247 挂载、L401-548 实现）
    四状态分支齐备（未授权/仅前台/齐备采集中/MK 不可用降级）；合规
    告知三要素+常驻通知+随时关闭（L441-442，主会话 Grep 核实）；开关
    前置检查沿用 P3-2 派生模式（markLocationPermissionRequested 持久化
    +实时 rationale，无 remember 内存态）。
  - 门禁：`:app:assembleDebug :app:assembleDebugAndroidTest
    :app:testDebugUnitTest` BUILD SUCCESSFUL, EXIT=0。
  - 偏离记录（子代理申报，主会话核实认可）：
    1. tasks.md 所称 `ApiClient.kt` 不存在——仓库 API 封装实际在
       `api/EveApi.kt`（Retrofit）+ `api/Dtos.kt`，uploadLocationBlocks
       按仓库事实落位（EveApi.kt L92-93，主会话 Grep 核实），DTO 五
       字段与服务端逐字段对齐（user_id/device_id 服务端 claims 注入
       不传）；
    2. "立即上传"复用阶段 3 一次性唯一任务（`uploadNow()=collectNow()`，
       oneShot 反馈），未新增独立 Worker；
    3. 后台定位引导复用 openAppDetails（Android 无公开的后台定位设置
       页 action，双兜底 try/catch）；
    4. 实现必需增补：LocationDao 增 incrementAttempts/
       observeCountSince/observeOutboxCount；CollectorSettings 增
       `location_last_upload_at` 键；ServiceLocator 注册
       locationUploader；Task 6 注释挂载点已接上 tryUpload()。
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - `api/ApiClient.kt` 增补 `uploadLocationBlocks(blocks: List<LocationBlockDto>):
    UploadResult`：POST /api/v1/locations/batch，cipher ByteArray→base64；
    DTO 与服务端 Task 2 对齐。
  - 新建 `collector/location/LocationUploader.kt`：
    - `tryUpload(): UploadOutcome`——outbox `pending()` 全取，按 ≤50 块/批
      切片顺次上行；单批成功 → `delete(该批 blockIds)`；失败（网络/5xx）→
      `attempts+1` 留队待下轮（指数退避由 WorkManager 周期窗口天然提供）；
      4xx 校验错误记录枚举原因并留队（不无限重试同一坏块：attempts ≥8 的块
      跳过并记 `give_up`，待真机/排查，不删不丢）。
    - 结果模型只含计数/枚举。
  - `CollectorWorker` doWork 末尾追加兜底：MK 非空时先 `packPending()`，
    再无条件 `uploader.tryUpload()`（与 repo.sync() 同 15 分钟窗口，不新增
    唤醒）；FGS 运行期封块后已由 Task 6 触发即传，周期兜底离线残留。
  - 采集页新增第 4 卡片"位置轨迹"（CollectorScreen + ViewModel）：
    - 开关：开启前置检查——FINE 未授权走 rememberLauncher 运行时申请
      （复用阶段 3 markPermissionRequested/永久拒绝派生模式）；FINE 已授权但
      后台定位未"始终允许"→ 跳转系统设置页引导（
      ACTION_APPLICATION_DETAILS_SETTINGS 或厂商后台定位页，try/catch 兜底）；
      两者齐备才启动 FGS 并置开关；关闭即 stopService。
    - 状态行：权限态（前台/后台/通知三项）、今日已采点数
      （`countSince(今日0点)`）、待传块数（outbox count）、上次上传时间；
    - "立即上传"按钮 → 一次性唯一任务或直接调用（对齐阶段 3 立即采集模式）；
    - 合规告知追加：明文点本地短暂缓冲、加密后即删、最长 24 小时；常驻通知
      可见；随时关闭。
  - `AppNav`/入口无需新增路由（复用采集页），卡片排序置于通话记录之后。
- **Acceptance Criteria Addressed**: AC-5、AC-8
- **Test Requirements**:
  - `rule` TR-7.1: 上行分批（>50 块切片）、成功删队、失败留队且 attempts 递增、
    坏块 give_up 不丢；证据：代码评审 + instrumented 用例（编译）。
  - `rule` TR-7.2: 卡片四状态（未授权/仅前台/齐备采集中/MK 不可用降级）分支
    齐备，告知文案含缓冲明示三要素；证据：代码走查。
  - `rubric` TR-7.3: 卡片 UI 体验质量；scale 1-5；anchors 见 spec AC-8；
    threshold >= 4；证据：独立评审走查（Review 阶段打分）。
- **Notes**: 权限"永久拒绝"判定必须沿用阶段 3 P3-2 修复后的派生模式
  （perm_requested_* 持久化 + rationale），不得回退 remember 内存态。

## Task 8: Web 轨迹纯函数核心（TS + Vitest 基建）

- **Status**: `completed`
- **Completion Evidence**:
  - TR-8.1：`npm test`（vitest run）4 文件 28 用例全绿（≥12 达标）——
    geohash 5（含已知对照 lat=57.64911,lon=10.40744,p=11→`u4pruydqqvj`）、
    decode 7、stays 7、days 7；`npm run build`（vue-tsc --noEmit && vite
    build）✓ built in 12.01s EXIT=0 不回归。vitest 5.0.1 基建落地
    （`"test": "vitest run"`、vite.config.ts 改自 `vitest/config` 导入 +
    `test:{environment:'node'}`）。
  - TR-8.2：core 包全部 import 仅相对路径模块 + `../../crypto/envelope`
    （纯函数）+ vitest（仅测试文件）；无 vue/pinia/DOM/localStorage 任何
    框架或 DOM API import（执行代理 grep 自证）。
  - TR-8.3（rubric 自评 5/5）：六模块职责单一（types/geohash/decode/stays/
    days/stats）；detectStays 质心均值更新+100m/10min 阈值与 spec FR-10
    逐条对应；haversineM 与 Android GeoMath 逐行一致；跨夜 visit 归开始日
    不截断并 overnight 标注；28 用例覆盖漂移簇/乱序/负时区等边角。
  - 跨端锚点落地：decode.test.ts 内联 Task 5 Android 线上
    sealLocationBlock 真实产物（固定 MK hex 000102…1e1f、
    blockId=`dev-fixed-1:1700000000000:1700000060000`、密文 hex 全文），
    openLocationBlock 解密后 points 逐字段 toEqual 断言 PASS，并附 AAD 错/
    密文篡改/MK 错三负例——Android seal ↔ Web open 三端一致性锚点实证打通。
- **Priority**: high
- **Depends On**: Task 3（块明文 JSON 契约）
- **Description**:
  - 测试基建：`npm i -D vitest`；package.json 加 `"test": "vitest run"`；
    `vite.config.ts` 增 `test: { environment: 'node' }`（纯函数无需 jsdom）。
  - `web/src/crypto/envelope.ts` 增补（与 Android Task 5 逐字节一致）：
    ```ts
    export function locationBlockAAD(blockId: string): Uint8Array {
      return new TextEncoder().encode(`eve:v1:location-block:${blockId}`)
    }
    export function openLocationBlock(
      sodium: Sodium, key: Uint8Array, sealed: Uint8Array, blockId: string,
    ): Uint8Array {
      return open(sodium, key, sealed, locationBlockAAD(blockId))
    }
    ```
  - 新建 `web/src/locations/core/` 纯函数包（不依赖 vue/pinia/DOM）：
    - `types.ts`：`TrackPoint`（同 Android DTO snake_case 字段）、
      `LocationBlockJson`、`Visit`（startTs/endTs/centerLat/centerLon/
      pointCount/name?）、`Trip`（startTs/endTs/points/distanceM）、
      `DayTimeline`（visits/trips/stats）。
    - `geohash.ts`：`encodeGeohash(lat, lon, precision): string`
      （base32 字符表 `0123456789bcdefghjkmnpqrstuvwxyz`，经纬二分交替）。
    - `decode.ts`：`decryptBlock(sodium, mk, raw: ApiLocationBlock):
      LocationBlockJson`——fromBase64 → openLocationBlock → JSON.parse；
      `blockId(deviceId, startTs, endTs)` 同 Android 规则重建 AAD id。
    - `stays.ts`：`detectStays(points: TrackPoint[]): {visits, trips}`——按 ts
      升序；维护当前簇（质心=均值更新）；点距簇质心 ≤100m 并入簇，否则结算：
      簇驻留（末ts-首ts）≥10min → Visit，否则簇点并入 Trip 段；Trip 跨簇连续
      拼接，distanceM 用 haversine 累加；`haversineM` 与 Android GeoMath 同公式。
    - `days.ts`：`splitByLocalDay(points, visits, trips, tzOffsetMin)` 按本地
      日切分；Visit 归开始时刻所在日（跨夜不截断，时间线标注跨夜）。
    - `stats.ts`：`dayStats(timeline)`——总距离（trip distanceM 求和）、移动
      时长、停留数、轨迹点数。
  - Vitest 单测 `web/src/locations/core/__tests__/`：
    - geohash 已知对照（lat=57.64911, lon=10.40744, precision=11 →
      `u4pruydqqvj`；precision=7 前缀一致）；
    - detectStays：居家过夜跨零点（23:50→07:00 单 visit 不截断）、3 分钟驻足
      归 trip 不误判 visit、漂移簇（半径内抖动点不炸簇）、两段通勤夹一 visit；
    - stats：已知折线距离累加、visit 内位移不计入；
    - days：本地日边界（+08:00 零点前后分日）、visit 归开始日；
    - decryptBlock：与 Android 密封产物互通（用 Android instrumented 导出的
      固定样例密文 + 固定 MK 解密断言，样例以 hex/base64 内联）。
- **Acceptance Criteria Addressed**: AC-9、AC-10、AC-12
- **Test Requirements**:
  - `rule` TR-8.1: `npm test` 全绿，新增 ≥12 个断言场景；证据：测试输出。
  - `rule` TR-8.2: core 包不 import vue/pinia/DOM API；证据：grep/评审。
  - `rubric` TR-8.3: 纯函数设计质量；scale 1-5；anchors 同 TR-3.3 口径；
    threshold >= 4；证据：代码评审。
- **Notes**: 解密样例密文是跨端一致性锚点（Android seal ↔ Web open），
  生成方式记录于测试注释；明文坐标仅存在测试内存。

## Task 9: Web 轨迹页——数据装载、日历与时间线

- **Status**: `completed`
- **Completion Evidence**:
  - TR-9.1：装载/切日/高亮/展示格式化抽纯函数于
    `web/src/locations/month.ts`，place 索引纯函数于
    `web/src/locations/places.ts`；新增 3 测试文件（month/places/
    vault-place）；`npm test` 7 文件 72 用例全绿, EXIT=0（含真密文
    密封→解密分日全链路、版本不回退、墓碑移除用例）。
  - TR-9.2：主会话 Grep 核实——`web/src/locations/*.ts` 仅 month.ts
    L7 注释声明红线，无 localStorage/IndexedDB/console 实际调用；
    网络层 `api/locations.ts` 仅收发 from/to 数字与 cipher base64；
    timelines 为 shallowRef Map 仅驻内存；AppShell 四处清场（lock/
    logout/onLockUseRecovery/onAuthExpired）均接 `locations.reset()`。
  - TR-9.3（rubric）：留待 Review 阶段与 TR-10.x 合并评定。
  - 门禁：`npm run build`（vue-tsc 零错误 + vite build 成功）EXIT=0。
  - 说明（无偏离申报，主会话核实补充）：router 子路由用相对路径
    `'locations'`（AppShell children 内，生效 /locations，router
    L30 Grep 核实）；vault.ts ingest 增 `module='place'` 分流 +
    placeRecords 独立缓存（L107/153-170 Grep 核实）+ reset 同步清空；
    `PlaceData` 接口追加于 types/vault.ts；未新增任何依赖。
- **Priority**: high
- **Depends On**: Task 8
- **Description**:
  - `web/src/api/` 增补 locations 端点封装（对齐 client.ts rawFetch 模式）：
    `listLocations(from, to): Promise<ApiLocationBlock[]>`（GET 带 query）、
    `deleteLocations(from, to): Promise<{deleted}>`。
  - 新建 `web/src/stores/locations.ts`（pinia）：
    - `loadMonth(year, month)`：本地月初 00:00 → 次月月初 00:00 换算 UTC 毫秒
      （跨度 ≤62 天约束内）→ listLocations → 逐块 decryptBlock（auth store 的
      sodium/mk，未解锁时置错误态）→ 合并点流 → detectStays →
      splitByLocalDay 缓存为 `Map<dayKey, DayTimeline>`（仅内存，store reset
      时清空）；
    - `selectDay(dayKey)`、`daysWithData`（日历高亮集合）、
      `placesByGeohash`（命名匹配索引，见下）；
    - place 记录来源：扩展 vault store 识别 `module="place"`（ingest 路径对
      place 建独立缓存 `placeRecords`，不混入 pass/identity 列表）；
      locations store 按记录 id 的 `place:{geohash7}` 建 Map 供时间线/地图
      查名；version 取自该缓存（命名覆盖时 version+1）。
  - 新建 `web/src/views/LocationsView.vue`（naive-ui 组件 + 既有页面骨架）：
    - 顶部：月份切换 + 日历网格（自建轻量月历或 n-calendar），有数据日期
      高亮点标；选中日载入；
    - 当日统计行：总距离（km 一位小数）/移动时长/停留数/轨迹点数；
    - 时间线（左栏）：按开始时间排序的 visit 卡（名称或"未命名地点"、
      HH:mm–HH:mm、时长，跨夜标"跨夜"）与 trip 段（距离、时长、起止时刻）；
    - 空态（无数据日）、加载态、解密失败/未解锁错误态（不泄坐标）；
    - 地图容器占位（Task 10 填充，本任务先挂空 div 保证布局稳定）。
  - `router/index.ts` 注册 `/locations`（需登录守卫，对齐既有路由）；
    AppShell/导航菜单加"轨迹"入口。
- **Acceptance Criteria Addressed**: AC-9、AC-10
- **Test Requirements**:
  - `rule` TR-9.1: 装载/切日/高亮逻辑走 store 纯函数路径并有 Vitest 覆盖
    （store 逻辑抽可测函数）；证据：测试输出。
  - `rule` TR-9.2: 代码评审确认明文坐标不写 localStorage/IndexedDB、不进日志；
    网络层只见密文（cipher base64）；证据：评审记录。
  - `rubric` TR-9.3: 轨迹页体验质量；scale 1-5；anchors 见 spec AC-14；
    threshold >= 4；证据：独立评审走查（Review 阶段打分，与 TR-10.x 合并评定）。
- **Notes**: 未解锁态（mk=null）展示"解锁后查看轨迹"引导，不缓存任何明文。

## Task 10: Web 地图、回放与 visit 命名

- **Status**: `completed`
- **Completion Evidence**:
  - TR-10.1：`web/src/locations/playback.ts`（L13 SPEED_LEVELS=[1,4,16,60]、
    L40 positionAt 二分+线性插值+端点钳制+空态 null，主会话 Grep 核实）；
    `playback.test.ts` 12 用例全绿（插值中点/任意比例/端点钳制/空点流/
    同 ts 不除零）。
  - TR-10.2：瓦片 URL 校验抽纯函数 `web/src/locations/tile.ts`
    `isValidTileUrl`（`^https?://`+`{z}/{x}/{y}` 占位符）；tile.test.ts
    13 用例（合法生效并持久化、非法拒绝不覆盖、脏数据回退缺省 OSM）；
    设置入口 LocationMap.vue `applyTileUrl`。
  - TR-10.3：`vault.ts` savePlace（L268）——id=`place:{geohash7}`、明文
    `{name,category,center_lat,center_lon,radius_m:100}`→sealRecord→
    pushRecords（与 vault.save 同链路）→skipped>0 触发 sync(true) 补
    同步；vault-save-place.test.ts 5 用例（锚点 place:u4pruyd、
    openRecord 可逆、重复命名 version 1→2 缓存仅 1 条）。
  - 门禁：`npm test` 10 文件 102 用例全绿 EXIT=0；`npm run build`
    （vue-tsc+vite build）EXIT=0；package.json 增 leaflet@^1.9.4 +
    @types/leaflet@^1.9.22（主会话 Grep 核实）。
  - 偏离记录（子代理申报，主会话认可）：
    1. 回放点流取当日全部 trip 点拼接（visit 簇按 DayTimeline 契约不
       保留轨迹点），visit 时段内播放头在相邻 trip 端点间线性过渡——
       positionAt 接口下对现有数据模型的自然落地，未改 Task 8 类型；
    2. 地图右栏宽度 320px→420px（纯样式，容纳回放控制条）。
- **Priority**: high
- **Depends On**: Task 9
- **Description**:
  - 依赖：`npm i leaflet` + `npm i -D @types/leaflet`；全局引入
    `leaflet/dist/leaflet.css`。
  - 新建 `web/src/locations/playback.ts` 纯函数：
    `positionAt(points, t): {lat, lon, index} | null`（时间点二分 + 相邻点线性
    插值，t 越界取端点）、`SPEED_LEVELS = [1, 4, 16, 60]`；Vitest 单测（插值
    中点、端点钳制、空点流）。
  - 新建 `web/src/components/LocationMap.vue`：
    - 初始化 `L.map`，`L.tileLayer(url)`——url 读
      `localStorage['eve.locations.tileUrl']`，缺省
      `https://tile.openstreetmap.org/{z}/{x}/{y}.png` + OSM 署名；
    - 设置入口：瓦片 URL 输入框，校验 `^https?://` 且含 `{z}/{x}/{y}` 占位符
      才保存生效，非法值提示不保存；
    - 渲染：trip 段 `L.polyline`（统一配色）、visit `L.circleMarker` +
      `L.circle`（半径 100m，命名/未命名两态配色）、播放头 `L.marker`；
      选日变化时重绘并 fitBounds；
    - 明文坐标不持久化（瓦片 URL 例外，非轨迹数据）。
  - 回放控制条（LocationsView 内）：播放/暂停按钮、倍速循环（1x/4x/16x/60x）、
    时间轴滑块（range）；requestAnimationFrame 驱动播放头 t 推进（虚拟时间 =
    真实流逝 × 倍速），positionAt 更新地图 marker 并联动时间线当前项高亮。
  - visit 命名（LocationsView 时间线 visit 卡操作）：
    - "标记地点"对话框：快捷选项"家/公司" + 自定义文本输入；
    - `savePlace(name, category, visit)`：geohash7 = encodeGeohash(center, 7)，
      id = `place:${geohash7}`；查 placesByGeohash 取现存 version（无则 1，
      有则 +1）；明文 `{name, category, center_lat, center_lon, radius_m: 100}`
      JSON → `sealRecord(sodium, mk, plaintext, id, "place", version)` →
      `api.pushRecords`（与 vault.save 同链路）→ skipped>0 时触发 vault 补
      同步；成功后更新缓存，时间线/地图即时显示名称。
- **Acceptance Criteria Addressed**: AC-11、AC-12
- **Test Requirements**:
  - `rule` TR-10.1: playback 纯函数 Vitest 全绿（插值/钳制/空态）；
    证据：测试输出。
  - `rule` TR-10.2: 瓦片 URL 校验（合法自定义生效并持久化、非法拒绝）；
    证据：代码评审 + 单测（校验函数抽出）。
  - `rule` TR-10.3: savePlace 复用 sealRecord/pushRecords 同链路，id 形如
    `place:{geohash7}`，重复命名幂等覆盖（version 递增、不重复建记录）；
    证据：代码评审 + geohash 单测。
- **Notes**: Leaflet 默认 marker 图标资源在 vite 下需处理（用 circleMarker
  规避 png 路径问题，播放头用 divIcon 纯 CSS，不引图片资源）。

## Task 11: 文档同步

- **Status**: `completed`
- **Completion Evidence**:
  - TR-11.1：`docs/module-schemas.md` 第 6 章 6.1 块明文字段表与
    Android TrackPoint/LocationBlockJson、Web types.ts 逐字段对照
    一致（块头 4 字段+轨迹点 8 字段的名字/类型/必填性/单位逐项 ✅，
    含块 id `{deviceId}:{startTs}:{endTs}` 与 100 点/3_600_000ms
    分块边界）；支持矩阵追加 place 行与轨迹块说明行（主会话 Grep
    核实 L320/425/465）。
  - TR-11.2：`docs/crypto.md` 第 6 节 AAD 前缀 `eve:v1:location-block:`
    与 CryptoEnvelope.kt L144-145、web envelope.ts L110-112 逐字节
    一致（主会话 Grep 核实 crypto.md L211/224）。
  - 交付：crypto.md 第 6 节（后续章节顺延）；module-schemas.md 第 6
    章（轨迹块）+第 7 章（place）+目录+支持矩阵；android.md 4a 章
    （六权限/前台服务/缓冲纪律/MK 停采/BootReceiver 降级）+五厂商保活
    表追加后台定位行；README.md 进度行+轨迹页节（web.md 不存在走
    备选路线）；everything_plan.md 阶段 4a 勾选（L130-131，注明日程
    属 4b、八项增强在 Future Enhancements，主会话 Grep 核实）。
  - 偏离记录：1) docs/web.md 不存在，按 tasks.md 备选方案采用 README
    web 节路线；2) 全程仅改文档，未动任何代码与 tasks.md。
- **Priority**: medium
- **Depends On**: Task 3、Task 5、Task 8
- **Description**:
  - `docs/crypto.md`：AAD 域清单新增 `eve:v1:location-block:{blockId}`
    （轨迹块，无版本号、块不可变幂等），标注三端（Go 不涉及/Android seal/
    Web open）一致性要求。
  - `docs/module-schemas.md`：
    - 新增"位置轨迹块（Android 采集，服务端零知识月表存储）"章：块明文 JSON
      全字段表（device_id/start_ts/end_ts/points[].ts·lat·lon·acc·speed·
      bearing·altitude·provider，单位与可空性）、块 id 规则
      `{deviceId}:{startTs}:{endTs}`、分块策略（≤100 点/≤1h）、月表归属
      （start_ts UTC 月）、明文缓冲纪律（封块即删/24h 过期）、API 三端点与
      限额（≤256KB/块、≤50 块/批、≤62 天跨度）、"Web 解密后分析、服务端
      永不接触明文"边界；
    - 新增 place schema 节：module/type=place/place、字段（name/category/
      center_lat/center_lon/radius_m）、id 规则 `place:{geohash7}`、幂等覆盖
      语义；更新目录与支持矩阵。
  - `docs/android.md`：六权限用途与申请时机（后台定位须设置页"始终允许"）、
    前台服务行为（常驻通知/降频/精度过滤）、缓冲与过期纪律、MK 不可用停采
    语义、BootReceiver 降级、轨迹相关保活说明（对齐阶段 3 五厂商表追加后台
    定位行）。
  - Web 文档（docs/web.md 或 README web 节）：轨迹页使用说明（按月装载/
    选日/回放/命名）、瓦片隐私提示（瓦片请求暴露大致视窗给瓦片服务商、
    可自配源）、明文不持久化承诺。
  - `.trae/documents/everything_plan.md` 阶段 4a 勾选（注明日程属 4b、八项
    增强在 Future Enhancements）；README.md 进度行同步。
- **Acceptance Criteria Addressed**: AC-13
- **Test Requirements**:
  - `rule` TR-11.1: 块 schema 字段表与 Task 3 DTO / Task 8 types.ts 逐字段
    一致（评审逐行对照）；证据：评审记录。
  - `rule` TR-11.2: crypto.md AAD 域与 Task 5/Task 8 代码逐字节一致；
    证据：对照记录。
- **Notes**: Future Enhancements 八项只在 plan/spec 语境引用，docs 用户文档不
  提前承诺。

## Task 12: 全量门禁、证据固化与评审准备

- **Status**: `completed`
- **Completion Evidence**:
  - TR-12.1：三端门禁 2026-09-16 复跑退出码全 0——Android
    `gradlew.bat :app:assembleDebug :app:testDebugUnitTest
    :app:assembleDebugAndroidTest` BUILD SUCCESSFUL（13:57:30）；server
    `go test ./...`（GOOS/GOARCH 进程内覆盖）ok 5 包 / FAIL 0
    （13:57:58，internal/api 实跑 22.7s）；web `npm run build`
    （vue-tsc 零错误 + vite 2767 modules, 13:58:58）+ `npm test`
    10 文件 102 用例全过（13:59:54）。
  - TR-12.2：Get-Item 核实——`app-debug.apk` 16,983,748 B
    （2026-09-16 13:23:55）与 `app-debug-androidTest.apk` 330,909 B
    （13:23:56）均真实存在；LastWriteTime 早于门禁时刻系 71 任务全量
    up-to-date 未重打包，up-to-date 判定本身即产物与当前代码一致。
  - TR-12.3：16 条 AC 映射齐备——规则类 AC 全部有独立证据（逐条映射
    见执行汇报，无缺口）；AC-14/AC-15 为 rubric 类型，按本文件分工
    留 Review 阶段独立评审打分（TR-7.3/TR-9.3 同）。Android JVM 单测
    自 test-results XML 聚合：11 套件 59 用例 0 失败（轨迹相关 6 套件
    31 用例：BlockPacker 9/RateDecider 6/TrackStartCheck 7/GeoMath 4/
    PointFilter 4/锚点 1）。
  - 真机冒烟项关闭条件（无设备不阻塞，全部并入 FU-7）：FGS 权限流、
    后台定位持续采集（含国产 ROM 保活）、BootReceiver 开机自愈、
    包体安装、instrumented 三套件（MigrationTest/
    LocationPackagerAndroidTest/LocationUploaderAndroidTest）真机运行。
- **Priority**: high
- **Depends On**: Task 2、Task 7、Task 10、Task 11
- **Description**:
  - 复跑门禁并在本文件记录命令、时间戳、退出码与产物绝对路径：
    - Android（cwd=android，JAVA_HOME=`D:\Program Files\Java\jdk-17`，
      GRADLE_USER_HOME=`D:\Program Files\Gradle\gradle-home`）：
      `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest
      :app:assembleDebugAndroidTest`；
    - server（cwd=server，PATH 前置 `C:\Program Files\Go\bin`；**进程内显式
      `$env:GOOS="windows"; $env:GOARCH="amd64"`** 覆盖系统级交叉编译污染）：
      `go test ./...`；
    - web（cwd=web）：`npm run build` 与 `npm test`（vitest）。
  - APK 产物存在性核实（Get-Item：app-debug.apk 与 app-debug-androidTest.apk
    绝对路径 + 大小 + LastWriteTime，不止引用构建日志）。
  - 自验全部 16 条 AC 与 TR，回填各任务 Completion Evidence；随后进入 Review
    阶段，由全新上下文独立评审代理创建 review.md（含 TR-7.3/TR-9.3 等 rubric
    打分），不在本任务内执行。
- **Acceptance Criteria Addressed**: AC-16
- **Test Requirements**:
  - `rule` TR-12.1: 上述命令退出码全 0；证据：输出摘录。
  - `rule` TR-12.2: APK 两产物存在性以绝对路径 + 大小/时间戳确认；
    证据：Get-Item 输出。
  - `rule` TR-12.3: 每个 AC（1-16）均有至少一条独立证据；证据：本文件回填 +
    review.md。
- **Notes**: 真机冒烟项（FGS 权限流、后台定位、BootReceiver、包体安装）在无
  设备环境下记录关闭条件并入 FU-7，不阻塞门禁。
