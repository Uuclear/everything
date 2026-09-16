# 架构总览

## 分层

```
┌───────────────────────┐   ┌────────────────────────┐
│  Web (Vue3 + WASM)    │   │  Android (Kotlin/JNA)  │
│  libsodium 加解密      │   │  libsodium 加解密       │
│  本地 IndexedDB(规划)  │   │  Room 离线库 + WM 同步  │
└──────────┬────────────┘   └───────────┬────────────┘
           │  HTTPS REST + SSE（密文）   │
┌──────────▼────────────────────────────▼────────────┐
│                    Go 服务端（单二进制）              │
│  auth(JWT 多类型令牌 · 设备配对状态机 · TOTP · 恢复)  │
│  vault(records 信封) · sync(SSE Hub)                │
│  modules(薄) · location(规划) · agent(规划)         │
│  SQLite(WAL) · 附件块存储(规划) · 备份(规划)          │
│  go:embed 托管网页构建产物                            │
└─────────────────────────────────────────────────────┘
```

**信任模型**：服务端是"诚实但好奇"的不可信存储——代码开源自托管，但即使拿到数据库文件，
没有客户端主密码也无法解密任何内容。所有搜索、过滤、AI 处理都在解锁会话中进行（见加密规范）。
恢复码、TOTP 登录因素与设备配对信道不改变这一前提：服务端只存恢复码的 Argon2id 验证器与
REK 包裹密文，配对时只透传 nacl/box 盒材料，MK 在任何路径上都不以明文到达服务端。

## 核心组件

| 目录 | 职责 |
|---|---|
| `server/cmd/eve` | 入口：配置、日志、优雅退出 |
| `server/internal/config` | yaml + 环境变量配置，注册策略 first/open/closed，access/refresh TTL |
| `server/internal/crypto` | Argon2id / XChaCha20-Poly1305 信封（与客户端字节级一致） |
| `server/internal/auth` | 注册登录、五类 JWT、刷新令牌（含吊销）、设备表与配对状态机、恢复码、TOTP |
| `server/internal/vault` | 通用加密记录信封存取，版本号幂等合并 |
| `server/internal/sync` | 用户级进程内事件总线（SSE），多副本时替换为 NATS/PG LISTEN |
| `server/internal/api` | chi 路由、分层鉴权中间件、认证失败限流、SSE、内嵌 SPA 托管 |
| `server/internal/db` | SQLite（modernc 纯 Go 驱动，免 CGO）+ 版本化迁移（0001/0002） |
| `web/src/crypto` | 与 Go 完全一致的 libsodium 信封、Crockford 恢复码、X25519 设备身份与 box |
| `web/src/stores` | Pinia：认证（含恢复/TOTP/配对流程）、资料库、SSE 事件通道 |
| `android/.../crypto` | 信封与 Crockford（lazysodium-android / JNA） |
| `android/.../auth` | AuthManager：令牌保管、设备 X25519 身份、内存态 MK、三态登录 |
| `android/.../data` | Room 加密记录、离线优先仓库 |
| `android/.../sync` | WorkManager 周期 + 即时同步 |

## 数据模型

0001 建立 `users / devices / refresh_tokens / records / audit_logs`；
0002（阶段 1 收尾）追加恢复材料、TOTP 确认列、设备审批状态与配对表。

- `users`：
  - 登录材料：`auth_salt`、`kek_salt`（各 16B）、`auth_verifier`（32B 派生值，非口令）、
    `wrapped_master_key`（KEK 包裹的 MK）；
  - 恢复材料（0002 新增，均可空以兼容旧开发库；新注册强制非空）：
    `recovery_auth_salt`、`recovery_kek_salt`、`recovery_verifier`、
    `wrapped_master_key_recovery`（REK 包裹的 MK，AAD 与主密码包裹域分离）；
  - TOTP：`totp_secret`（0001 预留，setup 后写入待确认密钥）与
    `totp_confirmed_at`（0002 新增毫秒时间戳，NULL = 未启用，只有它非空时登录才要求二步验证）。
- `devices`：设备名、X25519 `public_key`（32B）、`last_seen` 毫秒时间戳。
  审批状态以 0002 新增的 `state TEXT` 为准：`pending`（待审批，只能查配对状态）、
  `approved`（完整访问权，0001 旧设备迁移默认 approved）、`revoked`（手动吊销，
  刷新令牌即时失效）；配对被拒绝时设备短暂处于 `rejected`（可再次登录回到 pending）。
  旧整数列 `approved` 仍保留并由代码同步维护，仅为兼容历史路径。
- `device_pairings`（0002 新增）：新设备配对请求。
  - 键值：`id`（`pair-` + 16 hex 随机，防枚举）、`user_id`、`device_id`、
    `device_public_key`；
  - 盒材料（仅透传，服务端不可解密）：`ephemeral_public_key`（32B）、`nonce`（24B）、
    `wrapped_master_key`，批准时才写入；
  - 状态机 `state`：`pending / approved / rejected / expired`；
  - 毫秒时间戳：`created_at`、`expires_at`（pending 有效期 15 分钟，
    查询时惰性置 expired）、`responded_at`，以及 `responded_device_id`（审批设备）；
  - 索引：`(user_id, state)` 服务审批列表与按用户广播，`(device_id)` 服务状态机更新。
- `refresh_tokens`：仅存刷新令牌的 SHA-256 哈希、设备归属与过期毫秒时间戳；
  0001 即带 `revoked` 列，0002 的设备吊销、恢复接管、改密都会立即把对应行置 revoked=1，
  刷新接口每次校验吊销位与设备 `state`，失效即刻生效。
- `records`：通用加密信封 `(user_id, id)` 主键，明文列仅有 `module / type / 时间戳 /
  version / deleted / device_id`。
  - 合并规则：仅当传入 `version` 严格大于库中版本时覆盖（LWW）。
  - 增量同步：`GET /records?since=<updated_at 毫秒>`；`deleted=true` 墓碑允许空密文。
- `audit_logs`：`(user_id 可空, event, detail, ip, created_at)`。FR-25 要求的 14 类事件
  全部落表：`register`、`login`、`login_failed`、`recovery_start`、
  `recovery_start_failed`、`password_reset`、`password_change`、`totp_enabled`、
  `totp_disabled`、`totp_verify_failed`、`device_pair_requested`、`device_approved`、
  `device_rejected`、`device_revoked`。代码另记录若干过程事件：`login_mfa_required`、
  `totp_setup`、`totp_enable_failed`、`totp_disable_failed`、`auth_rate_limited`、
  `auth_locked`。**敏感值禁入日志**：detail 只允许出现设备名、设备 id、用户名等标识，
  主密码、恢复码、MK、TOTP 码、验证器字节及字段名都不得出现（有 e2e 全表 grep 断言）。

高频专用存储（后续阶段）：`locations`（按月分表）、短信/通话/联系人游标表、附件密文块。

## 令牌体系与鉴权链

HS256 对称签名，密钥为 `<data_dir>/jwt.key`（首启自动生成 32 字节，0600；轮换密钥会使
全部已签发 JWT 失效，refresh 不受影响但仍需重新登录）。JWT 声明：
`uid`、`did`（短期会话可为空）、`typ`、`scp` 及标准的 `iss=everything-eve / iat / exp / jti`。
服务端固定校验算法为 HS256，拒绝 `typ` 不符的令牌。

- access：`typ=access`，scope 分 `approved` 与 `pending` 两种；默认 15 分钟
  （yaml `access_token_ttl_minutes`）。
- 短期会话：`recovery`（10 分钟）、`mfa`（5 分钟）、`events`（5 分钟），
  typ 与 scope 同名，只承载身份、不落 refresh_tokens 表，且只能访问各自的唯一端点。
- refresh：非 JWT，32 字节随机数的 64 字符 hex；库中仅存 SHA-256；默认 90 天
  （yaml `refresh_token_ttl_days`）。approved 设备的令牌对在注册、批准登录、刷新、
  恢复重置、改密五个时点签发；pending access 不签发 refresh。

签发与校验链（`internal/auth` + `internal/api/middleware.go`）：

1. `signJWT` 统一签发；`issuePair` 同时落一行 refresh_tokens。
2. 路由按中间件分层：`requireAccessToken`（只认 typ=access）→
   `requireScope(approved|pending)` 按端点心校验；recovery/mfa 走
   `requireTypedToken`，typ 与 scope 双重匹配；SSE 走独立的
   `requireEventsAccess` 双入口（`?token=` events 或 Bearer approved，严格互斥）。
3. pending 访问 approved 端点返回 `403 device_pending`，客户端据此进入"等待审批"页。
4. refresh 每次联表校验：令牌哈希、`revoked`、过期时间与设备 `state='approved'`。

## 设备配对（端到端 X25519，服务端只透传）

每台设备在本机由 32B seed 恒定派生 X25519 身份密钥对；公钥随注册/登录提交，
服务端按公钥（而非设备名）识别"同一台设备"。

新设备登录状态机（`finishDeviceLogin`）：

1. 同公钥设备已 approved → 更新 `last_seen`，直接发正式令牌对；
2. 账户没有任何 approved 设备（首设备）→ 自动批准并发正式令牌（信任根）；
3. 其余公钥（全新、或历史 rejected/revoked/pending）→ 设备置 pending，
   旧 pending 配对置 expired 后新建一条 15 分钟有效的 `device_pairings`，
   只签发无 refresh 的 pending access；若账户启用 TOTP，整个设备判定推迟到
   `/auth/totp/verify` 通过后进行。

批准时的密钥下发（nacl/box，三端协议一致）：

1. 审批端（已批准设备）收到 SSE `device_pairing_requested`，从
   `GET /auth/pairings` 看到新设备公钥与配对码 `pairing_code`
   （公钥 SHA-256 前 3 字节的 6 位大写 hex）、指纹（前 4 字节 8 位），人工核对防调包；
2. 审批端在内存生成一次性临时 X25519 密钥对 `(eph_pk, eph_sk)` 与随机 24B nonce，
   `sealed = crypto_box_easy(MK, nonce, recipient=新设备公钥, eph_sk)`，
   提交 `{ephemeral_public_key, nonce, wrapped_master_key}` 到 approve；
3. 服务端校验配对归属/开放状态/不能审批自己，原样存储盒材料，设备与配对置 approved，
   广播 `device_pairing_resolved`；全程无法解密盒内容；
4. 新设备用 pending access 轮询 `GET /auth/pairing/status`，看到 approved 后一次性
   取得盒材料与换发的正式令牌对，本地
   `box_open_easy(sealed, nonce, sender=eph_pk, 本机 sk)` 还原 MK，开箱完成。
   每次批准态轮询都会新签一对令牌，客户端只应在批准后取一次。

拒绝路径不写任何密钥材料（设备/配对置 rejected）；pending 超过 15 分钟惰性置 expired；
恢复账户接管时所有 pending 配对 expired、pending 设备 rejected。

## TOTP 二次验证

RFC 6238（30 秒步长、6 位、SHA-1、±1 步容差），密钥由 `pquerna/otp` 生成，
二维码由服务端渲染成 PNG data URI（Web 不引入扫码/二维码库）。两段式启用：
`setup` 只写待确认密钥（不影响登录），`enable` 用当前验证码写 `totp_confirmed_at`；
已启用必须先 `disable` 才能重新 setup。登录时仅以确认时间戳是否非空判定，命中则不创建设备、
改发 5 分钟 mfa 会话，`/auth/totp/verify` 通过后才走设备状态机（故可能再落 pending）。
TOTP secret 只是登录因素，与资料库加密无关。

## 账户恢复

恢复码是 20 字节随机量的 Crockford Base32（32 字符，4 组×8 展示），与主密码独立。
客户端额外派生 REK 并用独立 AAD（`eve:v1:master-key-recovery/v1`）包裹 MK；
注册与恢复重置时恢复四材料强制存在。恢复流程两段式：

1. `recovery/start`：常量时间比对验证器，用户不存在/未登记材料/验证器不符同形报错，
   通过后签发 10 分钟 recovery 会话，并回传 `recovery_kek_salt` 与恢复包裹，
   客户端离线解出 MK（MK 在重置前后不变）；
2. `recovery/reset`：单事务重写登录材料与新恢复材料（恢复码一次性轮换）、
   吊销全部 refresh、作废 pending 设备/配对、把执行设备按公钥复用或新建为 approved，
   签发正式令牌——等价于账户接管。

改密（approved 设备）路径轻得多：MK 不变只换包裹，可选择性整组轮换恢复材料，
仅吊销其他设备（及随请求提交的当前设备旧 refresh）的令牌。

## 限流与滥用防护

敏感认证写端点（login、recovery/start、totp/verify）共享进程内按 IP 的 `auth-write`
失败计数桶：15 分钟窗口 5 次失败即锁 15 分钟，成功清零，请求体非法等不计数；
共享桶防止在三个端点间轮换爆破。锁定触发与拦截分别审计 `auth_locked`、
`auth_rate_limited`。单实例足够，多实例需换共享存储。其余防护：注册策略 first/open/closed、
所有凭证比对常量时间、用户枚举同形错误、JWT 最小 scope、配对 id 随机不可枚举。

## SSE Hub 与 events 短期令牌

`sync.Hub` 是按用户分组的进程内发布订阅（每订阅者 16 缓冲，满则非阻塞丢弃，
`sync.Event{type, module?}`）。四类广播：`records_changed`（batch 实际写入后）、
`device_pairing_requested`、`device_pairing_resolved`、`device_list_changed`。

`/events` 独立于 60 秒通用超时；建连发 `: connected`、30 秒 `: ping`，
帧为 `event: <type>\ndata: <json>`。浏览器 `EventSource` 无法自定义请求头，因此
approved 设备先 `POST /auth/events-token` 换取 5 分钟 events 令牌，以
`GET /events?token=` 建连；该令牌 typ/scope 都是 events，无法用于任何其他端点。
原生客户端可直接用 approved access 的 Bearer 建连。SSE 仅为加速通知，可靠性始终由
`GET /records?since=` 增量补拉兜底；pending 设备没有 SSE 权限，轮询配对状态。

## 请求生命周期（写入一条记录）

1. 客户端在内存持有 MK（32B，来自注册、主密码登录、恢复解包或配对 box 开箱）。
2. 明文 JSON → `XChaCha20-Poly1305(MK, plaintext, AAD=id+module+BE64(version))`。
3. `POST /api/v1/records/batch` 携带 Bearer approved JWT；服务端只做版本比较与落盘。
4. 服务端通过 SSE 向该用户其他在线设备广播 `records_changed`。
5. 其他设备收到信号后以 `since=` 拉取密文，本地用 MK 解密。

## 扩展模块的方式

新业务模块（密码库、证件、记账……）= 一份 JSON Schema + 前端表单/列表页，
服务端 `records` 表与同步通道零改动；需要特殊索引的模块再在 `internal/modules` 增加薄逻辑。
