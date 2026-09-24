# 阶段 6 — AI Agent 任务清单

> **阶段 6 调研任务（Task 1~12）**
> 状态：**调研完成，落地待启动**。
> 上游 spec：[`spec.md`](spec.md)（FR-V6-A~G + AC-V6-1~N）。
> 配套文档：
> - [`docs/ai-agent.md`](../../../docs/ai-agent.md)
> - [`docs/ai-agent-prompts.md`](../../../docs/ai-agent-prompts.md)
> - [`docs/ai-agent-tools.md`](../../../docs/ai-agent-tools.md)
> - [`docs/ai-agent-security.md`](../../../docs/ai-agent-security.md)

---

## 总览

| Task | 名称 | 依赖 | 状态 | 优先级 |
|---|---|---|---|---|
| **Task 1** | Provider 抽象层（服务端 Go 库） | 无 | **pending** | high |
| **Task 2** | 服务端 Agent 路由 + 审计 + 速率限制 | T1 | **pending** | high |
| **Task 3** | 服务端 Provider 驱动：OpenAI 兼容 + Ollama | T1 | **pending** | high |
| **Task 4** | 解锁会话抽象（Web + Android 共用） | T2 | **pending** | high |
| **Task 5** | Web 端 Chat Panel 骨架 + 工具注册表 | T2 + T4 | **pending** | high |
| **Task 6** | Web 端二次确认 + 脱敏 + 错误降级 | T5 | **pending** | high |
| **Task 7** | Web 端最小可对话（vault + finance 读工具） | T6 | **pending** | high |
| **Task 8** | Web 端写工具全接入（17 个） | T7 | **pending** | medium |
| **Task 9** | Android 端 AgentToolRegistry 骨架（仅接口） | T4 | **pending** | low |
| **Task 10** | 三端门禁复跑 + 端到端冒烟 | T1~T9 | **pending** | high |
| **Task 11** | 文档同步 + README/everything_plan 更新 | T10 | **pending** | high |
| **Task 12** | 异常模式检测 + 告警脚本 | T10 | **pending** | medium |

---

## Task 1: Provider 抽象层（服务端 Go 库）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: 无
- **Description**:
  - 在 `server/internal/agent/` 下新建 `provider.go`（接口）+ `chat.go`（请求 / 响应结构）；
  - 实现 `OpenAICompatible` 驱动（`provider_openai.go`）—— 可接 Ollama / OpenAI / DeepSeek / vLLM / LM Studio；
  - 实现 `AnthropicCompatible` 驱动（`provider_anthropic.go`，可选，v3+）；
  - 配置驱动：`config.yaml → agent.providers[]`，启动时注册；
  - 单元测试：`provider_test.go` 覆盖 mock LLM 响应解析。
- **TR 列表**:
  - TR-1.1 `provider.go` 接口定义（ChatCompletion / CountTokens / Name）
  - TR-1.2 `chat.go` ChatRequest / ChatResponse / ChatMessage / ToolSpec / ToolCall 结构
  - TR-1.3 `provider_openai.go` OpenAI 兼容驱动
  - TR-1.4 `provider_anthropic.go` Anthropic 兼容驱动（可选 stub）
  - TR-1.5 `provider_test.go` mock LLM 响应解析（10+ 用例）
  - TR-1.6 配置驱动启动逻辑（`config.yaml → providers[]` 注册）
  - TR-1.7 API Key 不入库（`api_key_source: user_settings` 标记必须 + 校验）

---

## Task 2: 服务端 Agent 路由 + 审计 + 速率限制

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T1
- **Description**:
  - 在 `server/internal/api/` 下新建 `agent.go` 路由：
    - `POST /api/v1/agent/chat` 接受 `{ session_id, user_msg, tool_history? }`；
    - `POST /api/v1/agent/tool-result` 接受 `{ session_id, tool_results: [...] }`；
    - `POST /api/v1/agent/cancel` 接受 `{ session_id }`；
    - `GET /api/v1/agent/sessions` 返回当前用户的所有会话元数据；
  - 在 `server/internal/agent/` 下新建 `proxy.go`（Agent Proxy）+ `audit.go`（审计 log）+ `ratelimit.go`（速率限制）；
  - 在 `server/migrations/` 下新建 `00021_agent_audit_log.up.sql` 建表；
  - 单元测试 + 集成测试。
- **TR 列表**:
  - TR-2.1 `agent.go` 路由 4 个 endpoint + JWT 中间件 + 解锁会话校验
  - TR-2.2 `proxy.go` Agent Proxy（路由 + 白名单 + 超时）
  - TR-2.3 `audit.go` 审计 log（**三不存**：不存 user_msg / assistant_msg / tool_args / tool_result 明文）
  - TR-2.4 `ratelimit.go` token bucket（Redis 后端，RPM / TPM / 并发 / 日工具）
  - TR-2.5 `00021_agent_audit_log.up.sql` 建表 + 2 个索引
  - TR-2.6 `agent_test.go` mock Provider + 单元测试（15+ 用例）
  - TR-2.7 集成测试（端到端：mock LLM + 客户端工具调用 + 审计）

---

## Task 3: 服务端 Provider 驱动：OpenAI 兼容 + Ollama

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T1
- **Description**:
  - 实现 `OpenAICompat` 驱动（`provider_openai.go` 完整版）：支持 Ollama + OpenAI + DeepSeek + vLLM + LM Studio；
  - 默认 Provider = `ollama`（`config.yaml → agent.default_provider: "ollama"`）；
  - 单元测试：mock HTTP 响应（10+ 用例）+ 错误降级（timeout / 429 / 500）。
- **TR 列表**:
  - TR-3.1 `provider_openai.go` HTTP 客户端 + OpenAI Chat Completions 协议
  - TR-3.2 工具调用协议（function calling JSON 序列化 / 反序列化）
  - TR-3.3 流式响应（v3+，本期留接口 stub）
  - TR-3.4 mock HTTP 测试（10+ 用例：成功 / 超时 / 429 / 500 / 401）
  - TR-3.5 配置驱动启动（Ollama 默认 + 用户切换 Provider）

---

## Task 4: 解锁会话抽象（Web + Android 共用）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T2
- **Description**:
  - 在 `server/internal/agent/` 下新建 `session.go`（会话状态机 + JWT 解锁 token）；
  - 在 `web/src/stores/` 下新建 `agentSession.ts`（Web 端会话状态机）；
  - 在 `android/app/src/main/java/com/everything/eve/agent/` 下新建 `AgentSession.kt`（Android 端 stub，本期仅接口）；
  - 单元测试：会话过期 / 续签 / 多设备隔离。
- **TR 列表**:
  - TR-4.1 `session.go` 会话状态机（LOCKED ↔ UNLOCKED）+ session_id 派生（device_id + random）
  - TR-4.2 `agentSession.ts` Web 端 store（15 分钟过期 + 续签 + 强制解锁）
  - TR-4.3 `AgentSession.kt` Android 端 stub（本期仅接口）
  - TR-4.4 会话过期 JWT 校验（401 → 客户端跳解锁页）
  - TR-4.5 多设备隔离（device_id 校验）
  - TR-4.6 `agentSession_test.ts` 单元测试（10+ 用例）

---

## Task 5: Web 端 Chat Panel 骨架 + 工具注册表

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T2 + T4
- **Description**:
  - 在 `web/src/components/agent/` 下新建 `AgentChatPanel.vue`（抽屉 + 侧边栏 + 独立页 三态可配置）；
  - 在 `web/src/stores/` 下新建 `agent.ts`（消息历史 + 工具调用 + Provider 选择）；
  - 在 `web/src/types/agent.ts` 定义 29 个工具的 TypeScript 接口（基于 [`docs/ai-agent-tools.md`](../../../docs/ai-agent-tools.md)）；
  - 集成 Naive UI `NPopconfirm` / `NPopover` / `NScrollbar` 等组件；
  - 单元测试 + 组件测试。
- **TR 列表**:
  - TR-5.1 `AgentChatPanel.vue` UI 骨架（消息列表 + 输入框 + Provider 选择 + 工具清单展示）
  - TR-5.2 `agent.ts` Pinia store（消息历史 + 工具调用栈 + 解锁会话状态）
  - TR-5.3 `types/agent.ts` TypeScript 接口（29 个工具）
  - TR-5.4 工具注册表骨架（29 个工具的元数据 + 执行器空函数）
  - TR-5.5 `AgentChatPanel.test.ts` 组件测试（10+ 用例）
  - TR-5.6 `stores/agent.test.ts` store 测试（10+ 用例）

---

## Task 6: Web 端二次确认 + 脱敏 + 错误降级

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T5
- **Description**:
  - 在 `web/src/stores/agent.ts` 实现 `executeToolCall`（解析入参 + 二次确认 + 执行 + 脱敏 + 错误处理）；
  - 在 `web/src/utils/` 下新建 `agentRedact.ts`（`redactForLLM` 函数实现，按 [`docs/ai-agent-prompts.md`](../../../docs/ai-agent-prompts.md) §4 规则）；
  - 在 `web/src/components/agent/` 下新建 `AgentConfirmDialog.vue`（写工具二次确认弹窗，5 秒倒计时）；
  - 单元测试 + E2E 测试。
- **TR 列表**:
  - TR-6.1 `agent.ts` executeToolCall 完整版（含白名单 / 入参校验 / 二次确认 / 执行 / 脱敏 / 错误码）
  - TR-6.2 `agentRedact.ts` redactForLLM 函数（policy_number / card_number / password / token / secret / 邮箱 / 手机 / 地址 / 主密码）
  - TR-6.3 `AgentConfirmDialog.vue` 二次确认弹窗（5 秒倒计时「同意」置灰）
  - TR-6.4 错误降级（401 → 跳解锁页 / 429 → 倒计时 / Provider 故障 → 切换 / 超时 → 重试）
  - TR-6.5 `agentRedact.test.ts` 单元测试（每个字段类型 1 用例 = 9+ 用例）
  - TR-6.6 `executeToolCall.test.ts` 单元测试（15+ 用例：成功 / 用户拒绝 / 入参非法 / 执行失败 / 超时）

---

## Task 7: Web 端最小可对话（vault + finance 读工具）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T6
- **Description**:
  - 接入 vault.search / vault.list_folders（读工具，2 个）；
  - 接入 finance.search / finance.aggregate（读工具，2 个）；
  - 接入 event.search（读工具，1 个）；
  - 接入 trajectory.search / trajectory.aggregate（读工具，2 个）；
  - 接入 utility.now / utility.list_tools / utility.help（工具，3 个）；
  - 端到端冒烟脚本（E2E 测试 ≥10 场景）；
  - 单元测试 + 集成测试。
- **TR 列表**:
  - TR-7.1 `vault.search` / `vault.list_folders` 工具执行器（接入阶段 2 vault 既有 search/get/list）
  - TR-7.2 `finance.search` / `finance.aggregate` 工具执行器（接入阶段 5 v2 finance 既有 search/aggregator）
  - TR-7.3 `event.search` 工具执行器（接入阶段 4b event 既有 search）
  - TR-7.4 `trajectory.search` / `trajectory.aggregate` 工具执行器（接入阶段 4a trajectory 既有 search/aggregator）
  - TR-7.5 `utility.now` / `utility.list_tools` / `utility.help` 工具执行器
  - TR-7.6 E2E 冒烟脚本 `docs/smoke/stage6-ai-agent-e2e.md`（≥10 场景：vault.search 关键词 / finance.aggregate 月报 / event.search 时间窗 / utility.list_tools / 等等）
  - TR-7.7 Web 47 文件 694 用例 vitest 全绿（含新增 50+ 用例）

---

## Task 8: Web 端写工具全接入（17 个）

- **Status**: `pending`
- **Priority**: medium
- **Depends On**: T7
- **Description**:
  - 接入 vault.create / vault.update / vault.delete（写工具，3 个）；
  - 接入 identity.revoke_device（写工具，1 个）；
  - 接入 event.create / event.update / event.delete（写工具，3 个）；
  - 接入 finance.create / finance.update / finance.delete（写工具，3 个）；
  - 接入 item 写工具（待阶段 7 / v3 启用，本期预留接口）；
  - 二次确认 + 脱敏 + 错误处理；
  - 单元测试 + E2E 测试。
- **TR 列表**:
  - TR-8.1 `vault.create` / `vault.update` / `vault.delete` 工具执行器（接入阶段 2 vault 既有 CRUD）
  - TR-8.2 `identity.revoke_device` 工具执行器（接入阶段 2 identity 既有 revoke）
  - TR-8.3 `event.create` / `event.update` / `event.delete` 工具执行器（接入阶段 4b event 既有 CRUD）
  - TR-8.4 `finance.create` / `finance.update` / `finance.delete` 工具执行器（接入阶段 5 v2 finance 既有 CRUD）
  - TR-8.5 E2E 冒烟脚本扩展（≥15 场景，含写工具二次确认全流程）
  - TR-8.6 Web 47 文件 750+ 用例 vitest 全绿（含新增 50+ 用例）
  - TR-8.7 写工具 result 强制脱敏验证（policy_number → `***5678` 等）

---

## Task 9: Android 端 AgentToolRegistry 骨架（仅接口）

- **Status**: `pending`
- **Priority**: low
- **Depends On**: T4
- **Description**:
  - 在 `android/app/src/main/java/com/everything/eve/agent/` 下新建：
    - `AgentToolRegistry.kt`（工具注册表接口 + 抽象执行器）；
    - `AgentSession.kt`（解锁会话 stub）；
    - `AgentRedact.kt`（脱敏函数骨架）；
  - 本期**仅留接口**，UI 实现留待阶段 7 / v3。
- **TR 列表**:
  - TR-9.1 `AgentToolRegistry.kt` 接口 + 注册骨架
  - TR-9.2 `AgentSession.kt` 解锁会话 stub
  - TR-9.3 `AgentRedact.kt` 脱敏函数骨架
  - TR-9.4 Android 编译通过（compileDebugKotlin）

---

## Task 10: 三端门禁复跑 + 端到端冒烟

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T1~T9
- **Description**:
  - 服务端 `go test ./...` 全包 ok；
  - Web 47 文件 800+ 用例 vitest 全绿 + `vue-tsc --noEmit` 0 错误；
  - Android `compileDebugKotlin` + ≥550 用例测 `testDebugUnitTest --rerun-tasks` 0 失败；
  - E2E 冒烟 ≥15 场景（含读工具 + 写工具 + 二次确认 + 错误降级）；
  - 零知识 grep（服务端 / Android / Web）全绿。
- **TR 列表**:
  - TR-10.1 服务端 `go test ./...` 全包 ok
  - TR-10.2 Web vitest 全绿（≥800 用例）
  - TR-10.3 Web `vue-tsc --noEmit` 0 错误
  - TR-10.4 Android 编译 + 单元测试（≥550 用例）
  - TR-10.5 E2E 冒烟脚本（≥15 场景）
  - TR-10.6 零知识 grep（user_msg / assistant_msg / tool_args / tool_result / MK / 主密码 / API Key）

---

## Task 11: 文档同步 + README/everything_plan 更新

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T10
- **Description**:
  - `README.md`：阶段 6 进度行 + AI Agent 节标题升级 + 新增「### AI Agent 架构」子节 + 功能矩阵表追加 Agent 行 + 已知问题节追加 v6 限制；
  - `everything_plan.md`：阶段 6 行 ✅；
  - `docs/architecture.md`：架构图更新（modules 层 agent 模块）；
  - `docs/crypto.md`：§5 records 通道补充「Agent 不绕过」；
  - `docs/finance.md`：§16 新增「Agent 编辑器帮填（v3 候选 #8，本期不实现）」；
  - `stage-summary.md` §八 下一步更新。
- **TR 列表**:
  - TR-11.1 `README.md` 阶段 6 进度行 + AI Agent 子节 + v6 限制
  - TR-11.2 `everything_plan.md` 阶段 6 行 ✅
  - TR-11.3 `docs/architecture.md` 架构图 agent 模块
  - TR-11.4 `docs/crypto.md` §5 records 通道 + Agent 不绕过说明
  - TR-11.5 `docs/finance.md` §16 Agent 编辑器帮填留接口
  - TR-11.6 `stage-summary.md` §八 下一步更新

---

## Task 12: 异常模式检测 + 告警脚本

- **Status**: `pending`
- **Priority**: medium
- **Depends On**: T10
- **Description**:
  - 在 `server/scripts/` 下新建 `agent-audit-detect.sh`（异常模式检测）：
    - 单用户 1 小时 RPM 超限 → 邮件告警 + 锁定 5 分钟；
    - 单用户 1 小时调用 5+ 写工具 → 邮件告警；
    - 单会话工具调用链 > 10 → 自动断开；
    - 同一 `tool_args_hash` 1 分钟内重复 > 3 → 卡死重试提示；
    - 用户确认 < 200ms 写工具连续 5 次 → 弹出二次密码确认；
  - 在 `server/internal/agent/` 下新建 `detect.go` 实时检测（异步 goroutine）；
  - 单元测试 + 集成测试。
- **TR 列表**:
  - TR-12.1 `agent-audit-detect.sh` 异常模式检测脚本
  - TR-12.2 `detect.go` 实时检测（每 5 分钟跑一次扫描）
  - TR-12.3 邮件告警（接入现有 SMTP 配置）
  - TR-12.4 锁定机制（5 分钟 / 二次密码确认）
  - TR-12.5 `detect_test.go` 单元测试（异常请求序列 → 验证告警触发）

---

## 引用

- [`spec.md`](spec.md)：FR-V6-A~G + AC-V6-1~N。
- [`../../../docs/ai-agent.md`](../../../docs/ai-agent.md)
- [`../../../docs/ai-agent-prompts.md`](../../../docs/ai-agent-prompts.md)
- [`../../../docs/ai-agent-tools.md`](../../../docs/ai-agent-tools.md)
- [`../../../docs/ai-agent-security.md`](../../../docs/ai-agent-security.md)