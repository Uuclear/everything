package api

import (
	"net/http"
	"sync"
	"time"
)

// 敏感认证端点共享同一个失败计数桶（login / recovery start / totp verify）：
// 任一端点的失败都消耗同一 IP 的尝试额度，防止攻击者在三个端点间轮换爆破。
const rateCategoryAuth = "auth-write"

// 默认阈值：15 分钟窗口内累计 5 次失败即锁定 15 分钟（FR-24）。
const (
	defaultFailLimit  = 5
	defaultFailWindow = 15 * time.Minute
	defaultLockWindow = 15 * time.Minute
)

// failBucket 是单个 "类别+IP" 的计数状态。
type failBucket struct {
	windowStart time.Time // 当前计数窗口起点
	failures    int       // 窗口内失败次数
	lockedUntil time.Time // 锁定截止时间；零值表示未锁定
}

// authLimiter 是进程内固定窗口失败限流器。
// 单实例自托管部署足够；多实例需换成共享存储（远期）。
type authLimiter struct {
	failLimit  int
	failWindow time.Duration
	lockWindow time.Duration

	mu      sync.Mutex
	buckets map[string]*failBucket
	ops     int // 自上次清理以来的操作计数，用于概率触发懒清理
}

func newAuthLimiter() *authLimiter {
	return &authLimiter{
		failLimit:  defaultFailLimit,
		failWindow: defaultFailWindow,
		lockWindow: defaultLockWindow,
		buckets:    make(map[string]*failBucket),
	}
}

// locked 判断键当前是否处于锁定态；过期的锁定会被就地清除。
func (l *authLimiter) locked(now time.Time, key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[key]
	if b == nil {
		return false
	}
	if !b.lockedUntil.IsZero() && !now.Before(b.lockedUntil) {
		// 锁定已结束：清零计数，给出一次全新窗口。
		b.lockedUntil = time.Time{}
		b.windowStart = now
		b.failures = 0
		return false
	}
	return !b.lockedUntil.IsZero()
}

// recordFailure 记录一次失败，返回当前是否处于锁定态以及本次是否触发"新锁定"
// （新锁定用于写一次审计，避免每次 429 都刷审计行）。
func (l *authLimiter) recordFailure(now time.Time, key string) (locked, justLocked bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[key]
	if b == nil {
		b = &failBucket{}
		l.buckets[key] = b
	}
	if !b.lockedUntil.IsZero() {
		if now.Before(b.lockedUntil) {
			return true, false // 已在锁定期内
		}
		// 锁定刚过期：以本次失败开启新窗口。
		b.lockedUntil = time.Time{}
		b.windowStart = time.Time{}
		b.failures = 0
	}
	if b.windowStart.IsZero() || now.Sub(b.windowStart) > l.failWindow {
		b.windowStart = now
		b.failures = 0
	}
	b.failures++
	if b.failures >= l.failLimit {
		b.lockedUntil = now.Add(l.lockWindow)
		l.ops++
		l.lockedCleanupLocked(now)
		return true, true
	}
	l.ops++
	l.lockedCleanupLocked(now)
	return false, false
}

// recordSuccess 成功响应清零该键计数（登录成功即"原谅"此前的失败）。
func (l *authLimiter) recordSuccess(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.buckets, key)
}

// lockedCleanupLocked 懒清理：每 256 次操作扫描一次，删除既未锁定、
// 计数窗口也早已过期的键，避免不活跃 IP 长期占内存。调用方须持锁。
func (l *authLimiter) lockedCleanupLocked(now time.Time) {
	if l.ops < 256 {
		return
	}
	l.ops = 0
	for k, b := range l.buckets {
		if !b.lockedUntil.IsZero() && now.Before(b.lockedUntil) {
			continue // 锁定期内保留
		}
		if b.windowStart.IsZero() || now.Sub(b.windowStart) > l.failWindow {
			delete(l.buckets, k)
		}
	}
}

// authResult 表示一次受限流保护的认证尝试的处理结果。
type authResult int

const (
	authFailed    authResult = iota // 凭证错误等失败：计数 +1
	authSucceeded                   // 成功：清零计数
	authSkipped                     // 与爆破无关的请求（如请求体非法）：不计数
)

// guardAuth 包装敏感认证处理器：
//   - 进入时若该 IP 已锁定，直接 429（审计 auth_rate_limited）；
//   - 内部处理器返回 authResult：失败计数、成功清零；
//   - 失败计数触发"新锁定"时审计一次 auth_locked。
//
// 处理器自身负责业务失败审计（login_failed 等），这里只做计数与锁定审计。
func (s *Server) guardAuth(category string, h func(http.ResponseWriter, *http.Request) authResult) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		key := category + "|" + r.RemoteAddr
		if s.limiter.locked(time.Now(), key) {
			s.audit("", "auth_rate_limited", "category="+category, r.RemoteAddr)
			writeError(w, http.StatusTooManyRequests, "rate_limited",
				"失败尝试过多，请 15 分钟后再试")
			return
		}
		result := h(w, r)
		switch result {
		case authSucceeded:
			s.limiter.recordSuccess(key)
		case authFailed:
			if _, justLocked := s.limiter.recordFailure(time.Now(), key); justLocked {
				s.audit("", "auth_locked", "category="+category, r.RemoteAddr)
			}
		}
	}
}
