package agent

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"log/slog"
)

// 审计 event 枚举（与 0005_agent_audit_log.sql event 列对齐）。
const (
	EventChatRequested   = "chat_requested"
	EventChatCompleted   = "chat_completed"
	EventToolInvoked     = "tool_invoked"
	EventToolCompleted   = "tool_completed"
	EventCancelled       = "cancelled"
	EventRateLimited     = "rate_limited"
	EventSessionLocked   = "session_locked"
	EventSessionExpired  = "session_expired"
	EventProviderError   = "provider_error"
	EventToolNotAllowed  = "tool_not_allowed"
	EventContextTooLong  = "context_too_long"
)

// 状态枚举。
const (
	StatusOK    = "ok"
	StatusError = "error"
)

// AuditEvent 是写往 agent_audit_logs 的结构化记录。
//
// **三不存契约**：
//   - 不含 user_msg / assistant_msg / tool_args / tool_result 明文字段；
//   - 工具入参仅落 tool_args_hash（SHA-256 hex），无法反向还原；
//   - detail 字段强制截断 ≤200 字符，且不允许落明文载荷（调用方传 "redacted" 标记）。
type AuditEvent struct {
	UserID          string
	DeviceID        string
	SessionID       string
	ProviderName    string
	Event           string
	Status          string // ok / error
	ErrorCode       ErrorCode // 0 表示 OK；其余见 errors.go
	ToolName        string
	ToolArgsJSON    string // 仅用于内部计算 SHA-256，不入库
	ToolResultKind  string // redacted_summary / error / '' 等
	PromptTokens    int
	CompletionTokens int
	LatencyMs       int
	IP              string
	Detail          string
}

// SHA256Hex 返回字符串的 SHA-256 hex（64 字符小写）。
// 空字符串返回空（避免空 hash 列误读取时混淆）。
func SHA256Hex(s string) string {
	if s == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

// AuditWriter 负责写 agent_audit_logs。
// 借助 *sql.DB 与 Prepare 提供高频插入的复用；nil db 时静默跳过（与 api.audit 风格一致）。
type AuditWriter struct {
	db *sql.DB
}

// NewAuditWriter 创建审计写入器。
// db 为 nil 时所有 Write 调用静默成功（兼容纯内存 Provider 测试场景）。
func NewAuditWriter(db *sql.DB) *AuditWriter {
	return &AuditWriter{db: db}
}

// Write 写入一条审计记录。
//
// 设计要点：
//   - ctx 用于传播请求级超时；调用方必须传非 nil ctx；
//   - ToolArgsJSON 立即计算 SHA-256 hex 后丢弃原值（不留任何中间缓冲）；
//   - Detail 截断至 200 字符（防日志/审计行膨胀）。
func (w *AuditWriter) Write(ctx context.Context, ev AuditEvent) {
	if w == nil || w.db == nil {
		return
	}
	if ev.Status == "" {
		ev.Status = StatusOK
	}
	if len(ev.Detail) > 200 {
		ev.Detail = ev.Detail[:200]
	}
	toolArgsHash := SHA256Hex(ev.ToolArgsJSON)
	// 用完即清零原字符串，避免函数栈上残留（防御性：现代 GC 不保证回收时机）。
	ev.ToolArgsJSON = ""

	errorCode := string(ErrorCodeName(ev.ErrorCode))
	if errorCode == "" || errorCode == "unknown" {
		errorCode = ""
	}

	_, err := w.db.ExecContext(ctx, `
		INSERT INTO agent_audit_logs (
			user_id, device_id, session_id, provider_name,
			event, status, error_code,
			tool_name, tool_args_hash, tool_result_kind,
			prompt_tokens, completion_tokens, latency_ms,
			ip, detail, created_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, unixepoch() * 1000)`,
		ev.UserID, ev.DeviceID, ev.SessionID, ev.ProviderName,
		ev.Event, ev.Status, errorCode,
		ev.ToolName, toolArgsHash, ev.ToolResultKind,
		ev.PromptTokens, ev.CompletionTokens, ev.LatencyMs,
		ev.IP, ev.Detail,
	)
	if err != nil {
		slog.Warn("agent 审计写入失败", "err", err, "event", ev.Event, "user_id", ev.UserID)
	}
}

// AuditSession 用于查询单用户所有活跃/历史 Agent 会话元数据。
// 本期落地时：仅返回最小元数据（session_id + 最近活动），避免泄露 session 内容。
type AuditSession struct {
	SessionID     string
	LastEventAt   int64  // unix 毫秒
	LastProvider  string
	EventCount    int
}

// ListSessions 返回指定用户的最近 N 个会话元数据（按 last_event_at DESC）。
// n<=0 时取 50。
func (w *AuditWriter) ListSessions(ctx context.Context, userID string, n int) ([]AuditSession, error) {
	if w == nil || w.db == nil {
		return nil, nil
	}
	if n <= 0 {
		n = 50
	}
	rows, err := w.db.QueryContext(ctx, `
		SELECT session_id,
		       COALESCE(MAX(created_at), 0) AS last_event_at,
		       COALESCE((SELECT provider_name FROM agent_audit_logs
		                 WHERE session_id = a.session_id AND provider_name != ''
		                 ORDER BY created_at DESC LIMIT 1), '') AS last_provider,
		       COUNT(1) AS event_count
		FROM agent_audit_logs a
		WHERE user_id = ? AND session_id != ''
		GROUP BY session_id
		ORDER BY last_event_at DESC
		LIMIT ?`, userID, n)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]AuditSession, 0, n)
	for rows.Next() {
		var s AuditSession
		if err := rows.Scan(&s.SessionID, &s.LastEventAt, &s.LastProvider, &s.EventCount); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}