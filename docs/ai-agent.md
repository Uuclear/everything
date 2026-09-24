# AI Agent 架构（阶段 6）

> 状态：调研稿 / 规划文档，未落地代码。
> 阶段：6 — AI Agent（服务端 OpenAI 兼容 Agent + 工具注册 + 解锁会话）。
> 上游：阶段 1–5 已落地（事件 / 密码库 / 财务 / 轨迹 / 物品 的 records 密文通道 + 端侧纯函数）。
> 目标：在不破坏零知识红线与既有 records 通道的前提下，提供自然语言入口 + 工具调用。

---

## §0 一句话定位

Agent 是**用户授权下的端侧+服务端混合**自然语言入口：服务端只做**调度、审计、白名单转发**；
真正解密与读写发生在**已解锁的客户端**（Web 端 Pinia store / Android 端 FinanceRepository 等），
服务端永不见明文。LLM 调用走 **OpenAI Chat Completions 兼容协议**，
Provider 可换（OpenAI / Anthropic / Ollama / DeepSeek / vLLM / LM Studio），
**默认本地 Ollama 自托管**，云端 API 由用户在设置中自填 API Key。

---

## §1 三大设计原则

1. **服务端是透明路由器**：服务端**不持有明文、不持有 MK、不解密任何记录**；
   只做「客户端发出密文 + LLM 工具调用描述 → 服务端转发 → 客户端执行 →
   客户端回报密文结果 → 服务端回包 LLM」。服务端审计仅记录「调用了哪个工具 / 何时 / 由谁」。
2. **解锁会话必现**：Agent 仅在用户**主动解锁期间**响应（Web 端 MK 在内存；
   Android 端 MK 在 AndroidKeyStore + 内存缓存）；
   锁屏 / MK 过期 / 会话超时 → Agent 强制返回 401 + UI 跳解锁页。
3. **工具调用二次确认**：所有**写操作工具**（create / update / delete）必须经过用户**显式确认**
   （Web 弹窗 / Android 系统对话框）；**读操作工具**默认信任用户授权（但仍审计）。

---

## §2 整体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                       Web / Android Client                            │
│                                                                       │
│   ┌───────────────┐     解锁会话 (MK in memory, 15 分钟无操作过期)  │
│   │  Chat Panel   │ ◀────────────────────────────────────────────┐  │
│   │  (Vue3/Compose)│                                              │  │
│   └──────┬────────┘                                              │  │
│          │ user_msg (明文) + signed JWT (session=15min)            │  │
│          ▼                                                         │  │
│   ┌───────────────┐     工具注册表 (本地客户端 = 真执行者)         │  │
│   │ Agent Router  │ ◀──────────────────────────────────────────┐  │  │
│   │ (stores/agent)│                                              │  │  │
│   └──────┬────────┘                                              │  │  │
│          │ (a) 先发 msg 给服务端 → 服务端转给 LLM → 拿 tool_calls  │
│          │ (b) 客户端执行工具 (decrypt local records / 写 records)  │
│          │ (c) 把工具结果回包给服务端 → 服务端再问 LLM → 最终回复  │
└──────────┼─────────────────────────────────────────────────────────┘
           │ HTTPS (Authorization: Bearer <session_jwt>)
           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          Go Server (EVE)                              │
│                                                                       │
│   ┌─────────────────────────────────────────────────────────┐       │
│   │  POST /api/v1/agent/chat                                │       │
│   │  POST /api/v1/agent/tool-result                         │       │
│   │  POST /api/v1/agent/cancel                              │       │
│   │  GET  /api/v1/agent/sessions (审计列表)                 │       │
│   └─────────────┬───────────────────────────────────────────┘       │
│                 │                                                    │
│   ┌─────────────▼───────────┐    ┌─────────────────────────────┐    │
│   │   Agent Proxy (Go)      │ ─▶ │  LLM Provider               │    │
│   │   - 工具白名单          │    │  OpenAI / Ollama / DeepSeek │    │
│   │   - 速率限制 (RPM/TPM)  │    │  (OpenAI Chat Completions   │    │
│   │   - 审计 log            │    │   兼容协议)                 │    │
│   │   - 超时 60s            │    └─────────────────────────────┘    │
│   │   - 错误降级            │                                       │
│   └─────────────┬───────────┘                                       │
│                 │                                                    │
│   ┌─────────────▼───────────┐                                       │
│   │   audit_log (SQLite)    │  仅记：                                │
│   │   - session_id          │  - 调用工具名 + 入参 / 出参字节数     │
│   │   - tool_called         │  - LLM Provider + 模型版本            │
│   │   - provider/model      │  - 会话时长 + Token 用量                │
│   │   - tokens_in/out       │  - 用户 ID + 设备 ID (同 §5 device_id)│
│   │   - timestamp           │  **不存**：明文 user_msg / 明文回复  │
│   └─────────────────────────┘                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## §3 Provider 抽象层（服务端）

### §3.1 接口定义

```go
// server/internal/agent/provider.go
type Provider interface {
    // ChatCompletion 发起一次 LLM 对话（OpenAI Chat Completions 兼容协议）
    ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error)
    // CountTokens 预估算输入 token 数（用于速率限制 + 成本预估）
    CountTokens(req ChatRequest) (int, error)
    // Name Provider 唯一标识（用于审计 + 配置）
    Name() string  // "openai" | "ollama" | "deepseek" | "anthropic" | ...
}

type ChatRequest struct {
    Model       string         // "gpt-4o-mini" | "qwen2.5:7b" | "deepseek-chat"
    Messages    []ChatMessage  // [{role, content, name?}]
    Tools       []ToolSpec     // 工具清单（OpenAI function calling 格式）
    ToolChoice  any            // "auto" | "none" | {"name": "..."}
    Temperature *float64
    MaxTokens   *int
    Stream      bool           // 暂定 false（先实现非流式，流式留 v2）
    Metadata    map[string]any // 透传给 Provider
}

type ChatMessage struct {
    Role       string         // "system" | "user" | "assistant" | "tool"
    Content    string
    Name       string         // tool 角色必填 = 工具名
    ToolCallID string         // tool 角色必填 = 工具调用 id
}

type ToolSpec struct {
    Type     string         // "function"
    Function ToolFunction
}

type ToolFunction struct {
    Name        string         // 工具唯一名（白名单约束）
    Description string         // 自然语言描述（给 LLM 看）
    Parameters  json.RawMessage // JSON Schema 格式
}

type ChatResponse struct {
    ID        string
    Model     string
    Choices   []ChatChoice
    Usage     Usage  // prompt_tokens / completion_tokens / total_tokens
}

type ChatChoice struct {
    Index        int
    Message      ChatMessage
    FinishReason string  // "stop" | "tool_calls" | "length" | "content_filter"
    ToolCalls    []ToolCall
}

type ToolCall struct {
    ID       string
    Type     string  // "function"
    Function ToolCallFunc
}

type ToolCallFunc struct {
    Name      string
    Arguments string  // JSON 字符串（客户端解析后执行）
}
```

### §3.2 路由策略

服务端读取配置 `config.yaml → agent.providers[]`，按用户在设置中选定的「激活 Provider」路由：

```yaml
agent:
  enabled: true
  default_provider: "ollama"
  providers:
      # ① Ollama 本地（默认）
    - name: OLLAMA
      base_url: "http://localhost:11434/v1"
      api_key: "ollama"  # Ollama 协议占位（实际不校验）
      models: ["qwen2.5:7b", "llama3.2:3b", "deepseek-r1:7b"]
      rpm_limit: 60
      tpm_limit: 200000
      # ② OpenAI 云端（用户在用户设置中自填 api_key，不入库仅内存）
    - name: OPENAI
      base_url: "https://api.openai.com/v1"
      api_key_source: "user_settings"  # 明示：不入 config / DB / 审计
      models: ["gpt-4o-mini", "gpt-4o"]
      rpm_limit: 500
      tpm_limit: 200000
      # ③ DeepSeek 云端（国内推荐）
    - name: DEEPSEEK
      base_url: "https://api.deepseek.com/v1"
      api_key_source: "user_settings"
      models: ["deepseek-chat", "deepseek-reasoner"]
      rpm_limit: 300
      tpm_limit: 200000
```

**关键约束**：
- **服务端配置表不存用户 API Key**（`api_key_source: user_settings` 标记）；
  API Key 由客户端在解锁会话中**加密回传**给服务端**单次使用内存**，
  用完即丢，**不写日志 / 不写 DB / 不透传给其他用户**。
- Provider 增加无需改服务端代码（注册驱动即可），后续加 Anthropic / Gemini / 国内豆包同款协议。

### §3.3 安全与超时

- 服务端**强制 60s 超时**（含 LLM 调用 + 重试一次）；
- 客户端每条 chat message 限 ≤4 KiB（防止 prompt 注入滥用）；
- 单会话累积 token ≤128 K（超出自动 trim 最早 1/3 消息）；
- 工具调用失败 → 自动 retry 一次（指数退避 500ms）→ 仍失败则返回错误给客户端（不静默）。

---

## §4 工具注册表（端侧真执行者）

### §4.1 注册表总览

工具按**模块**分组，注册在**客户端**的 `stores/agent.ts`（Web）+ `AgentToolRegistry.kt`（Android），
**服务端仅持白名单清单**（只校验「客户端有没有声明这个工具」+ 「是否在白名单」）。

| 模块 | 工具名 | 类型 | 风险等级 | 二次确认 |
|---|---|---|---|---|
| vault | `vault.search` | 读 | 低 | 否（信任用户授权） |
| vault | `vault.get` | 读 | 中（**解密 entry 明文**）| 否（仅会话内） |
| vault | `vault.create` | 写 | **高** | ✅ |
| vault | `vault.update` | 写 | **高** | ✅ |
| vault | `vault.delete` | 写 | **高** | ✅ |
| identity | `identity.get_profile` | 读 | 低 | 否 |
| identity | `identity.list_devices` | 读 | 低 | 否 |
| identity | `identity.revoke_device` | 写 | **高** | ✅ |
| event | `event.search` | 读 | 低 | 否 |
| event | `event.get` | 读 | 中（**解密事件明文**）| 否 |
| event | `event.create` | 写 | 中 | ✅ |
| event | `event.update` | 写 | 中 | ✅ |
| event | `event.delete` | 写 | 中 | ✅ |
| finance | `finance.search` | 读 | 中 | 否 |
| finance | `finance.aggregate` | 读（端侧纯函数）| 中 | 否 |
| finance | `finance.create` | 写 | **高**（写 records）| ✅ |
| finance | `finance.update` | 写 | **高** | ✅ |
| finance | `finance.delete` | 写 | **高** | ✅ |
| trajectory | `trajectory.search` | 读 | 中（**解密位置明文**）| 否 |
| trajectory | `trajectory.aggregate` | 读 | 中 | 否 |
| item | `item.search` | 读 | 低 | 否 |
| item | `item.expiring_soon` | 读 | 低 | 否 |

**详细工具清单 + 入参 / 出参 JSON Schema**见 [`ai-agent-tools.md`](ai-agent-tools.md)。

### §4.2 调用协议（Agent Loop）

标准的 ReAct loop（OpenAI function calling 协议）：

```
1. Client → Server: { session_id, user_msg, tool_history (可选) }
2. Server → LLM: { model, messages (含 system + 历史 + user_msg), tools (白名单) }
3. LLM → Server: { choices[0].finish_reason = "tool_calls", tool_calls: [...] }
4. Server → Client: { tool_calls: [{id, name, args}, ...], usage, finish_reason }
5. Client 解码 tool_calls → 本地查注册表 → 若「写操作」→ 弹确认 → 用户同意 → 客户端执行
6. Client → Server: { tool_results: [{tool_call_id, name, result_content (明文/脱敏)}] }
7. Server → LLM: { messages += tool_results }
8. 回到步骤 3，直到 finish_reason = "stop"
9. Server → Client: { assistant_msg (明文), usage, finish_reason: "stop" }
10. Server 审计 log：tool_name / 调用字节数 / token 用量 / 用户授权
```

**关键约束**：
- 步骤 5 中「写操作」的二次确认**必须服务端不可跳过**：
  即使客户端代码有 bug，服务端**不执行工具**（服务端本来就不知道工具细节）；
  二次确认走客户端 UI。
- 步骤 6 中 `result_content`：
  - **读工具**：返回**明文**（因为 LLM 需要理解才能回复用户）；
    但**服务端不缓存 result_content**（仅透传给 LLM，下次请求需重新调用）；
  - **写工具**：返回**操作摘要**（"已创建保单 POL-2026-AUTO-1234，到期 2026-11-21"）+ 失败时返回错误信息；
    **不**返回完整记录明文。

### §4.3 工具白名单约束（服务端）

服务端校验：
1. 客户端声明的工具名是否在白名单；
2. 是否在该用户的工具权限列表（用户可在设置中禁用某些工具）；
3. 累计 token 是否超限；
4. 是否在解锁会话内。

**服务端不校验**：
- 工具入参是否合法（客户端负责）；
- 工具是否真的执行（客户端负责）；
- 工具结果是否正确（客户端负责）。

服务端就是**路由器 + 审计员 + 速率限制器**。

---

## §5 解锁会话（Web + Android）

### §5.1 状态机

```
   ┌─────────┐  解锁成功 (输入主密码 / 生物认证)
   │ LOCKED  │ ─────────────────────────────────┐
   └─────────┘                                  │
        ▲                                        ▼
        │  锁屏 / MK 失效 / 会话过期        ┌─────────────┐
        │  (Web: 关闭标签 / 15 min 无操作)  │  UNLOCKED   │
        │                                  │  (MK in 内存) │
        │                                  └──────┬──────┘
        │                                         │
        │  Agent 请求                              │
        │  - 解锁会话: 200 OK + 工具清单           │
        │  - 锁定会话: 401 → 客户端跳解锁页        │
        └─────────────────────────────────────────┘
```

### §5.2 会话时长

- **Web 端**：默认 15 分钟无操作过期（可设置 5~60 分钟）；
- **Android 端**：跟随后台 Activity 解锁状态（onResume 续期 + onPause 立即锁）；
- **强制续期**：每次成功调用工具后服务端返回 `session_expires_at`，客户端自动续到当前 + 15 分钟。

### §5.3 多设备隔离

- 每个设备独立会话（session_id = `device_id + session_random`）；
- 一个设备锁定不影响其他设备解锁状态；
- `identity.revoke_device` 工具可远程踢出指定设备（需二次确认）。

---

## §6 审计与零知识纪律

### §6.1 服务端审计表（agent_audit_log）

```sql
CREATE TABLE agent_audit_log (
  id            TEXT PRIMARY KEY,        -- ULID
  session_id    TEXT NOT NULL,
  user_id       TEXT NOT NULL,           -- 来自 JWT subject
  device_id     TEXT NOT NULL,           -- 来自 §5 device_id
  provider      TEXT NOT NULL,           -- "ollama" | "openai" | ...
  model         TEXT NOT NULL,           -- "qwen2.5:7b" | "gpt-4o-mini" | ...
  tool_name     TEXT,                    -- NULL = 纯对话；非 NULL = 工具调用
  tool_args_bytes INTEGER,               -- 入参字节数（不存明文）
  tool_result_bytes INTEGER,             -- 出参字节数（不存明文）
  user_msg_bytes INTEGER,                -- user_msg 字节数
  assistant_msg_bytes INTEGER,           -- assistant_msg 字节数
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  total_tokens INTEGER,
  duration_ms   INTEGER,
  status       TEXT NOT NULL,             -- "ok" | "tool_calls" | "error" | "timeout" | "rate_limited"
  error_code   TEXT,                     -- 错误码（不存错误明文）
  created_at   INTEGER NOT NULL          -- Unix 毫秒
);

CREATE INDEX idx_agent_audit_user_time ON agent_audit_log(user_id, created_at DESC);
CREATE INDEX idx_agent_audit_session ON agent_audit_log(session_id, created_at);
```

**审计三不存**：
1. 不存明文 user_msg；
2. 不存明文 assistant_msg；
3. 不存明文 tool_args / tool_result（仅字节数）。

### §6.2 客户端审计表（agent_local_audit）

客户端在 `agent_local_audit`（IndexedDB / Web + Room / Android）存**完整会话历史**（明文），
**仅本地**、**仅当前设备**、**不上行**。用户可手动清理。

### §6.3 零知识纪律（v6 强化）

在阶段 1–5 红线基础上新增：

- 服务端 / 审计 / 日志**不出现**任何 `user_msg` / `assistant_msg` / `tool_args` / `tool_result` 明文；
- LLM Provider 不可见 MK / 主密码 / API Key（OpenAI 云端也不可见，仅收到密文调用描述）；
- 工具结果含敏感明文（如保单完整号 / 完整卡号 / 主密码）→ 客户端**强制脱敏**后再回包 LLM；
- 服务端配置表**不存用户 API Key**（`api_key_source: user_settings` 标记）；
- 写操作工具必须经用户**二次确认**（服务端不参与，但客户端代码层强制弹窗）。

### §6.4 速率限制

| 维度 | 限额 | 失败行为 |
|---|---|---|
| RPM（每用户每分钟请求）| 60（云端）/ 30（本地 Ollama） | 429 Too Many Requests |
| TPM（每用户每分钟 token）| 200 K（云端）/ 80 K（本地）| 429 + 重试提示 |
| 并发会话（每用户）| 3 | 429 |
| 单工具调用超时 | ≥15 分钟 | 自动 retry 一次 |
| 单 chat message 大小 | ≤4 KiB | 400 |
| 单会话累积 token | ≤128 K | 自动 trim 最早 1/3 消息 |

---

## §7 错误降级与可观测性

### §7.1 错误码（服务端 → 客户端）

| 错误码 | 含义 | 客户端处理 |
|---|---|---|
| `agent.session_locked` | 会话已锁 | 跳解锁页 |
| `agent.rate_limited` | 速率限制 | 显示倒计时 + 自动重试 |
| `agent.tool_not_whitelisted` | 工具不在白名单 | 隐藏相关功能 |
| `agent.tool_denied` | 用户拒绝二次确认 | 取消当前工具链 |
| `agent.provider_error` | Provider 故障 | 切换 Provider 重试 |
| `agent.timeout` | LLM 调用超时 | 显示「LLM 响应超时，请重试」 |
| `agent.context_too_long` | 上下文超限 | 自动 trim + 提示 |

### §7.2 可观测性

服务端暴露 `/api/v1/agent/sessions`（审计列表，仅元数据）+ `/metrics`（Prometheus 风格）：
- `agent_requests_total{provider, status}`
- `agent_tokens_total{provider, direction}`  // direction = "prompt" | "completion"
- `agent_tool_calls_total{tool_name, status}`
- `agent_session_duration_seconds`

客户端仅暴露本地指标（不上报服务端）。

---

## §8 与既有 records 通道的关系

Agent **不绕过既有 records 通道**：
- 工具调用写入的 records 走原通道 + 原 AAD（§5 AAD）；
- 工具读取的 records 解密后**仅在客户端内存**（不写库不入 IndexedDB / Room）；
- 多设备同步仍走 `POST /api/v1/records`（双向），Agent 只在客户端单端触发。

**换言之**：Agent 是「驱动既有 CRUD 的自然语言入口」，不是「新通道」。

---

## §9 与阶段 5 v2 编辑器联动（v3 候选 #8）

v3 候选 #8「编辑器 AI Agent 联动」= 阶段 6 落地后增量：
- 在 `SubscriptionEditorDialog.vue` / `PolicyEditorDialog.kt` 等编辑器顶部加「AI 帮填」按钮；
- 弹窗：「用一句话描述您要创建的订阅 / 保单 / 借款 / 合同 / 投资账户，Agent 自动填充表单」；
- Agent 调用 `finance.create` 工具（**二次确认**）→ 客户端跳转编辑器已预填态 → 用户微调 → 提交。

详细交互设计留待 v3 启动时单开 spec。

---

## §10 落地路径（建议）

按依赖关系排：

1. **基础层**：Provider 抽象层 + 配置 + 注册表（无 UI，纯库）；
2. **服务端路由**：POST /agent/chat + /agent/tool-result + /agent/sessions + 审计 log；
3. **Web 端 Chat Panel**：最小可对话 + 工具白名单展示（只读工具先 grep vault）；
4. **Web 端写工具**：finance.create + 二次确认弹窗；
5. **Android 端 Chat Panel**（Phase 2，留待阶段 7 / v3）；
6. **编辑器 AI 帮填**：v3 候选 #8 增量。

**调研稿边界**：以上 6 步在本 spec 中仅完成**步骤 1–2 的设计 + 接口定义**；
代码实现留待 Task 7~12（见 `tasks.md`）。

---

## §11 引用

- [`ai-agent-prompts.md`](ai-agent-prompts.md)：系统提示词 + 工具调用协议 + Few-shot 范例。
- [`ai-agent-tools.md`](ai-agent-tools.md)：29 个工具的入参 / 出参 JSON Schema。
- [`ai-agent-security.md`](ai-agent-security.md)：零知识边界 + 隐私红线 + 速率限制。
- `.trae/specs/stage6-ai-agent/spec.md`：FR-V6-A~G + AC-V6-1~N。
- `.trae/specs/stage6-ai-agent/tasks.md`：Task 1~N 落地路径。
- [`architecture.md`](architecture.md) §0 整体架构（Agent 在 modules 层）。
- [`crypto.md`](crypto.md) §5 records 通道 + AAD（Agent 不绕过）。
- [`finance.md`](finance.md) §12~§15 财务 v2（编辑器 AI 帮填依赖项）。