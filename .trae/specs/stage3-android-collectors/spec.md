# 阶段 3 — 安卓采集器 - 产品需求文档（PRD）

## Overview

- **Summary**：在 Android 端新增三类系统数据采集器——通讯录、短信、通话记录。经运行时
  权限授权后，采集器读取系统 ContentProvider，把条目以现有零知识信封（MK 加密，
  `module=contact/sms/calllog`）写入本地 Room，复用既有 WorkManager 增量同步通道上传
  自托管服务端；支持手动立即采集与周期增量采集，附权限向导与国产 ROM 保活指引。
- **Purpose**：把散落在手机系统中的个人数据（人脉、通信记录）纳入"人生操作系统"的
  加密个人库，使其可备份、可在未来统一检索/被 AI 助理使用，同时不破坏零知识边界。
- **Target Users**：自托管 Everything 服务、使用 Android 主机的单一用户本人（不合规
  场景：监控他人设备；产品内做用途告知）。

## Goals

- 三类数据（通讯录/短信/通话记录）授权后的**全量首轮采集 + 增量续采**。
- 采集即加密：明文不落盘、不进日志，信封与 pass/identity 记录同构、同链路同步。
- 用户可控：逐类授权、逐类开关、随时关闭；用途与限制在 UI 内明示。
- 后台可运转：复用 WorkManager 周期任务，提供电池优化/自启动保活引导。
- 可验证：核心映射/游标/幂等逻辑抽为纯函数并由 JVM 单测锁定；instrumented 测试随
  设备环境补跑（关闭条件并入既有 FU-7）。

## Non-Goals

- **不做**短信/通话的实时监听（不申请 RECEIVE_SMS、不注册 BroadcastReceiver/
  ContentObserver 做即时入库）；本期时效以 15 分钟周期轮询为准。
- **不做**系统侧删除镜像：用户在系统应用里删除联系人/短信/通话记录，Everything 内
  对应记录**不**自动置墓碑。
- **不做** Web 端浏览/搜索 UI（数据照常同步到服务端密文库；Web 对未知 module 天然
  不展示，后续阶段补查看页）。
- **不做**附件/照片/媒体块存储（后续独立阶段）。
- **不做**位置轨迹采集（阶段 4：前台定位服务）。
- **不做**应用内采集数据浏览 UI 的完整版；采集页只展示采集状态/计数，不做解密列表。
- 不以 Google Play 上架为目标（高危权限政策限制）；延续自建 APK 分发路线。

## Background & Context

- 现有通道：`SyncWorker`（android/.../sync/SyncWorker.kt）已由 WorkManager 每 15 分钟
  唯一周期调度（网络约束 + 指数退避），`RecordsRepository.sync()` 推 dirty（≤1000/批、
  applied+skipped 全数确认后 markClean 并用 server_time 校准）再按 updated_at 拉取。
- 信封机制：`CryptoEnvelope.sealRecord(key, plain, id, module, version)`，AAD 绑定
  `id/module/version`；服务端 records API 仅校验 id/module/密文非空与单批上限，
  **不校验 type、不接触明文、无 module 白名单**，新 module 可零改动复用。
- Room 已到 v2 并有显式迁移先例（MIGRATION_1_2 + instrumented MigrationTest）。
- MK 仅驻留内存（`AuthManager.masterKey`），应用进程死亡后须重新解锁；后台 worker
  在未解锁进程中运行时没有 MK。
- 设备身份：`AuthManager.deviceId`（服务端配对设备 id）可用于记录 id 前缀，避免同一
  用户多台 Android 设备采集同一系统 _id 时主键冲突。
- 权限现状：Manifest 仅 INTERNET/ACCESS_NETWORK_STATE/RECEIVE_BOOT_COMPLETED；
  docs/android.md 已将三类 READ 权限规划在阶段 3。
- FU-1 已落地服务端权威 updated_at，采集端只需产出密文信封，时间戳由服务端统一。

## Functional Requirements

- **FR-1 数据模型**：新增三个模块，明文 JSON 字段以 docs/module-schemas.md 为准：
  - `module="contact", type="contact"`：显示名、姓名部件、组织/职位、电话列表、
    邮箱列表、地址、生日、备注，以及采集溯源 `source`（system_id、lookup_key、
    系统 last_updated）。
  - `module="sms", type="sms"`：对方地址 address、正文 body、系统时间 date、
    方向/文件夹 type（inbox/sent/draft/outbox/failed/queued/unknown）、read、
    thread_id、source.system_id。
  - `module="calllog", type="call"`：号码 number、缓存姓名 name、时间 date、
    时长 duration（秒）、类型 type（incoming/outgoing/missed/rejected/blocked/
    voicemail/unknown）、source.system_id。
- **FR-2 确定性身份**：记录 id 规则为 `{deviceId}:{kind}:{systemId}`
  （kind ∈ contact/sms/calllog，systemId 取系统行 _id；联系人另存 lookup_key 于
  明文 source）。同源条目重复采集必须幂等 upsert，不得产生重复记录。
- **FR-3 增量游标**：每类维护持久化复合游标 `(系统时间戳, _id)`，查询
  `time > ? OR (time = ? AND _id > ?)`，按 `time ASC, _id ASC` 排序；时间列分别为
  联系人 `Contacts.CONTACT_LAST_UPDATED_TIMESTAMP`、短信 `Telephony.Sms.date`、
  通话 `CallLog.Calls.DATE`（minSdk 26 均可用）。首轮游标零值即全量。
- **FR-4 分页与预算**：单轮单类处理上限（默认 400 条，代码内命名常量），到量即停、
  游标停在最后处理行，下轮续采；保证单次 Worker 执行不因万级历史库超时。
- **FR-5 变化检测**：游标范围内若本地已存在同 id 记录，用 MK 解密本地密文并与本次
  规范化明文 JSON 比对：相同则仅推进游标、不升版本；不同才 `version+1` 重新密封。
- **FR-6 入库与同步**：新/变条目以 `sealRecord` 密封为 RecordEntity（dirty=1）写 Room；
  采集完成后调用既有 `repo.sync()` 走 `/records/batch` 推送与增量回拉。
- **FR-7 权限模型**：
  - Manifest 仅新增 `READ_CONTACTS`、`READ_SMS`、`READ_CALL_LOG` 三个危险权限，
    不新增 RECEIVE_SMS 等实时类权限。
  - 每类一个启用开关；打开开关触发运行时权限请求；未授权或被拒时该类不执行，
    UI 提供用途说明与"到系统设置授权"引导；可随时关闭。
  - 周期采集总开关（后台任务的启用/停用），与单类开关独立。
- **FR-8 调度**：保留唯一周期 Work（15 分钟、CONNECTED 约束、指数退避），Worker 内
  顺序为"采集（MK 可用且类启用且已授权）→ repo.sync()"。手动"立即采集"入队一次性
  唯一任务。应用启动时确保调度存在。
- **FR-9 未解锁降级**：Worker 运行时若 MK 不在内存（进程被重建后未解锁），**跳过
  全部扫描步骤**（不在磁盘暂存任何明文/游标中间结果），仍执行 repo.sync() 推送此前
  已加密的 dirty 数据；状态区可见"上次因未锁定跳过"。
- **FR-10 合规告知与保活引导**：
  - 采集页固定展示告知：数据仅存入用户本人的自托管服务器、端到端加密、可随时关闭；
    明示"不实时采集""不跟随系统删除"两条限制。
  - 提供电池优化白名单请求（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）入口与
    应用系统详情页入口；docs/android.md 给出主流国产 ROM 自启动/后台运行设置指引。
- **FR-11 可观测状态**：采集页展示每类开关态、权限态、上次成功采集时间、本地已采集
  条目数；手动采集进行中/成功/失败有明确反馈（失败原因不含任何明文片段）。
- **FR-12 文档**：module-schemas.md 增补三模块明文 schema 与 id 规则；android.md
  增补权限用途、合规告知、行为限制、保活指南；项目计划/README 进度同步。

## Non-Functional Requirements

- **NFR-1 零知识红线**：采集明文（号码、正文、姓名等）只存在于内存，严禁写日志、
  严禁写入未加密文件/偏好；日志与同步状态仅允许出现计数、时间戳、模块名、错误类别。
- **NFR-2 安全最小化**：不申请超出三类读取所需的权限；不导出任何组件；不建立通知
  栏常驻服务（无前台 Service，与阶段 4 定位服务区分）。
- **NFR-3 可测性**：系统无关逻辑（字段映射、类型码翻译、id 派生、游标推进、规范化
  比对、分页预算）必须抽为不依赖 Android Framework/Lazysodium 的纯 Kotlin，由 JVM
  单测覆盖；Room 迁移以 instrumented 测试覆盖（无设备环境至少保证编译通过）。
- **NFR-4 功耗**：周期采集绑定既有 15 分钟网络约束窗口，不额外唤醒设备；无数据变化
  的轮次除一次游标查询外无网络/加密开销。
- **NFR-5 兼容性**：目标 minSdk 26 / targetSdk 35；国产 ROM 上权限被拒/后台受限不
  崩溃，降级为仅手动可用。
- **NFR-6 一致性**：新代码沿用现有 ServiceLocator 手动注入、Moshi、Room 显式迁移、
  Compose + Material3、中文详细注释的既有约定。

## Constraints

- **Technical**：
  - 信封必须复用 CryptoEnvelope（Argon2id/XChaCha 参数与 AAD 规则不得新造）。
  - 服务端零改动（通用 records 通道）；Web 端零代码改动。
  - 后台执行受 WorkManager 单次 10 分钟预算约束，首轮必须分页（FR-4）。
  - 无 Google Play 服务依赖。
- **Business**：仅本人自托管场景；应用内必须有用途告知，避免跟踪类使用观感。
- **Dependencies**：现有 WorkManager/Room/Compose 依赖齐备，预计无需新增第三方库
  （联系人/短信/通话均走框架 ContentProvider）。

## Assumptions

- 单用户单账号在一台设备上只有一个 Everything 账户登录态（现状如此）；设备 id 在
  首次配对后稳定，卸载重装后换新 id（旧采集记录保留在库中、不回删，符合
  "不跟随删除"语义）。
- 短信/通话记录系统 _id 单调递增；不改变 date 的行级变化（如短信已读态）本期不
  保证捕获（联系人编辑可由 CONTACT_LAST_UPDATED_TIMESTAMP 捕获）。
- 通话记录在国产 ROM 上的权限授予/可读性差异通过手动降级处理，不做厂商专项适配。
- 周期任务实际节拍由系统 Doze/厂商省电策略决定，15 分钟为最短间隔而非保证值，
  保活指引仅提升概率。

## Acceptance Criteria

### AC-1: 三类数据授权采集与确定性身份
- **Type**: `rule`
- **Given**: 已登录、已解锁 MK，并授予某类 READ 权限的 Android 设备，系统中有若干
  联系人/短信/通话记录
- **When**: 触发一次手动采集
- **Then**: 对应条目以 `module=contact/sms|sms/sms|calllog/call` 的密封信封写入
  Room（dirty=1）；记录 id 形如 `{deviceId}:{kind}:{systemId}`；对同一批数据再次
  采集，记录数量不增加（幂等）
- **Pass Condition**: 纯函数 JVM 单测（id 派生/映射）通过；instrumented 测试编译
  通过；设备可用时真机冒烟证据记录于 tasks.md
- **Evidence**: `app/src/test/...` 单测输出、assembleDebugAndroidTest 结果、冒烟记录

### AC-2: 复合游标增量与分页追平
- **Type**: `rule`
- **Given**: 首轮采集后持久化了每类游标（Room v3 `collector_state`）
- **When**: 系统新增少量条目后再次采集；以及历史库超过单轮预算时连续多轮采集
- **Then**: 第二轮仅处理游标之后的新行；大批量经多轮（每轮 ≤400/类）最终全部入库，
  无重复无遗漏；Room v2→v3 走显式迁移而非破坏性重建
- **Pass Condition**: 游标纯函数单测（边界等值、_id 同向、预算截断）全绿；
  MigrationTest（v2→v3）instrumented 编译通过
- **Evidence**: 单测输出、EveDatabase MIGRATION_2_3 代码、迁移测试代码

### AC-3: 变化检测与版本纪律
- **Type**: `rule`
- **Given**: 某联系人已采集（v1）
- **When**: 该联系人在系统中被编辑触发 last_updated 变化后再采集；以及未编辑再采集
- **Then**: 编辑场景产出 version=2 新密文（dirty=1）；未编辑场景不升版本、不重封、
- 不产生无谓同步流量
- **Pass Condition**: 规范化 JSON 比对纯函数单测通过（字段顺序无关、内容敏感）
- **Evidence**: 单测用例与输出

### AC-4: 采集记录复用通用同步链路
- **Type**: `rule`
- **Given**: 采集产生 dirty 信封
- **When**: Worker 采集阶段结束
- **Then**: 调用现有 repo.sync()，记录经 /records/batch 上传（AAD 中 module 为
  contact/sms/calllog），成功后 dirty 清除并以 server_time 校准；服务端可原样回拉，
  Web 端对未知 module 不展示但不报错
- **Pass Condition**: 服务端 `go test ./...` 全绿（零改动回归）；代码路径审查确认
  复用 pushRecords/markClean，无平行上传实现
- **Evidence**: go test 输出、RecordsRepository/CollectorWorker 代码

### AC-5: 权限最小化与运行时授权
- **Type**: `rule`
- **Given**: 全新安装的应用
- **When**: 检查 Manifest 并在采集页操作三类开关（允许/拒绝/关闭）
- **Then**: Manifest 恰为 READ_CONTACTS/READ_SMS/READ_CALL_LOG 三个新权限（无
  RECEIVE_SMS、无定位、无前台服务权限）；拒绝授权时该类不执行且显示设置引导；
  关闭开关后该类永不再被周期任务读取
- **Pass Condition**: AndroidManifest.xml 审查；权限态判定纯函数/VM 逻辑可在
  instrumented 或设备冒烟中验证；编译产物存在
- **Evidence**: Manifest diff、CollectorScreen/ViewModel 代码、APK 产物路径

### AC-6: 周期调度、唯一任务与未解锁降级
- **Type**: `rule`
- **Given**: 应用启动完成
- **When**: 周期窗口到达，分别在已解锁/未解锁（MK=null）进程状态下执行
- **Then**: WorkManager 中存在唯一周期任务（15 分钟、CONNECTED、指数退避）；
  已解锁时按授权类采集后 sync；未解锁时跳过扫描但仍 sync，且磁盘上找不到任何明文
  暂存（无新文件、无含正文/号码的偏好项）
- **Pass Condition**: 调度代码审查（enqueueUniquePeriodicWork 单一名称）；grep
  证据证明明文仅流向 sealRecord；状态记录仅计数/枚举
- **Evidence**: Worker/Scheduler 代码、grep 检查记录

### AC-7: 零知识日志与错误信息
- **Type**: `rule`
- **Given**: 采集全流程代码
- **When**: 静态检查所有新增/改动代码
- **Then**: 不存在把 number/address/body/name/email 等明文字段写入 Log、异常消息、
  SharedPreferences、文件或通知文案的路径
- **Pass Condition**: grep 模式（Log[.dwiev]、printStackTrace、putString 等）
  逐处审查并在评审中复核
- **Evidence**: 评审检查记录、零命中/带说明的命中清单

### AC-8: 删除策略明示
- **Type**: `rule`
- **Given**: 用户已采集数据后在系统侧删除某联系人/短信
- **When**: 下一轮采集执行
- **Then**: Everything 内对应记录保持原状，不产生墓碑；采集页与 docs/android.md
  均明示"不跟随系统删除"
- **Pass Condition**: 采集代码无全量比对/墓碑路径（grep 审查）；UI 文案与文档
  包含该说明
- **Evidence**: UI 文案、文档章节、代码审查

### AC-9: 保活引导可用
- **Type**: `rule`
- **When**: 用户在采集页点击电池优化/系统设置入口
- **Then**: 正确机型上调起电池优化白名单请求对话框；另一入口跳应用系统详情页；
  docs/android.md 含至少主流国产 ROM（小米/华为/OPPO/vivo/荣耀）自启动路径指引
- **Pass Condition**: 代码（Settings intent + 异常兜底）与文档齐备；设备冒烟为
  加分项不作为本机阻塞
- **Evidence**: CollectorScreen 代码、docs/android.md 章节

### AC-10: 文档与计划同步
- **Type**: `rule`
- **When**: 实现完成
- **Then**: module-schemas.md 新增三模块 schema（含 id 规则、source 溯源字段、
  仅 Android 采集说明）；android.md 权限/合规/限制/保活章节更新；
  everything_plan.md 阶段 3 勾选；README 进度表述同步
- **Pass Condition**: 四份文档评审通过且字段与代码/DTO 一致
- **Evidence**: 文档 diff

### AC-11: 采集页可用性质量
- **Type**: `rubric`
- **Dimension**: 权限向导与状态页的用户体验质量
- **Scale**: 1-5
- **Anchors**: 1 = 用户无法理解为何授权/状态不明；3 = 功能齐全但文案粗略、权限被拒
  后无引导；5 = 用途/风险/限制一目了然，授权—采集—反馈闭环顺畅，错误态可恢复
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对 CollectorScreen 走查（含未授权/已授权/未解锁/采集中/
  失败五态）

### AC-12: 架构一致性与可测性
- **Type**: `rubric`
- **Dimension**: 新增代码与既有架构的契合度及纯函数可测性
- **Scale**: 1-5
- **Anchors**: 1 = 新造平行存储/网络/加密路径，逻辑耦合在 Activity 不可测；
  3 = 复用主通道但映射逻辑散落、部分单测；5 = 纯函数核心 + 薄 Android 适配层，
  完全复用信封/Room/Worker/ServiceLocator，单测覆盖全部系统无关分支
- **Pass Threshold**: >= 4
- **Evidence**: 代码评审 + 单测清单与覆盖率（分支枚举）

### AC-13: 全量门禁
- **Type**: `rule`
- **When**: 阶段实现结束
- **Then**: Android `assembleDebug`、`testDebugUnitTest`（含新增纯函数单测）、
  `assembleDebugAndroidTest` 均 EXIT=0/BUILD SUCCESSFUL；服务端 `go test ./...`
  与 Web `npm run build` 回归 EXIT=0；APK 产物路径真实存在
- **Pass Condition**: 四条命令输出与 APK 文件存在性核实
- **Evidence**: tasks.md 中记录命令、时间戳与产物绝对路径

## Open Questions

无（四个范围问题已按用户 2026-09-16 决策关闭：不做实时监听；不做 Web UI；
不做附件；不跟随系统删除）。实现期若发现厂商 ROM 特有阻断，记录为设备冒烟项，
不扩大本期范围。
