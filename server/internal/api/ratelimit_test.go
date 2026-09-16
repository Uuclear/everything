package api

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/everything-personal/eve/internal/db"
)

// TestLimiterConcurrentSmoke 并发记录不 panic、状态一致（race 检测器需在支持的环境运行）。
func TestLimiterConcurrentSmoke(t *testing.T) {
	l := newAuthLimiter()
	now := time.Now()
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 5; j++ {
				_, _ = l.recordFailure(now, "auth-write|192.0.2.1")
			}
		}(i)
	}
	wg.Wait()
	if !l.locked(now, "auth-write|192.0.2.1") {
		t.Fatal("500 次并发失败后该键必须处于锁定态")
	}
	// 并发成功/失败混合不应崩溃或死锁。
	var wg2 sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg2.Add(2)
		go func() { defer wg2.Done(); l.recordSuccess("auth-write|192.0.2.2") }()
		go func(i int) {
			defer wg2.Done()
			_, _ = l.recordFailure(now, "auth-write|192.0.2.3")
		}(i)
	}
	wg2.Wait()
}

// TestLimiterWindowAndLock 白盒时序：5 次失败锁定，锁定过期后自动恢复；成功清零。
func TestLimiterWindowAndLock(t *testing.T) {
	l := newAuthLimiter()
	now := time.Date(2026, 9, 15, 10, 0, 0, 0, time.UTC)
	const key = "auth-write|203.0.113.7"

	// 前 4 次失败不锁定。
	for i := 0; i < 4; i++ {
		if locked, _ := l.recordFailure(now, key); locked {
			t.Fatalf("第 %d 次失败不应锁定", i+1)
		}
	}
	// 第 5 次失败触发新锁定。
	locked, justLocked := l.recordFailure(now, key)
	if !locked || !justLocked {
		t.Fatal("第 5 次失败应触发锁定且标记 justLocked")
	}
	if !l.locked(now.Add(time.Minute), key) {
		t.Fatal("锁定应持续 15 分钟")
	}
	// 锁定期内再记失败仍是锁定态，但不应重复 justLocked。
	if locked, justLocked := l.recordFailure(now.Add(2*time.Minute), key); !locked || justLocked {
		t.Fatal("锁定期内失败应保持锁定且不重复审计")
	}
	// 15 分钟后锁定解除。
	if l.locked(now.Add(16*time.Minute), key) {
		t.Fatal("锁定应在 15 分钟后解除")
	}

	// 成功清零：再来 4 次失败 + 成功 + 4 次失败，全程不应锁定。
	for i := 0; i < 4; i++ {
		if locked, _ := l.recordFailure(now.Add(17*time.Minute), key); locked {
			t.Fatal("新窗口内前 4 次失败不应锁定")
		}
	}
	l.recordSuccess(key)
	for i := 0; i < 4; i++ {
		if locked, _ := l.recordFailure(now.Add(18*time.Minute), key); locked {
			t.Fatal("成功重置后 4 次失败不应锁定")
		}
	}
}

// TestGuardAuthHTTP 端到端验证 guardAuth：第 6 次请求被拦截 429，内部处理器不再执行，
// 且 audit_logs 留下 auth_locked 记录（TR-2.1/2.2）。
func TestGuardAuthHTTP(t *testing.T) {
	database, err := db.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	srv := &Server{db: database, limiter: newAuthLimiter()}

	calls := 0
	// 内部处理器始终失败：前 5 次应进入处理器并返回 401。
	h := srv.guardAuth(rateCategoryAuth, func(w http.ResponseWriter, r *http.Request) authResult {
		calls++
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "用户名或密码错误")
		return authFailed
	})
	do := func() int {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", nil)
		req.RemoteAddr = "198.51.100.9:4321" // 固定 IP
		rec := httptest.NewRecorder()
		h(rec, req)
		return rec.Code
	}
	for i := 0; i < 5; i++ {
		if code := do(); code != http.StatusUnauthorized {
			t.Fatalf("第 %d 次失败应得 401，实际 %d", i+1, code)
		}
	}
	if calls != 5 {
		t.Fatalf("前 5 次都应进入处理器，实际进入 %d 次", calls)
	}
	// 第 6 次：即便凭证正确（处理器本会放行），锁定中间件也直接 429。
	called6th := false
	hOK := srv.guardAuth(rateCategoryAuth, func(w http.ResponseWriter, r *http.Request) authResult {
		called6th = true
		w.WriteHeader(http.StatusOK)
		return authSucceeded
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", nil)
	req.RemoteAddr = "198.51.100.9:4321"
	rec := httptest.NewRecorder()
	hOK(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("第 6 次应被限流 429，实际 %d", rec.Code)
	}
	if called6th {
		t.Fatal("锁定后不应进入内部处理器")
	}

	// 锁定事件必须有审计行。
	var n int
	if err := database.QueryRow(
		`SELECT COUNT(1) FROM audit_logs WHERE event='auth_locked'`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("auth_locked 审计应恰好 1 行，实际 %d", n)
	}

	// 其他 IP 不受影响：成功处理器正常返回 200（而非 429）。
	reqOther := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", nil)
	reqOther.RemoteAddr = "198.51.100.10:1234"
	recOther := httptest.NewRecorder()
	hOK(recOther, reqOther)
	if recOther.Code != http.StatusOK {
		t.Fatalf("限流必须按 IP 隔离，其他 IP 应正常处理得到 200，实际 %d", recOther.Code)
	}
}
