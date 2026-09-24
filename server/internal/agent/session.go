// Package agent — session.go
//
// Agent 会话状态机 + JWT 解锁 token（TR-4.1 / TR-4.4 / TR-4.5）。
//
// 设计要点：
//   - 解锁 token 是 access_token 之上的二次解锁（access_token 仅证明 "你登录过"，解锁 token 证明 "你刚解锁了 MK"）。
//   - 密钥独立：与 auth.Service 的 jwt.key 解耦；调用方从 cfg.DataDir/agent_session.key 派生。
//   - 多设备隔离：session_id = "<device_id>:<random>"，Validate 强制校验 device_id 匹配，
//     防止 A 设备的解锁 token 被 B 设备"接管"。
//   - 三不存落地：token claims 仅含 user_id / device_id / session_id / jti / iat / exp，
//     不含主密码 / MK / API Key；服务端不持久化解锁 token（仅在内存 sessionRegistry 登记 session_id）。
package agent

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// 解锁 token 默认有效期（15 分钟）。
const DefaultUnlockTTL = 15 * time.Minute

// Agent 会话相关的错误。
var (
	// ErrSessionNotFound session_id 在注册表中不存在（已过期 / 被强制解锁）。
	ErrSessionNotFound = errors.New("agent session not found")
	// ErrSessionExpired 解锁 token 已过期或 session TTL 已到。
	ErrSessionExpired = errors.New("agent session expired")
	// ErrSessionUserMismatch session_id 与 user_id 不匹配（防碰撞 / 越权）。
	ErrSessionUserMismatch = errors.New("agent session user mismatch")
	// ErrSessionDeviceMismatch session_id 与 device_id 不匹配（多设备隔离）。
	ErrSessionDeviceMismatch = errors.New("agent session device mismatch")
	// ErrInvalidSessionToken JWT 解析 / 签名校验失败。
	ErrInvalidSessionToken = errors.New("invalid agent session token")
)

// SessionClaims 是 Agent 解锁 token 的业务字段。
// 字段命名沿用 auth 包风格：JSON 用 snake_case（与客户端协议对齐）。
//
// 三不存：不含主密码 / MK / API Key / 主密码哈希。
type SessionClaims struct {
	UserID    string `json:"sub"`           // 用户 ID（与 access_token 的 sub 一致）
	DeviceID  string `json:"device_id"`     // 当前设备 ID（防设备间 token 复用）
	SessionID string `json:"session_id"`    // 服务端 session_id（随机派生）
	jwt.RegisteredClaims                    // exp / iat / jti / nbf
}

// SessionManager 负责 Agent 解锁 token 的签发 / 校验 + 会话注册表写入。
//
// 线程安全：内部有 mu 锁 + 复用 sessionRegistry。
type SessionManager struct {
	signKey []byte        // HS256 签名密钥（调用方从 dataDir/agent_session.key 派生）
	reg     *sessionRegistry // 复用 Task 2 的 sessionRegistry（LOCKED/UNLOCKED 状态机）
	mu      sync.Mutex     // 保护 token 签发 / 注销的并发安全
	now     func() time.Time
}

// SessionManagerOptions 构造参数。
type SessionManagerOptions struct {
	// SigningKey 必填（HS256 密钥，>=32 字节）。
	SigningKey []byte
	// SessionTTL session 在注册表中的存活时长（秒）；<=0 默认 15 分钟。
	SessionTTL int
	// Now 可选：注入 wall clock 便于单测。
	Now func() time.Time
}

// NewSessionManager 构造 Agent 会话管理器。SigningKey 必填。
func NewSessionManager(opt SessionManagerOptions) (*SessionManager, error) {
	if len(opt.SigningKey) < 32 {
		return nil, fmt.Errorf("agent.NewSessionManager: SigningKey 至少 32 字节")
	}
	if opt.SessionTTL <= 0 {
		opt.SessionTTL = int(DefaultUnlockTTL.Seconds())
	}
	now := opt.Now
	if now == nil {
		now = time.Now
	}
	return &SessionManager{
		signKey: opt.SigningKey,
		reg:     newSessionRegistry(opt.SessionTTL, func() int64 { return now().UnixMilli() }),
		now:     now,
	}, nil
}

// LoadOrCreateSessionKey 从 dataDir/agent_session.key 读取或生成密钥。
// 与 auth.Service 的 jwt.key 解耦，避免一处泄漏连带 Agent 解锁 token 失守。
func LoadOrCreateSessionKey(dataDir string) ([]byte, error) {
	if dataDir == "" {
		return nil, errors.New("agent.LoadOrCreateSessionKey: dataDir 为空")
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, fmt.Errorf("agent_session 数据目录创建失败: %w", err)
	}
	p := filepath.Join(dataDir, "agent_session.key")
	b, err := os.ReadFile(p)
	if err == nil {
		if len(b) < 32 {
			return nil, fmt.Errorf("agent_session.key 长度不足 32 字节：%d", len(b))
		}
		return b, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, fmt.Errorf("agent_session.key 读取失败: %w", err)
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return nil, fmt.Errorf("agent_session.key 随机生成失败: %w", err)
	}
	if err := os.WriteFile(p, buf, 0o600); err != nil {
		return nil, fmt.Errorf("agent_session.key 写入失败: %w", err)
	}
	return buf, nil
}

// UnlockResult 是 Unlock 的返回值。
type UnlockResult struct {
	Token     string    // JWT 解锁 token（客户端持有）
	SessionID string    // 服务端 session_id（回显便于客户端持久化）
	ExpiresAt time.Time // 解锁 token 失效时刻
}

// Unlock 为指定 user+device 签发新的解锁 token。
//
//   - session_id 派生：sha256(device_id || random) 取 16 字节 hex；
//     防碰撞：同一 device 短时间内重复调用会得到不同 session_id（random 部分）；
//     多设备隔离：不同 device 永远派生不同 session_id。
//   - 注册表写入：通过 sessionRegistry 登记 SessionState（带 TTL）。
func (m *SessionManager) Unlock(userID, deviceID string) (UnlockResult, error) {
	if userID == "" || deviceID == "" {
		return UnlockResult{}, errors.New("agent.SessionManager.Unlock: userID / deviceID 必填")
	}
	m.mu.Lock()
	defer m.mu.Unlock()

	sessionID, err := deriveSessionID(deviceID)
	if err != nil {
		return UnlockResult{}, err
	}

	now := m.now()
	exp := now.Add(DefaultUnlockTTL)
	claims := SessionClaims{
		UserID:    userID,
		DeviceID:  deviceID,
		SessionID: sessionID,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(exp),
			ID:        randomJTI(),
		},
	}
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := tok.SignedString(m.signKey)
	if err != nil {
		return UnlockResult{}, fmt.Errorf("agent 解锁 token 签名失败: %w", err)
	}

	// 写入 sessionRegistry（带 TTL）。Lookup 时会自动校验 userID 防碰撞。
	m.reg.Unlock(sessionID, userID, deviceID)
	return UnlockResult{
		Token:     signed,
		SessionID: sessionID,
		ExpiresAt: exp,
	}, nil
}

// Validate 校验解锁 token + 校验注册表状态 + 校验 user/device 匹配。
//
// 错误返回：
//   - ErrInvalidSessionToken：JWT 签名 / 解析失败；
//   - ErrSessionExpired：JWT exp 已过 或 session TTL 到期；
//   - ErrSessionUserMismatch：token 与 user 不匹配；
//   - ErrSessionDeviceMismatch：token 与 device 不匹配（多设备隔离）；
//   - ErrSessionNotFound：session_id 在注册表不存在（被强制解锁 / 过期 GC）。
func (m *SessionManager) Validate(token, userID, deviceID string) (SessionState, error) {
	if token == "" {
		return SessionState{}, ErrInvalidSessionToken
	}
	claims := &SessionClaims{}
	parsed, err := jwt.ParseWithClaims(token, claims, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("意外签名算法: %v", t.Header["alg"])
		}
		return m.signKey, nil
	})
	if err != nil || parsed == nil || !parsed.Valid {
		return SessionState{}, ErrInvalidSessionToken
	}

	if claims.UserID != userID {
		return SessionState{}, ErrSessionUserMismatch
	}
	if claims.DeviceID != deviceID {
		return SessionState{}, ErrSessionDeviceMismatch
	}
	if claims.ExpiresAt != nil && m.now().After(claims.ExpiresAt.Time) {
		return SessionState{}, ErrSessionExpired
	}

	state, err := m.reg.Lookup(claims.SessionID, userID)
	if err != nil {
		// sessionRegistry.Lookup 已区分 ErrSessionLocked，但 SessionManager 转为 ErrSessionNotFound / ErrSessionExpired。
		if errors.Is(err, ErrSessionLocked) {
			return SessionState{}, ErrSessionNotFound
		}
		return SessionState{}, err
	}
	return state, nil
}

// Refresh 校验旧 token + 签发新 token + 续期注册表。
//
// 用途：客户端在 token 即将过期前主动调 Refresh 续签，避免回到解锁页。
//
// 返回的新 token SessionID 保持不变（便于审计日志连续）；TTL 重置为 DefaultUnlockTTL。
func (m *SessionManager) Refresh(token, userID, deviceID string) (UnlockResult, error) {
	state, err := m.Validate(token, userID, deviceID)
	if err != nil {
		return UnlockResult{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()

	now := m.now()
	exp := now.Add(DefaultUnlockTTL)
	claims := SessionClaims{
		UserID:    userID,
		DeviceID:  deviceID,
		SessionID: state.SessionID,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(exp),
			ID:        randomJTI(),
		},
	}
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := tok.SignedString(m.signKey)
	if err != nil {
		return UnlockResult{}, fmt.Errorf("agent 续签 token 签名失败: %w", err)
	}
	m.reg.Unlock(state.SessionID, userID, deviceID) // 重置 TTL
	return UnlockResult{
		Token:     signed,
		SessionID: state.SessionID,
		ExpiresAt: exp,
	}, nil
}

// Lock 主动强制解锁（场景：用户登出 / 安全告警 / 切换设备）。
// session_id 不存在时静默成功（幂等）。
func (m *SessionManager) Lock(sessionID, userID string) {
	if sessionID == "" {
		return
	}
	m.reg.Lock(sessionID, userID)
}

// Active 返回当前活跃会话数（用于监控 / 测试）。
func (m *SessionManager) Active() int {
	return m.reg.Active()
}

// GC 主动清理过期会话（测试 / 监控用）。
func (m *SessionManager) GC() int {
	return m.reg.GC()
}

// deriveSessionID 由 device_id + random 派生 session_id：
//
//	sha256(device_id || ":" || random16) → 32 hex。
//
// 注：32 hex 完整呈现，便于审计日志引用；长度足够防碰撞（128 bit 熵）。
func deriveSessionID(deviceID string) (string, error) {
	rnd := make([]byte, 16)
	if _, err := rand.Read(rnd); err != nil {
		return "", fmt.Errorf("session_id 随机分量失败: %w", err)
	}
	h := sha256.New()
	h.Write([]byte(deviceID))
	h.Write([]byte{':'})
	h.Write(rnd)
	return hex.EncodeToString(h.Sum(nil)), nil
}

// randomJTI 生成 16 字节 hex 作为 JWT jti，防重放。
func randomJTI() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b) // 忽略错误：crypto/rand 失败概率近 0
	return hex.EncodeToString(b)
}

// 便捷构造：从 cfg.DataDir 派生 SessionManager（service.go 接入用）。
//
// 调用方应保证 dataDir 已创建。
func NewSessionManagerFromDataDir(dataDir string) (*SessionManager, error) {
	key, err := LoadOrCreateSessionKey(dataDir)
	if err != nil {
		return nil, err
	}
	return NewSessionManager(SessionManagerOptions{SigningKey: key})
}

// SessionIDFromToken 不验签，仅从 JWT 字符串中解析 SessionID。
//
// 用途：审计日志 / 日志追踪；不替代 Validate。返回错误时调用方应 fallback 到空字符串。
func SessionIDFromToken(token string) string {
	if token == "" {
		return ""
	}
	parser := jwt.NewParser(jwt.WithoutClaimsValidation())
	claims := &SessionClaims{}
	parsed, _, err := parser.ParseUnverified(token, claims)
	if err != nil || parsed == nil {
		return ""
	}
	return claims.SessionID
}

// ParseTTLSeconds 把秒数（字符串或整数）转 int；非法返回 0。
//
// 仅供配置解析使用，不暴露给客户端。
func ParseTTLSeconds(raw string) int {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}
	n, err := strconv.Atoi(raw)
	if err != nil {
		return 0
	}
	return n
}
