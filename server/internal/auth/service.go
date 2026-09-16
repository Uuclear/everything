// Package auth 实现注册/登录、JWT 签发校验、刷新令牌与设备登记。
// 服务端始终不知道主密码明文，也不知道主密钥明文。
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/everything-personal/eve/internal/config"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

var (
	// ErrInvalidCredentials 用户名或登录验证器不匹配。
	ErrInvalidCredentials = errors.New("用户名或密码错误")
	// ErrRegistrationClosed 注册已被策略关闭。
	ErrRegistrationClosed = errors.New("注册已关闭")
	// ErrUserExists 用户名已存在。
	ErrUserExists = errors.New("用户名已存在")
	// ErrTokenExpired 刷新令牌无效或过期。
	ErrTokenExpired = errors.New("刷新令牌无效或已过期")
)

const (
	tokenTypeAccess  = "access"
	tokenTypeRefresh = "refresh"
	// tokenTypeRecovery 恢复码校验通过后的短期会话，仅可用于重置主密码。
	tokenTypeRecovery = "recovery"
	// tokenTypeMFA 密码正确但需 TOTP 时的短期会话，仅可用于提交 TOTP 验证码。
	tokenTypeMFA = "mfa"
	// tokenTypeEvents 仅可用于建立 /events SSE 连接的短期签名令牌。
	tokenTypeEvents = "events"
)

const (
	// ScopeApproved 已批准设备：完整资料库访问权。
	ScopeApproved = "approved"
	// ScopePending 待审批设备：只能查询自身配对状态。
	ScopePending = "pending"
	// ScopeRecovery 恢复会话；ScopeMFA 二次验证会话；ScopeEvents 仅 SSE。
	ScopeRecovery = "recovery"
	ScopeMFA      = "mfa"
	ScopeEvents   = "events"
)

// Service 是认证领域服务。
type Service struct {
	db         *sql.DB
	signKey    []byte
	accessTTL  time.Duration
	refreshTTL time.Duration
}

// New 创建认证服务；signingKey 为空时从 dataDir/jwt.key 读取或生成。
func New(database *sql.DB, cfg config.Config) (*Service, error) {
	key, err := loadOrCreateSigningKey(filepath.Join(cfg.DataDir, "jwt.key"))
	if err != nil {
		return nil, err
	}
	return &Service{
		db:         database,
		signKey:    key,
		accessTTL:  time.Duration(cfg.AccessTokenTTL) * time.Minute,
		refreshTTL: time.Duration(cfg.RefreshTokenTTL) * time.Duration(24*time.Hour),
	}, nil
}

// Claims 是 JWT 内的业务字段。
type Claims struct {
	UserID   string `json:"uid"`
	DeviceID string `json:"did"`
	Type     string `json:"typ"`
	// Scope 限制令牌可用范围（approved/pending/recovery/mfa/events），中间件按端点心校验。
	Scope string `json:"scp,omitempty"`
	jwt.RegisteredClaims
}

// TokenPair 是登录/刷新成功后返回给客户端的凭证。
type TokenPair struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"` // access token 有效期（秒）
	TokenType    string `json:"token_type"`
	UserID       string `json:"user_id"`
	DeviceID     string `json:"device_id"`
}

// RegisterInput 是注册请求中与认证相关的字段（盐与包裹密钥均由客户端生成）。
type RegisterInput struct {
	Username         string
	AuthSalt         []byte
	KEKSalt          []byte
	AuthVerifier     []byte
	WrappedMasterKey []byte
	DeviceName       string
	DevicePublicKey  []byte
	// 恢复密钥材料：注册强制携带，忘记主密码时凭恢复码重置（FR-1）。
	RecoveryAuthSalt         []byte
	RecoveryKEKSalt          []byte
	RecoveryVerifier         []byte
	WrappedMasterKeyRecovery []byte
}

// LoginBundle 是登录后连同令牌一起返回的解锁材料。
type LoginBundle struct {
	TokenPair
	Username         string `json:"username"`
	AuthSalt         []byte `json:"auth_salt"`
	KEKSalt          []byte `json:"kek_salt"`
	WrappedMasterKey []byte `json:"wrapped_master_key"`
}

// UserCount 返回已注册用户数（用于 first 注册策略）。
func (s *Service) UserCount() (int, error) {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(1) FROM users`).Scan(&n); err != nil {
		return 0, err
	}
	return n, nil
}

// Register 创建第一个/新用户并登记当前设备，返回令牌对。
func (s *Service) Register(in RegisterInput) (TokenPair, error) {
	now := time.Now()
	userID := uuid.NewString()
	deviceID := uuid.NewString()
	_, err := s.db.Exec(`INSERT INTO users
		(id, username, auth_salt, kek_salt, auth_verifier, wrapped_master_key,
			recovery_auth_salt, recovery_kek_salt, recovery_verifier, wrapped_master_key_recovery,
			created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		userID, in.Username, in.AuthSalt, in.KEKSalt, in.AuthVerifier, in.WrappedMasterKey,
		in.RecoveryAuthSalt, in.RecoveryKEKSalt, in.RecoveryVerifier, in.WrappedMasterKeyRecovery,
		now.UnixMilli())
	if err != nil {
		// modernc 驱动的 UNIQUE 冲突信息含 constraint，但这里用预检查用户名更直观。
		var exists int
		_ = s.db.QueryRow(`SELECT COUNT(1) FROM users WHERE username = ?`, in.Username).Scan(&exists)
		if exists == 1 {
			return TokenPair{}, ErrUserExists
		}
		return TokenPair{}, fmt.Errorf("创建用户: %w", err)
	}
	// 账户的第一台设备自动批准（state 默认 approved，是后续新设备登录的审批前提）。
	if _, err := s.db.Exec(`INSERT INTO devices (id, user_id, name, public_key, approved, state, created_at)
		VALUES (?, ?, ?, ?, 1, ?, ?)`,
		deviceID, userID, in.DeviceName, in.DevicePublicKey, ScopeApproved, now.UnixMilli()); err != nil {
		return TokenPair{}, err
	}
	return s.issuePair(userID, deviceID, ScopeApproved)
}

// Params 返回登录前所需的盐与包裹后的主密钥；恢复三字段供"忘记主密码"向导使用。
// 盐与包裹密文都不是秘密（安全性来自 Argon2id 与恢复码熵），与登录参数同模型公开。
type Params struct {
	AuthSalt         []byte
	KEKSalt          []byte
	WrappedMasterKey []byte
	// 恢复材料：未设置恢复密钥的旧账户可能为 nil，客户端应隐藏恢复入口。
	RecoveryAuthSalt         []byte
	RecoveryKEKSalt          []byte
	WrappedMasterKeyRecovery []byte
}

// LoginParams 按用户名取登录参数；用户不存在时返回 ErrInvalidCredentials 以防枚举。
func (s *Service) LoginParams(username string) (Params, error) {
	var p Params
	err := s.db.QueryRow(`SELECT auth_salt, kek_salt, wrapped_master_key,
		recovery_auth_salt, recovery_kek_salt, wrapped_master_key_recovery
		FROM users WHERE username = ?`, username).
		Scan(&p.AuthSalt, &p.KEKSalt, &p.WrappedMasterKey,
			&p.RecoveryAuthSalt, &p.RecoveryKEKSalt, &p.WrappedMasterKeyRecovery)
	if errors.Is(err, sql.ErrNoRows) {
		return Params{}, ErrInvalidCredentials
	}
	return p, err
}

// 登录结果类型。
const (
	LoginApproved    = "approved"     // 设备已批准（含首设备自动批准）：返回完整令牌对
	LoginPending     = "pending"      // 新/被拒设备：仅返回 pending access 令牌，等待审批
	LoginMFARequired = "mfa_required" // 密码正确但启用了 TOTP：仅返回 mfa 短期令牌
)

// LoginResult 是登录的多态结果；handler 按 Status 序列化不同响应。
type LoginResult struct {
	Status    string        `json:"status"`
	Bundle    *LoginBundle  `json:"bundle,omitempty"`    // approved 时填充
	Pending   *PendingLogin `json:"pending,omitempty"`   // pending 时填充
	MFAToken  string        `json:"mfa_token,omitempty"` // mfa_required 时填充
	ExpiresIn int64         `json:"expires_in,omitempty"`
}

// PendingLogin 是新设备待审批响应：只有短期 access（无 refresh），
// 客户端用它轮询/订阅自身配对状态，批准后凭盒内 MK 完成本地解锁。
type PendingLogin struct {
	AccessToken string      `json:"access_token"`
	ExpiresIn   int64       `json:"expires_in"`
	TokenType   string      `json:"token_type"`
	UserID      string      `json:"user_id"`
	DeviceID    string      `json:"device_id"`
	Pairing     PairingInfo `json:"pairing"`
}

// Login 校验登录验证器（常量时间比较），随后按设备公钥走审批状态机：
//   - 同公钥设备已 approved：重复登录，直接发完整令牌；
//   - 账户尚无任何 approved 设备（首设备/注册后首登）：自动批准；
//   - 其余（新公钥、曾被拒绝/吊销的公钥）：设备置 pending 并建立/刷新配对请求，
//     只签发 pending 短期 access 令牌（不落 refresh_tokens，无刷新权）。
func (s *Service) Login(username, deviceName string, verifier, devicePublicKey []byte) (LoginResult, error) {
	var (
		userID                          string
		authSalt, kekSalt, wrapped, got []byte
	)
	err := s.db.QueryRow(`SELECT id, auth_salt, kek_salt, auth_verifier, wrapped_master_key
		FROM users WHERE username = ?`, username).
		Scan(&userID, &authSalt, &kekSalt, &got, &wrapped)
	if errors.Is(err, sql.ErrNoRows) {
		return LoginResult{}, ErrInvalidCredentials
	}
	if err != nil {
		return LoginResult{}, err
	}
	if !constantTimeEqual(got, verifier) {
		return LoginResult{}, ErrInvalidCredentials
	}
	if deviceName == "" {
		deviceName = "未知设备"
	}

	// 已启用 TOTP：密码正确也不创建设备，改发 5 分钟 mfa 会话；
	// 设备判定推迟到 /auth/totp/verify 通过后（VerifyMFAFinishLogin）执行。
	enabled, err := s.IsTOTPEnabled(userID)
	if err != nil {
		return LoginResult{}, err
	}
	if enabled {
		token, err := s.IssueScopedToken(userID, "", ScopeMFA, MFATokenTTL)
		if err != nil {
			return LoginResult{}, err
		}
		return LoginResult{
			Status:    LoginMFARequired,
			MFAToken:  token,
			ExpiresIn: int64(MFATokenTTL.Seconds()),
		}, nil
	}
	return s.finishDeviceLogin(userID, username, deviceName, devicePublicKey, authSalt, kekSalt, wrapped)
}

// VerifyMFAFinishLogin 在 TOTP 验证码通过后接续设备分支（mfa 端点调用）。
// 用户名/盐/wrapped 按 mfa 会话中的 userID 重新取出，不采信客户端回传。
func (s *Service) VerifyMFAFinishLogin(userID, deviceName string, devicePublicKey []byte) (LoginResult, error) {
	var username string
	var authSalt, kekSalt, wrapped []byte
	err := s.db.QueryRow(`SELECT username, auth_salt, kek_salt, wrapped_master_key
		FROM users WHERE id=?`, userID).Scan(&username, &authSalt, &kekSalt, &wrapped)
	if err != nil {
		return LoginResult{}, err
	}
	if deviceName == "" {
		deviceName = "未知设备"
	}
	return s.finishDeviceLogin(userID, username, deviceName, devicePublicKey, authSalt, kekSalt, wrapped)
}

// finishDeviceLogin 密码（及 TOTP）均通过后的设备审批状态机分支。
func (s *Service) finishDeviceLogin(userID, username, deviceName string,
	devicePublicKey, authSalt, kekSalt, wrapped []byte) (LoginResult, error) {
	// 按公钥识别"同一台设备"：X25519 公钥由设备私钥派生，重复登录携带同一公钥。
	var existingID, existingState sql.NullString
	lookupErr := s.db.QueryRow(`SELECT id, state FROM devices
		WHERE user_id = ? AND public_key = ? ORDER BY created_at DESC LIMIT 1`,
		userID, devicePublicKey).Scan(&existingID, &existingState)
	if lookupErr != nil && !errors.Is(lookupErr, sql.ErrNoRows) {
		return LoginResult{}, lookupErr
	}
	now := time.Now()

	if lookupErr == nil && existingState.String == StateApproved {
		// 已批准设备重复登录：续上 last_seen 并签发完整令牌对。
		if _, err := s.db.Exec(`UPDATE devices SET last_seen=?, name=? WHERE id=?`,
			now.UnixMilli(), deviceName, existingID.String); err != nil {
			return LoginResult{}, err
		}
		pair, err := s.issuePair(userID, existingID.String, ScopeApproved)
		if err != nil {
			return LoginResult{}, err
		}
		return LoginResult{
			Status: LoginApproved,
			Bundle: &LoginBundle{
				TokenPair:        pair,
				Username:         username,
				AuthSalt:         authSalt,
				KEKSalt:          kekSalt,
				WrappedMasterKey: wrapped,
			},
		}, nil
	}

	// 账户是否存在已批准设备：不存在则本次登录的设备自动批准（首个设备信任根）。
	var approvedCount int
	if err := s.db.QueryRow(`SELECT COUNT(1) FROM devices WHERE user_id=? AND state=?`,
		userID, StateApproved).Scan(&approvedCount); err != nil {
		return LoginResult{}, err
	}

	var deviceID string
	if lookupErr == nil {
		// 复用旧设备行（pending/rejected/revoked）：改名、转 pending、刷新心跳。
		deviceID = existingID.String
		if _, err := s.db.Exec(`UPDATE devices SET state=?, name=?, last_seen=? WHERE id=?`,
			StatePending, deviceName, now.UnixMilli(), deviceID); err != nil {
			return LoginResult{}, err
		}
	} else {
		deviceID = uuid.NewString()
		initialState := StatePending
		if approvedCount == 0 {
			initialState = StateApproved
		}
		if _, err := s.db.Exec(`INSERT INTO devices (id, user_id, name, public_key, approved, state, created_at, last_seen)
			VALUES (?, ?, ?, ?, 0, ?, ?, ?)`,
			deviceID, userID, deviceName, devicePublicKey, initialState, now.UnixMilli(), now.UnixMilli()); err != nil {
			return LoginResult{}, err
		}
		if approvedCount == 0 {
			// 首设备：直接发完整令牌（与注册设备同权）。
			pair, err := s.issuePair(userID, deviceID, ScopeApproved)
			if err != nil {
				return LoginResult{}, err
			}
			return LoginResult{
				Status: LoginApproved,
				Bundle: &LoginBundle{
					TokenPair:        pair,
					Username:         username,
					AuthSalt:         authSalt,
					KEKSalt:          kekSalt,
					WrappedMasterKey: wrapped,
				},
			}, nil
		}
	}

	// 新设备/旧非批准设备：建立或刷新 pending 配对（旧 pending 配对置 expired）。
	pairing, err := s.createOrRenewPairing(userID, deviceID, devicePublicKey, now)
	if err != nil {
		return LoginResult{}, err
	}
	pairing.DeviceName = deviceName
	// pending access 令牌：typ 必须仍是 access（经 requireAccessToken），
	// 靠 scope=pending 限制只能访问配对状态端点；不落 refresh_tokens，无刷新权。
	now2 := time.Now()
	access, err := s.signJWT(userID, deviceID, tokenTypeAccess, ScopePending,
		now2.Add(s.accessTTL), now2)
	if err != nil {
		return LoginResult{}, err
	}
	return LoginResult{
		Status: LoginPending,
		Pending: &PendingLogin{
			AccessToken: access,
			ExpiresIn:   int64(s.accessTTL.Seconds()),
			TokenType:   "Bearer",
			UserID:      userID,
			DeviceID:    deviceID,
			Pairing:     pairing,
		},
	}, nil
}

// Refresh 用刷新令牌换新令牌对；设备被吊销时刷新令牌同样即时失效。
func (s *Service) Refresh(rawRefresh string) (TokenPair, error) {
	sum := sha256.Sum256([]byte(rawRefresh))
	var userID, deviceID, state string
	var expiresAt int64
	var revoked int
	err := s.db.QueryRow(`SELECT t.user_id, t.device_id, t.expires_at, t.revoked, d.state
		FROM refresh_tokens t JOIN devices d ON d.id = t.device_id
		WHERE t.token_hash = ?`, sum[:]).Scan(&userID, &deviceID, &expiresAt, &revoked, &state)
	if errors.Is(err, sql.ErrNoRows) || revoked == 1 || time.Now().UnixMilli() > expiresAt {
		return TokenPair{}, ErrTokenExpired
	}
	if err != nil {
		return TokenPair{}, err
	}
	// 只有已批准设备能持有完整访问令牌；被吊销设备的刷新请求直接拒绝。
	if state != ScopeApproved {
		return TokenPair{}, ErrTokenExpired
	}
	return s.issuePair(userID, deviceID, ScopeApproved)
}

// ParseAccessToken 校验并解析访问令牌（typ 必须为 access）。
func (s *Service) ParseAccessToken(token string) (Claims, error) {
	return s.parseToken(token, tokenTypeAccess)
}

// ParseTyped 校验并解析指定 typ 的令牌（recovery/mfa/events 等短期令牌用）。
func (s *Service) ParseTyped(token, typ string) (Claims, error) {
	return s.parseToken(token, typ)
}

func (s *Service) parseToken(token, wantType string) (Claims, error) {
	claims := Claims{}
	t, err := jwt.ParseWithClaims(token, &claims, func(t *jwt.Token) (interface{}, error) {
		if t.Method.Alg() != jwt.SigningMethodHS256.Alg() {
			return nil, fmt.Errorf("意外的签名算法: %s", t.Method.Alg())
		}
		return s.signKey, nil
	})
	if err != nil || !t.Valid || claims.Type != wantType {
		return Claims{}, ErrInvalidCredentials
	}
	return claims, nil
}

// IssueScopedToken 签发仅承载身份与 scope 的短期令牌（不落 refresh_tokens 表）。
// typ 与 scope 同名（recovery/mfa/events），但语义上前者是令牌种类、后者是访问范围。
// 用于 recovery（10min）、mfa（5min）、events（5min）等最小权限会话。
func (s *Service) IssueScopedToken(userID, deviceID, scope string, ttl time.Duration) (string, error) {
	now := time.Now()
	return s.signJWT(userID, deviceID, scope, scope, now.Add(ttl), now)
}

// RecoveryTTL / MFA TTL / Events TTL：各短期会话的有效期。
const (
	RecoveryTokenTTL = 10 * time.Minute
	MFATokenTTL      = 5 * time.Minute
	EventsTokenTTL   = 5 * time.Minute
)

// IssueEventsToken 签发仅能建立 /events SSE 连接的 5 分钟短期令牌。
// EventSource 无法设置 Authorization 头，客户端换取后以 ?token= 建连。
func (s *Service) IssueEventsToken(userID, deviceID string) (string, error) {
	return s.IssueEventsTokenWithTTL(userID, deviceID, EventsTokenTTL)
}

// IssueEventsTokenWithTTL 同 IssueEventsToken，但允许指定有效期。
// 仅供测试构造过期/边界令牌（内部包方法，不经任何 HTTP 端点暴露）。
func (s *Service) IssueEventsTokenWithTTL(userID, deviceID string, ttl time.Duration) (string, error) {
	return s.IssueScopedToken(userID, deviceID, ScopeEvents, ttl)
}

func (s *Service) issuePair(userID, deviceID, scope string) (TokenPair, error) {
	now := time.Now()
	access, err := s.signJWT(userID, deviceID, tokenTypeAccess, scope, now.Add(s.accessTTL), now)
	if err != nil {
		return TokenPair{}, err
	}
	refreshRaw := make([]byte, 32)
	if _, err := rand.Read(refreshRaw); err != nil {
		return TokenPair{}, err
	}
	refreshHex := hex.EncodeToString(refreshRaw)
	sum := sha256.Sum256([]byte(refreshHex))
	if _, err := s.db.Exec(`INSERT INTO refresh_tokens (token_hash, user_id, device_id, expires_at, created_at)
		VALUES (?, ?, ?, ?, ?)`, sum[:], userID, deviceID, now.Add(s.refreshTTL).UnixMilli(), now.UnixMilli()); err != nil {
		return TokenPair{}, err
	}
	return TokenPair{
		AccessToken:  access,
		RefreshToken: refreshHex,
		ExpiresIn:    int64(s.accessTTL.Seconds()),
		TokenType:    "Bearer",
		UserID:       userID,
		DeviceID:     deviceID,
	}, nil
}

func (s *Service) signJWT(userID, deviceID, typ, scope string, exp, iat time.Time) (string, error) {
	claims := Claims{
		UserID:   userID,
		DeviceID: deviceID,
		Type:     typ,
		Scope:    scope,
		RegisteredClaims: jwt.RegisteredClaims{
			ID:        uuid.NewString(),
			ExpiresAt: jwt.NewNumericDate(exp),
			IssuedAt:  jwt.NewNumericDate(iat),
			Issuer:    "everything-eve",
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(s.signKey)
}

func loadOrCreateSigningKey(path string) ([]byte, error) {
	if b, err := os.ReadFile(path); err == nil && len(b) >= 32 {
		return b[:32], nil
	} else if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, key, 0o600); err != nil {
		return nil, fmt.Errorf("写入 JWT 密钥: %w", err)
	}
	return key, nil
}

func constantTimeEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	var v byte
	for i := range a {
		v |= a[i] ^ b[i]
	}
	return v == 0
}
