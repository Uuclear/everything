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
		(id, username, auth_salt, kek_salt, auth_verifier, wrapped_master_key, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		userID, in.Username, in.AuthSalt, in.KEKSalt, in.AuthVerifier, in.WrappedMasterKey,
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
	if _, err := s.db.Exec(`INSERT INTO devices (id, user_id, name, public_key, approved, created_at)
		VALUES (?, ?, ?, ?, 1, ?)`, deviceID, userID, in.DeviceName, in.DevicePublicKey, now.UnixMilli()); err != nil {
		return TokenPair{}, err
	}
	return s.issuePair(userID, deviceID)
}

// Params 返回登录前所需的盐与包裹后的主密钥。
type Params struct {
	AuthSalt         []byte
	KEKSalt          []byte
	WrappedMasterKey []byte
}

// LoginParams 按用户名取登录参数；用户不存在时返回 ErrInvalidCredentials 以防枚举。
func (s *Service) LoginParams(username string) (Params, error) {
	var p Params
	err := s.db.QueryRow(`SELECT auth_salt, kek_salt, wrapped_master_key FROM users WHERE username = ?`,
		username).Scan(&p.AuthSalt, &p.KEKSalt, &p.WrappedMasterKey)
	if errors.Is(err, sql.ErrNoRows) {
		return Params{}, ErrInvalidCredentials
	}
	return p, err
}

// Login 校验登录验证器（常量时间比较），登记设备并签发令牌。
func (s *Service) Login(username, deviceName string, verifier []byte) (LoginBundle, error) {
	var (
		userID, deviceID                string
		authSalt, kekSalt, wrapped, got []byte
	)
	err := s.db.QueryRow(`SELECT id, auth_salt, kek_salt, auth_verifier, wrapped_master_key
		FROM users WHERE username = ?`, username).
		Scan(&userID, &authSalt, &kekSalt, &got, &wrapped)
	if errors.Is(err, sql.ErrNoRows) {
		return LoginBundle{}, ErrInvalidCredentials
	}
	if err != nil {
		return LoginBundle{}, err
	}
	if !constantTimeEqual(got, verifier) {
		return LoginBundle{}, ErrInvalidCredentials
	}
	now := time.Now()
	deviceID = uuid.NewString()
	if deviceName == "" {
		deviceName = "未知设备"
	}
	if _, err := s.db.Exec(`INSERT INTO devices (id, user_id, name, approved, created_at, last_seen)
		VALUES (?, ?, ?, 1, ?, ?)`, deviceID, userID, deviceName, now.UnixMilli(), now.UnixMilli()); err != nil {
		return LoginBundle{}, err
	}
	pair, err := s.issuePair(userID, deviceID)
	if err != nil {
		return LoginBundle{}, err
	}
	return LoginBundle{
		TokenPair:        pair,
		Username:         username,
		AuthSalt:         authSalt,
		KEKSalt:          kekSalt,
		WrappedMasterKey: wrapped,
	}, nil
}

// Refresh 用刷新令牌换新令牌对。
func (s *Service) Refresh(rawRefresh string) (TokenPair, error) {
	sum := sha256.Sum256([]byte(rawRefresh))
	var userID, deviceID string
	var expiresAt int64
	var revoked int
	err := s.db.QueryRow(`SELECT user_id, device_id, expires_at, revoked FROM refresh_tokens WHERE token_hash = ?`,
		sum[:]).Scan(&userID, &deviceID, &expiresAt, &revoked)
	if errors.Is(err, sql.ErrNoRows) || revoked == 1 || time.Now().UnixMilli() > expiresAt {
		return TokenPair{}, ErrTokenExpired
	}
	if err != nil {
		return TokenPair{}, err
	}
	return s.issuePair(userID, deviceID)
}

// ParseAccessToken 校验并解析访问令牌。
func (s *Service) ParseAccessToken(token string) (Claims, error) {
	claims := Claims{}
	t, err := jwt.ParseWithClaims(token, &claims, func(t *jwt.Token) (interface{}, error) {
		if t.Method.Alg() != jwt.SigningMethodHS256.Alg() {
			return nil, fmt.Errorf("意外的签名算法: %s", t.Method.Alg())
		}
		return s.signKey, nil
	})
	if err != nil || !t.Valid || claims.Type != tokenTypeAccess {
		return Claims{}, ErrInvalidCredentials
	}
	return claims, nil
}

func (s *Service) issuePair(userID, deviceID string) (TokenPair, error) {
	now := time.Now()
	access, err := s.signJWT(userID, deviceID, tokenTypeAccess, now.Add(s.accessTTL), now)
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

func (s *Service) signJWT(userID, deviceID, typ string, exp, iat time.Time) (string, error) {
	claims := Claims{
		UserID:   userID,
		DeviceID: deviceID,
		Type:     typ,
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
