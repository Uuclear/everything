package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// OpenAICompat 实现 Provider 接口，遵循 OpenAI Chat Completions 兼容协议。
// 该驱动可对接：Ollama / OpenAI / DeepSeek / vLLM / LM Studio / 其他 OpenAI 兼容服务。
//
// 设计要点：
//   - 单一结构体兼容所有兼容 Provider（差异在 BaseURL + Headers）；
//   - API Key 不落日志 / 不落 DB（详见 registry.go 的 api_key_source）；
//   - 强制超时由 ctx 控制（推荐 60s）；
//   - 错误信息不泄露 API Key / 请求明文。
type OpenAICompat struct {
	// name Provider 唯一标识（用于审计 + 配置），对外通过 Name() 方法读取。
	// 字段小写避免与 Provider 接口的 Name() 方法同名冲突。
	name string

	// BaseURL 如 "http://localhost:11434/v1" / "https://api.openai.com/v1"。
	BaseURL string

	// APIKey 供应商 API Key；Ollama 协议下可填占位（如 "ollama"）。
	// 本字段**不入任何持久层**：仅保存在内存中，由 Provider 实例生命周期持有。
	APIKey string

	// HTTPClient 自定义客户端（测试时可注入 mock Transport）。
	// 默认 60s 超时（与 Caller ctx 超时叠加取最小）。
	HTTPClient *http.Client

	// ExtraHeaders 额外的 HTTP Header（如 Anthropic 兼容模式下的 anthropic-version）。
	ExtraHeaders map[string]string
}

// NewOpenAICompat 创建 OpenAI 兼容驱动。
// 默认 HTTP 客户端超时 60s；调用方可通过 HTTPClient 字段覆盖。
func NewOpenAICompat(name, baseURL, apiKey string) *OpenAICompat {
	return &OpenAICompat{
		name:    name,
		BaseURL: strings.TrimRight(baseURL, "/"),
		APIKey:  apiKey,
		HTTPClient: &http.Client{
			Timeout: 60 * time.Second,
		},
	}
}

// Name 返回 Provider 唯一标识。
func (p *OpenAICompat) Name() string { return p.name }

// ChatCompletion 发起一次 LLM 对话。
// 强制流程：
//   1. 校验请求；
//   2. 构造 OpenAI 兼容协议的 HTTP POST；
//   3. 解析响应；
//   4. 失败时返回带 Code 字段的 Error（详见 errors.go）。
func (p *OpenAICompat) ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
	// 1. 校验请求合法性。
	if err := ValidateRequest(req); err != nil {
		return nil, err
	}

	// 2. 构造请求体（OpenAI 协议 stream=false）。
	body, err := json.Marshal(struct {
		Model       string      `json:"model"`
		Messages    []ChatMessage `json:"messages"`
		Tools       []ToolSpec   `json:"tools,omitempty"`
		ToolChoice  any         `json:"tool_choice,omitempty"`
		Temperature *float64    `json:"temperature,omitempty"`
		MaxTokens   *int        `json:"max_tokens,omitempty"`
		Stream      bool        `json:"stream"`
	}{
		Model:       req.Model,
		Messages:    req.Messages,
		Tools:       req.Tools,
		ToolChoice:  req.ToolChoice,
		Temperature: req.Temperature,
		MaxTokens:   req.MaxTokens,
		Stream:      false,
	})
	if err != nil {
		return nil, fmt.Errorf("agent: 编码请求体失败: %w", err)
	}

	// 3. 构造 HTTP 请求。
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost,
		p.BaseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("agent: 构造请求失败: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	if p.APIKey != "" {
		httpReq.Header.Set("Authorization", "Bearer "+p.APIKey)
	}
	for k, v := range p.ExtraHeaders {
		httpReq.Header.Set(k, v)
	}

	// 4. 发送请求（HTTPClient.Timeout 与 ctx 叠加）。
	client := p.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(httpReq)
	if err != nil {
		// 区分超时与其他网络错误。
		if errors.Is(err, context.DeadlineExceeded) || isTimeoutErr(err) {
			return nil, NewError(CodeTimeout, "LLM 调用超时", err)
		}
		return nil, NewError(CodeProviderError, "LLM 调用失败", err)
	}
	defer func() { _, _ = io.Copy(io.Discard, resp.Body); _ = resp.Body.Close() }()

	// 5. 解析响应。
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, NewError(CodeProviderError, "读取响应失败", err)
	}

	if resp.StatusCode != http.StatusOK {
		// 解析错误响应（OpenAI 协议下为 {"error": {"message": "..."}}）。
		// 注：错误信息可能含敏感字段，统一截断至 200 字符防日志泄漏。
		return nil, parseProviderError(resp.StatusCode, respBody)
	}

	var chatOut ChatResponse
	if err := json.Unmarshal(respBody, &chatOut); err != nil {
		return nil, NewError(CodeProviderError, "解码响应失败", err)
	}

	// 6. 校验响应至少有 1 个 choice。
	if len(chatOut.Choices) == 0 {
		return nil, NewError(CodeProviderError, "响应无 choice", nil)
	}

	// 7. 双写一致性：Choice.Message.ToolCalls 与 Choice.ToolCalls 同步。
	if len(chatOut.Choices[0].Message.ToolCalls) == 0 && len(chatOut.Choices[0].ToolCalls) > 0 {
		chatOut.Choices[0].Message.ToolCalls = chatOut.Choices[0].ToolCalls
	}

	return &chatOut, nil
}

// CountTokens 估算请求的输入 token 数。
// 当前实现：基于字符数的粗略估算（详见 provider.go estimateTokens）。
// 后续可接入 Provider 的 tokenize 端点（如 OpenAI 的 tokenizer）。
func (p *OpenAICompat) CountTokens(req ChatRequest) int {
	return TotalRequestTokens(req)
}

// parseProviderError 解析 Provider 的错误响应，按 HTTP 状态码映射到 Agent 错误码。
// 错误信息统一截断至 200 字符（防日志泄漏）。
func parseProviderError(status int, body []byte) error {
	// 截断响应体（防止 Provider 错误信息含用户明文 / API Key）。
	snippet := string(body)
	if len(snippet) > 200 {
		snippet = snippet[:200] + "..."
	}

	// 尝试解析标准 OpenAI 错误格式。
	var oe struct {
		Error struct {
			Message string `json:"message"`
			Type    string `json:"type"`
			Code    string `json:"code"`
		} `json:"error"`
	}
	msg := snippet
	if json.Unmarshal(body, &oe) == nil && oe.Error.Message != "" {
		msg = oe.Error.Message
		if len(msg) > 200 {
			msg = msg[:200] + "..."
		}
	}

	switch status {
	case http.StatusUnauthorized, http.StatusForbidden:
		return NewError(CodeProviderError, "LLM 鉴权失败: "+msg, nil)
	case http.StatusTooManyRequests:
		return NewError(CodeRateLimited, "LLM 速率限制: "+msg, nil)
	case http.StatusRequestEntityTooLarge:
		return NewError(CodeContextTooLong, "LLM 请求体过大: "+msg, nil)
	case http.StatusBadRequest:
		// 含 context_length_exceeded 等场景。
		if strings.Contains(strings.ToLower(msg), "context_length") {
			return NewError(CodeContextTooLong, "LLM 上下文超限: "+msg, nil)
		}
		return NewError(CodeProviderError, "LLM 请求非法: "+msg, nil)
	default:
		return NewError(CodeProviderError, fmt.Sprintf("LLM 返回 %d: %s", status, msg), nil)
	}
}

// isTimeoutErr 判断错误是否为网络层超时（http.Client 超时或 net.Error.Timeout）。
func isTimeoutErr(err error) bool {
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return true
	}
	// http.Client 超时时 err 直接是 *url.Error（含 Timeout()）。
	if ue := new(url.Error); errors.As(err, &ue) {
		if ne2, ok := ue.Err.(net.Error); ok && ne2.Timeout() {
			return true
		}
	}
	return false
}