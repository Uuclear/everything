# 阶段 1 收尾（恢复密钥/设备审批/TOTP/SSE 通道/Room 迁移）+ 阶段 2 密码库 — 产品需求文档

## Overview

- **Summary**：补齐总体计划中阶段 1 的五项遗留——恢复密钥、设备扫码/审批（登录确认+配对码方案）、Web 端 SSE 签名查询通道、Android Room 显式迁移、登录 TOTP 二次验证；随后交付阶段 2 首批业务模块：密码库（登录项/安全笔记/银行卡 + 密码生成器 + 登录项 TOTP 动态码）与证件/个人信息（含到期提醒）。
- **Purpose**：在进入大规模数据采集（阶段 3 起）前堵住"忘记主密码=数据永久丢失"和"任何人凭密码登录即获得全部密文"两个最大安全缺口；同时用首批真实业务模块验证"通用加密信封 + 客户端 schema"的模块扩展机制（服务端零改表、零业务端点）。
- **Target Users**：自托管单用户本人及其可信家庭成员；Web 端为完整管理界面，Android 端为采集/同步与设备审批端。

## Goals

- 忘记主密码后，可凭**分组恢复码**重置主密码并解回原有 MK，历史密文全部可读。
- 新设备登录必须经一台**已批准设备**核对配对码后授权；MK 经 X25519 端到端加密盒下发，服务端全程不可见。
- 登录支持 **TOTP 二次验证**（RFC 6238），启用后仅凭密码无法获得资料库访问权。
- Web 端用 **EventSource + 短期签名查询令牌**替代 10 秒轮询接收实时变更。
- Android Room 改为**显式版本化迁移**，升级不丢本地数据。
- Web 端交付密码库三类记录（登录项/安全笔记/银行卡）的完整增删改查、客户端搜索、密码生成器、登录项 TOTP 动态码；证件/个人信息模板与到期提醒视图。
- 登录/恢复/TOTP 等敏感端点具备**IP 限流**与完整审计日志。

## Non-Goals

- 摄像头扫码审批（本次采用登录确认+配对码；二维码扫码作为后续增强）。
- 密码泄露检测（HIBP）、家庭成员密码共享空间（MK 重封共享），推迟到后续阶段。
- 证件/密码的**服务端主动推送提醒**（属阶段 6 Agent/规则引擎）；本次只做客户端解密后的到期徽标与聚合视图。
- 附件/照片、浏览器密码 CSV 导入、图标/favicon 抓取。
- Android 端密码库/证件的浏览与编辑 UI（本次仅保证密文同步兼容、笔记继续可见、可作为设备审批端、登录支持 MFA）。
- 服务端 AI 解锁会话、任何服务端明文索引。

## Background & Context

- 现有底座（已完成并经 e2e 验证）：Argon2id(t=3,m=64MiB,p=1) + XChaCha20-Poly1305 信封（规范见 [docs/crypto.md](../../../docs/crypto.md)）；users/devices/refresh_tokens/records/audit_logs 五表；JWT access+refresh；records 版本化幂等批量同步与 since 增量拉取；SSE `/events` 仅支持 Authorization 头；设备登录自动批准；Room 用 `fallbackToDestructiveMigration()`。
- 已确认的用户决策：
  1. 恢复密钥 = **分组恢复码**（20 字节随机，Crockford Base32，4 组×8 字符）。
  2. 设备审批 = **登录确认 + 配对码**（无摄像头；X25519 crypto_box 通道下发 MK）。
  3. 阶段 2 范围 = 密码库核心 + 证件/个人信息（不含泄露检测、家庭共享）。
  4. Android = 同步兼容 + 设备审批端 + 登录 MFA；密码库完整 UI 仅 Web。
- 三端加密栈：Go `golang.org/x/crypto`（含 `nacl/box`，与 libsodium `crypto_box_easy` 互通）、Web libsodium-wrappers 0.7、Android lazysodium；均支持 crypto_box（X25519+XSalsa20-Poly1305）。
- 工具链：本机 Node 24/npm 11、JDK 17 可用；**Go 不在 PATH**，服务端测试以 GitHub Actions CI（`go test ./...` + 四目标交叉编译）为最终门禁，实现期尽量补装本地 Go 复验。

## Functional Requirements

### 恢复密钥

- **FR-1**：注册时客户端强制生成恢复码并同时上传恢复材料：`recovery_auth_salt(16B)`、`recovery_kek_salt(16B)`、`recovery_verifier = Argon2id(规范化恢复码, recovery_auth_salt)(32B)`、`wrapped_mk_recovery = XChaCha(REK, MK, AAD="eve:v1:master-key-recovery/v1")`，其中 `REK = Argon2id(规范化恢复码, recovery_kek_salt)`。缺少恢复材料的注册请求必须被拒绝。
- **FR-2**：Web 注册成功后必须全屏展示恢复码一次，要求用户勾选"我已离线保存"后才能进入资料库；提供打印/复制。Android 注册流程同等要求。
- **FR-3**：恢复流程（无需登录态）：① `POST /auth/recovery/start` 提交 username + recovery_verifier，服务端常量时间比对通过后签发 10 分钟有效的 recovery 会话令牌并返回 `recovery_kek_salt` 与 `wrapped_mk_recovery`；② 客户端派生 REK 解开 MK，让用户设置新主密码，并生成全新恢复码材料；③ `POST /auth/recovery/reset` 提交新密码包裹材料与新恢复材料，服务端更新 users 行、吊销该用户全部 refresh token、作废全部 pending 配对，返回新令牌对（本次设备标记 approved）。
- **FR-4**：已登录且已批准设备可修改主密码：`POST /auth/password/change` 提交新 auth/kek 材料（MK 不变），服务端更新后吊销其他 refresh token，当前设备获得新令牌对。
- **FR-5**：恢复码规范化规则三端一致：接受小写/空格/连字符，内部转大写并按 Crockford 字母表（`0123456789ABCDEFGHJKMNPQRSTVWXYZ`，易混字符映射 I→1、L→1、O→0）处理；解码固定 20 字节。

### 设备审批（配对码方案）

- **FR-6**：每台设备在注册/登录前生成 X25519 密钥对，公钥随注册/登录提交，私钥仅存本端（Web localStorage；Android EncryptedSharedPreferences）。首个设备（账户尚无 approved 设备）自动批准；此后新设备登录创建为 `pending` 设备与一条 pending 配对请求（TTL 15 分钟）。
- **FR-7**：pending 设备获得受限访问令牌（scope=pending），只能访问配对状态查询；访问 records/events 等业务接口必须返回 403。配对状态返回设备公钥指纹派生的 **6 位配对码**（SHA-256(公钥) 前 3 字节十六进制大写）供两端人工核对。
- **FR-8**：已批准设备可列出本账户设备与 pending 配对（设备名、时间、配对码、公钥指纹），执行批准/拒绝/吊销。批准时审批端生成一次性临时 X25519 密钥对，用 libsodium/nacl `box_easy(MK, nonce=随机24B, recipient_pk=新设备公钥, sender_sk=临时私钥)` 得到密文，连同临时公钥、nonce 提交；服务端仅透传存储。新设备轮询拿到后用自己的私钥开箱得到 MK，进入解锁态。
- **FR-9**：配对被拒绝、15 分钟超时、或恢复流程执行后，pending 令牌与配对失效；新设备可重新发起登录（同一公钥的 pending 设备复用，不产生重复设备行）。已批准设备可被吊销，吊销即时失效其 refresh token。
- **FR-10**：配对状态变化通过 SSE 事件 `device_pairing_requested` / `device_pairing_resolved` 通知在线已批准设备；Web 设备管理页实时刷新，Android 在前台时同步刷新审批列表。
- **FR-11**：等待审批页（Web/Android）展示设备名、配对码、剩余有效期与轮询状态，并提供"没有其他已批准设备？使用恢复码"的出口。

### TOTP 二次验证

- **FR-12**：已批准设备可启用 TOTP：`POST /auth/totp/setup` 返回 base32 secret、otpauth URI 与二维码 PNG（服务端生成，一次 setup 一份 secret）；`POST /auth/totp/enable` 校验 6 位 code（±1 时间步容差）后确认启用；`POST /auth/totp/disable` 校验当前 code 后关闭。未确认的 secret 不参与登录。
- **FR-13**：登录时密码验证器正确但已启用 TOTP，返回 `mfa_required` 与 5 分钟有效的 mfa 会话令牌（不创建设备、不返回任何包裹材料）；`POST /auth/totp/verify` 校验通过后才进入设备创建/配对分支并返回完整登录结果。Web 与 Android 登录界面都必须支持第二步输入码。

### SSE 签名查询通道

- **FR-14**：`POST /auth/events-token`（已批准）签发 5 分钟、仅对 `/events` 有效的 events 令牌（独立 typ）。`GET /events` 接受 `?token=`（typ 必须为 events）或 Authorization 头（typ 必须为 access 且 scope=approved）；其他端点一律不接受查询令牌。过期/伪造返回 401。
- **FR-15**：Web 端改为 EventSource 长连（建立前取 events-token，出错/过期自动重新取令牌重连），收到 `records_changed` 触发增量同步、收到设备配对事件刷新设备页；长连不可用时退化为不低于 30 秒间隔的轮询兜底。

### Room 显式迁移

- **FR-16**：Android Room 升级到 version 2 并提供显式 Migration(1→2)：新增 `sync_state(key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)`；每次同步成功写入 `last_successful_sync`，界面展示"上次同步"时间。移除 `fallbackToDestructiveMigration()`，v1 已有数据升级后不丢失。

### 阶段 2：密码库与证件（Web 完整、Android 兼容）

- **FR-17**：沿用通用信封，不新增服务端表与端点。记录明文 JSON 约定：
  - `module=pass,type=login`：`{title, username, password, uris:[], notes, totp:{secret,issuer,account,digits,period,algorithm}|null}`
  - `module=pass,type=note`：`{title, body}`
  - `module=pass,type=card`：`{title, cardholder, number, expiry:"MM/YY", cvc, notes}`
  - `module=identity,type=id_card|passport|driver_license|generic`：`{title, full_name, number, issuer, issued_at?, expires_at?(unix ms), notes}`
- **FR-18**：Web 提供模块导航 + 列表 + 新建/编辑/删除完整流程；更新即 version+1、重新密封（AAD 绑定新 version）并发批量同步；删除走墓碑。密码/卡号/CVC 默认遮蔽，可显示与复制。
- **FR-19**：Web 提供客户端解密后的内存搜索（标题/用户名/网址/卡号尾号/备注），明文不进入 localStorage 持久化（刷新页面需重新解锁）。
- **FR-20**：密码生成器（纯前端）：长度 8–64 可调，大写/小写/数字/符号分组开关，可排除歧义字符（`0O1lI` 等），保证所选字符集至少各出现一个字符；登录项编辑器内可直接生成填入。
- **FR-21**：登录项 TOTP 动态码：支持录入 otpauth URI 或手动 secret/issuer；按 RFC 6238 在本地计算 6/8 位码（默认 SHA-1/30s），展示剩余有效期倒计时；复制后剪贴板内容 30 秒后自动清空（浏览器 Clipboard API 允许时）。
- **FR-22**：证件到期视图与徽标：已过期红色、30 天内到期橙色、90 天内黄色；独立"到期提醒"聚合列表按到期日升序。
- **FR-23**：Android 继续无差别同步全部模块密文；笔记列表兼容显示旧 `note` 与新 `pass/note` 记录（仅取 title/body 展示），新模块记录不导致崩溃或同步中断；注册/登录产生 X25519 设备公钥，支持 pending 配对等待页、MFA 输入码、作为审批端批准/拒绝。

### 加固与审计

- **FR-24**：对 `/auth/login`、`/auth/recovery/start`、`/auth/totp/verify` 实施按 IP 的失败限流：同一 IP 对上述端点累计 5 次失败后锁定 15 分钟，返回 429；成功计数重置；锁定与失败事件写审计日志。限流状态进程内维护即可（单实例部署）。
- **FR-25**：以下事件必须落 audit_logs（user_id 可空）：register、login、login_failed、recovery_start、recovery_start_failed、password_reset、password_change、totp_enabled、totp_disabled、totp_verify_failed、device_pair_requested、device_approved、device_rejected、device_revoked。恢复码、MK、TOTP code、密码等敏感值不得以任何形式出现在日志 detail 中。

## Non-Functional Requirements

- **NFR-1（零知识不回退）**：任何新增服务端逻辑不得获得 MK/主密码/记录明文/恢复码明文/TOTP secret 的派生能力之外的资料库内容；TOTP secret 仅为登录因素，与资料库加密无关。配对盒服务端只透传。
- **NFR-2（三端互通）**：恢复信封 AAD、Crockford 编解码、crypto_box 封装必须有跨端测试向量（Go↔libsodium），写入 docs/crypto.md；同一恢复码/MK 材料三端派生结果逐字节一致。
- **NFR-3（构建门禁）**：`CGO_ENABLED=0 go test ./...`、`go vet`、四目标交叉编译、Web `npm run build`（vue-tsc 严格通过）、Android `assembleDebug` 均需通过；与现有 CI 四作业保持一致。
- **NFR-4（兼容升级）**：0002 迁移对现有开发库可直接执行；旧 Web/Android 客户端遇到新字段不崩溃（注册新字段为服务端强制，老客户端注册将被拒绝并提示升级——当前为单人开发期，可接受）。
- **NFR-5（可用性）**：Web 关键安全操作（恢复、审批、2FA）有明确状态反馈与错误提示；密码库列表 500 条记录量级下搜索/渲染无明显卡顿。
- **NFR-6（注释与文档）**：三端新增密码学代码保留详细中文注释；docs/api.md、docs/crypto.md 同步更新，新增模块 JSON 约定文档。

## Constraints

- **Technical**：Go 1.23、chi、modernc.org/sqlite；新依赖仅限纯 Go（TOTP 使用 `github.com/pquerna/otp`，nacl/box 已在 golang.org/x/crypto 内）；全程 CGO_ENABLED=0。Web 不引入重型依赖（二维码由服务端生成 PNG；TOTP/HMAC 用 WebCrypto 原生实现；不加摄像头/扫码库）。Android minSdk 26、lazysodium 已具备 crypto_box。
- **Business**：单用户/家庭可信模型；忘记主密码且丢失恢复码 = 数据不可恢复，注册与恢复后均需强提醒。
- **Dependencies**：系统时钟准确（TOTP）；CI 可访问 Go 模块代理；浏览器需支持 WebCrypto 与 EventSource（现代浏览器均可）。

## Assumptions

- 现有线上数据为开发期测试数据；users 新增恢复材料列允许为空（空材料账户只能在本机登录后通过"修改密码/补设恢复码"补齐——注册强制，补设入口在修改密码流程中顺带生成新恢复码）。
- 配对、MFA 等会话时间以服务端时钟为准；配对码只用于人工核对，不作为持密凭证。
- 单实例部署，进程内限流/状态足够；不引入 Redis 类外部依赖。

## Acceptance Criteria

### AC-1: 恢复材料随注册强制生成
- **Type**: `rule`
- **Given**: 未注册用户调用注册接口
- **When**: 请求缺少任一恢复材料字段或字段长度非法
- **Then**: 服务端返回 400 且不创建用户/设备
- **Pass Condition**: 合法注册（含恢复材料）201 创建；缺字段/错长度全部 400；DB 中用户行四个恢复列非空
- **Evidence**: Go e2e 测试用例 + 迁移后表结构检查

### AC-2: 恢复码可重置主密码并解回历史数据
- **Type**: `rule`
- **Given**: 用户已注册并加密写入若干记录；主密码"遗忘"
- **When**: 用恢复码走 start→reset 流程设置新密码，再以新密码登录
- **Then**: 解出的 MK 与原 MK 一致，全部历史密文可解密；旧 refresh token 全部失效；重置时签发的新恢复码可再次完成恢复
- **Pass Condition**: e2e 中用旧 MK 加密的记录在重置后用新流程解出的 MK 成功 Open；旧 refresh 调 /auth/refresh 返回 401；旧设备记录中 pending 配对全部失效
- **Evidence**: Go e2e 测试用例输出

### AC-3: 恢复码/密码规范化与跨端互通
- **Type**: `rule`
- **Given**: 固定恢复码测试向量（小写、带连字符输入与标准分组输出）
- **When**: Go 与 Web（libsodium）分别规范化、派生 REK、密封/打开 MK
- **Then**: 两派生密钥逐字节一致，任一端密封的恢复信封另一端可打开
- **Pass Condition**: docs/crypto.md 新增固定向量；Go 测试断言；Web 提供等价断言（vite 可执行的轻量测试或在 e2e 页面流程中可观测）
- **Evidence**: 测试代码 + docs/crypto.md 向量段落

### AC-4: pending 设备无法访问资料库
- **Type**: `rule`
- **Given**: 账户已有一台 approved 设备；第二台设备用正确密码（+TOTP）登录
- **When**: pending 令牌访问 /records、/records/batch、/auth/devices 之外业务接口
- **Then**: 一律 403 `device_pending`；设备列表中新设备为 pending；配对 15 分钟后状态 expired
- **Pass Condition**: Go e2e 覆盖 403、设备状态、过期；时间用可注入时钟或创建即测状态+SQL 层 expires_at 断言
- **Evidence**: e2e 测试输出

### AC-5: 审批后端到端加密盒下发 MK
- **Type**: `rule`
- **Given**: pending 设备 D2 已提交 X25519 公钥，D1 为 approved 设备且持有 MK
- **When**: D1 用 Go nacl/box 模拟（e2e）/ libsodium（真机）封装 MK 并批准；D2 查询配对结果并开箱
- **Then**: D2 得到与 D1 完全相同的 MK；服务端 DB、日志中不存在可识别的 MK（wrapped 为 48B 非明文，日志无密钥字节）；配对码=公钥 SHA-256 前 3 字节大写 hex
- **Pass Condition**: e2e 断言开箱 MK 字节相等、配对码格式与取值；DB 快照中 wrapped_master_key_pairing 不等于 MK
- **Evidence**: e2e 测试 + DB 断言

### AC-6: 拒绝/吊销/重复登录行为正确
- **Type**: `rule`
- **Given**: pending 配对存在
- **When**: D1 拒绝配对；或 D2 重复登录；或 D1 吊销一台 approved 设备
- **Then**: 拒绝后 D2 状态查询得到 rejected 且令牌无法再换取材料；同公钥重复登录不新增设备行而复用 pending；吊销后该设备 refresh token 即刻失效
- **Pass Condition**: 三项行为各有 e2e 断言（设备计数、状态、/auth/refresh 401）
- **Evidence**: e2e 测试输出

### AC-7: TOTP 登录与管理闭环
- **Type**: `rule`
- **Given**: 用户在 Web 端启用 TOTP 并通过 code 确认
- **When**: 之后登录只提交密码；以及提交错误 code；以及提交正确 code
- **Then**: 只交密码 → 200 mfa_required + mfa_token，响应不含 wrapped_master_key；错码 → 401 并审计 totp_verify_failed；正确 → 进入设备分支返回完整结果；disable 经 code 校验后恢复单因素登录
- **Pass Condition**: Go e2e 用 pquerna/otp 生成 code 完成全链路断言；setup 未确认时登录不要求 TOTP
- **Evidence**: e2e 测试输出

### AC-8: events 签名令牌最小权限
- **Type**: `rule`
- **Given**: 已批准登录会话
- **When**: 用 events-token 访问 /events；用它访问 /records；过期后再用；伪造/他用户 token
- **Then**: /events 建立成功并收到推送；/records 返回 401；过期 401；无效 401
- **Pass Condition**: Go e2e 覆盖四种情形（TTL 用可配置或测试专用短时效，最小实现可接受时钟等待 ≤6 秒，或签发接口支持测试TTL——优先不做后门，用 6 秒等待）
- **Evidence**: e2e 测试输出

### AC-9: Web 实时通道替代轮询
- **Type**: `rubric`
- **Dimension**: 实时性与资源占用平衡
- **Scale**: 1-5
- **Anchors**: 1 = 仍只靠 10 秒轮询；3 = EventSource 可用但断连不恢复/无兜底；5 = EventSource 自动续 token 与重连、事件即时触发同步与设备刷新、失败时 ≥30 秒轮询兜底且无重复风暴
- **Pass Threshold**: >= 4
- **Evidence**: Web 源码评审 + 浏览器手测：一条其他设备写入后 2 秒内列表更新；断网恢复后自动重连

### AC-10: Room v1→v2 显式迁移不丢数据
- **Type**: `rule`
- **Given**: version=1 的 EveDatabase 中已有 records 数据
- **When**: 升级到新代码打开数据库并触发同步
- **Then**: 执行显式 Migration(1,2)，records 行数与内容不变，sync_state 写入 last_successful_sync；代码中不存在 fallbackToDestructiveMigration
- **Pass Condition**: Android instrumented/本地测试（优先提供 Migration 测试；无设备时以源码 + assembleDebug 通过 + 测试目录存在为证据，由独立评审判定）
- **Evidence**: androidTest Migration 测试或评审证据 + APK 构建成功

### AC-11: 密码库三类型与证件的零知识 CRUD
- **Type**: `rule`
- **Given**: Web 已解锁
- **When**: 新建/编辑/删除 login、note、card 与四类 identity 记录并同步，随后刷新页面重新登录拉取
- **Then**: 全部记录可正确解密展示、version 递增、墓碑同步；服务端 DB 中对应记录的 ciphertext 之外不存在 title/密码/卡号/证件号明文（audit 与日志亦无）
- **Pass Condition**: Web 手测清单 + Go 侧对 records 表只含密文的静态事实（既有架构）+ 抽查服务端日志无明文
- **Evidence**: 手测记录 + 代码评审

### AC-12: 密码生成器合规
- **Type**: `rule`
- **Given**: 用户选择长度与字符集
- **When**: 生成 100 次
- **Then**: 长度恒为指定值；每个启用的字符集至少出现 1 个字符；禁用字符集不出现；排除歧义选项生效；使用 crypto.getRandomValues 等密码学随机源
- **Pass Condition**: 源码评审 + 控制台/页面可重复验证
- **Evidence**: Web 源码 + 手测

### AC-13: 登录项 TOTP 符合 RFC 6238
- **Type**: `rule`
- **Given**: RFC 6238 附录 B 标准测试密钥（SHA-1）与固定时间戳
- **When**: Web TOTP 实现生成动态码
- **Then**: 与 RFC 向量在 59/1111111109 等时刻给出的 8 位截断值一致；UI 按 period 展示倒计时；录入 otpauth URI 能正确解析
- **Pass Condition**: 源码内含 RFC 向量自测函数（开发期 console 断言或测试文件），评审运行通过
- **Evidence**: Web 源码中的向量用例

### AC-14: 证件到期提醒正确性
- **Type**: `rule`
- **Given**: 分别构造已过期、30 天内、90 天内、90 天外四条证件记录
- **When**: 查看列表徽标与到期聚合视图
- **Then**: 颜色分级正确，聚合视图按到期日升序且只含 ≤90 天与已过期项
- **Pass Condition**: 手测清单 + 日期边界（第 30/90 天当天）判定逻辑源码评审
- **Evidence**: 手测记录 + 源码

### AC-15: Android 兼容与审批端能力
- **Type**: `rule`
- **Given**: Android 端登录已批准账户
- **When**: ① 同步含 pass/identity 模块数据；② 另一设备发起配对；③ 新设备在 Android 登录并过 MFA/等待审批
- **Then**: 同步不中断、笔记列表兼容 pass/note；审批列表出现请求并可批准（lazysodium box 封装 MK，对端开箱成功）/拒绝；Android 新设备流程能输入 TOTP 并在批准后收到 MK 解锁
- **Pass Condition**: assembleDebug 通过 + 代码评审；真机/模拟器审批互通为尽力项（无设备时独立评审按代码与互通向量判定）
- **Evidence**: APK 构建产物 + 评审记录

### AC-16: 敏感端点限流
- **Type**: `rule`
- **Given**: 同一客户端 IP
- **When**: 对 login/recovery/start/totp verify 连续 5 次失败后第 6 次请求（即使凭证正确）
- **Then**: 返回 429 直至 15 分钟窗口结束；成功登录重置计数；锁定事件可在 audit_logs 查到
- **Pass Condition**: Go e2e/单元测试断言 429 与审计行；限流键含 IP 与端点类别
- **Evidence**: 测试输出

### AC-17: 审计覆盖且无密钥泄露
- **Type**: `rule`
- **Given**: 走完注册、失败登录、恢复、改密、TOTP 开关、配对批准/拒绝/吊销
- **When**: 查询 audit_logs
- **Then**: FR-25 所列事件全部存在；全表 detail 与服务端 stdout 日志中 grep 不到 MK 字节、恢复码、TOTP code、主密码
- **Pass Condition**: e2e 尾部统一审计断言 + 日志 grep 检查
- **Evidence**: 测试输出

### AC-18: 安全实现质量
- **Type**: `rubric`
- **Dimension**: 零知识边界严谨性（常量时间比较、错误信息不枚举用户、令牌最小权限、临时 nonce/密钥一次性、敏感内存注释与零化意识）
- **Scale**: 1-5
- **Anchors**: 1 = 出现可识别的明文泄露或越权路径；3 = 主链路正确但存在 2 处以上宽松错误处理；5 = 所有新端点遵循既有常量时间/统一错误/最小 scope 模式且注释清晰
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审逐端点走查

### AC-19: 全量构建与静态检查通过
- **Type**: `rule`
- **Given**: 完整实现后
- **When**: 运行 CI 等价命令
- **Then**: `go test ./...`、`go vet ./...`、四目标 CGO=0 交叉编译、`npm run build`（vue-tsc）、`:app:assembleDebug` 全部成功
- **Pass Condition**: 本地可运行项输出 0 退出码；Go 项以 CI 运行结果/补装 Go 后输出为准并在 review 记录
- **Evidence**: 命令输出与 CI 链接/截图

### AC-20: 文档与计划同步
- **Type**: `rule`
- **Given**: 功能完成
- **When**: 检查 docs 与计划文档
- **Then**: docs/api.md 覆盖全部新端点；docs/crypto.md 增补恢复 AAD、box 封装、Crockford 向量；新增模块 schema 约定文档；everything_plan.md 阶段 1 遗留清零勾选、阶段 2 状态更新；envelope.go 过时注释（threads=2）更正为 1
- **Pass Condition**: 文档 diff 评审
- **Evidence**: 文档提交内容

## Open Questions

- 无（四个关键决策已由用户确认；其余为实现细节，按本规格与既有代码规范执行）。
