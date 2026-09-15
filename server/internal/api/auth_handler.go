package api

import (
	"encoding/base64"
	"errors"
	"net/http"

	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/config"
	"github.com/everything-personal/eve/internal/crypto"
)

type registerRequest struct {
	Username         string `json:"username"`
	AuthSalt         []byte `json:"auth_salt"`
	KEKSalt          []byte `json:"kek_salt"`
	AuthVerifier     []byte `json:"auth_verifier"`
	WrappedMasterKey []byte `json:"wrapped_master_key"`
	DeviceName       string `json:"device_name"`
	DevicePublicKey  []byte `json:"device_public_key"`
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
		Username:         req.Username,
		AuthSalt:         req.AuthSalt,
		KEKSalt:          req.KEKSalt,
		AuthVerifier:     req.AuthVerifier,
		WrappedMasterKey: req.WrappedMasterKey,
		DeviceName:       req.DeviceName,
		DevicePublicKey:  req.DevicePublicKey,
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
	Username     string `json:"username"`
	AuthVerifier []byte `json:"auth_verifier"`
	DeviceName   string `json:"device_name"`
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if req.Username == "" || len(req.AuthVerifier) != 32 {
		writeError(w, http.StatusBadRequest, "bad_request", "用户名或验证器不合法")
		return
	}
	if req.DeviceName == "" {
		req.DeviceName = r.UserAgent()
	}
	bundle, err := s.auth.Login(req.Username, req.DeviceName, req.AuthVerifier)
	if err != nil {
		s.audit("", "login_failed", "username="+req.Username, r.RemoteAddr)
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "用户名或密码错误")
		return
	}
	s.audit(bundle.UserID, "login", "device="+req.DeviceName, r.RemoteAddr)
	writeJSON(w, http.StatusOK, bundle)
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
	if !decodeJSON(w, r, &req) || req.RefreshToken == "" {
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
