package agent

import (
	"errors"
	"sync"
	"testing"
	"time"
)

// 固定密钥（≥32 字节）便于跨用例复用。
var testSessionKey = []byte("test-agent-session-signing-key-32-bytes!")

// newTestSessionManager 构造 SessionManager 并注入固定时钟。
// 用 wall clock 的当前时刻（向下取整到秒），避免 JWT 库判定 iat 早于 now + leeway 时拒绝。
// 测试用例通过修改 *cur 推进虚拟时钟。
func newTestSessionManager(t *testing.T) (*SessionManager, *time.Time) {
	t.Helper()
	base := time.Now().Truncate(time.Second)
	cur := base
	mgr, err := NewSessionManager(SessionManagerOptions{
		SigningKey: testSessionKey,
		SessionTTL: int(DefaultUnlockTTL.Seconds()),
		Now:        func() time.Time { return cur },
	})
	if err != nil {
		t.Fatalf("NewSessionManager: %v", err)
	}
	return mgr, &cur
}

// 用例 1：Unlock 生成 token + SessionID + ExpiresAt。
func TestSessionManager_Unlock(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res, err := mgr.Unlock("u1", "d1")
	if err != nil {
		t.Fatalf("Unlock: %v", err)
	}
	if res.Token == "" {
		t.Error("Token 为空")
	}
	if res.SessionID == "" || len(res.SessionID) != 64 {
		t.Errorf("SessionID=%q 应为 64 hex 字符", res.SessionID)
	}
	if res.ExpiresAt.IsZero() {
		t.Error("ExpiresAt 未设置")
	}
}

// 用例 2：Validate 通过 + SessionState.SessionID 一致。
func TestSessionManager_Validate_OK(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	st, err := mgr.Validate(res.Token, "u1", "d1")
	if err != nil {
		t.Fatalf("Validate: %v", err)
	}
	if st.SessionID != res.SessionID {
		t.Errorf("SessionState.SessionID=%q 不匹配 Unlock 返回=%q", st.SessionID, res.SessionID)
	}
	if st.UserID != "u1" || st.DeviceID != "d1" {
		t.Errorf("SessionState 字段错误: %+v", st)
	}
}

// 用例 3：同 device 重复 Unlock 派生不同 session_id（random 部分）。
func TestSessionManager_Unlock_SameDeviceDifferentSession(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	r1, _ := mgr.Unlock("u1", "d1")
	r2, _ := mgr.Unlock("u1", "d1")
	if r1.SessionID == r2.SessionID {
		t.Error("同 device 重复 Unlock 应派生不同 session_id")
	}
}

// 用例 4：不同 device → 不同 session_id（多设备隔离）。
func TestSessionManager_Unlock_DeviceIsolation(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	r1, _ := mgr.Unlock("u1", "d1")
	r2, _ := mgr.Unlock("u1", "d2")
	if r1.SessionID == r2.SessionID {
		t.Error("不同 device 应派生不同 session_id")
	}
}

// 用例 5：JWT exp 过期 → ErrSessionExpired。
func TestSessionManager_Validate_Expired(t *testing.T) {
	mgr, cur := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	*cur = cur.Add(2 * DefaultUnlockTTL)
	_, err := mgr.Validate(res.Token, "u1", "d1")
	if !errors.Is(err, ErrSessionExpired) {
		t.Errorf("期望 ErrSessionExpired，实际=%v", err)
	}
}

// 用例 6：sessionRegistry TTL 过期 → ErrSessionNotFound（Lookup 已删）。
func TestSessionManager_Validate_RegistryExpired(t *testing.T) {
	mgr, cur := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	*cur = cur.Add(2 * DefaultUnlockTTL)
	_, err := mgr.Validate(res.Token, "u1", "d1")
	if !errors.Is(err, ErrSessionExpired) && !errors.Is(err, ErrSessionNotFound) {
		t.Errorf("期望过期错误，实际=%v", err)
	}
}

// 用例 7：user 不匹配 → ErrSessionUserMismatch。
func TestSessionManager_Validate_UserMismatch(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	_, err := mgr.Validate(res.Token, "u2", "d1")
	if !errors.Is(err, ErrSessionUserMismatch) {
		t.Errorf("期望 ErrSessionUserMismatch，实际=%v", err)
	}
}

// 用例 8：device 不匹配 → ErrSessionDeviceMismatch（多设备隔离校验）。
func TestSessionManager_Validate_DeviceMismatch(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	_, err := mgr.Validate(res.Token, "u1", "d2")
	if !errors.Is(err, ErrSessionDeviceMismatch) {
		t.Errorf("期望 ErrSessionDeviceMismatch，实际=%v", err)
	}
}

// 用例 9：篡改 token 签名 → ErrInvalidSessionToken。
func TestSessionManager_Validate_TamperedToken(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	// 替换最后一个字符破坏签名。
	tampered := res.Token[:len(res.Token)-1] + "A"
	if tampered == res.Token {
		tampered = res.Token[:len(res.Token)-2] + "AB"
	}
	_, err := mgr.Validate(tampered, "u1", "d1")
	if !errors.Is(err, ErrInvalidSessionToken) {
		t.Errorf("期望 ErrInvalidSessionToken，实际=%v", err)
	}
}

// 用例 10：Refresh 续签 → 新 token + SessionID 保持不变 + TTL 延长。
func TestSessionManager_Refresh(t *testing.T) {
	mgr, cur := newTestSessionManager(t)
	res1, _ := mgr.Unlock("u1", "d1")
	// 推进 1 分钟，TTL 仍在。
	*cur = cur.Add(1 * time.Minute)
	res2, err := mgr.Refresh(res1.Token, "u1", "d1")
	if err != nil {
		t.Fatalf("Refresh: %v", err)
	}
	if res2.Token == res1.Token {
		t.Error("Refresh 后 token 应变更")
	}
	if res2.SessionID != res1.SessionID {
		t.Errorf("Refresh 后 SessionID 应保持，实际=%q 期望 %q", res2.SessionID, res1.SessionID)
	}
	if !res2.ExpiresAt.After(res1.ExpiresAt) {
		t.Errorf("Refresh 后 ExpiresAt=%v 应晚于 %v", res2.ExpiresAt, res1.ExpiresAt)
	}
}

// 用例 11：Refresh 旧 token 后旧 token 仍有效（注册表未删）。
// 设计取舍：服务端 TTL 内 refresh 不应立即踢掉旧 token；远期可加 jti 黑名单。
func TestSessionManager_Refresh_OldTokenStillValid(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res1, _ := mgr.Unlock("u1", "d1")
	res2, _ := mgr.Refresh(res1.Token, "u1", "d1")
	// 旧 token 仍可通过 Validate（同一 session_id 在 TTL 内）。
	if _, err := mgr.Validate(res1.Token, "u1", "d1"); err != nil {
		t.Errorf("Refresh 后旧 token 应仍有效，实际=%v", err)
	}
	if _, err := mgr.Validate(res2.Token, "u1", "d1"); err != nil {
		t.Errorf("Refresh 后新 token 应有效，实际=%v", err)
	}
}

// 用例 12：Lock 强制解锁 → Validate 返 ErrSessionNotFound。
func TestSessionManager_Lock(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	mgr.Lock(res.SessionID, "u1")
	_, err := mgr.Validate(res.Token, "u1", "d1")
	if !errors.Is(err, ErrSessionNotFound) {
		t.Errorf("Lock 后期望 ErrSessionNotFound，实际=%v", err)
	}
}

// 用例 13：Lock 越权（其他 user）→ 不影响原 session。
func TestSessionManager_Lock_NoCrossUser(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	mgr.Lock(res.SessionID, "u2") // 越权解锁
	if _, err := mgr.Validate(res.Token, "u1", "d1"); err != nil {
		t.Errorf("越权 Lock 不应影响原 user，实际=%v", err)
	}
}

// 用例 14：Lock 幂等（不存在 session_id 不报错）。
func TestSessionManager_Lock_Idempotent(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	mgr.Lock("nonexistent", "u1")
	mgr.Lock("", "u1") // 空字符串也静默
}

// 用例 15：SigningKey 不足 32 字节 → NewSessionManager 返错。
func TestNewSessionManager_ShortKey(t *testing.T) {
	_, err := NewSessionManager(SessionManagerOptions{SigningKey: []byte("short")})
	if err == nil {
		t.Error("短密钥应报错")
	}
}

// 用例 16：LoadOrCreateSessionKey 跨调用稳定（写入后能读回）。
func TestLoadOrCreateSessionKey_Stable(t *testing.T) {
	dir := t.TempDir()
	k1, err := LoadOrCreateSessionKey(dir)
	if err != nil {
		t.Fatalf("第 1 次加载: %v", err)
	}
	if len(k1) < 32 {
		t.Errorf("密钥长度=%d 期望 ≥32", len(k1))
	}
	k2, err := LoadOrCreateSessionKey(dir)
	if err != nil {
		t.Fatalf("第 2 次加载: %v", err)
	}
	if string(k1) != string(k2) {
		t.Error("两次加载的密钥应一致")
	}
}

// 用例 17：不同密钥签发的 token 无法互相校验（密钥绑定）。
func TestSessionManager_CrossKeyValidation(t *testing.T) {
	mgr1, _ := newTestSessionManager(t)
	res, _ := mgr1.Unlock("u1", "d1")
	mgr2, _ := NewSessionManager(SessionManagerOptions{
		SigningKey: []byte("another-different-signing-key-32-bytes!"),
		SessionTTL: int(DefaultUnlockTTL.Seconds()),
	})
	_, err := mgr2.Validate(res.Token, "u1", "d1")
	if !errors.Is(err, ErrInvalidSessionToken) {
		t.Errorf("异密钥应返 ErrInvalidSessionToken，实际=%v", err)
	}
}

// 用例 18：GC 清过期 session。
func TestSessionManager_GC(t *testing.T) {
	mgr, cur := newTestSessionManager(t)
	mgr.Unlock("u1", "d1")
	mgr.Unlock("u2", "d2")
	if n := mgr.GC(); n != 0 {
		t.Errorf("TTL 内 GC 应清 0，实际=%d", n)
	}
	*cur = cur.Add(2 * DefaultUnlockTTL)
	if n := mgr.GC(); n != 2 {
		t.Errorf("过期后 GC 应清 2，实际=%d", n)
	}
	if n := mgr.Active(); n != 0 {
		t.Errorf("过期后 Active 应返 0，实际=%d", n)
	}
}

// 用例 19：并发 Unlock 100 次不 panic 且每个 session_id 唯一。
func TestSessionManager_Concurrent_Unlock(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	const n = 100
	ids := make(chan string, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res, err := mgr.Unlock("u1", "d1")
			if err != nil {
				t.Errorf("并发 Unlock 失败: %v", err)
				return
			}
			ids <- res.SessionID
		}()
	}
	wg.Wait()
	close(ids)
	seen := map[string]bool{}
	for id := range ids {
		if seen[id] {
			t.Errorf("session_id 碰撞: %s", id)
		}
		seen[id] = true
	}
	if len(seen) != n {
		t.Errorf("唯一 session_id 数=%d 期望 %d", len(seen), n)
	}
}

// 用例 20：SessionIDFromToken 不验签解析（远期审计/日志使用）。
func TestSessionIDFromToken(t *testing.T) {
	mgr, _ := newTestSessionManager(t)
	res, _ := mgr.Unlock("u1", "d1")
	if got := SessionIDFromToken(res.Token); got != res.SessionID {
		t.Errorf("SessionIDFromToken=%q 期望 %q", got, res.SessionID)
	}
	if got := SessionIDFromToken(""); got != "" {
		t.Errorf("空 token 应返空，实际=%q", got)
	}
	if got := SessionIDFromToken("not-a-jwt"); got != "" {
		t.Errorf("非法 JWT 应返空，实际=%q", got)
	}
}
