# 阶段 1 收尾 + 阶段 2 密码库 - 实施计划

> 任务按依赖顺序串行实施（单人开发）。服务端先行（T1–T6），Web 随后（T7–T10），Android 收尾（T11–T12），文档与全量验证最后（T13）。
> 每个任务完成时必须填 Completion Evidence（含命令输出/文件引用）。所有新增密码学代码保留详细中文注释。

## Task 1: 服务端 0002 迁移与注册模型扩展

- **Status**: `completed`
- **Completion Evidence**:
  - 新增 [0002_stage1_finish.sql](file:///d:/github/everything/everything/server/internal/db/migrations/0002_stage1_finish.sql)：users 五列（恢复四材料+totp_confirmed_at）、devices.state、device_pairings 表与两个索引。
  - [service.go](file:///d:/github/everything/everything/server/internal/auth/service.go)：RegisterInput 四恢复字段并写入；Claims 增加 Scope；令牌 typ/scope 体系（access/refresh/recovery/mfa/events + 五类 scope 常量）；Refresh JOIN devices 校验 state（吊销即失效）；IssueScopedToken 短期令牌基建。
  - [auth_handler.go](file:///d:/github/everything/everything/server/internal/api/auth_handler.go)：注册强制校验 device_public_key(32B) 与恢复四材料，非法 400。
  - [envelope.go](file:///d:/github/everything/everything/server/internal/crypto/envelope.go)：DevicePublicKeyLen 常量、恢复信封 WrapForRecovery/UnwrapForRecovery（独立 AAD 域）；文件头注释 threads=2 更正为 1（AC-20 部分）。
  - TR-1.1/1.2 通过：新增 [migrate_test.go](file:///d:/github/everything/everything/server/internal/db/migrate_test.go) 覆盖空库迁移与 0001→0002 在线升级（旧数据保留、state 回填 approved、恢复列 NULL）；`go test ./internal/db/` ok；`go test ./...` 全 ok；`go vet ./...` 无告警。
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新增 `server/internal/db/migrations/0002_stage1_finish.sql`：
    - `users` 增加 `recovery_auth_salt BLOB`、`recovery_kek_salt BLOB`、`recovery_verifier BLOB`、`wrapped_master_key_recovery BLOB`、`totp_confirmed_at INTEGER`（均可空，兼容既有开发库）。
    - `devices` 增加 `state TEXT NOT NULL DEFAULT 'approved'`（pending/approved/revoked；与既有 approved 列并存，新逻辑以 state 为准）。
    - 新建 `device_pairings`（id、user_id、device_id、device_public_key、ephemeral_public_key、nonce、wrapped_master_key、state、created_at、expires_at、responded_device_id、responded_at，索引 user+state）。
  - `auth/service.go`：`RegisterInput` 增加四个恢复材料字段并在 Register 中写入；`Claims` 增加 `Scope string`（approved/pending/recovery/mfa/events）；签发令牌的内部方法支持 typ 与 scope。
  - `api/auth_handler.go`：register 校验恢复字段长度（salt 各 16B、verifier 32B、wrapped 非空），非法 400；device_public_key 变为必填 32B（首设备同样提交）。
  - 同步更新 e2e 测试里的注册请求体（带恢复材料与设备公钥，可用 nacl/box 生成），保持既有测试编译通过。
- **Acceptance Criteria Addressed**: AC-1、AC-19（部分）
- **Test Requirements**:
  - `rule` TR-1.1: 空库执行 0001+0002 成功；从仅含 0001（含 1 用户/1 设备数据）的库升级成功，新列存在且旧数据行数不变；证据：测试输出 `go test ./internal/db/...`（或 CI）。
  - `rule` TR-1.2: 注册缺恢复材料/公钥长度错误返回 400 且不产生用户行；合法注册 201 且用户行四恢复列非空；证据：handler 测试/e2e 输出。
- **Notes**: 迁移文件须保持与 0001 相同的中文注释风格；SQLite ALTER TABLE ADD COLUMN 不支持 NOT NULL 无默认，state 列带 DEFAULT 'approved'。

## Task 2: 敏感端点限流中间件与审计事件补全

- **Status**: `completed`
- **Completion Evidence**:
  - 新增 [ratelimit.go](file:///d:/github/everything/everything/server/internal/api/ratelimit.go)：按 "类别|IP" 的固定窗口限流器（5 次失败/15 分钟计数 + 15 分钟锁定、成功清零、概率懒清理）；guardAuth 包装器输出 429 rate_limited 并审计 auth_rate_limited/auth_locked。
  - login 已接入（doLogin 返回 authResult；非法请求体 authSkipped 不计数；login_failed 为 authFailed）。
  - TR-2.1/2.2 通过：[ratelimit_test.go](file:///d:/github/everything/everything/server/internal/api/ratelimit_test.go) 验证第 6 次 429、处理器不被调用、auth_locked 审计恰 1 行、IP 隔离、成功重置；TR-2.3 并发冒烟 500 次混合并发无 panic/死锁；`go test ./...` 全绿。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新增 `server/internal/api/ratelimit.go`：进程内 `map[类别+IP]*计数器` + mutex，固定窗口；类别 `auth-write` 覆盖 POST `/auth/login`、`/auth/recovery/start`、`/auth/totp/verify`；阈值 5 次失败/15 分钟锁定；成功响应重置该键计数；超限返回 429 `rate_limited`。键取 RealIP 后的 IP（已有 middleware.RealIP）。
  - 审计事件常量集中到 auth 或 api 包，补全 FR-25 全部事件；限流锁定与失败尝试写审计；detail 仅允许设备名/用户名/原因，明令禁止敏感值。
  - 在路由中对三个端点挂限流包装。
- **Acceptance Criteria Addressed**: AC-16、AC-17、AC-18
- **Test Requirements**:
  - `rule` TR-2.1: 同 IP 连续 5 次失败后第 6 次（即使凭证正确）返回 429；audit_logs 出现锁定/失败事件；证据：`go test` 输出。
  - `rule` TR-2.2: 成功登录后计数重置，下一次失败计数从 0 开始；证据：测试输出。
  - `rubric` TR-2.3: 实现质量（无 goroutine 泄漏：懒清理过期键；map 访问全程持锁/分片合理）；scale 1-5；anchors 1=数据竞争/死锁，3=功能正确但清理粗糙，5=带清理且有并发测试；threshold >= 4；证据：`go test -race`（CI 镜像支持时）或代码评审。

## Task 3: 设备配对审批状态机（服务端）

- **Status**: `completed`
- **Completion Evidence**:
  - auth 层新增 [pairing.go](file:///d:/github/everything/everything/server/internal/auth/pairing.go)：设备/配对状态机（pending/approved/rejected/revoked/expired）、配对码=大写hex(sha256(pub)[:3])、createOrRenewPairing（旧 pending 置 expired）、LatestPairingForDevice（惰性过期）、ListOpenPairings、Approve/Reject（事务+归属/TTL/自审批校验+材料长度 32/24/非空）、IssueDeviceTokens（approved 复核换发）、ListDevices、RevokeDevice（refresh 即时吊销）、InvalidatePendingOnRecovery。
  - [service.go](file:///d:/github/everything/everything/server/internal/auth/service.go) Login 重写为双态：同公钥 approved 直通/无 approved 设备首登自动批准/其余 pending（typ=access、scp=pending、无 refresh）。
  - API 层新增 [devices_handler.go](file:///d:/github/everything/everything/server/internal/api/devices_handler.go) 与 scope 分层中间件（[middleware.go](file:///d:/github/everything/everything/server/internal/api/middleware.go)：requireScope 越权返回 403 device_pending；requireTypedToken 支持 recovery/mfa/events 与 ?token=）；SSE 广播 device_pairing_requested/resolved、device_list_changed；审计 device_pair_requested/device_approved/device_rejected/device_revoked。
  - TR-3.1~3.5 通过：[pairing_test.go](file:///d:/github/everything/everything/server/internal/api/pairing_test.go)（pending 403、配对码核对、盒材料透传、批准换发 access+refresh、重复登录不新增配对、拒绝/重申/409/404、材料 400、自吊销 400、吊销 refresh 401、SSE 两事件实测）+ [pairing_service_test.go](file:///d:/github/everything/everything/server/internal/auth/pairing_service_test.go)（TTL 惰性 expired、过期审批拒、跨用户 NotFound、自审批拒）；`go test ./...` 全绿、`go vet` 无告警。
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - 登录分支重写（auth service + handler）：
    - 密码验证通过后：若账户无 approved 设备（注册后的首登场景/或 devices 无 approved）→ 直接 approved（兼容历史：首个设备即注册设备）。
    - 否则按 `device_public_key`(32B) 查找该用户下现有 pending 设备：有则复用（刷新 last_seen），无则新建 state=pending 设备；upsert 一条 pending `device_pairings`（TTL 15 分钟）。
    - 返回体分两种形态：approved 返回既有 LoginBundle；pending 返回 `{device_id, state:"pending", pairing:{id, code, expires_at}, access_token}`，access 令牌 scope=pending、不发 refresh token。
  - 新增中间件 `requireApproved`：records/batch、records、events(header 路径)、devices 管理等要求 scope=approved；pending 访问返回 403 `device_pending`。
  - 新增处理器：
    - `GET /auth/pairing/status`（pending）：返回 pairing state；approved 时附带 ephemeral_public_key/nonce/wrapped_master_key（仅一次可重复读直到领取，简单实现：pending 设备 15 分钟内可重复 GET）；过期返回 expired。
    - `GET /auth/devices`（approved）：设备列表（id/name/state/current/last_seen/created_at/public_key 指纹）。
    - `GET /auth/pairings`（approved）：pending 配对列表（id、device_id、name、public_key、配对码、created_at、expires_at）。
    - `POST /auth/pairings/{id}/approve`（approved）：body `{ephemeral_public_key:32B, nonce:24B, wrapped_master_key}`；校验配对归属与 pending；置 pairing approved、设备 approved、写 responded_*；广播 `device_pairing_resolved`；审计 device_approved。
    - `POST /auth/pairings/{id}/reject`：pairing/device 置 rejected（设备行 state=rejected 或删除该行——选择更新 state=rejected 保留审计），广播，审计。
    - `POST /auth/devices/{id}/revoke`：state=revoked 且吊销其全部 refresh_tokens；不能吊销当前设备（400）；审计。
  - 新登录产生 pending 配对时，经 sync.Hub 向该用户在线 approved 连接广播 `device_pairing_requested`。
  - 配对码工具函数：`PairingCode(pubKey []byte) string` = hex 大写(SHA-256(pub)[:3])，6 字符。
- **Acceptance Criteria Addressed**: AC-4、AC-5（服务端部分）、AC-6、AC-10（服务端事件部分）、AC-17（部分）、AC-18
- **Test Requirements**:
  - `rule` TR-3.1: 二设备登录：D2 pending 令牌访问 /records 返回 403；设备列表中 D2 pending；pairing 未批准前 GET status 无 wrapped 字段；证据：e2e 输出。
  - `rule` TR-3.2: 配对码与 expires_at 正确：code==hex(sha256(pub)[:3]) 大写，expires_at-created_at≈900000ms；证据：e2e 断言。
  - `rule` TR-3.3: 重复登录同公钥不新增设备行；不同公钥新增；证据：设备计数断言。
  - `rule` TR-3.4: approve/reject/revoke 后状态、广播事件、refresh token 吊销（revoke 后 /auth/refresh 401）全部符合 FR-9；证据：e2e 输出。
  - `rubric` TR-3.5: 越权走查（他用户 pairing id、pending 令牌调管理端点、过期配对批准）均被拒；scale 1-5；anchors 1=存在越权，3=主要路径拦截但有 1 处遗漏，5=全部拒绝且有测试；threshold >= 4；证据：测试矩阵。

## Task 4: 恢复密钥流程与修改主密码（服务端）

- **Status**: `completed`
- **Completion Evidence**:
  - [recovery.go](file:///d:/github/everything/everything/server/internal/auth/recovery.go)：RecoveryStart（常量时间比对、统一 ErrInvalidCredentials 防枚举、10min recovery 会话）、RecoveryReset（事务更新 8 列+吊销全部 refresh+作废 pending 设备/配对+恢复设备按公钥复用/新建并批准+新令牌对）、ChangePassword（可选轮换恢复码、吊销他设备 refresh 与当前旧 refresh、当前设备换发）。
  - [recovery_handler.go](file:///d:/github/everything/everything/server/internal/api/recovery_handler.go)：三端点接入限流（共享 auth-write 桶）与 recovery scope 中间件；材料长度统一校验；审计 recovery_start(_failed)/password_reset/password_changed，日志不含码/验证器。parameters 增补三恢复字段。
  - TR-4.1~4.4 通过：[recovery_test.go](file:///d:/github/everything/everything/server/internal/api/recovery_test.go)（REK 解 MK 与原字节相等→新密码登录 MK 不变→旧密文可解→旧 refresh 401→旧密码 401→旧恢复码失效新码可用→挂起配对 expired；改密后他设备/旧 refresh 401、恢复码不轮换仍有效；恢复失败第 6 次 429）；[envelope_test.go](file:///d:/github/everything/everything/server/internal/crypto/envelope_test.go) 新增恢复信封往返/AAD 域分离/错误 REK 拒绝与锁定互通向量 `ZmZm...idK`；全量 `go test`/`go vet` 绿。
- **Priority**: high
- **Depends On**: Task 3
- **Description**:
  - `crypto/envelope.go`：新增 `recoveryWrapAAD = "eve:v1:master-key-recovery/v1"` 与 `WrapForRecovery/UnwrapForRecovery`；修正文件头注释 threads=2→1（实际常量为 1）。
  - auth service：
    - `RecoveryStart(username, verifier)`：常量时间比对 recovery_verifier（用户不存在同样返回统一错误，避免枚举）；成功签发 10 分钟 recovery scope JWT，并返回 recovery_kek_salt + wrapped_master_key_recovery；审计 recovery_start / recovery_start_failed；失败计入限流。
    - `RecoveryReset(userID, 新密码四材料, 新恢复四材料)`：事务更新 users 的 auth/kek/recovery 共 8 列；删除/吊销该用户全部 refresh_tokens；将 pending 设备与 pairings 全部置 rejected/expired（approved 设备保留——MK 未变，但其 refresh 已吊销需重新登录）；签发新令牌对（当前恢复设备 approved）；审计 password_reset。
    - `ChangePassword(userID, deviceID, 新 auth/kek 材料)`：更新两盐两列（恢复材料不变更，除非客户端一并提交新恢复材料——支持可选提交并审计）；吊销除当前设备外 refresh tokens；当前令牌对重新签发；审计 password_change。
  - 路由：`POST /auth/recovery/start`（限流、公开）、`POST /auth/recovery/reset`（requireScope=recovery）、`POST /auth/password/change`（approved）。
- **Acceptance Criteria Addressed**: AC-2、AC-3（Go 侧）、AC-17（部分）、AC-18
- **Test Requirements**:
  - `rule` TR-4.1: e2e 恢复闭环：旧 MK 写记录→恢复码 start→REK 解开 MK 得原字节→reset 新密码→新密码登录解 MK 与旧 MK 相等→旧密文可 Open；旧 refresh 401；pending 配对全部失效；证据：e2e 输出。
  - `rule` TR-4.2: 错误恢复码返回统一 401（与用户名不存在响应体一致）；连续失败受 Task 2 限流约束；证据：测试输出。
  - `rule` TR-4.3: recovery 令牌不能访问 /records，access 令牌不能调 reset；证据：403/401 断言。
  - `rule` TR-4.4: crypto 包恢复信封固定向量测试（固定 REK/AAD 的 Seal/Open）通过；证据：`go test ./internal/crypto`。

## Task 5: TOTP 二次验证（服务端）

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - 引入 `github.com/pquerna/otp`（纯 Go）；新增 `server/internal/auth/totp.go`：Setup（生成 secret + otpauth URL，issuer="Everything"，account=username；`key.Image()` 生成 QR PNG 返回 base64 data URI）、Enable(code)（Validate ±1 周期，通过写 totp_confirmed_at）、Disable(code)、Verify(code, secret)。
  - 登录流程改造：密码验证通过后若 totp_confirmed_at 非空 → 不创建设备，返回 200 `{mfa_required:true, mfa_token}`（JWT typ=mfa、scope=mfa、5 分钟，含 uid），响应不含盐/wrapped 材料。
  - `POST /auth/totp/verify`（公开 + mfa scope 令牌 + 限流）：校验 code 通过后执行 Task 3 的设备创建/配对分支，按设备状态返回 LoginBundle 或 pending 体；失败审计 totp_verify_failed。
  - `POST /auth/totp/setup|enable|disable`（approved）；未确认 secret 不影响登录；setup 幂等（重新生成覆盖未确认 secret）。
- **Acceptance Criteria Addressed**: AC-7、AC-17（部分）
- **Test Requirements**:
  - `rule` TR-5.1: setup→enable 错误码 401 不置 confirmed；正确码 200 置 confirmed；未确认时登录不要求 MFA；证据：测试输出。
  - `rule` TR-5.2: 启用后登录：仅密码 → 200 mfa_required 且无 wrapped_master_key；错码 401 + 审计；正确码 → 设备分支结果（approved/pending 形态正确）；disable（需正确码）后恢复单因素；证据：e2e 输出（测试用 totp.GenerateCode(secret, time.Now) 生成码）。
  - `rule` TR-5.3: mfa 令牌过期/伪造/用于其他端点均拒绝；证据：测试输出。
- **Completion Evidence**:
  - 实现文件：`server/internal/auth/totp.go`（SetupTOTP/EnableTOTP/DisableTOTP/VerifyTOTPCode/IsTOTPEnabled/TOTPSecretState；RFC6238 30s/6 位/SHA1、±1 周期容差；200×200 QR PNG base64 data URI；setup 只写 totp_secret 不确认）。
  - 登录三态：`auth/service.go` Login 增加 LoginMFARequired（密码正确但已启用 TOTP 时签发 5min、typ/scope=mfa 的短期会话，deviceID 为空，不创建设备）；设备逻辑抽 finishDeviceLogin；新增 VerifyMFAFinishLogin（按 userID 重查材料后接续 approved/pending 设备分支）；doLogin 增加 mfa_required 分支（审计 login_mfa_required，响应无任何密钥材料）。
  - 澄清：totp_secret 列 0001 已预留（BLOB）、0002 已加 totp_confirmed_at，无需 0003 迁移；曾误建 0003 后删除，migrate_test 版本断言保持 2。
  - HTTP：`server/internal/api/totp_handler.go`；路由 GET `/auth/totp`、POST setup/enable/disable（approved 组）、POST `/auth/totp/verify`（公开 + requireTypedToken(mfa,mfa) + guardAuth 限流，错码 authFailed 计桶）；错误映射 invalid_totp 401 / totp_not_configured 409 / totp_already_enabled 409；审计 totp_setup/enabled/disabled/enable_failed/disable_failed/totp_verify_failed/device_pair_requested(mfa=true)。
  - TR-5.1/5.2/5.3 由 `server/internal/api/totp_test.go` 三个 e2e 覆盖：TestTOTPSetupEnableDisable（错码 401 不确认、otpauth 含 Everything:nora、QR data URI、重复 setup 409、disable 需正确码）、TestTOTPLoginMFA（mfa_required 无 bundle/pending/wrapped_master_key 泄漏、错码 401×5 后第 6 次 429、mfa 令牌访问 /records 401、伪造令牌 401、access 令牌调 verify 401、审计 totp_verify_failed≥5 行且 detail 无验证码/verifier 明文）、TestTOTPVerifyDeviceBranch（已批准设备 verify→approved、新设备 verify→pending 且审批端列表可见）。
  - 命令输出：`go test ./internal/api/ -run TOTP -v` → 3/3 PASS；`go vet ./...` 无告警；`go test ./... -count=1` → api/auth/crypto/db 全部 ok。

## Task 6: events 签名令牌与服务端 e2e 集成扫尾

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 5
- **Description**:
  - `POST /auth/events-token`（approved）：签发 5 分钟、typ=events 的 JWT（jti 随机）。
  - `/events` 鉴权双入口：`?token=` 路径只接受 typ=events；Authorization 头只接受 scope=approved 的 access；events 令牌在任何其他端点一律 401。
  - 补充/整理端到端测试为按场景的完整链路：注册（含恢复材料/公钥）→配对批准（用 `golang.org/x/crypto/nacl/box` 模拟 D1/D2，断言开箱 MK 相等、服务端只透传）→恢复→改密→TOTP→events token 权限矩阵→限流→审计覆盖与日志敏感值检查（测试中接管 audit 写入与 slog 输出做 grep）。
  - `go vet ./...` 与四目标 CGO=0 交叉编译本地/CI 验证；尝试本地补装 Go 工具链，装不上则在证据中记录 CI 链接。
- **Acceptance Criteria Addressed**: AC-8、AC-5（Go 互通侧）、AC-16、AC-17、AC-19（服务端）
- **Test Requirements**:
  - `rule` TR-6.1: events token 四场景：可连 /events 并收到一条广播；访问 /records 401；过期（等待至过期，可用 300s 过长——测试服务支持构造自定义 TTL 的签发函数，内部包级可配置，不经过 HTTP 暴露后门）401；伪造 401；证据：测试输出。
  - `rule` TR-6.2: nacl/box 与封装约定互通：e2e 中 D2 box.Open 得到 MK 字节与 D1 相同；nonce 24B、ephemeral_pub 32B 校验存在；证据：测试输出。
  - `rule` TR-6.3: `go test ./... -count=1`、`go vet ./...`、`GOOS=linux/darwin/windows GOARCH=amd64/arm64 CGO_ENABLED=0 go build ./cmd/eve` 全部退出码 0；证据：命令输出或 CI 运行链接。
  - `rule` TR-6.4: FR-25 全部审计事件在测试链路后存在；audit detail 与日志缓冲中不含 MK/恢复码/TOTP code/密码子串；证据：测试断言输出。
- **Completion Evidence**:
  - events 令牌：`auth/service.go` 新增 IssueEventsToken / IssueEventsTokenWithTTL（后者仅供测试构造过期令牌，内部方法不经 HTTP 暴露）；`events_handler.go` 新增 POST `/auth/events-token`（approved），响应 `{events_token, expires_in:300}`。
  - SSE 双入口：`middleware.go` 新增 requireEventsAccess——`?token=` 只认 typ/scope=events，Authorization 只认 scope=approved access，pending→403；`server.go` 将 `/events` 移出 60s 超时组（SSE 长连不再被 60s 切断），events-token 挂 approved 组。
  - 审计修正：changePassword 事件名 `password_changed`→`password_change` 对齐 FR-25 目录。
  - TR-6.1 `events_token_test.go` TestEventsTokenMatrix：?token= 建连并收到 device_pairing_requested；events 令牌经 Authorization/query 访问 /records 均 401；approved access 入口可建连；过期令牌（IssueEventsTokenWithTTL(-1min)）401；伪造 401；无凭证 401；pending access 403。
  - TR-6.2 `pairing_box_test.go` TestPairingBoxInterop：用 `golang.org/x/crypto/nacl/box` 模拟真实两端——D2 持静态 X25519 密钥对，D1 用一次性临时密钥对 + 24B nonce box.Seal(MK, D2_pub, eph_sec)；D2 box.Open 得 MK 与注册 MK 逐字节相等；断言 32B/24B 长度与服务端原样透传；篡改密文/错误发送方公钥均开箱失败（真实认证加密证据）。
  - TR-6.4 `audit_coverage_test.go` TestAuditCoverageAndNoSecrets：单链路触发 FR-25 全部 14 类事件（register/login/login_failed/recovery_start/recovery_start_failed/password_reset/password_change/totp_enabled/totp_disabled/totp_verify_failed/device_pair_requested/device_approved/device_rejected/device_revoked），SQL 逐事件 COUNT≥1；GROUP_CONCAT(detail) grep 三个主密码、两个恢复码、错 TOTP 码、MK 的 base64/hex、"verifier" 字样均无命中；slog 重定向到 bytes.Buffer 同样 grep 无命中。
  - TR-6.3 命令输出：`go vet ./...` 无告警；`go test ./... -count=1` 全 ok（api 22.9s/auth/crypto/db）；`CGO_ENABLED=0 go build ./cmd/eve` 四目标 linux/amd64、linux/arm64、darwin/arm64、windows/amd64 全部 exit=0；无新增依赖（nacl/box 本就在 golang.org/x/crypto 内）。

## Task 7: Web 端密码学与 API 协议底座

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - `web/src/crypto/envelope.ts` 扩展（或同目录新增）：
    - Crockford Base32 编码（20 字节→4 组×8 字符分组展示）与规范化解码（小写、连字符、I/L/O 映射；固定 20 字节）；恢复包裹 AAD 与 wrap/unwrap。
    - X25519：设备密钥对生成（`crypto_box_keypair`）、seed 持久化；`boxSeal(MK, recipientPk)` 内部生成一次性临时密钥对+随机 24B nonce；`boxOpen(sealed, senderPk, ownSk)`；私钥存 localStorage 独立键。
    - RFC 6238 TOTP（WebCrypto HMAC-SHA1，支持 digits/period）；otpauth URI 解析；内置 RFC 6238 附录 B 向量自测函数（开发模式 console 输出一次）。
  - `api/client.ts`：补全全部新端点类型（register 新字段、recovery/*、password/change、totp/*、devices、pairings、pairing/status、events-token）；403 device_pending / mfa_required 等结构化错误保留 code。
  - 注册流程（stores/auth.ts）：生成恢复码与双盐材料、设备 X25519 公钥；新增 recovery/changePassword/设备密钥加载等 store action；MK 与设备私钥只存内存/localStorage 私钥键（注释说明风险边界）。
- **Acceptance Criteria Addressed**: AC-3、AC-13、AC-5（Web 侧能力）
- **Test Requirements**:
  - `rule` TR-7.1: Crockford 向量：固定 20 字节输入编码字符串与 docs 向量一致；含小写/连字符输入规范化后解码回原 20 字节；证据：源码中向量常量 + 浏览器 console 自测输出/评审。
  - `rule` TR-7.2: TOTP RFC 向量（时间 59/1111111109/1111111111，8 位）断言通过；证据：console 自测截图/代码评审。
  - `rule` TR-7.3: `npm run build`（vue-tsc 严格）退出码 0；证据：命令输出。
  - `rubric` TR-7.4: 与 Go 侧互通一致性（AAD 字符串、nonce 长度、box 参数顺序）逐项对照 docs/crypto.md；scale 1-5；anchors 1=任一不一致，3=主流程一致缺注释，5=全部一致且注释/向量齐备；threshold >= 4；证据：代码对照评审。
- **Completion Evidence**:
  - 新增 `web/src/crypto/crockford.ts`：Crockford Base32（字母表 0123456789ABCDEFGHJKMNPQRSTVWXYZ）MSB 优先编解码；20B↔32 字符；normalizeRecoveryCode（去空格/连字符、小写转大写、I/L→1、O→0、非法字符与长度报错）；formatRecoveryCode 4 组×8。
  - 新增 `web/src/crypto/device-box.ts`：X25519 身份（crypto_box_seed_keypair 从 32B seed 恒等导出，seed 存 localStorage 独立键 eve.deviceSeed，含风险边界注释）；boxSeal（一次性临时密钥对+24B nonce，参数序 recipientPk/ephSk 与 Go nacl/box 一致）；boxOpen（senderPk/ownSk，认证失败抛异常）。
  - 新增 `web/src/crypto/totp.ts`：WebCrypto HMAC-SHA1 RFC4226/6238（digits/period 可配、计数器大端 64 位、动态截断）、标准 RFC4648 Base32 解码、otpauth URI 解析、totpSelfTest（RFC 附录 B 8 位向量 t=59/1111111109/1111111111 → 94287082/07081804/14050471，加 6 位 t=59→287082）。
  - 新增 `web/src/crypto/selftest.ts`：Crockford 向量（全 0→'0'×32、全 FF→'Z'×32、Go 锁定恢复码 `0123456789ABCDEFGHJKMNPQRSTVWXYZ` 解码 20B 且重编码恒等、小写连字符归一化往返）+ TOTP 向量；main.ts 在 import.meta.env.DEV 启动执行并 console 输出（TR-7.1/7.2 证据入口）。
  - `envelope.ts` 增 wrapForRecovery/unwrapForRecovery，AAD=`eve:v1:master-key-recovery/v1` 与 Go 完全一致。
  - `client.ts` 全量重写：补 recovery/*、password/change、totp/*、devices、pairings、pairing/status（显式 pendingToken）、events-token 与恢复三字段 LoginParameters；request 的 auth 支持字符串短期令牌（recovery/mfa/pending）；ApiError 保留结构化 code；LoginResponse 三态联合类型。
  - `stores/auth.ts` 重写：注册生成 20B 恢复码（Crockford）+双盐四元组+设备 X25519 公钥；login 三态（approved 解 MK / pending 存会话 / mfa_required 内存暂存主密码 5min 会话用后即焚）；finishMfa（错码保留会话可重试）、finishPairing（boxOpen 审批盒得 MK）、approvePairing（boxSeal MK）、recoveryStart（REK 离线解 MK）、recoveryReset（强制轮换新恢复码）、changePassword（可选轮换）；MK 仅内存、设备 seed 独立 localStorage 键，注释说明风险边界。
  - TR-7.3：`npm run build`（vue-tsc --noEmit 严格 + vite）退出码 0，产物输出 ../server/web/dist（1185KB 含 libsodium WASM，仅有 chunk 体积既有警告）。TR-7.4：AAD 字符串/nonce 24B/box 参数顺序均与 Go 端及 T6 nacl/box 互操作测试逐项对齐。

## Task 8: Web 注册恢复码展示、恢复向导与修改密码

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 7
- **Description**:
  - WelcomeView：注册成功后进入"恢复码备份"全屏步骤——大字分组展示恢复码、复制/打印按钮、强制勾选"我已离线保存"才可进入；未确认不可跳过（重新注册需重来）。
  - WelcomeView 增加"忘记主密码/新设备恢复"入口：用户名→恢复码→（start 成功后客户端解 MK）→设置新主密码→reset→全屏展示**新恢复码**并强制确认→进入资料库。
  - 新建"安全设置"视图：修改主密码（需当前处于解锁态；提交新包裹材料；如服务端返回新恢复材料则同屏展示一次），入口放在后续布局的用户菜单。
  - 错误提示复用 Naive UI message；恢复码输入框支持分组粘贴（自动清洗）。
- **Acceptance Criteria Addressed**: AC-1、AC-2、AC-3（UI 闭环）
- **Test Requirements**:
  - `rule` TR-8.1: 手测闭环：注册→保存恢复码→退出→恢复向导→新密码登录→旧加密笔记可解密；恢复后旧 refresh 失效需重登；证据：手测记录（步骤+截图要点）。
  - `rule` TR-8.2: 未勾选确认不能进入资料库；恢复码错误在 start 步骤即提示且不进入下一步；证据：手测。
  - `rubric` TR-8.3: 关键文案风险提示充分（"恢复码丢失=数据永久丢失"、重置后旧码作废）；scale 1-5；anchors 1=无风险提示，3=有提示但不显著，5=节点完备且不可误操作；threshold >= 4；证据：UI 评审。
- **Completion Evidence**:
  - TR-8.1 手测闭环通过（2026-09-15，Chrome + 本地服务端）：注册新账户 → 全屏恢复码备份（4 组大字 + 复制）→ 退出 → "忘记主密码"恢复向导（用户名→恢复码→新密码×2→展示新恢复码）→ 新密码登录 → 旧加密笔记解密正确；恢复后旧浏览器会话刷新 401 强制重登（refresh 已吊销）。期间修复 Bug2（服务端 expires_at 毫秒/秒单位不一致）并复测通过。
  - TR-8.2：未勾选"我已离线保存"确认框时进入按钮禁用；错误恢复码在 recovery/start 即返回统一 401（与用户名不存在响应一致）并停留当前步骤，手测确认。
  - TR-8.3 评审 4 分：备份步骤含"恢复码丢失=数据永久丢失"显著警示，重置成功页提示旧码立即作废，强制勾选防误跳过。

## Task 9: Web 设备配对、TOTP 管理与 EventSource 实时通道

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 8
- **Description**:
  - 登录态分支：login 返回 mfa_required → 第二步入码视图（含重发/返回）；返回 pending → 全屏"等待设备审批"：展示设备名、6 位配对码（等宽大字）、15 分钟倒计时、3 秒轮询状态；approved 后取 box 材料开箱 MK → 解锁进入；提供"改用恢复码"出口。
  - 设备管理视图：设备列表（当前设备标识、state、last_seen）、吊销操作（二次确认）；pending 配对卡片实时出现（SSE）→ 展示设备名/配对码/公钥指纹→批准（boxSeal MK 提交）或拒绝；操作后 toast 与审计一致。
  - TOTP 卡片：setup 返回二维码（img data URI）+ 手动输入 secret；启用输入 6 位码；禁用需码；启用状态徽标。
  - 实时通道：新增 `stores/events.ts`（或并入 auth/app store）：通过 `/auth/events-token` 建 `EventSource('/api/v1/events?token=...')`；401/error 时重新取令牌重连（退避 1/2/5 秒封顶）；records_changed → 触发记录同步；device_pairing_* → 刷新设备 store；EventSource 不可用时 30 秒轮询兜底；移除 VaultView 的 10 秒轮询。
- **Acceptance Criteria Addressed**: AC-4、AC-5（Web 端）、AC-6、AC-7（UI）、AC-9、AC-10（实时侧）
- **Test Requirements**:
  - `rule` TR-9.1: 双浏览器手测：D2 登录显示 pending 与配对码，D1 收到事件出现审批卡，批准后 D2 10 秒内自动解锁且 records 可读；拒绝时 D2 显示被拒；证据：手测记录。
  - `rule` TR-9.2: MFA 手测：启用后登录需两步，错码停留并提示；禁用后单步；证据：手测。
  - `rule` TR-9.3: EventSource 建立后 Network 面板无 10 秒轮询请求；其他端写入后 2 秒内列表更新；断网 30 秒恢复后自动重连成功；证据：浏览器手测记录。
  - `rubric` TR-9.4: 等待/审批交互可用性（倒计时、错误态、重复登录复用、恢复出口）；scale 1-5；anchors 1=流程走不通，3=可用但缺状态反馈，5=全状态闭环；threshold >= 4；证据：手测+评审。
- **Completion Evidence**:
  - TR-9.1 双浏览器手测通过（Chrome D1 + Edge D2，2026-09-15）：D2 登录进入 pending 全屏等待页（设备名 + 6 位大写配对码 + 15 分钟倒计时 + 3 秒轮询）；D1 SSE 实时弹出审批卡（配对码/公钥指纹一致），批准后 D2 于 10 秒内 boxOpen 得 MK 自动解锁且 records 可读；另测拒绝路径 D2 显示被拒可返回。期间修复 Bug1（401 单飞刷新误拦截短期令牌）、Bug5（MfaPanel 卸载后 emit 丢失→改先 emit 后 consumeMfa）并复测。
  - TR-9.2 MFA 手测通过：setup 二维码（otpauth data URI）+ 手动 secret → 正确码启用；重新登录进入第二步入码视图，错码停留并提示、正确码放行；禁用（需码）后恢复单因素登录。
  - TR-9.3：EventSource 建连后 Network 面板无 10 秒轮询（VaultView 旧轮询已移除）；另一浏览器写入记录 2 秒内本端列表更新；断网 30 秒恢复后按 1/2/5 秒退避自动重取 events-token 重连成功。
  - TR-9.4 评审 4 分：倒计时/过期/被拒/重复登录复用同公钥/"改用恢复码"出口全状态闭环。

## Task 10: Web 密码库与证件业务模块

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 9
- **Description**:
  - 新增 `stores/vault.ts`：拉取全量/增量 records→MK 解密→按 module/type 建立内存索引；暴露搜索、CRUD（本地构造 JSON→sealRecord(version 递增)→push→SSE/轮询触发刷新）；删除墓碑；兼容旧 `note` 模块（读取时并入安全笔记，新建统一为 pass/note）。
  - 应用框架重构：左侧导航（登录项/安全笔记/银行卡/证件/到期提醒/设备管理/安全设置）+ 顶栏用户菜单（锁定/退出/同步状态/上次同步）。
  - 各模块列表与表单（Naive UI）：
    - login：标题、用户名、密码（遮蔽/复制）、URI 列表、备注、TOTP（otpauth URI 或手填 secret/issuer），详情展示 6/8 位码与环形倒计时；编辑器内嵌密码生成器（长度 8–64、四字符集开关、排除歧义）。
    - note：标题+正文（由现有加密笔记演进）。
    - card：标题/持卡人/卡号/有效期 MM-YY/CVC/备注，列表只显示尾号。
    - identity：类型（身份证/护照/驾照/通用）、姓名、证号、签发机构、签发日、到期日、备注；日期选择器。
  - 顶部搜索框：客户端内存过滤（标题/用户名/URI/卡号尾号/备注/证号）。
  - 到期提醒视图：聚合 expires_at ≤90 天及已过期，升序；列表项徽标颜色分级（红/橙/黄，边界含当天）。
  - 剪贴板：复制敏感字段，30 秒后自动清空（navigator.clipboard 支持时；不支持则提示手动清除）。
- **Acceptance Criteria Addressed**: AC-11、AC-12、AC-13（UI 集成）、AC-14、NFR-5
- **Test Requirements**:
  - `rule` TR-10.1: 每类型各建 2 条→编辑 1 次（version=2）→删除 1 条→刷新重登，剩余记录解密展示正确；服务端 data/eve.db 用 sqlite 字符串 grep 不到输入的标题/密码/卡号/证号明文；证据：手测记录 + grep 输出。
  - `rule` TR-10.2: 生成器连续 100 次长度/字符集合规；使用 crypto.getRandomValues（源码可查）；证据：源码评审 + 页面 console 抽样。
  - `rule` TR-10.3: TOTP 详情码与同一 secret 的标准认证器（或 Go 测试程序）同刻一致，倒计时随 period 归零刷新；证据：手测对比。
  - `rule` TR-10.4: 到期边界构造（过期前 0/30/90 天与之外）颜色与聚合排序正确；证据：手测记录。
  - `rubric` TR-10.5: 信息架构与易用性（导航、空状态、表单校验、500 条列表搜索流畅度）；scale 1-5；anchors 1=混乱不可用，3=功能齐但交互粗糙，5=结构清晰反馈完备；threshold >= 4；证据：评审与手测。
  - `rule` TR-10.6: `npm run build` 退出码 0 且产物可被 go:embed 托管（构建产物输出到 server/web/dist）；证据：构建输出。
- **Completion Evidence**:
  - TR-10.1 手测通过（2026-09-15）：login/note/card/identity 各建 2 条 → 编辑 1 条（version=2）→ 删除 1 条（墓碑）→ 刷新重登后剩余记录解密展示正确（含历史 module='note' 笔记兼容并入安全笔记）；`Select-String` 扫描 server/data/eve.db 二进制确认标题/密码/卡号/证号明文均不可见（仅存密文与 module/type 元数据）。
  - TR-10.2：生成器源码使用 `crypto.getRandomValues`（web/src/crypto/generator.ts），console 抽样 100 次长度 8–64 与所选字符集全部合规，排除歧义开关生效。
  - TR-10.3：条目详情 TOTP 码与同 secret 的 Go RFC6238 测试程序同刻一致；环形倒计时随 period 归零自动刷新码值。
  - TR-10.4 到期边界手测：过期（days<0）红、0–30 天（含到期当天）橙、31–90 天黄、>90 天不聚合；≤90 天及已过期按到期日升序聚合于"到期提醒"视图。
  - TR-10.5 评审 4 分：左侧导航七入口 + 顶栏用户菜单（锁定/退出/同步态/上次同步）、空状态与表单校验齐备，顶部搜索客户端内存过滤流畅。
  - TR-10.6：`npm run build`（vue-tsc 严格）退出码 0，产物输出 server/web/dist 由 go:embed 托管实测可访问。期间修复 Bug3/4/6/7（卡片有效期序列化、搜索索引、证件日期、SSE 刷新竞态）并复测。

## Task 11: Android 密码学/协议扩展与认证流程改造

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - `CryptoEnvelope.kt`：Crockford Base32 编码/规范化（向量与 docs 一致）、恢复包裹 AAD、X25519 密钥对生成（lazysodium `cryptoBoxKeypair`/`cryptoBoxEasy`/`cryptoBoxOpenEasy`）；设备私钥存 EncryptedSharedPreferences。
  - DTO/API/AuthManager：注册携带恢复材料与设备公钥；登录返回密封类结果（ApprovedBundle/PendingBundle/MfaRequired）；`recover/reset/passwordChange/totp(verify)/pairing(status)/pairings(approve|reject)/devices/events-token` 接口与 DTO；AuthManager 增加恢复与改密方法、pending 开箱（boxOpen→masterKey→isUnlocked）。
  - UI：WelcomeScreen 增加恢复码注册后展示/确认步骤（ScrollView 大字分组+勾选）、"恢复设备"入口向导；登录后按结果分支到 MFA 输入屏或配对等待屏。
- **Acceptance Criteria Addressed**: AC-3、AC-7（Android 登录侧）、AC-15（发起方）
- **Test Requirements**:
  - `rule` TR-11.1: Crockford/恢复 AAD/box 与文档向量一致（androidTest 单测或本地 JVM 单测；lazysodium 依赖 native，优先 instrumented 测试；若环境受限以代码评审 + assembleDebug 为证据并在 review 注明）；证据：测试或评审。
  - `rule` TR-11.2: 注册→恢复码确认→进入；退出后恢复向导→新密码→历史 note 可解密；MFA 登录二步可用；证据：手测/评审。
  - `rule` TR-11.3: `./gradlew :app:assembleDebug` 退出码 0 产出 APK；证据：构建输出。
- **Completion Evidence**:
  - TR-11.1：[CrockfordTest.kt](file:///d:/github/everything/everything/android/app/src/test/java/com/everything/eve/crypto/CrockfordTest.kt) JVM 单测 9/9 通过（`./gradlew :app:testDebugUnitTest`）：全 0/全 FF 向量、往返、归一化（去连字符/大写/I·L→1/O→0）、U 非法抛异常、长度校验、分组格式化。lazysodium 5.1.0 无 Lazy AEAD 字符串重载，CryptoEnvelope 全部改用 Native API（AEAD 9 参 / PwHash 8 参 NativeLong / Box 6 参），真实签名经 javap 反查 aar 内 classes.jar 确认；Argon2id 参数（t=3/m=64MiB/p=1/32B）、恢复 AAD（eve:v1:master-key-recovery/v1）、box 布局（eph_pk32||nonce24||box(MK)）与 docs/crypto.md 向量逐项对照一致（评审）。
  - TR-11.2 环境限制说明：本机无 Android 设备/模拟器（`adb devices` 为空），注册→恢复码→恢复向导→MFA 链路未真机手测；以代码评审 + assembleDebug + 三端向量一致性为替代证据（TR-11.1 条款允许），待有设备环境补手测。评审要点：WelcomeScreen 五态状态机（凭据/注册备份/MFA/配对等待/恢复向导）与 AuthManager 三态分流、pendingUsername 暂存、RecoverySessionHolder 短期材料不落盘均符合规格。
  - TR-11.3：`./gradlew :app:assembleDebug` 退出码 0，产出 app-debug.apk（16.7MB，2026-09-15 21:43）。

## Task 12: Android 审批端、Room v2 迁移与模块兼容

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 11
- **Description**:
  - 审批 UI：新增"设备与审批"屏（入口放 Vault 顶栏溢出菜单）：本账户设备列表+当前标识+吊销；pending 卡片（SSE 不可用则进入页面前台 5 秒轮询）：展示设备名/配对码，批准（boxSeal MK）或拒绝；配对等待屏（Task 11）3 秒轮询 status，approved 后开箱解锁。
  - Room：version=2；显式 `Migration(1,2)` 建 `sync_state` 表；Dao 增加读写；移除 `fallbackToDestructiveMigration`；同步成功写 `last_successful_sync`；Vault 顶栏展示"上次同步 HH:mm"。
  - 模块兼容：DAO 查询由 module='note' 放宽为 module IN ('note','pass')；Repository 解密 note 与 pass/note 均取 title/body；login/card/identity 记录不展示但不影响同步；拉取循环对未知 module 无特殊处理（天然兼容）。
- **Acceptance Criteria Addressed**: AC-10、AC-15
- **Test Requirements**:
  - `rule` TR-12.1: androidTest Migration 测试：v1 插入 records→迁移到 v2→行数/内容不变且 sync_state 可写可读；证据：测试输出（无设备时需记录环境限制，由评审决定是否 blocked）。
  - `rule` TR-12.2: 端到端手测/代码走查：Android 审批 Web 新设备（Web 端开箱得 MK）或反向（其一即可，另一方向以向量一致性保证）；pass/identity 密文同步不中断；笔记兼容显示；证据：手测记录或评审。
  - `rule` TR-12.3: assembleDebug 退出码 0；代码中 grep 不到 fallbackToDestructiveMigration；证据：构建输出 + grep。
- **Completion Evidence**:
  - TR-12.1：[MigrationTest.kt](file:///d:/github/everything/everything/android/app/src/androidTest/java/com/everything/eve/data/MigrationTest.kt) 用 MigrationTestHelper 实现（v1 建 records 插 2 行 → 迁移 v2 → 行数/内容不变且 sync_state 可写读），`./gradlew :app:assembleDebugAndroidTest` 编译通过；**环境限制**：本机无设备/模拟器无法执行 connectedDebugAndroidTest，待 CI/有设备环境运行，评审时请据此判定。
  - TR-12.2 代码走查通过：审批端 DevicesScreen/DevicesViewModel 5 秒前台轮询 pending 配对，approvePairing 用内存 MK 经 boxSeal（一次性临时密钥对 + 24B nonce）提交，字节布局与 Web boxOpen 及 T6 nacl/box 互通测试一致；DAO `module='note' OR (module='pass' AND type='note')` 双模块兼容查询，decryptNote 按记录自身 module 算 AAD，未知 module（login/card/identity）同步落库不展示、天然兼容不中断。
  - TR-12.3：assembleDebug 退出码 0；全 android/ 目录 grep `fallbackToDestructiveMigration` 无任何匹配（EveDatabase 使用 `.addMigrations(MIGRATION_1_2)`）。

## Task 13: 文档、计划同步与全量门禁

- **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 10、Task 12
- **Description**:
  - 更新 docs/api.md：全部新端点（恢复、改密、配对、设备、TOTP、events-token）、登录三态响应、403/429 错误码。
  - 更新 docs/crypto.md：恢复信封 AAD、Crockford 规则与固定向量、crypto_box 配对封装约定（临时密钥/nonce/字节布局）。
  - 新增 docs/module-schemas.md：pass/identity 各 type 的 JSON 字段约定与版本/墓碑规则。
  - 更新 docs/architecture.md（pairings 表、scope、限流）、docs/development.md（设备自动批准→配对审批的变化）、README（恢复码提示）。
  - 更新 .trae/documents/everything_plan.md：阶段 1 四项遗留清零（恢复密钥/设备审批/SSE 通道/Room/TOTP），阶段 2 勾选密码库+证件；时间 2026-09-15。
  - 全量门禁：web `npm run build`、server `go test ./...`/`go vet`/四目标编译（本地或 CI）、android assembleDebug；修复门禁中发现的所有编译/类型错误。
- **Acceptance Criteria Addressed**: AC-19、AC-20
- **Test Requirements**:
  - `rule` TR-13.1: 四类构建命令全部退出码 0（Go 侧若无本地工具链则引用 CI 成功记录，缺一不可判定 blocked 而非 pass）；证据：输出/CI 链接汇总。
  - `rule` TR-13.2: 文档与实现一致性抽查（每个新端点均在 api.md；字段名/字节长度与代码一致；计划文档勾选与实际一致）；证据：评审检查清单。
- **Completion Evidence**:
  - 文档更新（2026-09-15）：[api.md](file:///d:/github/everything/everything/docs/api.md) 覆盖全部新端点（register/login 三态/parameters/recovery start+reset/totp verify+管理/password change/devices/pairings approve+reject/pairing status/events-token/SSE/records）+ 令牌 typ/scope 体系 + 403/429 错误码 + 限流 + 配置；[crypto.md](file:///d:/github/everything/everything/docs/crypto.md) 补恢复信封、Crockford 规则与向量、crypto_box 配对约定（向量经 Python 复核）；新增 [module-schemas.md](file:///d:/github/everything/everything/docs/module-schemas.md)（pass login/note/card + identity 四类型 JSON 约定、墓碑、AAD 不可变、UI 支持矩阵）；[architecture.md](file:///d:/github/everything/everything/docs/architecture.md) 补 0002 迁移/device_pairings/scope 鉴权链/限流/SSE Hub；[development.md](file:///d:/github/everything/everything/docs/development.md) 补配置表与当前状态（旧"已知限制"四项已清零）；[README.md](file:///d:/github/everything/everything/README.md) 进度/文档列表/恢复码提示；[android.md](file:///d:/github/everything/everything/docs/android.md) 能力清单与测试命令；[everything_plan.md](file:///d:/github/everything/everything/.trae/documents/everything_plan.md) 勾选阶段 1 全部完成 + 阶段 2 密码库核心。修复 api.md 示例漂移（module "password"→"pass"）。
  - TR-13.1 全量门禁复跑（2026-09-15 本地）：`go test ./... -count=1` 全 ok（api 20.0s/auth 2.1s/crypto 0.9s/db 0.3s）EXIT=0；`go vet ./...` 无告警 EXIT=0；`CGO_ENABLED=0 go build ./cmd/eve` 四目标 linux/amd64、linux/arm64、darwin/arm64、windows/amd64 全部 EXIT=0；web `npm run build`（vue-tsc 严格 + vite）EXIT=0 产物入 server/web/dist；android `./gradlew :app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL EXIT=0。
  - TR-13.2：一致性抽查随文档更新完成——api.md 端点与 server/internal/api 路由逐一对照、crypto.md 字节长度（salt16/nonce24/key32/eph_pk32）与三端代码一致、module-schemas.md 字段与 web/src/types/vault.ts 一致、计划勾选与实际交付一致；遗留项（Android 密码库 UI、附件）在计划中明确标注未做。

## 独立评审发现物（Follow-up，不阻塞验收）

> 来源：[review.md](file:///d:/github/everything/everything/.trae/specs/stage1-completion-stage2-vault/review.md)（2026-09-15，结论 **pass 附条件**：AC-1~20 全过，0 blocker / 0 major / 3 minor / 4 nit）。

- [x] FU-1（minor-1，已修 2026-09-16）：增量同步游标依赖客户端本地时钟，时钟显著偏差时可能漏拉他端记录。已落地"服务端权威 `updated_at`"：server `ApplyBatch` 统一以服务端 `now` 覆盖全部 `updated_at`（客户端上传值仅占位），`BatchResult` 新增 `server_time` 返回；Web vault.ts save/remove 以 `res.server_time` 校准本地记录 `updatedAt` 与 `since` 游标（替代 `Date.now()`）；Android `BatchResult.serverTime` + `markClean(ids, serverTime)` 清脏同时覆盖本地 `updatedAt`，拉取游标 `maxUpdatedAt` 只反映服务端时钟；api.md `/records/batch` 明示时钟假设（`updated_at` 服务端权威、`created_at` 仅展示）。证据：`go test ./...` 全 ok、web `npm run build` EXIT=0、android assembleDebug+testDebugUnitTest+assembleDebugAndroidTest BUILD SUCCESSFUL。
- [x] FU-2（minor-2，已修 2026-09-16）：恢复信封锁定向量已下沉三端：Web `selftest.ts` 新增 `recoveryEnvelopeSelfTest()`（固定盐 0x44×16/nonce 0x66×24/MK 0x5A×32，AAD 独立构造，手工调 sodium 原语比对锁定 base64，再走生产 `unwrapForRecovery` 往返），node 实跑输出与 Go 锁定值逐字节一致（VECTOR OK）；Android 新增 `RecoveryEnvelopeVectorTest`（androidTest，lazysodium 需设备原生库，本机仅编译——运行关闭条件并入 FU-7）；crypto.md §6.2 修订歧义表述（tag 与密文一体、恢复码归一化形式、三端断言位置清单）。
- [x] FU-3（minor-3，已修 2026-09-16）：recovery JWT 单次化——0003 迁移 `users.recovery_reset_at`；`RecoveryReset` 事务内校验 `tokenIAT <= recovery_reset_at` 即 401（零值/缺失 iat 天然被拒，宁可误杀不放行），reset 成功与恢复码轮换（ChangePassword rec 分支）均写入新时间戳，旧 recovery 令牌全部作废；recovery_test.go 新增"同一令牌 TTL 内重放 reset 必须 401"断言。证据：`go test ./internal/db/ ./internal/api/` 全 ok（含 TestUpgradeFrom0001 0001→0003 升级链）。
- [ ] FU-4（nit-2）：web/src/crypto/generator.ts 洗牌 `jBuf[0] % (i+1)` 轻微模偏差，建议改拒绝采样（字符集抽样本身已无偏）。
- [ ] FU-5（nit-3）：Android ServiceLocator 401 拦截器未排除显式短期令牌请求（recovery/reset、totp/verify、pairing/status），会多一次无谓刷新（等价失败、无安全后果），建议对齐 Web client.ts 的 canRefresh 判定。
- [ ] FU-6（nit-4）：pairingStatus approved 态每次轮询冗余落一行 refresh_tokens（双端客户端均取一次即停，量极小）；可选加幂等复用。
- [ ] FU-7（环境受限项关闭条件）：有 Android 设备/模拟器时执行 `./gradlew :app:connectedDebugAndroidTest`（Migration 测试全绿）+ 一次真机冒烟（注册/登录三态/审批下发/恢复向导/笔记同步），并在本文件补记运行证据；同时验证低端机 Argon2id（64MiB）派生耗时。
- [x] FU-8（nit-1，已修）：tasks.md TR-10.2 证据路径漂移 utils/password.ts → crypto/generator.ts，已回改。
