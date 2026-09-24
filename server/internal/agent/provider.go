// Package agent 实现阶段 6 AI Agent 抽象层：Provider 接口 + 工具调用协议 +
// 解锁会话 + 审计 + 速率限制。服务端在本包内仅作为「透明路由器」，
// 绝不持久化任何 user_msg / assistant_msg / tool_args / tool_result 明文。
package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
)

// Role 是 ChatMessage 的角色枚举：OpenAI 兼容协议使用。
const (
	RoleSystem    = "system"
	RoleUser      = "user"
	RoleAssistant = "assistant"
	RoleTool      = "tool"
)

// FinishReason 是 ChatChoice 的终止原因枚举。
const (
	FinishStop          = "stop"
	FinishToolCalls     = "tool_calls"
	FinishLength        = "length"
	FinishContentFilter = "content_filter"
)

// Provider 是 LLM 供应商抽象接口。所有驱动（OpenAI 兼容 / Anthropic 兼容）
// 必须实现此接口。服务端通过 Registry 调度（见 registry.go）。
type Provider interface {
	// ChatCompletion 发起一次 LLM 对话，返回首个 choice 或错误。
	// 实现必须：
	//   - 强制超时（默认 60s，由调用方通过 ctx 控制）；
	//   - 失败时返回带 Code 字段的 Error（详见 errors.go）；
	//   - 不在错误信息中泄露用户明文 / API Key。
	ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error)

	// CountTokens 估算输入 token 数（用于速率限制 + 成本预估）。
	// 估算方法：按字符数近似（无 tokenizer 时 fallback）；
	// 或调用 Provider 的 tokenize 端点（可选）。
	CountTokens(req ChatRequest) int

	// Name Provider 唯一标识（用于审计 + 配置）。
	// 取值：openai_compat / anthropic_compat / stub。
	Name() string
}

// ChatRequest 是 Provider.ChatCompletion 的入参。
type ChatRequest struct {
	// Model 选定模型，如 "gpt-4o-mini" / "qwen2.5:7b" / "deepseek-chat"。
	Model string `json:"model"`
	// Messages 完整对话历史（system 在前）。
	Messages []ChatMessage `json:"messages"`
	// Tools 工具清单（OpenAI function calling 格式）。
	Tools []ToolSpec `json:"tools,omitempty"`
	// ToolChoice 工具选择策略："auto" / "none" / {"type":"function","name":"..."}。
	ToolChoice any `json:"tool_choice,omitempty"`
	// Temperature 采样温度（0~2）；nil 表示用 Provider 默认。
	Temperature *float64 `json:"temperature,omitempty"`
	// MaxTokens 最大输出 token；nil 表示不限。
	MaxTokens *int `json:"max_tokens,omitempty"`
	// Stream 流式开关（本期固定 false，留 v3+）。
	Stream bool `json:"-"`
	// Metadata 透传给 Provider 的元数据（不实现，并发空）。
	Metadata map[string]any `json:"-"`
}

// ChatMessage 是对话中的单条消息。
type ChatMessage struct {
	// Role 角色：system / user / assistant / tool。
	Role string `json:"role"`
	// Content 文本内容；assistant 角色若要发起工具调用则置空字符串。
	Content string `json:"content"`
	// Name tool 角色必填 = 工具名；其余角色可选。
	Name string `json:"name,omitempty"`
	// ToolCallID tool 角色必填 = 对应的 ToolCall.ID。
	ToolCallID string `json:"tool_call_id,omitempty"`
	// ToolCalls assistant 角色可选 = 发起的工具调用列表。
	ToolCalls []ToolCall `json:"tool_calls,omitempty"`
}

// ToolSpec 是工具定义（OpenAI function calling 格式）。
type ToolSpec struct {
	// Type 固定 "function"。
	Type string `json:"type"`
	// Function 工具函数描述。
	Function ToolFunction `json:"function"`
}

// ToolFunction 描述一个工具的入参。
type ToolFunction struct {
	// Name 工具唯一名（白名单约束；详见 Registry.AllowedTools）。
	Name string `json:"name"`
	// Description 自然语言描述（供 LLM 理解工具用途）。
	Description string `json:"description"`
	// Parameters JSON Schema 格式的参数定义（任意 JSON 对象）。
	Parameters json.RawMessage `json:"parameters"`
}

// ToolCall 是 assistant 角色发起的工具调用。
type ToolCall struct {
	// ID 工具调用唯一标识（client 回包时填回 ToolCallID）。
	ID string `json:"id"`
	// Type 固定 "function"。
	Type string `json:"type"`
	// Function 调用的函数详情。
	Function ToolCallFunc `json:"function"`
}

// ToolCallFunc 描述一次工具调用的目标函数。
type ToolCallFunc struct {
	// Name 工具名（白名单约束）。
	Name string `json:"name"`
	// Arguments JSON 字符串（客户端解析为对象后执行）。
	Arguments string `json:"arguments"`
}

// ChatResponse 是 Provider.ChatCompletion 的出参。
type ChatResponse struct {
	// ID 响应唯一标识（用于审计 + 调试）。
	ID string `json:"id"`
	// Model 实际使用的模型（可能与请求不一致）。
	Model string `json:"model"`
	// Choices 候选回复列表（OpenAI 协议下通常 1 个）。
	Choices []ChatChoice `json:"choices"`
	// Usage token 用量统计。
	Usage Usage `json:"usage"`
}

// ChatChoice 是单个候选回复。
type ChatChoice struct {
	// Index 候选索引。
	Index int `json:"index"`
	// Message assistant 角色的回复消息。
	Message ChatMessage `json:"message"`
	// FinishReason 终止原因：stop / tool_calls / length / content_filter。
	FinishReason string `json:"finish_reason"`
	// ToolCalls 同 Message.ToolCalls（OpenAI 双写）。
	ToolCalls []ToolCall `json:"tool_calls,omitempty"`
}

// Usage 是 token 用量统计。
type Usage struct {
	// PromptTokens 输入 token 数。
	PromptTokens int `json:"prompt_tokens"`
	// CompletionTokens 输出 token 数。
	CompletionTokens int `json:"completion_tokens"`
	// TotalTokens 总 token 数（= Prompt + Completion）。
	TotalTokens int `json:"total_tokens"`
}

// estimateTokens 粗略估算文本 token 数（中英文混合近似）。
// 规则：每 4 个 ASCII 字符约 1 token；每个 CJK 字符约 1 token。
// 用于无 tokenizer 时的兜底（CountTokens 的 fallback）。
func estimateTokens(s string) int {
	if s == "" {
		return 0
	}
	n := 0
	cjk := 0
	for _, r := range s {
		if r < 0x80 {
			n++
		} else {
			cjk++
		}
	}
	// ASCII 部分按 4 字符/token；CJK 按 1 字符/token。
	asciiTokens := (n + 3) / 4
	return asciiTokens + cjk
}

// TotalRequestTokens 估算整个 ChatRequest 的输入 token 数（含 system + 历史 + tool_calls 描述）。
// 用于速率限制 + 审计（不返回任何明文，仅字节数 / token 数）。
func TotalRequestTokens(req ChatRequest) int {
	total := 0
	for _, m := range req.Messages {
		total += estimateTokens(m.Content)
		total += estimateTokens(m.Name)
		// tool_calls 的 arguments 视为消息内容的一部分。
		for _, tc := range m.ToolCalls {
			total += estimateTokens(tc.Function.Arguments)
			total += estimateTokens(tc.Function.Name)
		}
	}
	// tools 描述本身也是 LLM 的输入（OpenAI 协议下计入 prompt）。
	for _, t := range req.Tools {
		total += estimateTokens(t.Function.Description)
		total += estimateTokens(t.Function.Name)
		total += estimateTokens(string(t.Function.Parameters))
	}
	return total
}

// ValidateMessage 校验 ChatMessage 的角色与必填字段。
// 返回 nil 表示合法；否则返回带 CodeInvalidRequest 的 Error（详见 errors.go）。
func ValidateMessage(m ChatMessage) error {
	switch m.Role {
	case RoleSystem, RoleUser, RoleAssistant, RoleTool:
	default:
		return NewError(CodeInvalidRequest, fmt.Sprintf("非法 role %q", m.Role), nil)
	}
	if m.Role == RoleTool {
		if m.Name == "" {
			return NewError(CodeInvalidRequest, "tool 消息缺 name", nil)
		}
		if m.ToolCallID == "" {
			return NewError(CodeInvalidRequest, "tool 消息缺 tool_call_id", nil)
		}
	}
	return nil
}

// ValidateRequest 校验整个 ChatRequest 的合法性（在调用 Provider 前必走）。
// 约束：
//   - Model 非空；
//   - Messages 非空且首条必须是 system；
//   - 每条消息角色合法；
//   - 工具名仅允许 [a-z0-9_.]；
//   - 单条 Content ≤ 4 KiB（防 DoS）。
// 所有失败均以 CodeInvalidRequest 返回，便于调用方统一处理。
func ValidateRequest(req ChatRequest) error {
	if strings.TrimSpace(req.Model) == "" {
		return NewError(CodeInvalidRequest, "Model 必填", nil)
	}
	if len(req.Messages) == 0 {
		return NewError(CodeInvalidRequest, "Messages 必填", nil)
	}
	if req.Messages[0].Role != RoleSystem {
		return NewError(CodeInvalidRequest, "Messages[0] 必须是 system", nil)
	}
	for i, m := range req.Messages {
		if err := ValidateMessage(m); err != nil {
			return NewError(CodeInvalidRequest, fmt.Sprintf("Messages[%d]: %s", i, err.Error()), err)
		}
		if len(m.Content) > 4096 {
			return NewError(CodeInvalidRequest, fmt.Sprintf("Messages[%d].Content 超过 4 KiB", i), nil)
		}
	}
	for i, t := range req.Tools {
		if t.Type != "function" {
			return NewError(CodeInvalidRequest, fmt.Sprintf("Tools[%d].Type 仅支持 function", i), nil)
		}
		if !isValidToolName(t.Function.Name) {
			return NewError(CodeInvalidRequest, fmt.Sprintf("Tools[%d].Function.Name 非法: %q", i, t.Function.Name), nil)
		}
	}
	return nil
}

// isValidToolName 校验工具名合法（仅允许小写字母、数字、下划线、点）。
func isValidToolName(name string) bool {
	if name == "" || len(name) > 64 {
		return false
	}
	for _, r := range name {
		switch {
		case r >= 'a' && r <= 'z':
		case r >= '0' && r <= '9':
		case r == '_' || r == '.':
		default:
			return false
		}
	}
	return true
}