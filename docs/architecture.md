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
│  auth(JWT) · vault(records 信封) · sync(SSE Hub)    │
│  modules(薄) · location(规划) · agent(规划)         │
│  SQLite(WAL) · 附件块存储(规划) · 备份(规划)          │
│  go:embed 托管网页构建产物                            │
└─────────────────────────────────────────────────────┘
```

**信任模型**：服务端是"诚实但好奇"的不可信存储——代码开源自托管，但即使拿到数据库文件，
没有客户端主密码也无法解密任何内容。所有搜索、过滤、AI 处理都在解锁会话中进行（见加密规范）。

## 核心组件

| 目录 | 职责 |
|---|---|
| `server/cmd/eve` | 入口：配置、日志、优雅退出 |
| `server/internal/config` | yaml + 环境变量配置，注册策略 first/open/closed |
| `server/internal/crypto` | Argon2id / XChaCha20-Poly1305 信封（与客户端字节级一致） |
| `server/internal/auth` | 注册登录、JWT、刷新令牌、设备表、登录验证器校验 |
| `server/internal/vault` | 通用加密记录信封存取，版本号幂等合并 |
| `server/internal/sync` | 用户级进程内事件总线（SSE），多副本时替换为 NATS/PG LISTEN |
| `server/internal/api` | chi 路由、认证中间件、SSE、内嵌 SPA 托管 |
| `server/internal/db` | SQLite（modernc 纯 Go 驱动，免 CGO）+ 版本化迁移 |
| `web/src/crypto` | 与 Go 完全一致的 libsodium 信封实现 |
| `android/.../crypto` | 同上（lazysodium-android / JNA） |
| `android/.../data` | Room 加密记录、离线优先仓库 |
| `android/.../sync` | WorkManager 周期 + 即时同步 |

## 数据模型

- `users`：用户名、两个 Argon2id 盐、**登录验证器**（口令派生值，非口令本身）、包裹后的主密钥。
- `devices`：设备名、公钥（设备审批阶段启用）、批准状态、最后在线。
- `refresh_tokens`：刷新令牌的 SHA-256 哈希与过期时间。
- `records`：通用加密信封 `(user_id, id)` 主键，明文列仅有 `module / type / 时间戳 / version / deleted`。
  - 合并规则：仅当传入 `version` 严格大于库中版本时覆盖（LWW）。
  - 增量同步：`GET /records?since=<updated_at 毫秒>`。
- `audit_logs`：登录、注册、失败尝试等安全事件。

高频专用存储（后续阶段）：`locations`（按月分表）、短信/通话/联系人游标表、附件密文块。

## 请求生命周期（写入一条记录）

1. 客户端在内存持有 MK（32B，来自注册或登录解锁）。
2. 明文 JSON → `XChaCha20-Poly1305(MK, plaintext, AAD=id+module+version)`。
3. `POST /api/v1/records/batch` 携带 Bearer JWT；服务端只做版本比较与落盘。
4. 服务端通过 SSE 向该用户其他在线设备广播 `records_changed`。
5. 其他设备收到信号后以 `since=` 拉取密文，本地用 MK 解密。

## 扩展模块的方式

新业务模块（密码库、证件、记账……）= 一份 JSON Schema + 前端表单/列表页，
服务端 `records` 表与同步通道零改动；需要特殊索引的模块再在 `internal/modules` 增加薄逻辑。
