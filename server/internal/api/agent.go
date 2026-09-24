package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/everything-personal/eve/internal/agent"
)

// ---- Agent HTTP 路由（阶段 6 Task 2，TR-2.1）----
//
// 端点：
//   POST /api/v1/agent/chat         { session_id, user_msg, tool_history?, provider?, model? }
//   POST /api/v1/agent/tool-result  { session_id, tool_results: [...] }   （Task 6/7 由前端执行工具后回调；Task 2 仅接收并审计）
//   POST /api/v1/agent/cancel        { session_id }                        （Task 2 仅返回 ok；Task 5+ 接入取消语义）
//   GET  /api/v1/agent/sessions                                              （返回当前用户最近 50 个会话元数据）
//
// 三不存契约（HTTP 层）：
//   - user_msg 仅出现在请求体内存中；
//   - 错误响应绝不回显 user_msg / tool_args 明文；
//   - 所有错误信息走统一 agent.ErrorCode → HTTP 状态码映射。
//
// 仅 approved scope 可访问；session 未解锁时一律 401 agent.session_locked。

// chatRequestBody 是 /agent/chat 的入参。
type chatRequestBody struct {
	SessionID   string             `json:"session_id"`
	Provider    string             `json:"provider,omitempty"`
	Model       string             `json:"model,omitempty"`
	UserMessage string             `json:"user_msg"`
	ToolHistory []agent.ChatMessage `json:"tool_history,omitempty"`
}

// chatResponseBody 是 /agent/chat 的出参（不含任何服务端内部状态）。
type chatResponseBody struct {
	Provider         string             `json:"provider"`
	Model            string             `json:"model"`
	AssistantText    string             `json:"assistant_text"`
	ToolCalls        []agent.ToolCall   `json:"tool_calls,omitempty"`
	PromptTokens     int                `json:"prompt_tokens"`
	CompletionTokens int                `json:"completion_tokens"`
	TotalTokens      int                `json:"total_tokens"`
	LatencyMs        int64              `json:"latency_ms"`
}

// toolResultRequestBody 是 /agent/tool-result 的入参（Task 6/7 前仅落审计）。
type toolResultRequestBody struct {
	SessionID    string `json:"session_id"`
	ToolCallID   string `json:"tool_call_id"`
	ToolName     string `json:"tool_name"`
	ToolArgsJSON string `json:"tool_args"`
	ResultKind   string `json:"result_kind"` // redacted_summary / error / ...
	Detail       string `json:"detail,omitempty"`
}

// cancelRequestBody 是 /agent/cancel 的入参。
type cancelRequestBody struct {
	SessionID string `json:"session_id"`
}

// sessionsResponseBody 是 /agent/sessions 的出参。
type sessionsResponseBody struct {
	Sessions []agent.AuditSession `json:"sessions"`
}

// agentErrorResponse 是 Agent 模块错误的统一出口。
type agentErrorResponse struct {
	Error     string `json:"error"`
	Message   string `json:"message"`
	ErrorCode string `json:"error_code,omitempty"`
}

// agentChat 处理 /agent/chat：服务端透明路由器，转发到 LLM Provider。
func (s *Server) agentChat(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var body chatRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.SessionID == "" {
		writeAgentError(w, http.StatusBadRequest, "session_required", "session_id 必填", "agent.session_required")
		return
	}
	if body.UserMessage == "" {
		writeAgentError(w, http.StatusBadRequest, "user_msg_required", "user_msg 必填", "agent.invalid_request")
		return
	}
	ctx := context.Background()
	if s.agentProxy == nil {
		writeAgentError(w, http.StatusServiceUnavailable, "agent_disabled", "Agent 模块未启用", "agent.internal")
		return
	}
	out, err := s.agentProxy.Chat(ctx, agent.ChatTurnInput{
		UserID:      claims.UserID,
		DeviceID:    claims.DeviceID,
		SessionID:   body.SessionID,
		IP:          clientIP(r),
		Provider:    body.Provider,
		Model:       body.Model,
		UserMessage: body.UserMessage,
		ToolHistory: body.ToolHistory,
	})
	if err != nil {
		writeAgentProxyError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, chatResponseBody{
		Provider:         out.Provider,
		Model:            out.Model,
		AssistantText:    out.AssistantText,
		ToolCalls:        out.ToolCalls,
		PromptTokens:     out.PromptTokens,
		CompletionTokens: out.CompletionTokens,
		TotalTokens:      out.TotalTokens,
		LatencyMs:        out.LatencyMs,
	})
}

// agentToolResult 处理 /agent/tool-result：客户端执行工具后回调（Task 2 范围内仅落审计）。
func (s *Server) agentToolResult(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var body toolResultRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.SessionID == "" || body.ToolCallID == "" || body.ToolName == "" {
		writeAgentError(w, http.StatusBadRequest, "bad_request", "session_id / tool_call_id / tool_name 必填", "agent.invalid_request")
		return
	}
	if s.agentProxy == nil || s.agentProxy.Audit() == nil {
		writeAgentError(w, http.StatusServiceUnavailable, "agent_disabled", "Agent 模块未启用", "agent.internal")
		return
	}
	// 客户端执行工具后回调：仅落审计，不驱动 LLM（写工具由前端确认 / 脱敏 / 执行，
	// 服务端不做任何透明转发，避免把工具入参/结果明文滞留内存）。
	s.agentProxy.Audit().Write(r.Context(), agent.AuditEvent{
		UserID:         claims.UserID,
		DeviceID:       claims.DeviceID,
		SessionID:      body.SessionID,
		Event:          agent.EventToolCompleted,
		Status:         agent.StatusOK,
		ToolName:       body.ToolName,
		ToolArgsJSON:   body.ToolArgsJSON, // 内部立即 SHA-256，落库前清零
		ToolResultKind: body.ResultKind,
		IP:             clientIP(r),
		Detail:         truncate(body.Detail, 200),
	})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// agentCancel 处理 /agent/cancel：Task 2 仅返回 ok；
// 真正的取消语义留待 Task 5+ 接入 Proxy 的 ctx 取消通道。
func (s *Server) agentCancel(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var body cancelRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.SessionID == "" {
		writeAgentError(w, http.StatusBadRequest, "bad_request", "session_id 必填", "agent.invalid_request")
		return
	}
	if s.agentProxy != nil && s.agentProxy.Audit() != nil {
		s.agentProxy.Audit().Write(r.Context(), agent.AuditEvent{
			UserID:    claims.UserID,
			DeviceID:  claims.DeviceID,
			SessionID: body.SessionID,
			Event:     agent.EventCancelled,
			Status:    agent.StatusOK,
			IP:        clientIP(r),
			Detail:    "<redacted>",
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// agentListSessions 处理 GET /agent/sessions。
func (s *Server) agentListSessions(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	if s.agentProxy == nil || s.agentProxy.Audit() == nil {
		writeJSON(w, http.StatusOK, sessionsResponseBody{Sessions: []agent.AuditSession{}})
		return
	}
	sessions, err := s.agentProxy.Audit().ListSessions(r.Context(), claims.UserID, 50)
	if err != nil {
		writeAgentError(w, http.StatusInternalServerError, "internal", "读取会话列表失败", "agent.internal")
		return
	}
	writeJSON(w, http.StatusOK, sessionsResponseBody{Sessions: sessions})
}

// ---- Agent 错误码 → HTTP 状态码映射 ----

func writeAgentProxyError(w http.ResponseWriter, err error) {
	var ae *agent.Error
	if !errors.As(err, &ae) {
		writeAgentError(w, http.StatusInternalServerError, "internal", "Agent 调用失败", "agent.internal")
		return
	}
	code := agent.ErrorCodeName(ae.Code)
	switch ae.Code {
	case agent.CodeSessionLocked:
		writeAgentError(w, http.StatusUnauthorized, "session_locked", "Agent 会话未解锁或已过期", code)
	case agent.CodeRateLimited:
		writeAgentError(w, http.StatusTooManyRequests, "rate_limited", ae.Message, code)
	case agent.CodeInvalidRequest:
		writeAgentError(w, http.StatusBadRequest, "invalid_request", ae.Message, code)
	case agent.CodeContextTooLong:
		writeAgentError(w, http.StatusRequestEntityTooLarge, "context_too_long", ae.Message, code)
	case agent.CodeTimeout:
		writeAgentError(w, http.StatusGatewayTimeout, "timeout", ae.Message, code)
	case agent.CodeProviderError:
		writeAgentError(w, http.StatusBadGateway, "provider_error", ae.Message, code)
	default:
		writeAgentError(w, http.StatusInternalServerError, "internal", ae.Message, code)
	}
}

func writeAgentError(w http.ResponseWriter, status int, code, message, errorCode string) {
	writeJSON(w, status, agentErrorResponse{
		Error:     code,
		Message:   message,
		ErrorCode: errorCode,
	})
}

// clientIP 提取请求方 IP（无 r.RemoteAddr 端口的纯地址部分）。
// 优先 RealIP（chi 中间件已根据 X-Forwarded-For / X-Real-IP 修正），失败回退 RemoteAddr。
func clientIP(r *http.Request) string {
	if ip := r.Header.Get("X-Real-IP"); ip != "" {
		return ip
	}
	if h := r.Header.Get("X-Forwarded-For"); h != "" {
		return h
	}
	// r.RemoteAddr 形如 "1.2.3.4:5678"；截掉端口。
	addr := r.RemoteAddr
	for i := len(addr) - 1; i >= 0; i-- {
		if addr[i] == ':' {
			return addr[:i]
		}
	}
	return addr
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}