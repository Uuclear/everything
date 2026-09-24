package agent

import (
	"strings"
	"testing"
)

// validAgentCfg 返回一个合法 AgentConfig（含 OpenAI 兼容 + Anthropic stub + stub 驱动）。
func validAgentCfg() AgentConfig {
	return AgentConfig{
		Enabled:         true,
		DefaultProvider: "ollama",
		Providers: []ProviderConfig{
			{
				Name:         "ollama",
				Driver:       "openai_compat",
				BaseURL:      "http://localhost:11434/v1",
				APIKeySource: "config",
				APIKey:       "ollama",
				Models:       []string{"qwen2.5:7b"},
			},
			{
				Name:         "openai",
				Driver:       "openai_compat",
				BaseURL:      "https://api.openai.com/v1",
				APIKeySource: "user_settings",
				Models:       []string{"gpt-4o-mini"},
				RPMLimit:     60,
				TPMLimit:     100000,
			},
			{
				Name:         "anthropic",
				Driver:       "anthropic_compat",
				BaseURL:      "https://api.anthropic.com",
				APIKeySource: "user_settings",
			},
			{
				Name:         "stub1",
				Driver:       "stub",
				BaseURL:      "stub://local",
				APIKeySource: "config",
			},
		},
		AllowedTools: []string{
			"vault.search", "vault.get", "utility.now",
		},
	}
}

// 用例 1：DefaultAgentConfig 包含完整 29 个工具。
func TestDefaultAgentConfig(t *testing.T) {
	cfg := DefaultAgentConfig()
	if cfg.Enabled {
		t.Errorf("默认配置应 Enabled=false")
	}
	if len(cfg.AllowedTools) != 29 {
		t.Errorf("默认白名单长度=%d 期望 29", len(cfg.AllowedTools))
	}
	// 抽样校验。
	for _, want := range []string{"vault.search", "identity.get_profile", "finance.aggregate", "utility.cancel"} {
		found := false
		for _, got := range cfg.AllowedTools {
			if got == want {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("默认白名单缺 %q", want)
		}
	}
}

// 用例 2：合法配置 → NewRegistry 成功 + Default 路由。
func TestNewRegistry_SuccessAndDefault(t *testing.T) {
	r, err := NewRegistry(validAgentCfg())
	if err != nil {
		t.Fatalf("NewRegistry 失败: %v", err)
	}
	if r.DefaultName() != "ollama" {
		t.Errorf("DefaultName=%q 期望 ollama", r.DefaultName())
	}
	p, ok := r.Default()
	if !ok {
		t.Fatal("Default() 不应 miss")
	}
	if p.Name() != "ollama" {
		t.Errorf("Default().Name()=%q 期望 ollama", p.Name())
	}
	// OpenAI 兼容驱动的类型校验。
	if _, ok := p.(*OpenAICompat); !ok {
		t.Errorf("Default() Provider 应为 *OpenAICompat，实际 %T", p)
	}
}

// 用例 3：Names 按字典序返回 4 个 Provider。
func TestRegistry_NamesSorted(t *testing.T) {
	r, err := NewRegistry(validAgentCfg())
	if err != nil {
		t.Fatal(err)
	}
	names := r.Names()
	want := []string{"anthropic", "ollama", "openai", "stub1"}
	if len(names) != len(want) {
		t.Fatalf("Names 长度=%d 期望 %d", len(names), len(want))
	}
	for i, n := range want {
		if names[i] != n {
			t.Errorf("Names[%d]=%q 期望 %q", i, names[i], n)
		}
	}
}

// 用例 4：AllowedTools 白名单校验（IsAllowed + AllowedTools）。
func TestRegistry_AllowedTools(t *testing.T) {
	r, err := NewRegistry(validAgentCfg())
	if err != nil {
		t.Fatal(err)
	}
	if !r.IsAllowed("vault.search") {
		t.Error("vault.search 应在白名单内")
	}
	if r.IsAllowed("evil.tool") {
		t.Error("evil.tool 不应在白名单内")
	}
	got := r.AllowedTools()
	if len(got) != 3 {
		t.Errorf("AllowedTools 长度=%d 期望 3", len(got))
	}
}

// 用例 5：重复 provider name → 拒绝。
func TestAgentConfig_Validate_DuplicateName(t *testing.T) {
	cfg := validAgentCfg()
	cfg.Providers = append(cfg.Providers, cfg.Providers[0]) // 复制 ollama
	err := cfg.Validate()
	if err == nil {
		t.Fatal("期望重复 name 错误")
	}
	if !strings.Contains(err.Error(), "重复") {
		t.Errorf("错误信息应含\"重复\"，实际=%q", err.Error())
	}
}

// 用例 6：非法 driver → 拒绝。
func TestAgentConfig_Validate_InvalidDriver(t *testing.T) {
	cfg := validAgentCfg()
	cfg.Providers[0].Driver = "unknown_driver"
	if err := cfg.Validate(); err == nil {
		t.Fatal("期望非法 driver 错误")
	}
}

// 用例 7：非法 APIKeySource → 拒绝。
func TestAgentConfig_Validate_InvalidAPIKeySource(t *testing.T) {
	cfg := validAgentCfg()
	cfg.Providers[0].APIKeySource = "env"
	if err := cfg.Validate(); err == nil {
		t.Fatal("期望非法 api_key_source 错误")
	}
}

// 用例 8：白名单工具名非法 → 拒绝。
func TestAgentConfig_Validate_InvalidToolName(t *testing.T) {
	cfg := validAgentCfg()
	cfg.AllowedTools = []string{"VAULT.search"} // 大写非法
	if err := cfg.Validate(); err == nil {
		t.Fatal("期望非法工具名错误")
	}
}

// 用例 9：DefaultProvider 不在 Providers 内 → 拒绝。
func TestAgentConfig_Validate_DefaultNotInProviders(t *testing.T) {
	cfg := validAgentCfg()
	cfg.DefaultProvider = "ghost"
	if err := cfg.Validate(); err == nil {
		t.Fatal("期望 default_provider 缺失错误")
	}
}

// 用例 10：禁用时（Enabled=false）跳过校验。
func TestAgentConfig_Validate_Disabled(t *testing.T) {
	cfg := DefaultAgentConfig() // 默认 disabled + 空 providers
	cfg.DefaultProvider = "x"   // 即使非法也不报错
	if err := cfg.Validate(); err != nil {
		t.Errorf("disabled 时应跳过校验，实际=%v", err)
	}
}

// 用例 11：APIKeySource="user_settings" 构造时 APIKey 留空 + 运行时 SetAPIKey 注入。
func TestRegistry_SetAPIKey_RuntimeInject(t *testing.T) {
	cfg := validAgentCfg()
	r, err := NewRegistry(cfg)
	if err != nil {
		t.Fatal(err)
	}
	// 构造时 openai（user_settings）APIKey 应为空。
	openai, ok := r.Get("openai")
	if !ok {
		t.Fatal("Get(openai) miss")
	}
	if oac, ok := openai.(*OpenAICompat); ok {
		if oac.APIKey != "" {
			t.Errorf("user_settings 构造时 APIKey 应为空，实际=%q", oac.APIKey)
		}
	} else {
		t.Errorf("openai 类型=%T 期望 *OpenAICompat", openai)
	}
	// 运行时注入。
	if !r.SetAPIKey("openai", "sk-runtime-1") {
		t.Fatal("SetAPIKey(openai) 应返回 true")
	}
	openai2, ok2 := r.Get("openai")
	if !ok2 {
		t.Fatal("Get(openai) 不应 miss")
	}
	oac, ok := openai2.(*OpenAICompat)
	if !ok || oac.APIKey != "sk-runtime-1" {
		t.Errorf("注入后 APIKey=%q 期望 sk-runtime-1", oac.APIKey)
	}
	// SetAPIKey 不存在的 name → false。
	if r.SetAPIKey("ghost", "x") {
		t.Error("SetAPIKey(ghost) 应返回 false")
	}
}

// 用例 12：Config() 返回只读副本并强制清空 APIKey（防泄漏）。
func TestRegistry_Config_StripsAPIKey(t *testing.T) {
	cfg := validAgentCfg()
	r, err := NewRegistry(cfg)
	if err != nil {
		t.Fatal(err)
	}
	_ = r.SetAPIKey("openai", "sk-secret") // 即便注入也要在 Config() 中清空
	out := r.Config()
	for _, p := range out.Providers {
		if p.APIKey != "" {
			t.Errorf("Config().Providers[%s].APIKey=%q 不应泄露", p.Name, p.APIKey)
		}
	}
	if out.DefaultProvider != "ollama" {
		t.Errorf("Config().DefaultProvider=%q 期望 ollama", out.DefaultProvider)
	}
}

// 用例 13：Get 找不到 name → ok=false。
func TestRegistry_GetMissing(t *testing.T) {
	r, err := NewRegistry(validAgentCfg())
	if err != nil {
		t.Fatal(err)
	}
	p, ok := r.Get("ghost")
	if ok || p != nil {
		t.Errorf("Get(ghost) 应返回 (nil, false)，实际 (%v, %v)", p, ok)
	}
}

// 用例 14：AllowedTools 重复返回变更时必须构造期锁定，运行期不可修改。
func TestRegistry_AllowedToolsReadOnly(t *testing.T) {
	r, err := NewRegistry(validAgentCfg())
	if err != nil {
		t.Fatal(err)
	}
	got := r.AllowedTools()
	if len(got) != 3 {
		t.Fatalf("len=%d", len(got))
	}
	// 修改返回值不应影响内部白名单。
	got[0] = "evil.tool"
	if !r.IsAllowed("vault.search") {
		t.Error("修改 AllowedTools() 返回值不应影响内部白名单")
	}
	if r.IsAllowed("evil.tool") {
		t.Error("evil.tool 不应被加入白名单")
	}
}

// 用例 15：Driver=stub → 构造 StubProvider。
func TestRegistry_StubProvider(t *testing.T) {
	r, err := NewRegistry(validAgentCfg())
	if err != nil {
		t.Fatal(err)
	}
	p, ok := r.Get("stub1")
	if !ok {
		t.Fatal("Get(stub1) miss")
	}
	if _, ok := p.(*StubProvider); !ok {
		t.Errorf("stub1 类型=%T 期望 *StubProvider", p)
	}
}

// 用例 16：Driver=anthropic_compat → 构造 AnthropicCompat stub。
func TestRegistry_AnthropicCompat(t *testing.T) {
	r, err := NewRegistry(validAgentCfg())
	if err != nil {
		t.Fatal(err)
	}
	p, ok := r.Get("anthropic")
	if !ok {
		t.Fatal("Get(anthropic) miss")
	}
	if _, ok := p.(*AnthropicCompat); !ok {
		t.Errorf("anthropic 类型=%T 期望 *AnthropicCompat", p)
	}
}

// 用例 17：AllowedTools 空 → 拒绝。
func TestAgentConfig_Validate_EmptyAllowedTools(t *testing.T) {
	cfg := validAgentCfg()
	cfg.AllowedTools = nil
	if err := cfg.Validate(); err == nil {
		t.Fatal("期望 allowed_tools 必填错误")
	}
}

// 用例 18：Providers 空 → 拒绝。
func TestAgentConfig_Validate_EmptyProviders(t *testing.T) {
	cfg := validAgentCfg()
	cfg.Providers = nil
	if err := cfg.Validate(); err == nil {
		t.Fatal("期望 providers 必填错误")
	}
}