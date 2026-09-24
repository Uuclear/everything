# AI Agent 安全文档（阶段 6）

> 配套 [`ai-agent.md`](ai-agent.md) §1 三大设计原则 + §3.3 安全与超时 + §6 审计与零知识纪律；
> 本文档详述 Agent 特有的零知识边界、隐私红线、敏感操作审计与速率限制。
> 状态：调研稿，规划阶段。

---

## §0 三道防线

| 防线 | 位置 | 作用 |
|---|---|---|
| **第一道**（协议层） | 服务端 Provider 配置 + LLM 协议 | 强制白名单 + 强制 API Key 不入库 |
| **第二道**（代码层） | 客户端工具注册表 | 强制二次确认 + 强制脱敏 + 强制解锁会话 |
| **第三道**（审计层） | 服务端 audit_log + 客户端 local_audit | 强制不存明文 + 强制速率限制 + 强制审计 |

任何一道防线失守，其他两道仍可阻断或追溯。

---

## §1 零知识边界

### §1.1 服务端**永不见**

| 数据 | 服务端可见性 | 备注 |
|---|---|---|
| **MK / 主密码 / Argon2id 参数** | ❌ 永不可见 | 服务端只存 MK 包裹后的密文 |
| **用户自填 API Key** | ⚠️ 仅**内存单次使用** | 用完即丢，**不写日志 / DB / 配置文件** |
| **明文 user_msg** | ❌ 永不可见 | 服务端只存 `user_msg_bytes`（字节数） |
| **明文 assistant_msg** | ❌ 永不可见 | 同上 |
| **明文 tool_args** | ❌ 永不可见 | 服务端只存字节数 + tool_name |
| **明文 tool_result** | ❌ 永不可见 | 服务端只透传字节流给 LLM，不解析不存 |
| **解锁状态** | ⚠️ 仅**会话级标识**（session_id 有效 / 过期） | 不存用户主密码 / MK |

### §1.2 服务端**可见**

| 数据 | 用途 |
|---|---|
| `session_id`（UUID + device_id + 时间） | 关联审计 |
| `device_id`（来自 JWT） | 多设备隔离 |
| `user_id`（来自 JWT） | 速率限制 + 审计 |
| `provider` + `model` | 配置路由 |
| `tool_name`（白名单内枚举） | 审计 |
| `prompt_tokens` / `completion_tokens` | 速率限制 + 成本预估 |
| `total_tokens` | 审计 |
| `duration_ms` | 性能监控 |
| `status` / `error_code` | 错误聚合 |
| `tool_args_bytes` / `tool_result_bytes` | 流量统计 |

### §1.3 服务端**禁止做**（代码层硬约束）

```go
// server/internal/agent/proxy.go

// ❌ 禁止：服务端解析 user_msg 明文
func (p *AgentProxy) HandleChat(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
    // ❌ 反例：解析消息内容做敏感词过滤 / 命名实体识别 / 日志记录
    // for _, msg := range req.Messages {
    //     log.Info("user_msg: " + msg.Content)  // 违规：明文落日志
    // }
    // ❌ 反例：服务端解析工具入参
    // args, _ := json.Marshal(req.ToolCalls[0].Function.Arguments)
    // log.Info("tool_args: " + string(args))  // 违规
}

// ✅ 正确：只透传字节流
func (p *AgentProxy) HandleChat(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
    // 记录字节数
    userMsgBytes := 0
    for _, msg := range req.Messages {
        userMsgBytes += len(msg.Content)  // 只数长度，不解析
    }
    // 转发给 LLM
    resp, err := p.provider.ChatCompletion(ctx, req)
    // 记录审计
    p.audit.Log(AuditEntry{
        UserMsgBytes:    userMsgBytes,
        AssistantBytes:  len(resp.Choices[0].Message.Content),  // 仅字节数
        ToolName:        extractToolNames(resp.Choices[0].ToolCalls),  // 仅名字
        // ❌ 不存 Content / Arguments 明文
    })
    return resp, nil
}
```

### §1.4 服务端**强制做**

1. **强制白名单**：服务端启动时从配置读取白名单工具清单 → 启动后**运行时不可修改**（重新部署才能加新工具）；
2. **强制速率限制**：每个用户的 RPM / TPM / 并发会话数受限（详见 §6）；
3. **强制超时**：每个 LLM 调用 60s 超时 + 客户端单 tool 15 分钟超时；
4. **强制审计**：每个请求必产 1 条 audit_log；
5. **强制不存**：服务端配置 `audit_log.disk_persistence` 必须 true（不许关）；
   `audit_log.retention_days = 90`（默认 90 天，可配置）。

---

## §2 隐私红线（v6 强化）

在阶段 1–5 红线基础上，**Agent 特有红线**：

### §2.1 提示词注入防护

LLM Provider 收到的内容**不可信**——user_msg 可能是用户主动注入，也可能是
「密码库条目 title」「事件 title」被恶意构造触发。

**服务端防护**：

1. **system prompt 不可被 user 覆盖**：服务端构造 messages 时**始终**把 system
   放在最前 → OpenAI / Anthropic 协议保证 system 优先级最高；
2. **system prompt 注入检测**：客户端解析 user_msg → 若包含 `<system>` / `<<SYS>>`
   / `<|im_start|>system` 等系统提示词标记 → **直接拒绝**（客户端层）；
3. **工具白名单约束**：服务端校验 LLM 返回的 tool_calls 必须在白名单内 → 不可调
   `system.execute_shell` / `database.query` 等未注册工具；
4. **输出长度限制**：服务端对 LLM 返回的 content 截断 ≤ 4 KiB（防 DoS + 减少意外泄露）；
5. **拒绝抓**：LLM 输出含「以下是您的密码」/「以下是您的主密码」等 → 服务端
   在透传给客户端前**截断**（防 LLM 被 prompt 注入后泄露客户端历史内容）。

### §2.2 敏感字段脱敏（写工具 result 强制）

详见 [`ai-agent-prompts.md`](ai-agent-prompts.md) §4。客户端 `redactForLLM` 函数
**强制在写工具 result 序列化前**脱敏——服务端不参与（服务端本就不见 result 明文）。

**关键字段脱敏规则**：

| 字段类型 | 脱敏规则 | 例 |
|---|---|---|
| 完整保单号 | 末 4 位 + `***` 前缀 | `"PINGAN-2026-AUTO-12345678"` → `"***5678"` |
| 完整卡号 | 末 4 位 + `***` 前缀 | `"6222021234567890"` → `"***7890"` |
| 密码 / token / secret | 替换为 `"已设置"` | `"MyP@ssw0rd123"` → `"已设置"` |
| 邮箱 | 首字母 + `***@domain` | `"alice@example.com"` → `"a***@example.com"` |
| 手机 | 前 3 + 后 4 + `***` | `"13812345678"` → `"138****5678"` |
| 地址 / 备注 | 替换为 `"已保存"` | `"北京市朝阳区..."` → `"已保存"` |
| 主密码 / MK | **整个绝不出现在 result** | 替换为 `"已保存"` + 删除字段 |

### §2.3 通知文案脱敏（阶段 5 v2 红线延续）

Agent 触发的任何**系统通知**（Web Notification / Android NotificationManager）
**不渲染**：
- 金额
- 后四位（卡号 / 保单号）
- 具体日期数字（`明天` / `9 月 25 日` 可以，时间 `14:30` 不行）

Agent 输出通知文案前**强制走** [`finance.md`](finance.md) §5.2 通知文案模板。

---

## §3 二次确认强制约束

### §3.1 服务端**不参与**

服务端**绝不执行工具**——它只透传 tool_calls 给客户端，由客户端执行。
服务端**不校验**「这个工具的入参是否真的需要二次确认」——这是客户端的责任。

### §3.2 客户端**强制弹窗**

**所有写工具**（17 个）必须经过客户端弹窗确认：

- **Web 端**：`Naive UI` 的 `NPopconfirm` 组件，标题：「AI 助手请求执行 {tool_name}」，
  内容：工具入参的**脱敏摘要** + 「同意」/「拒绝」按钮；
- **Android 端**：`AlertDialog`，同上规范；
- **默认倒计时**：弹窗出现后 5 秒内「同意」按钮置灰，防误触；
- **审计**：用户每次同意 / 拒绝 → 客户端 `agent_local_audit` 记录（不上行服务端）。

### §3.3 例外场景（v6 留待后续）

下列场景**未来可放宽**二次确认（本期不做）：

- 用户在设置中开启「免确认快速模式」（仅 24h 有效 + 仅限定额）；
- 用户在编辑器 AI 帮填场景下「一次确认多次提交」。

本期全部写工具一律必弹窗。

---

## §4 敏感操作审计

### §4.1 写工具额外审计

除 §1.2 通用审计外，**写工具**额外记录：

| 字段 | 含义 |
|---|---|
| `tool_args_hash` | 入参 SHA-256 哈希（用于跨会话关联同一操作，但不暴露明文）|
| `confirm_method` | `"dialog"` / `"click"` / `"shortcut"`（防 UX 绕过）|
| `confirm_duration_ms` | 用户从看到弹窗到点击同意的耗时（< 200ms 标记为可疑）|

### §4.2 异常模式检测（服务端）

服务端定期跑异常检测（`scripts/audit-detect.sh`）：

| 模式 | 阈值 | 告警 |
|---|---|---|
| 单用户 1 小时 RPM 超限 | > 60 | 邮件 + 锁定 5 分钟 |
| 单用户 1 小时调用 5+ 写工具 | > 5 | 邮件告警 |
| 单会话工具调用链过长 | > 10 | 自动断开 + 审计 |
| 同一 `tool_args_hash` 1 分钟内重复 | > 3 | 视为卡死，重试提示 |
| 用户确认耗时 < 200ms 写工具 | 连续 5 次 | 弹出二次密码确认 |

---

## §5 解锁会话安全

### §5.1 会话时长

- **Web 端**：默认 15 分钟无操作过期；可设置 5~60 分钟；过期后**强制**重新输入主密码；
- **Android 端**：跟随 Activity 解锁状态（`onResume` 续期 + `onPause` 立即锁）；
- **强制续期**：每次成功调用工具后服务端返回 `session_expires_at`，客户端自动续到当前 + 15 分钟。

### §5.2 解锁失败处理

- **主密码错误 5 次** → 锁定 5 分钟（服务端 brute force 防护）；
- **MK 派生失败**（Argon2id 异常）→ 服务端立即踢出 + 客户端跳登录页；
- **生物识别失败 3 次**（Android）→ 强制主密码兜底；
- **服务端检测异常地理登录** → 强制二次密码确认。

### §5.3 多设备隔离

- 每个设备独立 `session_id` = `device_id + session_random`；
- 一个设备锁定不影响其他设备；
- `identity.revoke_device` 工具可远程踢出（**二次确认必**）。

---

## §6 速率限制（服务端强制）

### §6.1 配额表

| 维度 | 限额（云端 / 本地 Ollama） | 失败行为 |
|---|---|---|
| RPM（每用户每分钟请求）| 60 / 30 | 429 Too Many Requests |
| TPM（每用户每分钟 token）| 200 K / 80 K | 429 + 重试提示 |
| 并发会话（每用户）| 3 | 429 |
| 单工具调用超时 | 15 分钟 | 自动 retry 一次 |
| 单 chat message 大小 | ≤ 4 KiB | 400 |
| 单会话累积 token | ≤ 128 K | 自动 trim 最早 1/3 消息 |
| 单用户每日工具调用 | ≤ 5000 | 429 + 提示「已达日额度，明天继续」 |

### §6.2 实现（服务端 token bucket）

```go
// server/internal/agent/ratelimit.go
type RateLimiter struct {
    userRPM    *redis_rate.Limiter  // user_id -> RPM
    userTPM    *redis_rate.Limiter  // user_id -> TPM
    userConc   *redis_rate.Limiter  // user_id -> 并发会话
    dailyTool  *redis_rate.Limiter  // user_id -> 日工具调用
}

func (rl *RateLimiter) Check(ctx context.Context, userID string, msgBytes, tokens int) error {
    // 1. RPM 检查
    ok, _ := rl.userRPM.Allow(ctx, userID, redis_rate.PerMinute(60))
    if !ok { return ErrRateLimited("rpm") }

    // 2. TPM 检查
    ok, _ = rl.userTPM.Allow(ctx, userID, redis_rate.PerMinute(200_000))
    if !ok { return ErrRateLimited("tpm") }

    // 3. 每日工具配额检查（仅写工具）
    // ...

    return nil
}
```

### §6.3 客户端配合

- 客户端本地也做粗粒度限流（防误操作刷服务端）；
- 收到 429 → 显示倒计时 + 自动重试（指数退避）；
- 收到 403 → 检查解锁会话状态 + 跳解锁页。

---

## §7 LLM Provider 选择策略

### §7.1 默认本地 Ollama

- 服务端默认 `default_provider: "ollama"` → 用户无感知；
- 客户端在 Chat Panel 顶部显示「本地 Ollama」徽章；
- 用户可手动切换到云端（设置页）。

### §7.2 云端 Provider 风险

- **OpenAI / Anthropic / Gemini** 等云端 LLM 看到的是 tool_calls 明文 + tool_result 明文；
- 用户**主动选择**云端 Provider 时**必须**勾选同意协议：
  > 「我理解我发送给云端 LLM 的内容（包括解密后的密码库条目明文、事件明文、财务明文）
  > 会被该 LLM Provider 看到并用于生成回复。该 Provider 的隐私政策详见 xxx。」
- 服务端**不缓存**任何云端 LLM 的请求 / 响应；
- 服务端**不透传**用户 API Key 给其他用户或云端日志。

### §7.3 Provider 故障降级

- 云端 Provider 故障 → 自动降级到本地 Ollama（若可用）；
- 本地 Ollama 故障 → 客户端显示「LLM 服务不可用」+ 重试按钮；
- **绝不**在 Provider 不可用时静默失败（用户主动发起的请求必须有响应）。

---

## §8 攻击场景与防护矩阵

| 攻击场景 | 防护位置 | 防护措施 |
|---|---|---|
| **Prompt 注入**（user_msg 含 `<system>`）| 客户端 | 检测到标记 → 拒绝 |
| **Prompt 注入**（tool_result 含恶意指令）| 客户端 + 服务端 | 服务端截断 LLM 输出 ≤ 4 KiB；客户端二次确认强制 |
| **服务端代码 bug 泄露明文** | 服务端 | 强制单元测试覆盖 + 代码审查 + `strings.Contains(msg.Content, "<password>")` 类红线 grep |
| **LLM 幻觉返回伪造工具** | 服务端 | 强制白名单（tool_name 枚举约束） |
| **LLM 拒绝服务（DoS）** | 服务端 | 速率限制 + 超时 + 输出截断 |
| **暴力破解主密码** | 服务端 | 5 次失败 → 锁定 5 分钟 |
| **会话劫持**（JWT 泄露） | 服务端 | JWT 短期 + refresh token + 解锁会话独立校验 |
| **跨用户串扰** | 服务端 | JWT subject 校验 + user_id 隔离 |
| **客户端反向工程** | 客户端 | MK 仅在内存 + 解锁会话过期即清；MK 不入 SharedPreferences / DataStore |
| **审计被删改** | 服务器 | 审计 log 仅追加不可删；定期异地备份 |

---

## §9 引用

- [`ai-agent.md`](ai-agent.md) §1 三大设计原则 + §3.3 安全与超时 + §6 审计。
- [`ai-agent-prompts.md`](ai-agent-prompts.md) §1 系统提示词 + §4 隐私脱敏。
- [`ai-agent-tools.md`](ai-agent-tools.md) §6 utility 工具清单 + §7 客户端实现。
- [`crypto.md`](crypto.md) §5 records 通道 + AAD（Agent 不绕过）。
- [`finance.md`](finance.md) §5.2 通知文案模板（Agent 触发通知沿用）。
- OpenAI Safety Best Practices：https://platform.openai.com/docs/guides/safety-best-practices
- OWASP LLM Top 10：https://owasp.org/www-project-top-10-for-large-language-model-applications/