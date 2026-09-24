package agent

import (
	"context"
	"net/http"
	"time"
)

// AnthropicCompat 是 Anthropic Messages API 兼容驱动 stub。
//
// 当前实现仅作为接口占位（v3+ 完整实现）：
//   - 通过 NewAnthropicCompat 创建；
//   - ChatCompletion / CountTokens 暂返回 CodeProviderError；
//   - 完整的 Anthropic 原生协议（Messages API / Tool Use 格式）与 OpenAI 协议不兼容，
//     需要独立编解码路径，留待 v3 启动。
//
// 保留原因：v3 spec 已明确「Anthropic 原生协议（非 OpenAI 兼容模式）」为后续项；
// 本期先 stub，确保 Provider 接口可被后续模块引用 + 单元测试覆盖。
type AnthropicCompat struct {
	// name Provider 唯一标识（对外通过 Name() 方法读取）。
	// 字段小写避免与 Provider 接口的 Name() 方法同名冲突。
	name string

	// BaseURL 如 "https://api.anthropic.com/v1"。
	BaseURL string

	// APIKey Anthropic API Key。
	APIKey string

	// APIVersion Anthropic API 版本（如 "2023-06-01"）。
	APIVersion string

	// HTTPClient 自定义客户端（默认 60s 超时）。
	HTTPClient *http.Client
}

// NewAnthropicCompat 创建 Anthropic 兼容驱动 stub。
func NewAnthropicCompat(name, baseURL, apiKey, apiVersion string) *AnthropicCompat {
	return &AnthropicCompat{
		name:       name,
		BaseURL:    baseURL,
		APIKey:     apiKey,
		APIVersion: apiVersion,
		HTTPClient: &http.Client{Timeout: 60 * time.Second},
	}
}

// Name 返回 Provider 唯一标识。
func (p *AnthropicCompat) Name() string { return p.name }

// ChatCompletion 当前 stub 实现：返回 CodeProviderError。
// v3 启动时实现 Anthropic Messages API + Tool Use 格式的完整编解码。
func (p *AnthropicCompat) ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
	_ = ctx
	_ = req
	return nil, NewError(CodeProviderError, "Anthropic 驱动尚未实现，当前阶段请使用 OpenAI 兼容驱动", nil)
}

// CountTokens 估算 token 数（沿用统一估算器）。
func (p *AnthropicCompat) CountTokens(req ChatRequest) int {
	return TotalRequestTokens(req)
}