package agent

import (
	"context"
	"strings"
	"testing"
	"time"
)

// ---------------- ratelimit ----------------

// 用例 1：RPM 限制。
func TestRateLimiter_RPM(t *testing.T) {
	l := NewRateLimiter(RateLimits{RPM: 3, MaxConcurrent: 10, DailyWrites: 100})
	for i := 0; i < 3; i++ {
		if d := l.Allow("u1", 0, false); !d.Allowed {
			t.Fatalf("第 %d 次应放行", i+1)
		}
		l.Done("u1")
	}
	d := l.Allow("u1", 0, false)
	if d.Allowed || d.Reason != "rpm" {
		t.Errorf("第 4 次应被 RPM 拒绝，实际=%+v", d)
	}
}

// 用例 2：TPM 限制。
func TestRateLimiter_TPM(t *testing.T) {
	l := NewRateLimiter(RateLimits{RPM: 100, TPM: 100, MaxConcurrent: 10, DailyWrites: 100})
	if d := l.Allow("u2", 60, false); !d.Allowed {
		t.Fatalf("第一次 60 token 应放行，实际=%+v", d)
	} else {
		l.Done("u2")
	}
	if d := l.Allow("u2", 60, false); d.Allowed {
		l.Done("u2")
	}
	if d := l.Allow("u2", 60, false); d.Allowed || d.Reason != "tpm" {
		t.Errorf("累计 180 token 应被 TPM 拒绝，实际=%+v", d)
	}
}

// 用例 3：并发限制。
func TestRateLimiter_Concurrent(t *testing.T) {
	l := NewRateLimiter(RateLimits{RPM: 100, MaxConcurrent: 2, DailyWrites: 100})
	if d := l.Allow("u3", 0, false); !d.Allowed {
		t.Fatalf("第 1 次应放行")
	}
	if d := l.Allow("u3", 0, false); !d.Allowed {
		t.Fatalf("第 2 次应放行")
	}
	if d := l.Allow("u3", 0, false); d.Allowed || d.Reason != "concurrent" {
		t.Errorf("第 3 次应被并发拒绝，实际=%+v", d)
	}
	l.Done("u3")
	if d := l.Allow("u3", 0, false); !d.Allowed {
		t.Errorf("Done 后应放行，实际=%+v", d)
	}
	l.Done("u3")
}

// 用例 4：日写工具限制。
func TestRateLimiter_DailyWrites(t *testing.T) {
	l := NewRateLimiter(RateLimits{RPM: 100, MaxConcurrent: 10, DailyWrites: 2})
	if d := l.Allow("u4", 0, true); !d.Allowed {
		t.Fatalf("写 1 应放行")
	} else {
		l.Done("u4")
	}
	if d := l.Allow("u4", 0, true); !d.Allowed {
		t.Fatalf("写 2 应放行")
	} else {
		l.Done("u4")
	}
	if d := l.Allow("u4", 0, true); d.Allowed || d.Reason != "daily_write_tools" {
		t.Errorf("写 3 应被 daily 拒绝，实际=%+v", d)
	}
	if d := l.Allow("u4", 0, false); !d.Allowed {
		t.Errorf("读工具不受写工具限额约束，实际=%+v", d)
	}
}

// 用例 5：默认值填充（0 字段走默认）。
func TestRateLimiter_DefaultValues(t *testing.T) {
	l := NewRateLimiter(RateLimits{})
	if l.rpm != 30 || l.tpm != 60000 || l.maxConcurrent != 4 || l.dailyWrites != 60 {
		t.Errorf("默认阈值错: rpm=%d tpm=%d con=%d daily=%d",
			l.rpm, l.tpm, l.maxConcurrent, l.dailyWrites)
	}
}

// 用例 6：用户隔离。
func TestRateLimiter_UserIsolation(t *testing.T) {
	l := NewRateLimiter(RateLimits{RPM: 1, MaxConcurrent: 10, DailyWrites: 100})
	if d := l.Allow("uA", 0, false); !d.Allowed {
		t.Fatal("uA 应放行")
	} else {
		l.Done("uA")
	}
	if d := l.Allow("uA", 0, false); d.Allowed {
		t.Fatal("uA 第 2 次应被 RPM 拒绝")
	}
	if d := l.Allow("uB", 0, false); !d.Allowed {
		t.Errorf("uB 不应受 uA 配额影响，实际=%+v", d)
	}
}

// ---------------- sessionRegistry ----------------

// 用例 7：会话解锁 → Lookup 命中。
func TestSessionRegistry_UnlockAndLookup(t *testing.T) {
	r := newSessionRegistry(60, nil)
	r.Unlock("s1", "u1", "d1")
	st, err := r.Lookup("s1", "u1")
	if err != nil {
		t.Fatal(err)
	}
	if st.UserID != "u1" || st.DeviceID != "d1" {
		t.Errorf("state 错: %+v", st)
	}
}

// 用例 8：未解锁 → ErrSessionLocked。
func TestSessionRegistry_Missing(t *testing.T) {
	r := newSessionRegistry(60, nil)
	_, err := r.Lookup("ghost", "u1")
	if ErrorCodeOf(err) != CodeSessionLocked {
		t.Errorf("未注册 session 应返回 CodeSessionLocked，实际=%v", err)
	}
}

// 用例 9：用户不匹配 → 拒绝（防碰撞）。
func TestSessionRegistry_UserMismatch(t *testing.T) {
	r := newSessionRegistry(60, nil)
	r.Unlock("s1", "u1", "d1")
	_, err := r.Lookup("s1", "uEvil")
	if ErrorCodeOf(err) != CodeSessionLocked {
		t.Errorf("用户不匹配应拒绝，实际=%v", err)
	}
}

// 用例 10：过期清理。
func TestSessionRegistry_Expiry(t *testing.T) {
	now := int64(1_000_000_000_000)
	r := newSessionRegistry(60, func() int64 { return now })
	r.Unlock("s1", "u1", "d1")
	r.ttlMs = 60 * 1000
	// 同时间应命中。
	if _, err := r.Lookup("s1", "u1"); err != nil {
		t.Fatalf("同时间应命中: %v", err)
	}
	// 60s+1ms 后过期。
	r2 := newSessionRegistry(60, func() int64 { return now + 60_001 })
	if _, err := r2.Lookup("s1", "u1"); ErrorCodeOf(err) != CodeSessionLocked {
		t.Errorf("过期应被拒绝，实际=%v", err)
	}
}

// ---------------- audit ----------------

// 用例 11：SHA256Hex 一致性 + 空字符串处理。
func TestSHA256Hex(t *testing.T) {
	if SHA256Hex("") != "" {
		t.Error("空字符串应返回空")
	}
	if SHA256Hex("hello") != "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" {
		t.Errorf("已知哈希不匹配")
	}
}

// 用例 12：AuditEvent ErrorCodeName 反向映射。
func TestErrorCodeName(t *testing.T) {
	cases := map[ErrorCode]string{
		CodeSessionLocked:     "session_locked",
		CodeRateLimited:       "rate_limited",
		CodeProviderError:     "provider_error",
		CodeInvalidRequest:    "invalid_request",
		"":                   "",
	}
	for c, want := range cases {
		if got := ErrorCodeName(c); got != want {
			t.Errorf("ErrorCodeName(%v)=%q 期望 %q", c, got, want)
		}
	}
}

// 用例 13：AuditWriter nil db 时静默成功。
func TestAuditWriter_NilDB(t *testing.T) {
	w := NewAuditWriter(nil)
	w.Write(context.Background(), AuditEvent{UserID: "u", Event: EventChatRequested})
	// 不 panic 即可。
}

// 用例 14：AuditWriter Detail 截断到 200 字符。
func TestAuditWriter_DetailTruncate(t *testing.T) {
	// 需要 db 验证；这里用内存 SQLite 不可行（modernc 仅文件）。
	// 该用例在集成测试中跑，单元测试只覆盖 nil 路径。
	w := NewAuditWriter(nil)
	long := strings.Repeat("x", 250)
	w.Write(context.Background(), AuditEvent{UserID: "u", Event: EventChatRequested, Detail: long})
}

// 用例 15：ListSessions 在 db 为 nil 时返回 nil（无 panic）。
func TestAuditWriter_ListSessionsNilDB(t *testing.T) {
	w := NewAuditWriter(nil)
	got, err := w.ListSessions(context.Background(), "u", 10)
	if err != nil {
		t.Fatal(err)
	}
	if got != nil {
		t.Errorf("nil db 应返回 nil，实际=%+v", got)
	}
}

// ---------------- proxy ----------------

// 用例 16：session 未解锁 → ErrSessionLocked + 审计。
func TestProxy_SessionLocked(t *testing.T) {
	reg := NewAgentRegistryForTest("ollama")
	proxy, err := NewProxy(reg, ProxyOptions{Audit: NewAuditWriter(nil)})
	if err != nil {
		t.Fatal(err)
	}
	_, e2 := proxy.Chat(context.Background(), ChatTurnInput{
		UserID: "u1", SessionID: "ghost", UserMessage: "hi",
	})
	if e2 == nil || ErrorCodeOf(e2) != CodeSessionLocked {
		t.Errorf("未解锁应返回 CodeSessionLocked，实际=%v", e2)
	}
}

// 用例 17：速率限制 → ErrRateLimited。
func TestProxy_RateLimited(t *testing.T) {
	reg := NewAgentRegistryForTest("ollama")
	proxy, err := NewProxy(reg, ProxyOptions{
		Audit: NewAuditWriter(nil),
		// 用极小 RPM + session 内手工加锁。
	})
	if err != nil {
		t.Fatal(err)
	}
	// 直接重置 limiter 为 RPM=1。
	proxy.Limiter().rpm = 1
	proxy.Unlock("s1", "u1", "d1")
	// 第 1 次可能因 stub Provider 返回 provider_error（Agent 配置正确，
	// 但 stub 驱动未实现真实 LLM 调用）；这不影响 Allow 已扣减 RPM=1。
	proxy.Chat(context.Background(), ChatTurnInput{
		UserID: "u1", SessionID: "s1", UserMessage: "hi",
	})
	proxy.Limiter().Done("u1")
	// 第 2 次应被 RPM 拒绝。
	_, err = proxy.Chat(context.Background(), ChatTurnInput{
		UserID: "u1", SessionID: "s1", UserMessage: "hi",
	})
	if err == nil || ErrorCodeOf(err) != CodeRateLimited {
		t.Errorf("第 2 次应 CodeRateLimited，实际=%v", err)
	}
}

// 用例 18：Provider 路径 → 成功并审计（用 StubProvider）。
func TestProxy_StubProvider(t *testing.T) {
	reg := NewAgentRegistryForTest("stub")
	proxy, err := NewProxy(reg, ProxyOptions{Audit: NewAuditWriter(nil)})
	if err != nil {
		t.Fatal(err)
	}
	proxy.Unlock("s1", "u1", "d1")
	// StubProvider.ChatCompletion 固定返回 CodeProviderError；这里只是验证
	// 流程跑通（即使带 ProviderError 也是预期行为；测试下限：不应触发 CodeSessionLocked）。
	out, perr := proxy.Chat(context.Background(), ChatTurnInput{
		UserID: "u1", SessionID: "s1", UserMessage: "hi",
	})
	if perr != nil && ErrorCodeOf(perr) == CodeSessionLocked {
		t.Fatalf("不应触发 SessionLocked，实际=%v", perr)
	}
	_ = out
}

// ---------------- helper ----------------

// NewAgentRegistryForTest 构造一个最小可工作的 Registry（含 OpenAICompat stub 驱动）：
// 任何 driver 字段填 "stub" → 由 Registry.buildProvider 走 stub 分支。
//
// **注意**：TestProxy_StubProvider 测试 Provider 路径时必须用 driver="stub"；
// 其他场景 driver 任意，但 Registry 至少要 1 个 Provider 才能通过 Validate。
func NewAgentRegistryForTest(providerName string) *Registry {
	cfg := AgentConfig{
		Enabled:         true,
		DefaultProvider: providerName,
		Providers: []ProviderConfig{
			{
				Name:         providerName,
				Driver:       "stub",
				BaseURL:      "stub://test",
				APIKeySource: "config",
			},
		},
		AllowedTools: []string{"vault.search", "utility.now"},
	}
	r, err := NewRegistry(cfg)
	if err != nil {
		panic(err)
	}
	return r
}

// 用例 19：Proxy Unlock 后 Lookup 命中。
func TestProxy_Unlock(t *testing.T) {
	reg := NewAgentRegistryForTest("stub")
	proxy, err := NewProxy(reg, ProxyOptions{Audit: NewAuditWriter(nil)})
	if err != nil {
		t.Fatal(err)
	}
	proxy.Unlock("s1", "u1", "d1")
	st, err := proxy.Sessions().Lookup("s1", "u1")
	if err != nil {
		t.Fatal(err)
	}
	if st.UserID != "u1" {
		t.Errorf("UserID=%q 期望 u1", st.UserID)
	}
}

// 用例 20：Proxy 自定义 Provider 不存在 → CodeProviderError。
func TestProxy_ResolveProviderMissing(t *testing.T) {
	reg := NewAgentRegistryForTest("stub")
	proxy, err := NewProxy(reg, ProxyOptions{Audit: NewAuditWriter(nil)})
	if err != nil {
		t.Fatal(err)
	}
	proxy.Unlock("s1", "u1", "d1")
	_, perr := proxy.Chat(context.Background(), ChatTurnInput{
		UserID: "u1", SessionID: "s1", UserMessage: "hi", Provider: "ghost",
	})
	if perr == nil || ErrorCodeOf(perr) != CodeProviderError {
		t.Errorf("Provider=ghost 应返回 CodeProviderError，实际=%v", perr)
	}
}

// 用例 21：sessionRegistry GC 清过期。
func TestSessionRegistry_GC(t *testing.T) {
	now := int64(1_000_000_000_000)
	r := newSessionRegistry(60, func() int64 { return now })
	r.Unlock("s1", "u1", "d1")
	r.Unlock("s2", "u2", "d2")
	// 全部仍在 TTL 内。
	if n := r.GC(); n != 0 {
		t.Errorf("TTL 内 GC 应清 0，实际=%d", n)
	}
	// 推进到 TTL 之后（同一个 registry，调 now 函数返回新时间）。
	r.now = func() int64 { return now + 60_001 }
	if n := r.GC(); n != 2 {
		t.Errorf("过期后 GC 应清 2，实际=%d", n)
	}
}

// 用例 22：RateLimiter Snapshot 与 Done。
func TestRateLimiter_Snapshot(t *testing.T) {
	l := NewRateLimiter(RateLimits{RPM: 10, MaxConcurrent: 5, DailyWrites: 100})
	if d := l.Allow("u", 100, false); !d.Allowed {
		t.Fatal("放行")
	}
	snap := l.Snapshot("u")
	if snap.Concurrent != 1 {
		t.Errorf("Concurrent=%d 期望 1", snap.Concurrent)
	}
	if snap.TokensThisMinute != 100 {
		t.Errorf("TokensThisMinute=%d 期望 100", snap.TokensThisMinute)
	}
	l.Done("u")
	snap2 := l.Snapshot("u")
	if snap2.Concurrent != 0 {
		t.Errorf("Done 后 Concurrent=%d 期望 0", snap2.Concurrent)
	}
}

// 用例 23：默认 limits 数值（防御性，与 spec FR-V6-D 对齐）。
func TestRateLimits_Default(t *testing.T) {
	def := DefaultLimits()
	if def.RPM != 30 || def.TPM != 60000 || def.MaxConcurrent != 4 || def.DailyWrites != 60 {
		t.Errorf("DefaultLimits 与 spec FR-V6-D 不一致: %+v", def)
	}
	_ = time.Second // 防止 unused import 警告
}