package agent

import (
	"errors"
	"fmt"
)

// ErrorCode 是 Agent 模块统一的错误码枚举。
// 前端根据 Code 选择 UI 文案 + 重试策略。
type ErrorCode string

const (
	// CodeSessionLocked 解锁会话已过期 → 客户端跳解锁页。
	CodeSessionLocked ErrorCode = "agent.session_locked"
	// CodeRateLimited 速率限制（服务端 / Provider 双重来源）。
	CodeRateLimited ErrorCode = "agent.rate_limited"
	// CodeToolNotWhitelisted 工具不在白名单。
	CodeToolNotWhitelisted ErrorCode = "agent.tool_not_whitelisted"
	// CodeToolDenied 用户拒绝二次确认。
	CodeToolDenied ErrorCode = "agent.tool_denied"
	// CodeToolArgsInvalid 工具入参非法（客户端责任）。
	CodeToolArgsInvalid ErrorCode = "agent.tool_args_invalid"
	// CodeToolExecutionFailed 工具执行失败（客户端责任）。
	CodeToolExecutionFailed ErrorCode = "agent.tool_execution_failed"
	// CodeToolTimeout 工具执行超时。
	CodeToolTimeout ErrorCode = "agent.tool_timeout"
	// CodeProviderError Provider 故障（HTTP 非 200 / 网络异常 / 解码失败）。
	CodeProviderError ErrorCode = "agent.provider_error"
	// CodeTimeout LLM 调用超时（服务端 ctx 超时）。
	CodeTimeout ErrorCode = "agent.timeout"
	// CodeContextTooLong 上下文超限（含 Prompt tokens / 单条 content 过大）。
	CodeContextTooLong ErrorCode = "agent.context_too_long"
	// CodeInvalidRequest 请求非法（缺 model / 缺 system 等）。
	CodeInvalidRequest ErrorCode = "agent.invalid_request"
	// CodeInternal 服务端内部错误。
	CodeInternal ErrorCode = "agent.internal"
)

// Error 是 Agent 模块的统一错误类型。
// 携带错误码（用于客户端决策）+ 用户可读消息（用于 UI 文案）+ 底层错误（用于日志）。
//
// 关键纪律：错误 Message 字段**绝不包含**：
//   - 用户 API Key；
//   - user_msg / assistant_msg 明文；
//   - tool_args / tool_result 明文。
type Error struct {
	// Code 错误码（前端据此决策）。
	Code ErrorCode
	// Message 用户可读消息（不含敏感信息，可安全展示）。
	Message string
	// Cause 底层错误（用于服务端日志，**不返回客户端**）。
	Cause error
}

// Error 实现 error 接口。
func (e *Error) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("[%s] %s: %v", e.Code, e.Message, e.Cause)
	}
	return fmt.Sprintf("[%s] %s", e.Code, e.Message)
}

// Unwrap 让 errors.Is / errors.As 可穿透到 Cause。
func (e *Error) Unwrap() error { return e.Cause }

// NewError 构造 Agent 错误。
func NewError(code ErrorCode, message string, cause error) *Error {
	return &Error{Code: code, Message: message, Cause: cause}
}

// ErrorCodeOf 提取错误中的 ErrorCode（不是 *Error 则返回 CodeInternal）。
// 用于把任意 error 归类到统一错误码，便于上层做错误聚合 + 告警。
func ErrorCodeOf(err error) ErrorCode {
	if err == nil {
		return ""
	}
	var ae *Error
	if errors.As(err, &ae) {
		return ae.Code
	}
	// 兜底：未知错误归类为 CodeInternal。
	return CodeInternal
}

// ErrorCodeName 返回 ErrorCode 的简短字符串（去掉 "agent." 前缀），
// 便于写库（如 agent_audit_logs.error_code 列）与前端展示。
// 未知 / 空值返回 "unknown"。
func ErrorCodeName(c ErrorCode) string {
	if c == "" {
		return ""
	}
	s := string(c)
	if len(s) > 6 && s[:6] == "agent." {
		s = s[6:]
	}
	return s
}