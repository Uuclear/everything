package agent

import (
	"sync"
	"time"
)

// RateLimiter 是阶段 6 Agent 模块的速率限制器（TR-2.4 / FR-V6-D）：
//   - 单用户每分钟请求数（RPM，默认 30）
//   - 单用户每分钟 token 数（TPM，默认 60 000）
//   - 单用户并发请求数（默认 4）
//   - 单用户每日调用写工具次数（默认 60）
//
// 进程内固定窗口足够阶段 6 自托管单实例；远期需换成共享存储（Redis）。
// 锁粒度：每个键一把 sync.Mutex，跨键通过 ToMu 协调删除清理。
//
// 三类决策（按优先级顺序）：
//   1. 若当前并发已达上限 → 拒绝（CodeRateLimited / "concurrent"）。
//   2. 若今日写工具已达上限 → 拒绝（CodeRateLimited / "daily_write_tools"）。
//   3. 若本分钟已超过 RPM / TPM → 拒绝（CodeRateLimited / "rpm"|"tpm"）。
//   4. 否则放行并扣减配额。
//
// **不可见性**：写工具计数与 RPM/TPM 在 Allow 成功后立刻扣减；并发计数由 Inc/Dec 控制。
type RateLimiter struct {
	rpm           int
	tpm           int
	maxConcurrent int
	dailyWrites   int

	mu      sync.Mutex
	buckets map[string]*rateBucket
}

type rateBucket struct {
	// minuteMsWindowStart 本分钟窗口起点（unix 毫秒）
	minuteMsWindowStart int64
	// requests 本分钟已用请求数
	requests int
	// tokens 本分钟已用 token 数
	tokens int
	// concurrent 当前并发计数
	concurrent int
	// dayStart UTC 日起点（unix 秒，用于日写工具窗口）
	dayStart int64
	// writeTools 今日已用写工具次数
	writeTools int
}

// RateLimits 默认值（可由配置驱动覆盖，Task 3+ 衔接；Task 2 仅暴露构造函数）。
type RateLimits struct {
	RPM           int
	TPM           int
	MaxConcurrent int
	DailyWrites   int
}

// DefaultLimits 返回阶段 6 默认阈值（与 spec FR-V6-D 一致）。
func DefaultLimits() RateLimits {
	return RateLimits{
		RPM:           30,
		TPM:           60000,
		MaxConcurrent: 4,
		DailyWrites:   60,
	}
}

// NewRateLimiter 创建限流器。传入 0 字段使用默认值。
func NewRateLimiter(lim RateLimits) *RateLimiter {
	def := DefaultLimits()
	if lim.RPM <= 0 {
		lim.RPM = def.RPM
	}
	if lim.TPM <= 0 {
		lim.TPM = def.TPM
	}
	if lim.MaxConcurrent <= 0 {
		lim.MaxConcurrent = def.MaxConcurrent
	}
	if lim.DailyWrites <= 0 {
		lim.DailyWrites = def.DailyWrites
	}
	return &RateLimiter{
		rpm:           lim.RPM,
		tpm:           lim.TPM,
		maxConcurrent: lim.MaxConcurrent,
		dailyWrites:   lim.DailyWrites,
		buckets:       make(map[string]*rateBucket),
	}
}

// nowMsMillis 当前 unix 毫秒（解耦 wall clock 便于单测）。
var nowMsMillis = func() int64 { return time.Now().UnixMilli() }

// utcDayStart 返回指定时间戳对应的 UTC 日起点（unix 秒）。
var utcDayStart = func(t time.Time) int64 {
	t = t.UTC()
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC).Unix()
}

// rateBucketApply 调整窗口起点与计数；调用方须持 RateLimiter.mu。
func (l *RateLimiter) bucketApply(b *rateBucket, nowMs int64) {
	// 分钟窗口：滚动到当前分钟。
	minuteStart := nowMs / 60000 * 60000
	if b.minuteMsWindowStart != minuteStart {
		b.minuteMsWindowStart = minuteStart
		b.requests = 0
		b.tokens = 0
	}
	// 日窗口：滚动到 UTC 今日。
	daySec := utcDayStart(time.UnixMilli(nowMs))
	if b.dayStart != daySec {
		b.dayStart = daySec
		b.writeTools = 0
	}
}

// RateDecision 是 Allow 的返回值。
type RateDecision struct {
	// Allowed 是否允许本次调用。
	Allowed bool
	// Reason 当 Allowed=false 时填写："rpm"|"tpm"|"concurrent"|"daily_write_tools"。
	Reason string
	// RetryAfterMs 推荐重试间隔（毫秒），仅拒绝时填写。
	RetryAfterMs int64
}

// Allow 决定是否允许本次调用，并在 Allowed=true 时同步扣减配额。
//
// userID 用户标识；
// tokens 本次预估的 prompt_tokens（用于 TPM 扣减）；
// isWrite 是否为写工具调用（用于日写工具配额）。
//
// 失败时不扣减（前置检查）。
func (l *RateLimiter) Allow(userID string, tokens int, isWrite bool) RateDecision {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[userID]
	if b == nil {
		b = &rateBucket{}
		l.buckets[userID] = b
	}
	now := nowMsMillis()
	l.bucketApply(b, now)

	// 并发检查。
	if b.concurrent >= l.maxConcurrent {
		// 拒时不扣减；但要给一个小的退避。
		return RateDecision{Allowed: false, Reason: "concurrent", RetryAfterMs: 1000}
	}
	// 日写工具检查。
	if isWrite && b.writeTools >= l.dailyWrites {
		daySec := utcDayStart(time.Now())
		endOfDayMs := (daySec + 86400) * 1000
		return RateDecision{
			Allowed:     false,
			Reason:      "daily_write_tools",
			RetryAfterMs: endOfDayMs - now,
		}
	}
	// RPM 检查。
	if b.requests >= l.rpm {
		nextMinuteMs := (b.minuteMsWindowStart/60000 + 1) * 60000
		return RateDecision{Allowed: false, Reason: "rpm", RetryAfterMs: nextMinuteMs - now}
	}
	// TPM 检查。
	if tokens > 0 && b.tokens+tokens > l.tpm {
		nextMinuteMs := (b.minuteMsWindowStart/60000 + 1) * 60000
		return RateDecision{Allowed: false, Reason: "tpm", RetryAfterMs: nextMinuteMs - now}
	}

	// 通过：扣减配额 + 提升并发。
	b.requests++
	if tokens > 0 {
		b.tokens += tokens
	}
	if isWrite {
		b.writeTools++
	}
	b.concurrent++
	return RateDecision{Allowed: true}
}

// Done 释放并发槽（无论成功失败都必须调用，对称于 Allow）。
// 建议通过 defer 调用。
func (l *RateLimiter) Done(userID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[userID]
	if b == nil {
		return
	}
	if b.concurrent > 0 {
		b.concurrent--
	}
}

// Snapshot 返回某用户的当前速率使用快照（不修改状态；用于探测 / 监控）。
type UsageSnapshot struct {
	MinuteWindowStartMs int64
	RequestsThisMinute  int
	TokensThisMinute    int
	Concurrent          int
	DayStartSec         int64
	WriteToolsToday     int
}

// Snapshot 在持锁情况下读取用户桶状态。
func (l *RateLimiter) Snapshot(userID string) UsageSnapshot {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[userID]
	if b == nil {
		return UsageSnapshot{}
	}
	return UsageSnapshot{
		MinuteWindowStartMs: b.minuteMsWindowStart,
		RequestsThisMinute:  b.requests,
		TokensThisMinute:    b.tokens,
		Concurrent:          b.concurrent,
		DayStartSec:         b.dayStart,
		WriteToolsToday:     b.writeTools,
	}
}

// Limits 暴露当前阈值（只读）。
func (l *RateLimiter) Limits() RateLimits {
	return RateLimits{
		RPM:           l.rpm,
		TPM:           l.tpm,
		MaxConcurrent: l.maxConcurrent,
		DailyWrites:   l.dailyWrites,
	}
}