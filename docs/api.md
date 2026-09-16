# HTTP API（v1）

基础路径：`/api/v1`。所有字节字段（salt、verifier、公钥、nonce、密文等）在 JSON 中均为
**标准 Base64 字符串**（不是 Base64url）；时间戳一律 **Unix 毫秒 int64**。

除 `/health`、注册、登录、登录参数、刷新与恢复入口外，其余接口都需要令牌；
不同接口接受的令牌种类不同（见下节），并非一张 access token 走全部端点。

## 通用约定

### 令牌体系（typ / scope）

JWT 为 HS256 签名，业务声明为 `uid`（用户）、`did`（设备）、`typ`（令牌种类）、
`scp`（访问范围），并带 `iss=everything-eve`、`iat`、`exp`、`jti`。共五类：

| 令牌 | `typ` | `scp` | 有效期 | 传递方式 | 能访问的接口 |
|---|---|---|---|---|---|
| 已批准 access | `access` | `approved` | 15 分钟（默认，可配置） | `Authorization: Bearer` | 除 pending 专用端点外的全部业务接口 |
| 待审批 access | `access` | `pending` | 15 分钟 | `Authorization: Bearer` | 仅 `GET /auth/pairing/status` |
| refresh | 不使用 JWT：32 字节随机数的 64 字符小写 hex，库存 SHA-256 | — | 90 天（默认，可配置） | 请求体 `refresh_token` | 仅 `POST /auth/refresh` |
| recovery | `recovery` | `recovery` | 10 分钟（常量） | `Authorization: Bearer` | 仅 `POST /auth/recovery/reset` |
| mfa | `mfa` | `mfa` | 5 分钟（常量） | `Authorization: Bearer` | 仅 `POST /auth/totp/verify` |
| events | `events` | `events` | 5 分钟（常量） | `?token=`（SSE 建连） | 仅 `GET /events` |

鉴权中间件分层：

- `requireAccessToken`：只认 `typ=access`，随后由 `requireScope(...)` 按端点校验
  `approved` / `pending`。pending 设备访问 approved 接口时返回 `403 device_pending`，
  客户端据此引导到"等待审批"页，而不是当成未登录。
- `requireTypedToken(recovery|mfa)`：短期会话与 access 严格互斥，不能混用。
- `/events` 使用独立的双入口鉴权 `requireEventsAccess`：`?token=` 只接受 events 短期令牌，
  `Authorization` 只接受 scope=approved 的 access；pending/recovery/mfa 会话不能借道 SSE。

### 错误格式

```json
{ "error": "invalid_credentials", "message": "用户名或密码错误" }
```

完整错误码见文末[错误码](#错误码)章节。

### 其它传输约定

- 请求体上限 4 MiB；除 SSE 外所有 JSON 接口统一 60 秒服务端超时。
- 注册策略由配置 `registration` 控制：`first`（默认，注册过一个用户即关闭）、
  `open`（持续开放，仅建议可信测试环境）、`closed`。

## 系统

### GET /health

无需认证。

```json
{ "status": "ok", "version": "dev", "time": 1757952000000 }
```

## 账户与认证

### POST /auth/register

创建账户，同时登记首台设备（**自动批准**，是后续新设备登录的审批信任根）。
0002 协议起 `device_public_key` 与恢复密钥四材料均为**强制项**，缺失返回 400。

```json
{
  "username": "alice",
  "auth_salt": "<base64 16B>",
  "kek_salt": "<base64 16B>",
  "auth_verifier": "<base64 32B, Argon2id(password, auth_salt)>",
  "wrapped_master_key": "<base64, XChaCha20-Poly1305(KEK, MK), AAD=eve:v1:master-key/v1>",
  "device_name": "MacBook Chrome",
  "device_public_key": "<base64 32B X25519 公钥>",
  "recovery_auth_salt": "<base64 16B>",
  "recovery_kek_salt": "<base64 16B>",
  "recovery_verifier": "<base64 32B, Argon2id(归一化恢复码, recovery_auth_salt)>",
  "wrapped_master_key_recovery": "<base64, XChaCha(REK, MK), AAD=eve:v1:master-key-recovery/v1>"
}
```

`device_name` 可省略，服务端取 `User-Agent`。恢复码由客户端生成（20 字节随机量的
Crockford Base32，32 字符，展示为 4 组×8 字符），服务端永远接触不到恢复码与 MK 明文。

`201`（令牌对，scope=approved）：

```json
{
  "access_token": "<jwt>",
  "refresh_token": "<hex64>",
  "expires_in": 900,
  "token_type": "Bearer",
  "user_id": "uuid",
  "device_id": "uuid"
}
```

错误：`400 bad_request`（材料缺失或长度不符）、`403 registration_closed`、`409 user_exists`。

### GET /auth/parameters?username=alice

登录/恢复向导前取盐与包裹密文，无需认证。盐与包裹密文都不是秘密。

```json
{
  "auth_salt": "<base64 16B>",
  "kek_salt": "<base64 16B>",
  "wrapped_master_key": "<base64>",
  "recovery_auth_salt": "<base64 16B>",
  "recovery_kek_salt": "<base64 16B>",
  "wrapped_master_key_recovery": "<base64>",
  "argon2": { "algorithm": "argon2id", "time": 3, "memory_kib": 65536, "threads": 1, "key_len": 32 }
}
```

用户不存在（或缺少 `username` 参数之外的情形）一律 `401 invalid_credentials`，
不暴露用户名是否存在。`wrapped_master_key_recovery` 对 0001 时代的旧账户可能为空，
客户端应隐藏恢复入口。

### POST /auth/login

```json
{
  "username": "alice",
  "auth_verifier": "<base64 32B>",
  "device_name": "Pixel 8",
  "device_public_key": "<base64 32B X25519 公钥>"
}
```

`device_name` 可省略；`device_public_key` 必填（32B），服务端按公钥识别"同一台设备"。
密码校验常量时间比较；账户启用了 TOTP 时不创建设备，先发 mfa 会话。响应为三态：

**1）`status:"approved"` —— 已批准设备（含首设备自动批准、已登记公钥重复登录）：**

```json
{
  "status": "approved",
  "bundle": {
    "access_token": "<jwt>",
    "refresh_token": "<hex64>",
    "expires_in": 900,
    "token_type": "Bearer",
    "user_id": "uuid",
    "device_id": "uuid",
    "username": "alice",
    "auth_salt": "<base64>",
    "kek_salt": "<base64>",
    "wrapped_master_key": "<base64>"
  }
}
```

客户端用 `Argon2id(password, kek_salt)` 解出 MK 驻留内存。

**2）`status:"pending"` —— 新公钥、或曾被拒绝/吊销的公钥再次登录：**

```json
{
  "status": "pending",
  "pending": {
    "access_token": "<jwt, typ=access scp=pending, 15 分钟>",
    "expires_in": 900,
    "token_type": "Bearer",
    "user_id": "uuid",
    "device_id": "uuid",
    "pairing": {
      "id": "pair-xxxxxxxxxxxxxxxx",
      "device_id": "uuid",
      "device_name": "Pixel 8",
      "device_public_key": "<base64 32B>",
      "state": "pending",
      "created_at": 1757952000000,
      "expires_at": 1757952900000,
      "pairing_code": "A1B2C3",
      "fingerprint": "A1B2C3D4"
    }
  }
}
```

pending 令牌**没有** refresh、不能访问资料库；配对请求 15 分钟有效。
服务端同时向该用户在线的已批准设备推送 SSE `device_pairing_requested`。

**3）`status:"mfa_required"` —— 密码正确且账户启用了 TOTP：**

```json
{ "status": "mfa_required", "mfa_token": "<jwt, 5 分钟>", "expires_in": 300 }
```

错误：`400 bad_request`、`401 invalid_credentials`（用户不存在同形）、`429 rate_limited`。

### POST /auth/totp/verify

mfa 二步验证接续登录，`Authorization: Bearer <mfa_token>`。

```json
{ "code": "123456", "device_public_key": "<base64 32B>", "device_name": "Pixel 8" }
```

验证码（RFC 6238，30 秒步长、±1 步容差）通过后才进入设备审批状态机，因此响应仍是
登录三态中的 **approved 或 pending**（结构同 `/auth/login`，不会再返回 mfa_required）。

错误：`400 bad_request`、`401 invalid_totp`（错码/过期）、
`409 totp_not_configured`、`429 rate_limited`（与登录共享失败计数桶）。

### POST /auth/refresh

```json
{ "refresh_token": "<hex64>" }
```

`200`：新的令牌对（结构同注册响应；refresh token 本身不轮换）。
以下情形一律 `401 token_expired`：令牌不存在、已过期、**已被吊销（含设备被吊销/恢复/改密后）**、
所属设备不是 approved —— 吊销后即刻生效，无需等待 access 过期。请求体格式不合法为
`400 bad_request`。

## 账户恢复

恢复码是与主密码独立的账户级根凭证。恢复成功等价于账户接管：重设主密码材料、
强制轮换新恢复码（旧码立即作废）、吊销全部既有 refresh、作废待审批设备与配对，
执行恢复的设备凭其公钥直接成为 approved 设备。

### POST /auth/recovery/start

无需认证。

```json
{ "username": "alice", "recovery_verifier": "<base64 32B>" }
```

`200`：

```json
{
  "recovery_token": "<jwt, typ=recovery, 10 分钟>",
  "expires_in": 600,
  "token_type": "Bearer",
  "user_id": "uuid",
  "recovery_kek_salt": "<base64 16B>",
  "wrapped_master_key_recovery": "<base64>"
}
```

客户端在本地用 `Argon2id(恢复码, recovery_kek_salt)` 派生 REK 解开 MK（MK 不变），
再生成新主密码与新恢复码材料。

错误：`400 bad_request`、`401 invalid_credentials`（恢复码错误、用户不存在、
账户未登记恢复材料三者**完全同形**，防枚举）、`429 rate_limited`。

### POST /auth/recovery/reset

`Authorization: Bearer <recovery_token>`。请求体 = 新主密码四材料 + **强制**的新恢复码
四材料 + 恢复设备公钥：

```json
{
  "auth_salt": "<16B>", "kek_salt": "<16B>",
  "auth_verifier": "<32B>", "wrapped_master_key": "<base64>",
  "recovery_auth_salt": "<16B>", "recovery_kek_salt": "<16B>",
  "recovery_verifier": "<32B>", "wrapped_master_key_recovery": "<base64>",
  "device_public_key": "<32B X25519>",
  "device_name": "MacBook (恢复)"
}
```

`200`：恢复设备的 approved 令牌对（结构同注册响应），该设备自动批准。
错误：`400 bad_request`、`401 unauthorized`（recovery 会话无效/过期）。

### POST /auth/password/change（approved）

在已批准设备上修改主密码（MK 不变，仅更换包裹与登录材料）。

```json
{
  "auth_salt": "<16B>", "kek_salt": "<16B>",
  "auth_verifier": "<32B>", "wrapped_master_key": "<base64>",
  "recovery_auth_salt": "<可选，四字段必须整组出现>",
  "recovery_kek_salt": "<可选>",
  "recovery_verifier": "<可选>",
  "wrapped_master_key_recovery": "<可选>",
  "refresh_token": "<可选，当前设备旧 refresh，随改密一并吊销换发>"
}
```

恢复四字段要么整组缺省（不轮换恢复码），要么整组合法，否则 `400 bad_request`。
`200`：当前设备的新令牌对；**其他设备的 refresh 全部即刻吊销**。

## TOTP 二次验证管理（approved）

| 端点 | 请求体 | 成功响应 |
|---|---|---|
| `GET /auth/totp` | — | `{ "enabled": false, "has_secret": false }` |
| `POST /auth/totp/setup` | — | `{ "secret": "<Base32>", "otpauth_url": "otpauth://...", "qr_data_uri": "data:image/png;base64,...", "confirmed": false }` |
| `POST /auth/totp/enable` | `{ "code": "123456" }` | `{ "enabled": true }` |
| `POST /auth/totp/disable` | `{ "code": "123456" }` | `{ "enabled": false }` |

- setup 只写入待确认密钥（参数：30 秒步长、6 位、SHA-1，issuer=`Everything`），
  不影响登录；必须用当前码 enable 后才生效。已启用时 setup 返回
  `409 totp_already_enabled`（须先 disable，防止静默换掉在用密钥）。
- enable/disable/登录验证错码均为 `401 invalid_totp`；未配置时为
  `409 totp_not_configured`；已启用再 enable 为 `409 totp_already_enabled`。
- disable 成功后服务端清除密钥与确认时间戳。

## 设备与配对

### GET /auth/devices（approved）

```json
{
  "devices": [
    {
      "id": "uuid",
      "name": "MacBook Chrome",
      "state": "approved",
      "fingerprint": "A1B2C3D4",
      "last_seen": 1757952000000,
      "created_at": 1757865600000,
      "current": true
    }
  ]
}
```

`state` 为 `pending` / `approved` / `rejected` / `revoked`；`current` 标记调用令牌所属设备。

### POST /auth/devices/{id}/revoke（approved）

无请求体。吊销目标设备并**即时吊销其全部 refresh token**。

`200`：`{ "status": "revoked", "device_id": "uuid" }`，并广播 SSE `device_list_changed`。
不能吊销当前设备：`400 bad_request`；目标不存在或已是 revoked：`404 not_found`。

### GET /auth/pairings（approved）

返回当前全部待处理配对（审批列表/角标用）；过期 pending 会被惰性置为 expired 后过滤。

```json
{
  "pairings": [
    {
      "id": "pair-xxxxxxxxxxxxxxxx",
      "device_id": "uuid",
      "device_name": "Pixel 8",
      "device_public_key": "<32B>",
      "state": "pending",
      "created_at": 1757952000000,
      "expires_at": 1757952900000,
      "pairing_code": "A1B2C3",
      "fingerprint": "A1B2C3D4"
    }
  ]
}
```

### POST /auth/pairings/{id}/approve（approved）

批准配对并端到端下发 MK。审批端在本机内存生成一次性 X25519 临时密钥对与 24B nonce，
用新设备公钥做 nacl/box（`crypto_box_easy`）密封 MK；**服务端只透传盒材料，无法接触 MK**。

```json
{
  "ephemeral_public_key": "<base64 32B，临时公钥>",
  "nonce": "<base64 24B>",
  "wrapped_master_key": "<base64, nacl/box(eph_sk, 新设备公钥, MK)>"
}
```

`200`：完整 `PairingInfo`（`state:"approved"`，含三件盒材料与 `responded_device_id`），
设备转为 approved，并广播 SSE `device_pairing_resolved`。

错误：材料长度不符 `400 bad_request`；配对不存在 `404 not_found`；
已处理/已过期/审批自己 `409 pairing_closed`。

### POST /auth/pairings/{id}/reject（approved）

无请求体、不传递任何密钥材料。设备与配对均置 rejected。

`200`：`{ "status": "rejected", "device_id": "uuid" }`，同样广播 `device_pairing_resolved`；
错误码同 approve。被拒绝的公钥之后可以再次登录，重新进入 pending。

### GET /auth/pairing/status（pending）

待审批设备用自己的 pending access 令牌轮询（不能使用默认 approved 令牌）。
返回该设备最新一条配对（结构同 `pairings` 列表项，approved 时另带盒材料）：

```json
{
  "id": "pair-...",
  "device_id": "uuid",
  "device_name": "Pixel 8",
  "device_public_key": "<32B>",
  "ephemeral_public_key": "<批准后出现，32B>",
  "nonce": "<批准后出现，24B>",
  "wrapped_master_key": "<批准后出现>",
  "state": "approved",
  "created_at": 1757952000000,
  "expires_at": 1757952900000,
  "responded_device_id": "uuid",
  "pairing_code": "A1B2C3",
  "fingerprint": "A1B2C3D4",
  "tokens": {
    "access_token": "<jwt>",
    "refresh_token": "<hex64>",
    "expires_in": 900,
    "token_type": "Bearer",
    "user_id": "uuid",
    "device_id": "uuid"
  }
}
```

`state` 可能为 `pending` / `approved` / `rejected` / `expired`；仅在 approved 且盒材料
存在时响应才附 `tokens` 正式令牌对——新设备轮询到批准后一次性取出 MK 盒与正式凭证，
本地 `box_open` 解出 MK 即完成开箱。没有任何配对记录时 `404 not_found`；
pending 请求超过 15 分钟会被惰性标记为 expired。

## 加密记录

### POST /records/batch（approved）

单批 1–1000 条。`device_id` 由服务端按令牌覆盖；墓碑记录（`deleted:true`）允许空
`ciphertext`，普通记录必须携带密文。

**时钟假设（FU-1）**：`updated_at` 一律由服务端以自身权威时间覆盖写入，客户端上传值
仅作占位、不被采用——增量同步游标因此只依赖服务端时钟，设备本地时钟偏快/偏慢都不会
导致漏拉。`created_at` 保留客户端上传值（缺省回填服务端当前时间），仅作展示字段，
不参与同步判定。

```json
{
  "records": [
    {
      "id": "uuid",
      "module": "pass",
      "type": "login",
      "ciphertext": "<base64 nonce(24)||cipher, AAD=eve:v1:record:id:module:BE64(version)>",
      "version": 1,
      "created_at": 1757865600000,
      "updated_at": 1757865600000,
      "deleted": false
    }
  ]
}
```

合并规则：仅当传入 `version` **严格大于**库中版本时覆盖（LWW），否则计入 skipped；
新记录直接插入。

`200`：`{ "applied": 1, "skipped": 0, "server_time": 1726400000000 }`。`server_time` 为
本批写入使用的服务端权威时间（毫秒），客户端推送成功后应以它校准本地增量游标（而非
本地时钟）。本批实际有写入时广播 SSE `records_changed`。
空批次/超过 1000 条/缺 id、module 或非墓碑缺密文均为 `400 bad_request`。

### GET /records?since=0&limit=500（approved）

返回 `updated_at > since` 的记录，按 `updated_at ASC, id ASC` 排序。
`limit` 默认 500、最大 2000（超出或非正均回落 500）。

```json
{
  "records": [ { "id": "uuid", "module": "pass", "type": "login", "ciphertext": "...",
    "version": 1, "device_id": "uuid", "created_at": 0, "updated_at": 0, "deleted": false } ],
  "has_more": false
}
```

`has_more=true` 时以本页最后一条的 `updated_at` 作为下次 `since` 游标继续翻页。
注意判定式为"返回条数达到 limit"，因此整页恰好等于 limit 时即使没有更多数据也会报
`true`，客户端再拉一次得到空页即可终止。

## 实时事件

### POST /auth/events-token（approved）

为浏览器 `EventSource`（无法自定义 Authorization 头）换发建连专用短期令牌：

```json
{ "events_token": "<jwt, typ=events, 5 分钟>", "expires_in": 300 }
```

该令牌只能用于建立 `/events` 连接，不能调用任何其他接口。

### GET /events（SSE）

双入口鉴权（二选一，严格互斥）：

- `GET /api/v1/events?token=<events_token>`：仅接受 events 短期令牌（浏览器场景）；
- `GET /api/v1/events` + `Authorization: Bearer <approved access>`：仅接受已批准 access。

响应为 `text/event-stream`（`Cache-Control: no-cache`、`X-Accel-Buffering: no`），
不受 60 秒通用超时限制。建连即发注释帧 `: connected`，之后每 30 秒发 `: ping` 保活；
业务帧格式：

```
event: records_changed
data: {"type":"records_changed"}

```

事件类型（data 均为 `{"type": "<type>"}` 形态）：

| 事件 | 触发时机 |
|---|---|
| `records_changed` | 同账户某设备 batch 实际写入了记录 |
| `device_pairing_requested` | 新设备登录进入 pending（含 mfa 通过后转 pending） |
| `device_pairing_resolved` | 配对被批准或拒绝 |
| `device_list_changed` | 设备被吊销 |

SSE 只是加速通知，不保证投递（进程内总线，慢消费者会丢帧）；断线后一律以
`GET /records?since=` 增量补拉为准。pending 设备无权订阅事件，靠轮询
`/auth/pairing/status` 获取审批结果。

## 限流

`login` / `recovery/start` / `totp/verify` 三个敏感写端点共享同一个按 IP 计数的
`auth-write` 失败桶，防止在端点间轮换爆破：

- 15 分钟计数窗口内累计 **5 次失败**即锁定 **15 分钟**，锁定期间直接
  `429 rate_limited`（`{ "error": "rate_limited", "message": "失败尝试过多，请 15 分钟后再试" }`）；
- 任一凭证成功即清零该 IP 计数；请求体非法等与爆破无关的请求不计数；
- 限流器为进程内实现，单实例自托管足够，多实例部署需换共享存储；
- 锁定/拦截另写审计事件 `auth_locked`（新锁定一次）与 `auth_rate_limited`（每次拦截）。

## 错误码

| HTTP | `error` | 典型场景 |
|---|---|---|
| 400 | `bad_request` | JSON 非法、字段缺失/长度不符、批次为空或超 1000、吊销自己 |
| 401 | `invalid_credentials` | 登录用户名/密码错误、parameters 用户不存在、恢复码错误（同形不枚举） |
| 401 | `invalid_totp` | TOTP 验证码错误或过期 |
| 401 | `token_expired` | refresh 令牌无效、过期或已被吊销（设备吊销/恢复/改密后即刻失效） |
| 401 | `unauthorized` | 缺少/无效的 Authorization 或短期会话令牌、recovery 会话失效 |
| 403 | `registration_closed` | 注册策略关闭（first 模式已有用户，或 closed） |
| 403 | `device_pending` | pending 设备访问 approved 接口 |
| 403 | `forbidden` | scope 不匹配的其他情况（如非 approved 令牌订阅 SSE） |
| 404 | `not_found` | 设备/配对不存在、未知 `/api/` 路径 |
| 409 | `user_exists` | 注册用户名冲突 |
| 409 | `totp_not_configured` | 未 setup/未启用却执行 enable/disable/verify |
| 409 | `totp_already_enabled` | 已启用仍调用 setup/enable |
| 409 | `pairing_closed` | 配对已处理、已过期，或试图审批/拒绝自己 |
| 429 | `rate_limited` | 认证写桶连续失败触发锁定 |
| 500 | `internal` | 服务端内部错误（不携带内部细节） |

## 配置

配置文件默认位于 `<data_dir>/config.yaml`，可用 `--config` 指定；命令行另有
`--addr`、`--data-dir` 覆盖项。仅以下三项支持环境变量覆盖；TTL 只能在 yaml 中配置。

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `addr` | `EVE_ADDR` | `:8787` | HTTP 监听地址 |
| `data_dir` | `EVE_DATA_DIR` | `./data` | SQLite 数据库、`jwt.key`（HS256 签名密钥，首启自动生成 32B，0600）存放处 |
| `registration` | `EVE_REGISTRATION` | `first` | `first` / `open` / `closed`，本地测试可设 `open` |
| `access_token_ttl_minutes` | —（仅 yaml） | `15` | access 令牌（approved 与 pending 同值） |
| `refresh_token_ttl_days` | —（仅 yaml） | `90` | refresh 令牌有效期 |

不可配置的常量 TTL：recovery 会话 10 分钟、mfa 会话 5 分钟、events 令牌 5 分钟、
pending 配对请求 15 分钟。完整示例：[deploy/config.example.yaml](../deploy/config.example.yaml)
