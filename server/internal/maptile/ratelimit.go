// Package maptile 提供同源瓦片代理：上游拉取、磁盘缓存、按 IP 轻量限流。
package maptile

import (
	"sync"
	"time"
)

// IPRateLimiter 进程内按 IP 的固定分钟窗口请求计数（仅瓦片代理使用）。
type IPRateLimiter struct {
	rpm int

	mu      sync.Mutex
	buckets map[string]*ipBucket
}

type ipBucket struct {
	windowStart time.Time
	count       int
}

// NewIPRateLimiter 创建限流器；rpm≤0 时默认 240（约 4 rps，够平移缩放一批瓦片）。
func NewIPRateLimiter(rpm int) *IPRateLimiter {
	if rpm <= 0 {
		rpm = 240
	}
	return &IPRateLimiter{rpm: rpm, buckets: make(map[string]*ipBucket)}
}

// Allow 本分钟窗口内未超限则计数 +1 并返回 true。
func (l *IPRateLimiter) Allow(ip string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[ip]
	if b == nil {
		b = &ipBucket{windowStart: now}
		l.buckets[ip] = b
	}
	if now.Sub(b.windowStart) >= time.Minute {
		b.windowStart = now
		b.count = 0
	}
	if b.count >= l.rpm {
		return false
	}
	b.count++
	return true
}
