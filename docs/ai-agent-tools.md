# AI Agent 工具清单（阶段 6）

> 配套 [`ai-agent.md`](ai-agent.md) §4 工具注册表；本文档详述 29 个工具的入参 / 出参 / 风险等级 / 二次确认规范。
> 状态：调研稿，规划阶段。

---

## §0 总览

| 模块 | 工具数 | 风险 | 是否需要二次确认 |
|---|---|---|---|
| **vault**（密码库） | 5 | 中→高 | 读工具非，写工具必 |
| **identity**（身份） | 3 | 低→高 | 写工具必 |
| **event**（事件） | 5 | 低→中 | 写工具必 |
| **finance**（财务） | 5 | 中→高 | 写工具必 |
| **trajectory**（轨迹） | 2 | 中 | 读工具非 |
| **utility**（工具） | 4 | 低 | 否 |
| **总计** | **29** | — | **17 写工具全必** |

**风险等级定义**：
- **低**：纯本地缓存查询（用户自己的配置 / 偏好）；
- **中**：解密并返回记录明文（密码库 / 事件 / 财务 / 轨迹）；
- **高**：写操作（create / update / delete）+ revoke_device 等不可逆操作。

**二次确认规范**：
- 写工具**必须**客户端弹窗（Web `Naive UI` `NPopconfirm` / Android `AlertDialog`）；
- 弹窗标题：「AI 助手请求执行 {tool_name}」；
- 弹窗内容：工具入参的**脱敏摘要**（详见 [`ai-agent-prompts.md`](ai-agent-prompts.md) §4）；
- 弹窗按钮：「同意」+「拒绝」（默认倒计时 5 秒后才能点同意，防误触）；
- 用户拒绝 → 服务端收到 `tool_denied` 错误码 → LLM 礼貌回复。

---

## §1 vault（密码库，5 个工具）

### 1.1 `vault.search`

```json
{
  "name": "vault.search",
  "description": "在用户的密码库中按关键词搜索条目（按 name / username / url 模糊匹配）。返回前 N 条摘要（不含密码字段）。",
  "risk": "中（返回 username / url 明文）",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {
      "query": {"type": "string", "minLength": 1, "maxLength": 100},
      "limit": {"type": "integer", "default": 10, "minimum": 1, "maximum": 50}
    },
    "required": ["query"]
  },
  "returns": "Array<{ id, name, username, folder?, url? }>"
}
```

### 1.2 `vault.get`

```json
{
  "name": "vault.get",
  "description": "根据 id 获取密码条目的完整内容（**包含密码明文**，仅会话内返回）。",
  "risk": "中（返回密码明文，**敏感**）",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string", "description": "条目 id（来自 vault.search）"}
    },
    "required": ["id"]
  },
  "returns": "{ id, name, username, password, url?, notes?, totp_secret?, custom_fields? }",
  "脱敏": "默认全部字段原样返回；如用户在意可在设置中开启「密码脱敏」→ 密码字段改为 null"
}
```

### 1.3 `vault.create`

```json
{
  "name": "vault.create",
  "description": "创建一条新的密码库条目。",
  "risk": "高（写 records + 增加条目）",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {
      "name": {"type": "string", "minLength": 1, "maxLength": 200},
      "username": {"type": "string", "maxLength": 200},
      "password": {"type": "string", "minLength": 1, "maxLength": 500},
      "url": {"type": "string", "maxLength": 500},
      "folder": {"type": "string", "maxLength": 100},
      "notes": {"type": "string", "maxLength": 5000},
      "totp_secret": {"type": "string", "maxLength": 100},
      "custom_fields": {"type": "object"}
    },
    "required": ["name", "password"]
  },
  "returns": "{ id, summary: \"已创建条目 GitHub\" }"
}
```

### 1.4 `vault.update`

```json
{
  "name": "vault.update",
  "description": "更新一条已有的密码库条目。",
  "risk": "高",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "fields": {"type": "object", "description": "要更新的字段（password / username / notes 等）"}
    },
    "required": ["id", "fields"]
  },
  "returns": "{ id, summary: \"已更新 GitHub 的密码字段\" }"
}
```

### 1.5 `vault.delete`

```json
{
  "name": "vault.delete",
  "description": "软删除一条密码库条目（墓碑语义，可恢复期 30 天）。",
  "risk": "高",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "hard_delete": {"type": "boolean", "default": false, "description": "立即硬删除（不可恢复）"}
    },
    "required": ["id"]
  },
  "returns": "{ id, summary: \"已删除 GitHub（30 天内可在回收站恢复）\" }"
}
```

---

## §2 identity（身份，3 个工具）

### 2.1 `identity.get_profile`

```json
{
  "name": "identity.get_profile",
  "description": "获取当前用户的资料（用户名 / 邮箱 / 显示名）。",
  "risk": "低",
  "confirm": false,
  "parameters": {"type": "object", "properties": {}},
  "returns": "{ user_id, username, email?, display_name }"
}
```

### 2.2 `identity.list_devices`

```json
{
  "name": "identity.list_devices",
  "description": "列出当前用户的所有已登录设备（含最后活跃时间 / 平台 / 设备名）。",
  "risk": "低",
  "confirm": false,
  "parameters": {"type": "object", "properties": {}},
  "returns": "Array<{ device_id, device_name, platform, last_active_at, is_current }>"
}
```

### 2.3 `identity.revoke_device`

```json
{
  "name": "identity.revoke_device",
  "description": "远程踢出指定设备（被踢设备的会话立即失效，需要重新解锁）。",
  "risk": "高（不可逆，影响其他设备）",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {
      "device_id": {"type": "string"}
    },
    "required": ["device_id"]
  },
  "returns": "{ device_id, summary: \"已踢出设备 iPhone 15 Pro\" }"
}
```

---

## §3 event（事件，5 个工具）

### 3.1 `event.search`

```json
{
  "name": "event.search",
  "description": "按时间范围 / 关键词 / 日历搜索事件。",
  "risk": "中（返回 title / notes 明文）",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {
      "from_ts": {"type": "string", "format": "date"},
      "to_ts": {"type": "string", "format": "date"},
      "query": {"type": "string", "maxLength": 100},
      "calendar_id": {"type": "string"},
      "limit": {"type": "integer", "default": 20, "maximum": 100}
    }
  },
  "returns": "Array<{ id, title, start_ts, end_ts, calendar_id, has_recurrence? }>"
}
```

### 3.2 `event.get`

```json
{
  "name": "event.get",
  "description": "获取事件详情（含 RRULE 规则）。",
  "risk": "中",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {"id": {"type": "string"}},
    "required": ["id"]
  },
  "returns": "{ id, title, start_ts, end_ts, notes?, rrule?, calendar_id, location? }"
}
```

### 3.3 `event.create`

```json
{
  "name": "event.create",
  "description": "创建一条新事件（含 RRULE 重复规则）。",
  "risk": "中",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {
      "title": {"type": "string", "minLength": 1, "maxLength": 200},
      "start_ts": {"type": "string", "format": "date-time"},
      "end_ts": {"type": "string", "format": "date-time"},
      "notes": {"type": "string", "maxLength": 5000},
      "location": {"type": "string", "maxLength": 500},
      "calendar_id": {"type": "string"},
      "rrule": {"type": "string", "description": "RRULE 字符串（B 档子集）"},
      "reminders": {"type": "array", "items": {"type": "integer"}, "description": "提前分钟数"}
    },
    "required": ["title", "start_ts", "end_ts"]
  },
  "returns": "{ id, summary: \"已创建事件 '周会' (2026-09-30 10:00)\" }"
}
```

### 3.4 `event.update`

```json
{
  "name": "event.update",
  "description": "更新一条已有事件。",
  "risk": "中",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "fields": {"type": "object"}
    },
    "required": ["id", "fields"]
  },
  "returns": "{ id, summary: \"已更新事件 '周会' 的开始时间\" }"
}
```

### 3.5 `event.delete`

```json
{
  "name": "event.delete",
  "description": "软删除一条事件（墓碑语义）。",
  "risk": "中",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {"id": {"type": "string"}},
    "required": ["id"]
  },
  "returns": "{ id, summary: \"已删除事件 '周会'\" }"
}
```

---

## §4 finance（财务，5 个工具）

### 4.1 `finance.search`

```json
{
  "name": "finance.search",
  "description": "按类型 / 关键词 / 时间范围搜索财务条目（订阅 / 保单 / 借款 / 合同 / 投资账户 / 流水）。",
  "risk": "中",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {
      "type": {"type": "string", "enum": ["subscription", "policy", "loan", "contract", "investment_account", "tx"]},
      "query": {"type": "string", "maxLength": 100},
      "from_ts": {"type": "string", "format": "date"},
      "to_ts": {"type": "string", "format": "date"},
      "limit": {"type": "integer", "default": 20, "maximum": 100}
    }
  },
  "returns": "Array<{ id, type, name, summary_fields: { ... } }>"
}
```

### 4.2 `finance.aggregate`

```json
{
  "name": "finance.aggregate",
  "description": "调用端侧纯函数 FinanceAggregator 做聚合（净资产 / 月报 / 预算阈值）。",
  "risk": "中",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {
      "period": {"type": "string", "enum": ["current_month", "last_month", "ytd"], "default": "current_month"},
      "metric": {"type": "string", "enum": ["net_worth", "monthly_report", "budget_threshold"], "required": true}
    },
    "required": ["metric"]
  },
  "returns": "{ metric, value, breakdown: { ... }, threshold?: \"OK\" | \"WARNING\" | \"EXCEEDED\" }"
}
```

### 4.3 `finance.create`

```json
{
  "name": "finance.create",
  "description": "创建一条财务条目（subscription / policy / loan / contract / investment_account / tx）。",
  "risk": "高（写 records）",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {
      "type": {"type": "string", "enum": ["subscription", "policy", "loan", "contract", "investment_account", "tx"], "required": true},
      "fields": {"type": "object", "description": "按类型填字段，参考 docs/finance.md §12~§15"}
    },
    "required": ["type", "fields"]
  },
  "returns": "{ id, type, summary: \"已创建保单 平安车险，到期 2026-11-21\" }",
  "脱敏": "policy_number 返回末 4 位 + ***；card_number 同款；其他金额原值"
}
```

### 4.4 `finance.update`

```json
{
  "name": "finance.update",
  "description": "更新一条已有财务条目。",
  "risk": "高",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "fields": {"type": "object"}
    },
    "required": ["id", "fields"]
  },
  "returns": "{ id, summary: \"已更新订阅 Netflix 的金额\" }"
}
```

### 4.5 `finance.delete`

```json
{
  "name": "finance.delete",
  "description": "软删除一条财务条目（墓碑语义）。",
  "risk": "高",
  "confirm": true,
  "parameters": {
    "type": "object",
    "properties": {"id": {"type": "string"}},
    "required": ["id"]
  },
  "returns": "{ id, summary: \"已删除订阅 Netflix\" }"
}
```

---

## §5 trajectory（轨迹，2 个工具）

### 5.1 `trajectory.search`

```json
{
  "name": "trajectory.search",
  "description": "按时间范围搜索位置轨迹块（**返回解密后的位置点**）。",
  "risk": "中（**敏感**——位置隐私）",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {
      "from_ts": {"type": "string", "format": "date-time"},
      "to_ts": {"type": "string", "format": "date-time"},
      "limit": {"type": "integer", "default": 50, "maximum": 500}
    },
    "required": ["from_ts", "to_ts"]
  },
  "returns": "Array<{ block_id, points: Array<{ lat, lng, ts, speed?, accuracy? }> }>",
  "额外约束": "服务端不下行任何位置密文；只回块 id 列表给客户端 → 客户端解密后调用此工具聚合"
}
```

### 5.2 `trajectory.aggregate`

```json
{
  "name": "trajectory.aggregate",
  "description": "轨迹统计聚合（总里程 / 最常访问地点 / 通勤距离）。",
  "trend": "中",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {
      "from_ts": {"type": "string", "format": "date"},
      "to_ts": {"type": "string", "format": "date"},
      "metric": {"type": "string", "enum": ["total_distance_km", "frequent_places", "commute_distance"]}
    },
    "required": ["from_ts", "to_ts", "metric"]
  },
  "returns": "{ metric, value: ..., frequent_places?: Array<{ place_id, name, visit_count }> }"
}
```

---

## §6 utility（工具，4 个工具）

### 6.1 `utility.now`

```json
{
  "name": "utility.now",
  "description": "获取当前服务器时间（ISO 8601）。",
  "risk": "低",
  "confirm": false,
  "parameters": {"type": "object", "properties": {}},
  "returns": "{ now: \"2026-09-24T16:30:00+08:00\", timezone: \"Asia/Shanghai\" }"
}
```

### 6.2 `utility.list_tools`

```json
{
  "name": "utility.list_tools",
  "description": "列出当前用户可用的工具清单（含启用状态）。",
  "risk": "低",
  "confirm": false,
  "parameters": {"type": "object", "properties": {}},
  "returns": "Array<{ name, description, risk, requires_confirmation, enabled? }>"
}
```

### 6.3 `utility.help`

```json
{
  "name": "utility.help",
  "description": "获取帮助（自然语言命令示例）。",
  "risk": "低",
  "confirm": false,
  "parameters": {
    "type": "object",
    "properties": {
      "topic": {"type": "string", "enum": ["vault", "event", "finance", "trajectory", "general"]}
    }
  },
  "returns": "{ examples: Array<{ user_msg, assistant_action }> }"
}
```

### 6.4 `utility.cancel`

```json
{
  "name": "utility.cancel",
  "description": "取消当前正在执行的工具调用链（用户主动中断）。",
  "risk": "低",
  "confirm": false,
  "parameters": {"type": "object", "properties": {}},
  "returns": "{ status: \"cancelled\" }"
}
```

---

## §7 工具注册表客户端实现骨架

### §7.1 Web 端（`web/src/stores/agent.ts`）

```typescript
// 工具元数据（与上面的 JSON Schema 一一对应）
export interface AgentTool {
  name: string
  description: string
  risk: 'low' | 'medium' | 'high'
  requiresConfirmation: boolean
  parameters: JSONSchema  // JSON Schema 格式
  // 客户端本地执行器
  execute: (args: any) => Promise<any>
}

// 注册表（启动时注册到全局）
const tools: AgentTool[] = [
  {
    name: 'vault.search',
    description: '在用户的密码库中按关键词搜索条目...',
    risk: 'medium',
    requiresConfirmation: false,
    parameters: { /* JSON Schema */ },
    execute: async (args) => {
      const result = await financeVaultStore.search(args.query, args.limit)
      return result.map(r => ({ id: r.id, name: r.name, username: r.username, url: r.url }))
    }
  },
  // ... 其他 28 个工具
]

// 服务端发来 tool_calls 时
async function executeToolCall(call: ToolCall): Promise<ToolResult> {
  const tool = tools.find(t => t.name === call.function.name)
  if (!tool) return { tool_call_id: call.id, status: 'error', code: 'tool_not_whitelisted' }

  // 解析入参
  let args: any
  try { args = JSON.parse(call.function.arguments) }
  catch { return { tool_call_id: call.id, status: 'error', code: 'tool_args_invalid' } }

  // 二次确认
  if (tool.requiresConfirmation) {
    const confirmed = await showConfirmDialog(tool.name, args)  // Naive UI NPopconfirm
    if (!confirmed) return { tool_call_id: call.id, status: 'denied', reason: 'user_denied' }
  }

  // 真正执行
  try {
    const result = await tool.execute(args)
    // 写工具 result 强制脱敏
    const redacted = tool.risk === 'high' ? redactForLLM(result) : result
    return { tool_call_id: call.id, status: 'ok', content: JSON.stringify(redacted) }
  } catch (e) {
    return { tool_call_id: call.id, status: 'error', code: 'tool_execution_failed', message: e.message }
  }
}
```

### §7.2 Android 端（`AgentToolRegistry.kt`）

```kotlin
// 同款骨架，Kotlin 协程实现
class AgentToolRegistry(private val context: Context) {
    private val tools = mutableMapOf<String, AgentTool>()

    fun register(tool: AgentTool) { tools[tool.name] = tool }

    suspend fun executeToolCall(call: ToolCall): ToolResult {
        val t = tools[call.function.name] ?: return ToolResult.error(call.id, "tool_not_whitelisted")
        val args = try { JSONObject(call.function.arguments) } catch (e: JSONException) {
            return ToolResult.error(call.id, "tool_args_invalid")
        }

        if (t.requiresConfirmation) {
            val confirmed = withContext(Dispatchers.Main) {
                showConfirmDialog(context, t.name, args)  // AlertDialog
            }
            if (!confirmed) return ToolResult.denied(call.id)
        }

        return try {
            val result = t.execute(args)
            val redacted = if (t.risk == Risk.HIGH) redactForLLM(result) else result
            ToolResult.ok(call.id, redacted)
        } catch (e: Exception) {
            ToolResult.error(call.id, "tool_execution_failed", e.message ?: "")
        }
    }
}
```

---

## §8 引用

- [`ai-agent.md`](ai-agent.md) §4.1 工具注册总览 + §4.3 服务端白名单约束。
- [`ai-agent-prompts.md`](ai-agent-prompts.md) §2 工具调用协议 + §4 隐私脱敏。
- [`ai-agent-security.md`](ai-agent-security.md) §3 二次确认强制约束。
- [`finance.md`](finance.md) §12~§15：finance.create 字段规范。
- [`architecture.md`](architecture.md) §0：Agent 在 modules 层的位置。