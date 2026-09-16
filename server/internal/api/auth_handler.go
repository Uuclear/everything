package api

import (
	"encoding/base64"
	"errors"
	"net/http"

	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/config"
	"github.com/everything-personal/eve/internal/crypto"
	"github.com/everything-personal/eve/internal/sync"
)

type registerRequest struct {
	Username         string `json:"username"`
	AuthSalt         []byte `json:"auth_salt"`
	KEKSalt          []byte `json:"kek_salt"`
	AuthVerifier     []byte `json:"auth_verifier"`
	WrappedMasterKey []byte `json:"wrapped_master_key"`
	DeviceName       string `json:"device_name"`
	// 设备 X25519 公钥（32B）：设备配对审批的端到端信道根基，0002 起注册强制携带。
	DevicePublicKey []byte `json:"device_public_key"`
	// 恢复密钥四材料（FR-1），注册强制，服务端只见派生验证器与包裹密文。
	RecoveryAuthSalt         []byte `json:"recovery_auth_salt"`
	RecoveryKEKSalt          []byte `json:"recovery_kek_salt"`
	RecoveryVerifier         []byte `json:"recovery_verifier"`
	WrappedMasterKeyRecovery []byte `json:"wrapped_master_key_recovery"`
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if req.Username == "" || len(req.AuthSalt) != crypto.SaltLen || len(req.KEKSalt) != crypto.SaltLen ||
		len(req.AuthVerifier) != 32 || len(req.WrappedMasterKey) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request",
			"username/auth_salt(16)/kek_salt(16)/auth_verifier(32)/wrapped_master_key 不合法")
		return
	}
	// 0002 起：设备公钥与恢复材料为强制项，缺失即拒绝（防止老客户端注册出无法恢复的账户）。
	if len(req.DevicePublicKey) != crypto.DevicePublicKeyLen {
		writeError(w, http.StatusBadRequest, "bad_request",
			"device_public_key 必须为 32 字节 X25519 公钥")
		return
	}
	if len(req.RecoveryAuthSalt) != crypto.SaltLen || len(req.RecoveryKEKSalt) != crypto.SaltLen ||
		len(req.RecoveryVerifier) != 32 || len(req.WrappedMasterKeyRecovery) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request",
			"恢复材料不完整：recovery_auth_salt(16)/recovery_kek_salt(16)/recovery_verifier(32)/wrapped_master_key_recovery 必填")
		return
	}
	count, err := s.auth.UserCount()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "服务器内部错误")
		return
	}
	if s.cfg.Registration == config.RegClosed ||
		(s.cfg.Registration == config.RegFirst && count > 0) {
		writeError(w, http.StatusForbidden, "registration_closed", "注册已关闭（已有用户）")
		return
	}
	if req.DeviceName == "" {
		req.DeviceName = r.UserAgent()
	}
	pair, err := s.auth.Register(auth.RegisterInput{
		Username:                 req.Username,
		AuthSalt:                 req.AuthSalt,
		KEKSalt:                  req.KEKSalt,
		AuthVerifier:             req.AuthVerifier,
		WrappedMasterKey:         req.WrappedMasterKey,
		DeviceName:               req.DeviceName,
		DevicePublicKey:          req.DevicePublicKey,
		RecoveryAuthSalt:         req.RecoveryAuthSalt,
		RecoveryKEKSalt:          req.RecoveryKEKSalt,
		RecoveryVerifier:         req.RecoveryVerifier,
		WrappedMasterKeyRecovery: req.WrappedMasterKeyRecovery,
	})
	if err != nil {
		if errors.Is(err, auth.ErrUserExists) {
			writeError(w, http.StatusConflict, "user_exists", "用户名已存在")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal", "注册失败")
		return
	}
	s.audit(pair.UserID, "register", "device="+req.DeviceName, r.RemoteAddr)
	writeJSON(w, http.StatusCreated, pair)
}

type loginRequest struct {
	Username        string `json:"username"`
	AuthVerifier    []byte `json:"auth_verifier"`
	DeviceName      string `json:"device_name"`
	DevicePublicKey []byte `json:"device_public_key"` // X25519 公钥 32B，审批端据此做 crypto_box
}

// login 经限流包装：凭证错误计失败（连续 5 次锁 15 分钟），成功清零。
func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	s.guardAuth(rateCategoryAuth, s.doLogin)(w, r)
}

func (s *Server) doLogin(w http.ResponseWriter, r *http.Request) authResult {
	var req loginRequest
	if !decodeJSON(w, r, &req) {
		return authSkipped
	}
	if req.Username == "" || len(req.AuthVerifier) != 32 {
		writeError(w, http.StatusBadRequest, "bad_request", "用户名或验证器不合法")
		return authSkipped
	}
	if len(req.DevicePublicKey) != crypto.DevicePublicKeyLen {
		writeError(w, http.StatusBadRequest, "bad_request", "设备公钥必须为 32 字节")
		return authSkipped
	}
	if req.DeviceName == "" {
		req.DeviceName = r.UserAgent()
	}
	result, err := s.auth.Login(req.Username, req.DeviceName, req.AuthVerifier, req.DevicePublicKey)
	if err != nil {
		// detail 只记录用户名与设备名，禁止记录验证器/密码等任何敏感字节。
		s.audit("", "login_failed", "username="+req.Username, r.RemoteAddr)
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "用户名或密码错误")
		return authFailed
	}
	switch result.Status {
	case auth.LoginPending:
		// 新设备进入审批：通知该用户已批准设备上的在线客户端弹出审批请求。
		p := result.Pending
		s.audit(p.UserID, "device_pair_requested",
			"device_id="+p.DeviceID+" device="+req.DeviceName, r.RemoteAddr)
		s.hub.Publish(p.UserID, sync.Event{Type: "device_pairing_requested"})
	case auth.LoginMFARequired:
		// 密码正确但要求 TOTP：不记录任何设备，审计"进入二次验证"。
		s.audit("", "login_mfa_required", "username="+req.Username, r.RemoteAddr)
	default:
		s.audit(result.Bundle.UserID, "login", "device="+req.DeviceName, r.RemoteAddr)
	}
	// pending 与 approved 都代表密码正确，失败计数应清零。
	writeJSON(w, http.StatusOK, result)
	return authSucceeded
}

// loginParameters 返回登录前派生验证器/Kek 所需的盐。
func (s *Server) loginParameters(w http.ResponseWriter, r *http.Request) {
	username := r.URL.Query().Get("username")
	if username == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少 username")
		return
	}
	params, err := s.auth.LoginParams(username)
	if err != nil {
		// 不暴露用户是否存在：两种情况返回相同错误。
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "用户名或密码错误")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"auth_salt":          params.AuthSalt,
		"kek_salt":           params.KEKSalt,
		"wrapped_master_key": params.WrappedMasterKey,
		// 恢复向导所需材料（盐可公开；wrapped 只有恢复码派生出的 REK 能解开）。
		"recovery_auth_salt":          params.RecoveryAuthSalt,
		"recovery_kek_salt":           params.RecoveryKEKSalt,
		"wrapped_master_key_recovery": params.WrappedMasterKeyRecovery,
		"argon2": map[string]any{
			"algorithm": "argon2id", "time": crypto.ArgonTime,
			"memory_kib": crypto.ArgonMemory, "threads": crypto.ArgonThreads, "key_len": crypto.KeyLen,
		},
	})
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if !decodeJSON(w, r, &req) {
		return // decodeJSON 已写出 400 错误响应
	}
	if req.RefreshToken == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少 refresh_token")
		return
	}
	if _, err := base64.RawStdEncoding.DecodeString(req.RefreshToken); err != nil &&
		len(req.RefreshToken) != 64 { // hex 长度
		writeError(w, http.StatusBadRequest, "bad_request", "refresh_token 不合法")
		return
	}
	pair, err := s.auth.Refresh(req.RefreshToken)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "token_expired", "刷新令牌无效或已过期")
		return
	}
	writeJSON(w, http.StatusOK, pair)
}
