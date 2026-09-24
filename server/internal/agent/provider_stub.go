package agent

import "context"

// StubProvider 是 Provider 接口的最小占位实现。
//
// 用途：
//   - 配置联调（Registry.buildProvider 在 driver="stub" 时构造）；
//   - 单元测试中无需真实 LLM 时返回固定响应；
//   - 阶段 6 启动初期给运维 / 客户端一个可观察的「已注册但不可用」状态。
//
// ChatCompletion 固定返回 CodeProviderError（驱动未启用）；
// CountTokens 委托给 TotalRequestTokens（保持估值一致）。
type StubProvider struct {
	// name Provider 唯一标识（对外通过 Name() 方法读取）。
	name string
}

// NewStubProvider 创建 Stub Provider。
func NewStubProvider(name string) *StubProvider {
	return &StubProvider{name: name}
}

// Name 返回 Provider 唯一标识。
func (p *StubProvider) Name() string { return p.name }

// ChatCompletion 占位实现：始终返回 CodeProviderError。
// 联调场景下让客户端能识别「驱动已注册但功能未启用」。
func (p *StubProvider) ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
	_ = ctx
	_ = req
	return nil, NewError(CodeProviderError, "stub Provider 未启用实际 LLM，仅用于配置联调", nil)
}

// CountTokens 估算 token 数（沿用统一估算器）。
func (p *StubProvider) CountTokens(req ChatRequest) int {
	return TotalRequestTokens(req)
}