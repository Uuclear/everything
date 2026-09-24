package agent

import (
	"fmt"
	"sort"
	"strings"
	"sync"
)

// ProviderConfig 是单个 Provider 的配置项（对应 config.yaml → agent.providers[]）。
// 与驱动类型解耦：Registry 根据 Driver 字段决定构造哪种 Provider 实例。
type ProviderConfig struct {
	// Name Provider 唯一标识（用于 audit + 选择 API）。
	// 取值：ollama / openai / deepseek / vllm / anthropic / custom_*。
	Name string `yaml:"name" json:"name"`
	// Driver 驱动类型：openai_compat / anthropic_compat / stub。
	Driver string `yaml:"driver" json:"driver"`
	// BaseURL API Base URL，如 "http://localhost:11434/v1"。
	BaseURL string `yaml:"base_url" json:"base_url"`
	// APIKeySource API Key 来源：
	//   "config" —— 直接从 config 读（仅适用本地 Ollama 等不需要真实 Key 的场景）；
	//   "user_settings" —— 客户端单次回传给服务端内存使用（**不入 config / DB**）。
	APIKeySource string `yaml:"api_key_source" json:"api_key_source"`
	// APIKey 仅在 APIKeySource="config" 时使用；其余情况运行时由 WithAPIKey 注入。
	APIKey string `yaml:"api_key,omitempty" json:"api_key,omitempty"`
	// Models 该 Provider 支持的模型清单（用于客户端 UI 选择）。
	Models []string `yaml:"models" json:"models"`
	// RPMLimit 每分钟请求上限（0 表示不限速）。
	RPMLimit int `yaml:"rpm_limit" json:"rpm_limit"`
	// TPMLimit 每分钟 token 上限（0 表示不限速）。
	TPMLimit int `yaml:"tpm_limit" json:"tpm_limit"`
}

// AgentConfig 是 config.yaml → agent 节的顶层配置。
type AgentConfig struct {
	// Enabled Agent 是否启用（默认 false；阶段 6 启动后切 true）。
	Enabled bool `yaml:"enabled" json:"enabled"`
	// DefaultProvider 默认 Provider 名（启动时路由起点）。
	DefaultProvider string `yaml:"default_provider" json:"default_provider"`
	// Providers Provider 配置列表。
	Providers []ProviderConfig `yaml:"providers" json:"providers"`
	// AllowedTools 服务端白名单工具清单（启动时锁定，运行时不可增删）。
	// 取值对应 docs/ai-agent-tools.md 中 29 个工具的 name 字段。
	AllowedTools []string `yaml:"allowed_tools" json:"allowed_tools"`
}

// DefaultAgentConfig 返回默认配置（关闭 + 空 Provider + 完整白名单）。
func DefaultAgentConfig() AgentConfig {
	return AgentConfig{
		Enabled:         false,
		DefaultProvider: "ollama",
		Providers:       nil,
		// 完整 29 个工具白名单（启动后不可修改；运行期校验使用）。
		// 新增工具必须重新部署服务端才能生效。
		AllowedTools: []string{
			// vault（5）
			"vault.search", "vault.get", "vault.create", "vault.update", "vault.delete",
			// identity（3）
			"identity.get_profile", "identity.list_devices", "identity.revoke_device",
			// event（5）
			"event.search", "event.get", "event.create", "event.update", "event.delete",
			// finance（5）
			"finance.search", "finance.aggregate", "finance.create", "finance.update", "finance.delete",
			// trajectory（2）
			"trajectory.search", "trajectory.aggregate",
			// utility（4）
			"utility.now", "utility.list_tools", "utility.help", "utility.cancel",
			// item（5，留待阶段 7 启用）
			"item.search", "item.get", "item.create", "item.update", "item.delete",
		},
	}
}

// Validate 校验 Agent 配置合法性。
//   - AllowedTools 非空；
//   - DefaultProvider 非空；
//   - Providers 至少 1 个；
//   - 每条 Provider.Name / Driver / BaseURL 非空；
//   - APIKeySource 仅允许 "config" / "user_settings"。
//   - AllowedTools 中每个工具名都通过 isValidToolName。
func (c AgentConfig) Validate() error {
	if !c.Enabled {
		// 未启用时不校验 Providers（保持空配置合法）。
		return nil
	}
	if strings.TrimSpace(c.DefaultProvider) == "" {
		return fmt.Errorf("agent: default_provider 必填")
	}
	if len(c.Providers) == 0 {
		return fmt.Errorf("agent: providers 至少 1 个")
	}
	if len(c.AllowedTools) == 0 {
		return fmt.Errorf("agent: allowed_tools 至少 1 个")
	}
	names := make(map[string]bool)
	for i, p := range c.Providers {
		if strings.TrimSpace(p.Name) == "" {
			return fmt.Errorf("agent: providers[%d].name 必填", i)
		}
		if names[p.Name] {
			return fmt.Errorf("agent: providers[%d].name %q 重复", i, p.Name)
		}
		names[p.Name] = true
		if strings.TrimSpace(p.Driver) == "" {
			return fmt.Errorf("agent: providers[%d].driver 必填", i)
		}
		if strings.TrimSpace(p.BaseURL) == "" {
			return fmt.Errorf("agent: providers[%d].base_url 必填", i)
		}
		if p.APIKeySource != "config" && p.APIKeySource != "user_settings" {
			return fmt.Errorf("agent: providers[%d].api_key_source 仅允许 config / user_settings", i)
		}
		switch p.Driver {
		case "openai_compat", "anthropic_compat", "stub":
		default:
			return fmt.Errorf("agent: providers[%d].driver %q 非法", i, p.Driver)
		}
	}
	// 校验 DefaultProvider 必须在 Providers 列表内。
	if !names[c.DefaultProvider] {
		return fmt.Errorf("agent: default_provider %q 不在 providers 内", c.DefaultProvider)
	}
	// 校验 AllowedTools 工具名合法。
	for _, t := range c.AllowedTools {
		if !isValidToolName(t) {
			return fmt.Errorf("agent: allowed_tools 含非法名 %q", t)
		}
	}
	return nil
}

// Registry 是 Provider 注册表 + 白名单校验器 + 默认路由。
// 服务端启动时构造一次，运行期只读。
type Registry struct {
	mu sync.RWMutex

	cfg AgentConfig

	// providers 按 Name 索引的 Provider 实例。
	providers map[string]Provider

	// allowedTools 白名单工具集合（构造时拷贝，运行期不可修改）。
	allowedTools map[string]bool

	// defaultName 默认 Provider 名。
	defaultName string
}

// NewRegistry 根据配置构造 Registry 实例。
//   - 校验配置合法性；
//   - 按 Driver 构造具体 Provider；
//   - 锁定 AllowedTools 白名单（运行期不可修改）。
func NewRegistry(cfg AgentConfig) (*Registry, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	r := &Registry{
		cfg:          cfg,
		providers:    make(map[string]Provider, len(cfg.Providers)),
		allowedTools: make(map[string]bool, len(cfg.AllowedTools)),
		defaultName:  cfg.DefaultProvider,
	}
	for _, t := range cfg.AllowedTools {
		r.allowedTools[t] = true
	}
	for _, p := range cfg.Providers {
		prov, err := buildProvider(p)
		if err != nil {
			return nil, fmt.Errorf("构造 provider %q: %w", p.Name, err)
		}
		r.providers[p.Name] = prov
	}
	return r, nil
}

// buildProvider 按 Driver 类型构造具体 Provider 实例。
// 注：APIKeySource="user_settings" 时构造期 APIKey 留空，由运行时
// SetAPIKey 注入（**不入 config / DB**，仅 Registry 内存）。
func buildProvider(p ProviderConfig) (Provider, error) {
	switch p.Driver {
	case "openai_compat":
		key := p.APIKey
		if p.APIKeySource == "user_settings" {
			key = "" // 运行时注入
		}
		return NewOpenAICompat(p.Name, p.BaseURL, key), nil
	case "anthropic_compat":
		key := p.APIKey
		if p.APIKeySource == "user_settings" {
			key = ""
		}
		return NewAnthropicCompat(p.Name, p.BaseURL, key, "2023-06-01"), nil
	case "stub":
		// 占位 Provider：返回错误，用于联调 / 配置测试。
		return NewStubProvider(p.Name), nil
	default:
		return nil, fmt.Errorf("未知 driver %q", p.Driver)
	}
}

// SetAPIKey 注入用户的 API Key（仅运行期内存，不持久化）。
// 用于 APIKeySource="user_settings" 的 Provider。
// 返回 false 表示 CodeKey 不在已注册 Provider 列表内。
func (r *Registry) SetAPIKey(name, apiKey string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	prov, ok := r.providers[name]
	if !ok {
		return false
	}
	switch p := prov.(type) {
	case *OpenAICompat:
		p.APIKey = apiKey
	case *AnthropicCompat:
		p.APIKey = apiKey
	}
	return true
}

// Get 按 Name 获取 Provider 实例。
func (r *Registry) Get(name string) (Provider, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.providers[name]
	return p, ok
}

// Default 获取默认 Provider 实例。
func (r *Registry) Default() (Provider, bool) {
	return r.Get(r.defaultName)
}

// DefaultName 返回默认 Provider 名称。
func (r *Registry) DefaultName() string { return r.defaultName }

// Names 返回所有已注册 Provider 名（按字典序）。
// 用于工具 utility.list_tools 返回 + 客户端 UI 选择。
func (r *Registry) Names() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]string, 0, len(r.providers))
	for n := range r.providers {
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

// IsAllowed 判断工具名是否在白名单内。
// 客户端声明的 tool_name 必须通过此校验。
func (r *Registry) IsAllowed(toolName string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.allowedTools[toolName]
}

// AllowedTools 返回白名单工具名清单（按字典序，便于稳定输出）。
func (r *Registry) AllowedTools() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]string, 0, len(r.allowedTools))
	for t := range r.allowedTools {
		out = append(out, t)
	}
	sort.Strings(out)
	return out
}

// Config 返回当前配置的只读副本（用于调试 / 审计展示，**不暴露 API Key**）。
func (r *Registry) Config() AgentConfig {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := r.cfg
	out.Providers = make([]ProviderConfig, len(r.cfg.Providers))
	for i, p := range r.cfg.Providers {
		// 强制清空 APIKey 字段防止意外泄露。
		p.APIKey = ""
		out.Providers[i] = p
	}
	return out
}