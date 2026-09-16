# 阶段 4a — 位置轨迹 - 产品需求文档（PRD）

## Overview

- **Summary**：在 Android 端新增前台定位服务采集位置轨迹（纯原生 LocationManager，
  零 GMS 依赖），采样点在设备本地打包成块后以 MK 加密（XChaCha20-Poly1305 信封），
  经新增的批量 API 上行自托管服务端；服务端按月分表（`locations_YYYYMM`）零知识
  存储密文块；Web 端新增轨迹页，浏览器内解密后做停留点检测，以"左栏时间线 +
  右栏 Leaflet 地图"双视图回放当日行踪（形态参考 Reitti / Dawarich，但分析全部
  在客户端完成）。
- **Purpose**：把个人位置历史纳入"人生操作系统"的加密个人库，形成可回放、可命名、
  可被未来 AI 助理使用的行踪档案，同时维持服务端零知识边界（服务端只见密文与
  最小明文元数据）。
- **Target Users**：自托管 Everything 服务、使用 Android 主机的单一用户本人
  （不合规场景：跟踪他人设备；产品内做用途告知，前台服务常驻通知全程可见）。

## Goals

- 授权后持续采集：前台服务运行期间按 60s/25m 参数采样，静止自动降频省电。
- 采集即加密：明文点仅在本地有界缓冲，封块加密后立即删除明文；服务端只存密文。
- 用户可控：独立开关、权限引导、随时关闭；用途/限制/缓冲行为在 UI 内明示。
- 可回放：Web 轨迹页按日查看时间线（停留点/行程段）+ 地图轨迹，支持倍速回放。
- 地点可命名：停留点可标记"家/公司/自定义"，命名经既有 vault 通道加密同步。
- 可验证：采样/降频/分块/停留点/统计等核心逻辑抽为纯函数，由 JVM 与 TS 单测锁定。

## Non-Goals

- **不做**日程/日历/任务/提醒（阶段 4b 独立规划，本期仅为轨迹）。
- **不做**服务端空间分析/地理索引：零知识约束下服务端无法接触明文坐标，
  停留点/行程/统计全部在客户端（Web）解密后计算。
- **不做**多用户/位置共享/社交功能（单用户自托管定位不变）。
- **不做** iOS 端。
- **不做**交通方式识别所必需的 ACTIVITY_RECOGNITION 权限申请（该能力列入
  Future Enhancements，届时再评估权限与合规）。
- 不以 Google Play 上架为目标；延续自建 APK 分发路线。

## Future Enhancements

> 以下能力**并非明确不做**，本期因范围控制先记录在案，后续阶段逐一完善
> （用户 2026-09-16 决策）。各项落地时均须继承本期零知识纪律（服务端只见密文，
> 分析在客户端或经用户明示的本地能力完成）。

- **热图 / Fog of War**：地图热图层与"世界迷雾"式探索可视化（客户端解密后渲染）。
- **交通方式分类**：步行/骑行/驾车/公交等行程段自动标注（评估纯本地推断或
  Activity Recognition 权限的合规路径）。
- **统计洞察页**：按国家/城市/年/月的里程、停留、出行统计聚合视图。
- **GPX / GeoJSON 导入导出**：与第三方轨迹工具互操作（导出在客户端解密后生成；
  导入在客户端加密后入库）。
- **Live 实时模式**：轨迹页跟随最新上行块近实时刷新（服务端 hub 推送块到达事件，
  客户端解密追加）。
- **地理围栏提醒**：到达/离开命名地点时提醒（Android 本地围栏，明文不出设备）。
- **Android 端轨迹查看**：应用内地图/时间线浏览（当前仅 Web 端）。
- **照片集成**：时间线按时间关联系统相册照片（涉媒体读取权限，届时单独评估）。

## Background & Context

- 现有采集通道：阶段 3 已落地 CollectorEngine（手动 + 15 分钟周期 WorkManager），
  采集产物以 `CryptoEnvelope.sealRecord` 信封写 Room 并经 `repo.sync()` 走
  `/records/batch` 上行；MK 仅驻留内存（`AuthManager.masterKey`），未解锁进程
  无 MK，阶段 3 语义为"跳过采集、仅同步"。
- 信封机制：XChaCha20-Poly1305，AAD 绑定记录身份；轨迹块沿用同一加密原语与
  AAD 规则（AAD 绑块 id），不新造参数。
- 服务端路由：Chi `approved` 分组（`requireAccessToken` + `requireScope(
  auth.ScopeApproved)`）下挂 API；`records_handler.go` 提供批量幂等参考模式
  （单批上限校验、device_id 服务端覆盖、hub 广播、writeJSON/writeError）。
- 审计：`s.audit(userID, event, detail, ip)`（api/util.go）既有，轨迹三类事件
  直接复用。
- 迁移：服务端 `internal/db/migrations/` 现有 0001~0003，本期新增 0004 登记
  轨迹月表约定；Android Room 已到 v3，本期 v3→v4 显式迁移（新增两张轨迹表）。
- 参考形态：Reitti（时间线+地图双视图、停留点/行程检测、自定义地图样式）与
  Dawarich（多图层地图、Visits、统计洞察）均为服务端明文 + PostGIS 架构，
  Everything 零知识约束下不能照搬其服务端分析，仅借鉴交互形态。
- 时间纪律：FU-1 已落地服务端权威时间；块 `created_at` 由服务端写入，点/块的
  `ts` 取设备系统 UTC 毫秒。

## Functional Requirements

- **FR-1 定位采样（纯原生）**：前台服务内使用 LocationManager，优先 GPS_PROVIDER、
  不可用时 NETWORK_PROVIDER 兜底；请求参数 minTime=60s、minDistance=25m；
  精度 `accuracy > 100m` 的点丢弃。静止降频：滚动窗口 10 分钟内位移 < 50m 时
  将请求间隔降为 300s，恢复移动后回到 60s（降频判定与精度过滤均为纯函数）。
- **FR-2 前台服务与权限模型**：
  - Manifest 恰新增六项：`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`、
    `ACCESS_BACKGROUND_LOCATION`、`FOREGROUND_SERVICE`、
    `FOREGROUND_SERVICE_LOCATION`（API 34 强制）、`POST_NOTIFICATIONS`
    （API 33+ 前台服务通知）。不申请 ACTIVITY_RECOGNITION 等其余权限。
  - 打开"位置轨迹"开关触发 FINE/COARSE 运行时申请；后台定位（API 29+）须经
    系统设置"始终允许"引导（跳转设置页，不伪造运行时弹窗）；开关生效前置检查
    = FINE 已授权 + 后台定位已"始终允许"，缺一即引导、不启动服务。
  - 服务以 `foregroundServiceType="location"` 运行，常驻通知全程可见（内容仅
    状态/计数，不含坐标）；关闭开关即停服务。
- **FR-3 本地缓冲与打包分块**：Room v4 新增两表——`location_points`（明文缓冲：
  ts/lat/lon/acc/speed?/bearing?/altitude?/provider）与 `location_outbox`
  （密文块待传队列）。缓冲点达到 ≤100 点或距块首点 ≥1 小时即封块；块明文 JSON
  schema 以 docs/module-schemas.md 为准（块头 device_id/start_ts/end_ts +
  points 数组）。块 id = `{deviceId}:{startTs}:{endTs}`（UTC 毫秒），
  同参数重复封块产生同 id，幂等不重复。
- **FR-4 加密与明文纪律**：封块即以 MK 加密为 XChaCha20-Poly1305 信封
  （AAD 绑块 id）写入 outbox，随后**删除对应明文行**；明文缓冲设 24 小时过期
  （超时未加密则删除，防 MK 长期不可用导致滞留）；坐标明文严禁进日志/通知/
  SharedPreferences/崩溃消息。
- **FR-5 上行链路**：前台服务在线时封块即传；失败/离线入 outbox，由既有 15 分钟
  周期 Worker 兜底重传（指数退避，与 sync 同窗口不新增唤醒）。上行 API：
  `POST /api/v1/locations/batch`，单块密文 ≤256KB、单批 ≤50 块；服务端按块 id
  幂等 upsert（同 id 重复提交不产生重复块）。
- **FR-6 服务端存储**：`locations_YYYYMM` 按月分表（按块 start_ts 的 UTC 月份
  归属），DDL 为代码内常量，写入路径 `CREATE TABLE IF NOT EXISTS` 动态建表；
  列：`id TEXT PRIMARY KEY`（含 deviceId 前缀，全局唯一）、`user_id TEXT NOT NULL`
  （与 records 同口径的用户隔离）、`device_id TEXT NOT NULL`、`start_ts INTEGER`、
  `end_ts INTEGER`、`point_count INTEGER`、`cipher BLOB NOT NULL`、
  `created_at INTEGER`（服务端权威写入）；索引 `(user_id, start_ts)`。
  迁移 0004 登记该约定（文件内注释记录 DDL 与动态建表语义）。
- **FR-7 服务端 API**：三个端点均挂 approved 分组——
  - `POST /api/v1/locations/batch`：校验块数/单块大小/必填字段；device_id 以
    token claims 为准覆盖；写库后审计 `location.upload`（块数）。
  - `GET /api/v1/locations?from&to`：from/to 跨度 ≤62 天，按 start_ts 升序
    跨月表返回块（含密文）；审计 `location.download`（范围/块数）。
  - `DELETE /api/v1/locations?from&to`：范围删除跨月表执行；审计
    `location.delete`（范围/删除块数）。
  - 写库成功后经 hub 向该用户广播 `locations_changed` 事件（为后续 Live 模式
    留口，本期 Web 不订阅）。
- **FR-8 MK 不可用与生命周期**：MK 不在内存时不得新采点——前台服务停止定位并
  退出前台（沿用阶段 3 `mk_unavailable` 语义）；已缓冲明文行保留于应用沙箱
  Room 待解锁后封块（仍受 24h 过期约束）。BootReceiver 开机自愈：开关开启时
  尽力重启前台服务，遇系统后台启动限制（如
  BackgroundServiceStartNotAllowedException）降级为下次解锁/应用启动时恢复，
  不崩溃。
- **FR-9 Android 采集页第 4 卡片**："位置轨迹"卡片展示开关态、权限态（含后台
  定位是否"始终允许"）、今日已采点数、待传块数、上次上传时间；手动"立即上传"
  入口；卡片固定展示合规告知——数据仅入本人自托管服务器、端到端加密、常驻通知
  可见、可随时关闭，并明示"明文点在本地短暂缓冲、加密后即删、最长 24 小时"。
- **FR-10 Web 轨迹页-数据装载**：新增路由（如 `/locations`）；进入页面按当月
  （from=月初/to=月末，本地时区换算 UTC，跨度 ≤62 天约束内）拉取密文块，
  浏览器内存解密后按本地日切分；日历选日，有数据日期高亮；切换月份重新拉取。
  明文坐标不持久化（不写 localStorage/IndexedDB；瓦片源配置等非轨迹数据除外）。
- **FR-11 Web 停留点检测与时间线**：纯函数检测——按月解密点流全局按 ts 排序
  遍历，与当前停留簇质心距离 ≤100m 并入簇并更新质心/驻留时长，否则结算：
  簇驻留 ≥10 分钟 → visit，否则簇内点归入移动段；visit 归属其开始时刻所在
  本地日（跨夜停留不截断）。左栏时间线按日展示 visit 卡片（起止时间/时长/
  名称或未命名）与 trip 段（起止/距离/时长），顶部当日统计行：总距离
  （haversine 累加 trip 段）、移动时长、停留数、轨迹点数。
- **FR-12 Web 地图与回放**：右栏 Leaflet 地图，默认 OSM 瓦片，瓦片源 URL 可配
  （存 localStorage，表单校验 http/https）；渲染当日轨迹折线（trip 段）与
  visit 标记（圆点/半径圈）；回放支持播放/暂停/倍速（1x/4x/16x/60x）与时间轴
  滑块拖动，播放头移动时地图标记与时间线联动高亮。
- **FR-13 visit 命名**：visit 可命名"家/公司/自定义文本"，写入既有 vault
  records 通道：`module="place"`、`type="place"`，明文含 name/category/
  center_lat/center_lon/radius_m；id = `place:{geohash7(中心点)}`（geohash 编码
  以纯函数实现），同一地点重复命名幂等覆盖；时间线/地图优先展示已命名名称。
- **FR-14 文档**：module-schemas.md 增补轨迹块明文 schema（块头 + points 字段
  与单位）与 place schema（含 id 规则）；android.md 增补五项权限用途、前台服务
  行为、缓冲/加密纪律、后台定位设置引导与保活说明；web 使用文档增补轨迹页
  说明；everything_plan.md 阶段 4a 进度同步。

## Non-Functional Requirements

- **NFR-1 零知识红线**：服务端持久化与日志中不得出现明文坐标（仅块时间范围/
  点数/大小等元数据与密文）；Android 明文点仅存应用沙箱 Room 且有界（封块即删/
  24h 过期）；Web 明文坐标仅驻留浏览器内存；全链路日志/通知/审计禁坐标明文。
- **NFR-2 权限最小化**：恰申请 FR-2 所列权限，不申请 ACTIVITY_RECOGNITION、
  不导出任何组件、不为轨迹新增常驻网络长连。
- **NFR-3 可测性**：降频判定、精度过滤、分块策略、块 id 派生、geohash 编码、
  停留点检测、当日统计均抽为不依赖 Android Framework / 浏览器的纯函数，
  由 JVM（Kotlin）与 Vitest（TS）单测覆盖；Room v3→v4 走显式迁移并有
  instrumented 迁移测试（无设备环境至少编译通过）。
- **NFR-4 功耗**：静止降频 + 单前台服务实例；上行复用既有周期窗口兜底，
  不新增唤醒锁滥用；无点时段除 LocationManager 回调外零网络/加密开销。
- **NFR-5 兼容性**：minSdk 26 / targetSdk 35；零 GMS 依赖；国产 ROM 杀后台/
  权限收紧场景不崩溃，降级为"解锁后恢复/手动上传"，保活引导仅提升概率。
- **NFR-6 隐私合规**：用途告知与常驻通知全程可见；随时可关闭采集；提供
  范围删除接口与入口说明。
- **NFR-7 一致性**：Android 沿用 ServiceLocator 手动注入/Room 显式迁移/Compose
  Material3/中文详细注释；服务端沿用 Chi 分组/audit/vault 既有模式；Web 沿用
  stores/crypto(envelope)/router 分层；不新造平行链路。

## Constraints

- **Technical**：
  - 加密复用 CryptoEnvelope 同参数（Argon2id/XChaCha20-Poly1305 与 AAD 规则
    不得新造）；轨迹块 AAD 绑块 id。
  - 服务端零知识：不解密、不校验块内点数真实性（`point_count` 为客户端自声明
    元数据，仅作展示/配额参考；服务端只约束密文字节上限）。
  - 月表一律按块 start_ts 的 UTC 月份归属与命名，避免时区歧义。
  - 单块密文 ≤256KB、单批 ≤50 块、GET 跨度 ≤62 天（服务端强制）。
  - Web 明文坐标不持久化；地图瓦片请求会暴露大致视窗给瓦片服务商（默认 OSM
    公共瓦片），文档中明示并允许自配瓦片源。
- **Business**：仅本人自托管场景；应用内必须有用途告知与常驻通知，避免跟踪类
  使用观感。
- **Dependencies**：Android 不新增第三方库（LocationManager 为框架 API）；
  Web 新增运行时依赖 `leaflet`（及其类型定义）与开发依赖 `vitest`
  （此前 Web 端无单测基建，本期建立）；服务端预计无新增依赖
  （chi/modernc.org/sqlite 既有）。

## Assumptions

- 单用户单账号；设备 id 首次配对后稳定，多设备块经 id 前缀天然隔离。
- 设备系统时间大致准确（点 ts 取系统 UTC 毫秒）；异常时钟导致的块时间漂移
  由用户在删除接口下自行处理，不做服务端矫正。
- 室内/隧道/飞行模式等无定位空洞属正常，轨迹允许断段，时间线如实呈现。
- 后台定位在国产 ROM 上可能被限制或杀死，保活指引仅提升概率；被杀后按
  FR-8 路径恢复，期间空洞可接受。
- 瓦片服务可用性/隐私由用户选择瓦片源自担（默认可用 OSM 公共瓦片）。

## Acceptance Criteria

### AC-1: 采样参数、精度过滤与静止降频
- **Type**: `rule`
- **Given**: 位置轨迹开关开启且权限齐备的 Android 设备
- **When**: 前台服务运行，输入模拟点流（含精度超差、长时间静止、恢复移动场景）
- **Then**: 请求参数为 minTime=60s/minDistance=25m；accuracy>100m 的点被丢弃；
  滚动 10 分钟位移<50m 时目标间隔降为 300s，恢复移动回到 60s
- **Pass Condition**: 降频/过滤纯函数 JVM 单测全绿（边界等值、窗口滑动、恢复
  档位）；服务代码审查确认参数落地
- **Evidence**: 单测输出、LocationTracker 相关代码

### AC-2: 权限模型与前台服务生命周期
- **Type**: `rule`
- **Given**: 全新安装的应用
- **When**: 检查 Manifest，并在采集页操作"位置轨迹"开关（允许/拒绝/仅前台
  授权/后台始终允许/关闭）
- **Then**: Manifest 恰新增 FR-2 所列六项权限（无 ACTIVITY_RECOGNITION 等）；
  FINE 未授权或后台定位未"始终允许"时开关不生效并给出对应引导（运行时弹窗 /
  系统设置页）；服务以 location 类型前台运行且常驻通知可见（通知无坐标）；
  关闭开关服务停止
- **Pass Condition**: AndroidManifest.xml 审查；开关前置检查与权限态判定逻辑
  可在单测/设备冒烟验证；编译产物存在
- **Evidence**: Manifest diff、Collector 页/服务代码、APK 产物路径

### AC-3: 分块策略与幂等块 id
- **Type**: `rule`
- **Given**: 缓冲中持续累积明文点
- **When**: 点数达 100、或距块首点达 1 小时、或同一批点重复触发封块
- **Then**: 分别产出 ≤100 点块、按 1 小时窗口封块；块 id 形如
  `{deviceId}:{startTs}:{endTs}`；重复封块产生同 id，outbox/服务端均不重复
- **Pass Condition**: 分块策略与 id 派生纯函数单测全绿（恰好 100、跨小时边界、
  空缓冲、单点块）
- **Evidence**: 单测输出、Packer 相关代码

### AC-4: 加密密封与明文滞留红线
- **Type**: `rule`
- **Given**: 已封块的明文点
- **When**: 封块加密完成；以及构造 MK 不可用超过 24 小时的场景（时间参数注入）
- **Then**: outbox 出现 XChaCha20-Poly1305 密文块（AAD 绑块 id），对应明文行
  即删；超 24h 未加密明文被清除；grep 证明坐标明文不流向日志/通知/偏好
- **Pass Condition**: 密封/删除/过期逻辑单测与代码审查；grep 模式（Log[.dwiev]、
  putString、通知文案）零命中或带说明
- **Evidence**: 单测输出、grep 检查记录

### AC-5: 批量上行与幂等入库
- **Type**: `rule`
- **Given**: outbox 中有若干密文块，服务端为空库
- **When**: 触发上行（含同一批重复提交）
- **Then**: `POST /api/v1/locations/batch` 接受 ≤50 块/批、单块 ≤256KB（超限
  400）；服务端按块 id 幂等 upsert，重复提交块数不增；上传成功后 outbox 清除；
  审计表出现 `location.upload`
- **Pass Condition**: 服务端 handler 单测全绿（上限/幂等/claims 覆盖）；
  Android 上行代码审查确认复用周期窗口与退避
- **Evidence**: go test 输出、上行链路代码、审计记录断言

### AC-6: 月表动态建表与范围查询/删除
- **Type**: `rule`
- **Given**: 写入跨 UTC 月份的若干块
- **When**: 写入、按范围 GET、按范围 DELETE
- **Then**: 自动创建 `locations_YYYYMM`（列与索引符合 FR-6）；GET 按 start_ts
  升序跨月表返回且跨度 >62 天被拒；DELETE 跨月表删除并返回块数；两类读删各
  产生 `location.download`/`location.delete` 审计；迁移 0004 存在且语义为
  动态建表约定
- **Pass Condition**: 服务端单测覆盖跨月写入/查询/删除与跨度上限；迁移文件审查
- **Evidence**: go test 输出、0004 文件、store 层代码

### AC-7: MK 不可用停采与开机自愈
- **Type**: `rule`
- **Given**: 开关开启的设备
- **When**: MK 变为不可用（锁定/进程重建未解锁）；以及设备重启
- **Then**: MK 不可用时服务停止定位并退出前台、不新采点，已缓冲明文保留且
  受 24h 过期约束；BootReceiver 尽力重启服务，遇后台启动限制捕获降级不崩溃；
  解锁后采集与上传自动恢复
- **Pass Condition**: 生命周期分支代码审查；可注入的状态判定逻辑单测；设备
  冒烟证据记录于 tasks.md（无设备则记录关闭条件）
- **Evidence**: 服务/BootReceiver 代码、单测输出、冒烟记录

### AC-8: 采集页卡片与合规告知
- **Type**: `rule`
- **Given**: 采集页
- **When**: 查看"位置轨迹"卡片并操作开关/立即上传
- **Then**: 卡片展示开关态/权限态（含后台定位）/今日点数/待传块数/上次上传；
  固定告知文案包含"仅本人服务器、端到端加密、常驻通知可见、可随时关闭"与
  明文缓冲明示（加密即删、最长 24h）
- **Pass Condition**: UI 代码与文案审查；状态来源（Room 统计查询）真实非 mock
- **Evidence**: CollectorScreen 相关代码、文案截图或代码行

### AC-9: Web 数据装载与零知识纪律
- **Type**: `rule`
- **Given**: 服务端存有当月密文块
- **When**: 打开轨迹页、切换月份、选中有数据/无数据日期
- **Then**: 当月块一次拉取（跨度 ≤62 天），浏览器内解密并按本地日切分；日历
  高亮有数据日；无数据日显示空态；devtools 验证明文坐标未写入
  localStorage/IndexedDB，网络面板只见密文
- **Pass Condition**: 装载/切日纯函数 TS 单测；代码审查确认无明文持久化路径
- **Evidence**: 单测输出、store/view 代码、审查记录

### AC-10: 停留点检测、时间线与当日统计
- **Type**: `rule`
- **When**: 输入构造点流（居家过夜跨零点、通勤两段、短时驻足 3 分钟、精度漂移
  簇）
- **Then**: ≥10 分钟且半径 ≤100m 的簇判定为 visit（跨夜 visit 归开始日、不被
  截断），3 分钟驻足归入 trip 不误判；时间线 visit/trip 次序与起止正确；统计行
  距离=haversine 累加 trip 段、移动时长/停留数/点数正确
- **Pass Condition**: 停留点检测与统计纯函数 TS 单测覆盖上述场景全绿
- **Evidence**: 单测输出、检测器代码

### AC-11: 地图渲染、瓦片配置与回放
- **Type**: `rule`
- **When**: 选中某日并点击播放、拖动时间轴、修改瓦片源 URL（合法/非法）
- **Then**: Leaflet 正确渲染轨迹折线与 visit 标记（默认 OSM）；瓦片 URL 校验
  通过即生效并持久化（localStorage），非法值拒绝并提示；播放/暂停/倍速/拖动
  联动地图标记与时间线高亮
- **Pass Condition**: 组件代码审查；回放控制纯逻辑（时间轴换算）单测；构建通过
- **Evidence**: 组件/单测代码、npm run build 输出

### AC-12: visit 命名与幂等同步
- **Type**: `rule`
- **Given**: 某 visit 与已解锁 vault
- **When**: 命名为"家"，再次命名/修改为"公司"
- **Then**: 经既有 records 通道产生 `module=place/type=place` 密文记录，id 为
  `place:{geohash7(中心点)}`；重复命名幂等覆盖不重复建记录；刷新后时间线/地图
  展示新名称
- **Pass Condition**: geohash 纯函数单测（已知坐标对照）；记录构造代码审查
  确认复用 envelope/vault 通道
- **Evidence**: 单测输出、命名链路代码

### AC-13: 文档与计划同步
- **Type**: `rule`
- **When**: 实现完成
- **Then**: module-schemas.md 含轨迹块明文 schema（块头+points 字段/单位）与
  place schema（含 geohash id 规则）；android.md 含权限/前台服务/缓冲纪律/后台
  定位引导/保活章节；web 文档含轨迹页说明（含瓦片隐私提示）；plan 阶段 4a
  进度同步
- **Pass Condition**: 文档评审通过且字段与代码/DTO 一致
- **Evidence**: 文档 diff

### AC-14: Web 轨迹页体验质量
- **Type**: `rubric`
- **Dimension**: 轨迹页（时间线+地图+回放+命名）的用户体验质量
- **Scale**: 1-5
- **Anchors**: 1 = 视图错乱/解密失败无提示；3 = 功能可用但空态/加载/错误态粗略，
  回放生硬；5 = 装载-选日-回放-命名闭环顺畅，空/错/加载态清晰，双视图联动自然
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对轨迹页走查（含空日/跨夜/大量点/未命名 visit 四态）

### AC-15: 架构一致性与可测性
- **Type**: `rubric`
- **Dimension**: 新增代码与既有架构的契合度及纯函数可测性
- **Scale**: 1-5
- **Anchors**: 1 = 新造平行存储/网络/加密路径，逻辑耦合在 Activity/组件不可测；
  3 = 复用主通道但检测/分块逻辑散落、部分单测；5 = 纯函数核心 + 薄平台适配层，
  完全复用信封/Room/Worker/Chi 分组/audit/envelope.ts，单测覆盖全部平台无关
  分支
- **Pass Threshold**: >= 4
- **Evidence**: 代码评审 + 单测清单与覆盖率（分支枚举）

### AC-16: 全量门禁
- **Type**: `rule`
- **When**: 阶段实现结束
- **Then**: Android `assembleDebug`、`testDebugUnitTest`（含新增纯函数单测）、
  `assembleDebugAndroidTest` 均 EXIT=0/BUILD SUCCESSFUL；服务端 `go test ./...`
  （含新增 handler/store 测试）EXIT=0；Web `npm run build` 与 TS 单测 EXIT=0；
  APK 产物路径真实存在
- **Pass Condition**: 各命令输出与 APK 文件存在性核实（注意本机 GOOS/GOARCH
  系统级污染需进程内显式覆盖为 windows/amd64）
- **Evidence**: tasks.md 中记录命令、时间戳与产物绝对路径

## Open Questions

无（范围拆分、定位方案、地图参考、本期深度、存储架构五项已按用户 2026-09-16
决策关闭；八项增强已登记 Future Enhancements 而非排除）。实现期若发现国产 ROM
后台定位专项阻断，记录为设备冒烟项，不扩大本期范围。
