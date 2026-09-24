# 阶段 6 — AI Agent 规格说明

> **阶段 6 调研规格（FR-V6-A~G + AC-V6-1~N）**
> 状态：调研稿 / 规划，未落地代码。
> 上游：阶段 1–5（事件 / 密码库 / 财务 / 轨迹 / 物品）records 密文通道 + 端侧纯函数。
> 配套文档：
> - [`docs/ai-agent.md`](../../../docs/ai-agent.md)：架构 + Provider 抽象 + 工具注册表 + 解锁会话 + 审计。
> - [`docs/ai-agent-prompts.md`](../../../docs/ai-agent-prompts.md)：系统提示词 + 工具调用协议 + Few-shot。
> - [`docs/ai-agent-tools.md`](../../../docs/ai-agent-tools.md)：29 个工具的 JSON Schema。
> - [`docs/ai-agent-security.md`](../../../docs/ai-agent-security.md)：零知识边界 + 隐私红线 + 速率限制。

---

## §0 一句话定位

Agent 是「**用户授权下的端侧+服务端混合**自然语言入口」：
- **服务端只做调度、审计、白名单转发**（透明路由器）；
- **真正解密与读写发生在已解锁客户端**（Web Pinia store / Android Repository）；
- **LLM 调用走 OpenAI Chat Completions 兼容协议**，Provider 可换（Ollama / OpenAI / DeepSeek / Anthropic / vLLM）；
- **默认本地 Ollama 自托管**，云端 API 由用户在设置中自填 Key（不落服务端配置 / DB / 审计）。

---

## §1 FR（功能需求）

### FR-V6-A：服务端 Provider 抽象层
- 提供 `Provider` 抽象接口（`ChatCompletion` / `CountTokens` / `Name`）；
- 默认实现 OpenAI 兼容协议驱动 → 兼容 Ollama + OpenAI + DeepSeek + Anthropic（兼容模式）+ vLLM；
- 配置驱动：服务端 `config.yaml → agent.providers[]` 列表，启动时注册；
- 用户 API Key 不入库（`api_key_source: user_settings` 标记，由客户端单次回传服务端内存使用）。

### FR-V6-B：服务端 Agent 路由 + 审计
- 路由：`POST /api/v1/agent/chat` + `POST /api/v1/agent/tool-result` + `POST /api/v1/agent/cancel` + `GET /api/v1/agent/sessions`；
- 速率限制：RPM / TPM / 并发会话 / 单日工具调用；
- 审计 log（agent_audit_log）：仅元数据（不存明文 user_msg / assistant_msg / tool_args / tool_result）；
- 超时：服务端 LLM 调用 60s + 客户端单工具 15 分钟；
- 错误码：详见 [`ai-agent-security.md`](../../../docs/ai-agent-security.md) §7.1。

### FR-V6-C：Web 端 Chat Panel（最小可对话）
- Vue3 + Naive UI 新增 `AgentChatPanel.vue`（侧边栏 / 抽屉 / 独立页 三态可配置）；
- 集成 `stores/agent.ts`：消息历史 + 解锁会话状态 + Provider 选择 + 工具清单展示；
- 工具注册表（29 个，详见 [`ai-agent-tools.md`](../../../docs/ai-agent-tools.md)）；
- 二次确认弹窗（`NPopconfirm`，5 秒倒计时「同意」按钮置灰）。

### FR-V6-D：Android 端 Chat Panel（Phase 2，本期仅留接口）
- Compose 新增 `AgentChatScreen.kt`（本期不实现 UI，仅留路由 + 接口）；
- `AgentToolRegistry.kt` 注册表骨架（与 Web 端同款接口）；
- 本期**留待阶段 7 / v3**，阶段 6 仅落地 Web 端。

### FR-V6-E：解锁会话（Web + Android 共用抽象）
- 会话状态机：`LOCKED ↔ UNLOCKED`；
- 会话时长：默认 15 分钟无操作过期（可设置 5~60 分钟）；
- 强制续期：每次成功调用工具后服务端返回 `session_expires_at`；
- 多设备隔离：`session_id = device_id + session_random`，独立会话。

### FR-V6-F：端侧工具注册表 + 二次确认 + 脱敏
- 客户端实现工具执行器（Web `stores/agent.ts` + Android `AgentToolRegistry.kt`）；
- 写工具（17 个）必弹二次确认；
- 写工具 result 强制脱敏（`redactForLLM`，详见 [`ai-agent-prompts.md`](../../../docs/ai-agent-prompts.md) §4）；
- 读工具默认信任用户授权，但仍审计。

### FR-V6-G：v3 候选 #8 编辑器 AI 帮填（本期不实现）
- 在 `SubscriptionEditorDialog.vue` / `PolicyEditorDialog.kt` 等编辑器顶部加「AI 帮填」按钮；
- Agent 调用 `finance.create` 工具（**二次确认**）→ 客户端跳转编辑器已预填态 → 用户微调 → 提交；
- 本期**留待 v3**，阶段 6 仅落 FR-V6-A~F-V6。

---

## §2 AC（验收清单）

### AC-V6-1：服务端 Provider 注册
- 启动时读取 `config.yaml → agent.providers[]`，按驱动注册（OpenAI 兼容驱动默认）；
- 默认 Provider = `ollama`（本地），可在 `config.yaml` 覆盖；
- 用户 API Key 不入库（`api_key_source: user_settings` 标记必须 + 启动时校验：若云端 Provider 配置为 `api_key_source: user_settings` 则密钥回传路径必须存在）。

### AC-V6-2：服务端 Agent 路由
- `POST /api/v1/agent/chat` 接受 `{ session_id, user_msg, tool_history? }`，返回 `{ tool_calls?, assistant_msg?, usage, session_expires_at }`；
- `POST /api/v1/agent/tool-result` 接受 `{ session_id, tool_results: [...] }`，返回同上；
- `POST /api/v1/agent/cancel` 接受 `{ session_id }`，返回 `{ status: "cancelled" }`；
- `GET /api/v1/agent/sessions` 返回当前用户的所有会话元数据（仅元数据）。

### AC-V6-3：服务端审计三不存
- `agent_audit_log` 表中**不存在** `user_msg_content` / `assistant_msg_content` 字段；
- 仅 `user_msg_bytes` / `assistant_msg_bytes` / `tool_args_bytes` / `tool_result_bytes` 字段；
- 单元测试断言：`SELECT * FROM agent_audit_log WHERE ...` 返回行中**无明文**；
- 服务端日志（`stdout` / `stderr`）**不出现** user_msg / assistant_msg / tool_args / tool_result 明文（CI grep 校验）。

### AC-V6-4：服务端白名单约束
- 客户端声明的 tool_name **必须在** 服务端 `config.yaml → agent.allowed_tools[]` 内；
- 否则返回 400 + 错误码 `tool_not_whitelisted`；
- 单元测试覆盖：白名单外工具调用 → 拒绝。

### AC-V6-5：服务端速率限制
- RPM 超限 → 429 + 错误码 `rate_limited(rpm)`；
- TPM 超限 → 429 + 错误码 `rate_limited(tpm)`；
- 并发会话超限 → 429 + 错误码 `rate_limited(concurrent)`；
- 单日工具调用超限 → 429 + 错误码 `rate_limited(daily)`；
- 单元测试覆盖：注入 60+ RPM → 后续请求全被拒。

### AC-V6-6：服务端超时
- LLM 调用 60s 超时 → 返回 `agent.timeout` 错误码 + 服务端审计 `status="timeout"`；
- 客户端单工具 15 分钟超时 → 自动 retry 一次，仍失败则返回 `tool_timeout` 错误码；
- 单元测试覆盖：mock LLM Provider 阻塞 70s → 服务端 60s 切断。

### AC-V6-7：Web 端 Chat Panel 最小可对话
- 用户在 Chat Panel 输入「我的 github 密码是什么？」→ 客户端调用 `vault.search` → LLM 回复（不含密码明文，只含「用户名 alice」+「请在密码库详情页查看」）；
- 用户在 Chat Panel 输入「帮我创建一个 Netflix 订阅，每月 25 元」→ 客户端弹二次确认 → 用户同意 → 客户端调用 `finance.create` → LLM 回复「已创建 Netflix 订阅（每月 25 元，下次扣费 2026-10-15）」；
- 单元测试覆盖：UI 渲染 + 工具调用 + 二次确认 + 脱敏。

### AC-V6-8：Web 端二次确认强制
- 17 个写工具全部弹窗（`vault.create/update/delete` + `identity.revoke_device` + `event.create/update/delete` + `finance.create/update/delete` 等）；
- 弹窗显示脱敏摘要（policy_number 显示 `***5678` 等）；
- 「同意」按钮 5 秒置灰；
- 用户拒绝 → 客户端返回 `tool_denied` 错误码 → LLM 礼貌回复；
- E2E 测试覆盖：mock LLM 返回写工具调用 → 用户拒绝 → 服务端收到 `tool_denied`。

### AC-V6-9：Web 端解锁会话
- Web 端 15 分钟无操作 → 自动锁屏；
- 服务端返回 `session_expires_at` → 客户端自动续期；
- 会话过期 → 客户端跳解锁页；
- 多设备隔离：A 设备锁定不影响 B 设备解锁状态；
- 单元测试覆盖：mock 15 分钟超时 → 客户端跳解锁页。

### AC-V6-10：端侧脱敏
- 写工具 result 在序列化前**强制**走 `redactForLLM` 函数；
- policy_number → `***5678`；
- card_number → `***7890`；
- password / token / secret → `"已设置"`；
- 邮箱 → `a***@example.com`；
- 手机 → `138****5678`；
- 主密码 / MK → 字段删除（不留 `"已保存"`，彻底删除）；
- 单元测试覆盖：每个字段类型都有脱敏测试用例。

### AC-V6-11：服务端 / 客户端零知识纪律
- 服务端 grep `user_msg|assistant_msg|tool_args|tool_result` 命中位置**全部**为字段定义 / 接口签名 / 测试断言（**无明文落盘**）；
- 客户端 `localStorage` / `IndexedDB` / `SharedPreferences` / `DataStore` 不存 user_msg / assistant_msg 明文（仅存 `agent_local_audit` 表中 + 用户可手动清理）；
- Android 日志（`Log.d` / `Log.e`）不含明文 user_msg / assistant_msg；
- CI 零知识 grep 脚本：`scripts/zerosgrep.sh`（详见 §6.1）。

### AC-V6-12：异常模式检测（服务端定期审计）
- 单用户 1 小时 RPM 超限 → 邮件告警 + 锁定 5 分钟；
- 单用户 1 小时调用 5+ 写工具 → 邮件告警；
- 单会话工具调用链 > 10 → 自动断开；
- 同一 `tool_args_hash` 1 分钟内重复 > 3 → 卡死重试提示；
- 用户确认 < 200ms 写工具连续 5 次 → 弹出二次密码确认；
- 单元测试覆盖：mock 异常请求序列 → 验证告警触发。

### AC-V6-13：v1–v5 零回归
- 阶段 1–5 全部 558+ Android 用例通过；
- 阶段 1–5 全部 694 Web 用例通过；
- 服务端 `go test ./...` 全包 ok；
- 阶段 5 v2 冒烟 16 场景全过。

### AC-V6-14：文档同步
- `docs/ai-agent.md` ✅（本文档配套）；
- `docs/ai-agent-prompts.md` ✅；
- `docs/ai-agent-tools.md` ✅；
- `docs/ai-agent-security.md` ✅；
- `everything_plan.md` 阶段 6 行 ✅；
- `stage-summary.md` §八 下一步 ✅；
- `README.md` 阶段 6 进度行 + 功能矩阵 + 已知问题（**任务 11 落地时**追加）。

---

## §3 不做（按规格留待后续）

| 项 | 留待 |
|---|---|
| Android 端 Chat Panel UI | 阶段 7 / v3 |
| 编辑器 AI 帮填（v3 候选 #8） | v3 启动时 |
| LLM 流式响应（Web SSE）| v3 启动时 |
| Web RRULE 全档 + Agent 联动（v3 候选 #7） | v3 启动时 |
| 投资账户行情自动同步（v3 候选 #3） | v3 启动时 |
| 预算阈值随 records 同步（v3 候选 #4） | v3 启动时 |
| 多端同步 quote 上行 records（v3 候选 #5） | v3 启动时 |
| 服务端 OpenAPI 公开金融只读聚合（v3 候选 #2） | v3 启动时 |
| Anthropic 原生协议（非 OpenAI 兼容模式） | v3+ |
| 云端 LLM API Key 加密落盘（用户可信当下入 KDF） | v3+ |

---

## §4 验收依赖

- **T19 / T20（任务书）**：见 [`tasks.md`](tasks.md) —— Task 7~12 落地路径。
- **依赖既有**：
  - 阶段 1–5 全部 records 通道（`docs/crypto.md` §5 AAD）；
  - 阶段 2 vault 既有条目 CRUD（用于 `vault.search` / `vault.get` / `vault.create` 等工具）；
  - 阶段 4b event 既有条目 CRUD（用于 `event.search` / `event.create` 等）；
  - 阶段 5 v2 finance 既有条目 CRUD + 投资账户 + 手动行情（用于 `finance.search` / `finance.create` 等）；
  - 阶段 4a trajectory 既有路径加密 + 端侧聚合（用于 `trajectory.search` / `trajectory.aggregate`）。

---

## §5 引用

- [`docs/ai-agent.md`](../../../docs/ai-agent.md)
- [`docs/ai-agent-prompts.md`](../../../docs/ai-agent-prompts.md)
- [`docs/ai-agent-tools.md`](../../../docs/ai-agent-tools.md)
- [`docs/ai-agent-security.md`](../../../docs/ai-agent-security.md)
- [`docs/architecture.md`](../../../docs/architecture.md)
- [`docs/crypto.md`](../../../docs/crypto.md)
- [`docs/finance.md`](../../../docs/finance.md)