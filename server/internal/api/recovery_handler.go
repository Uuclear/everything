package api

import (
	"errors"
	"net/http"
	"time"

	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/crypto"
)

type recoveryStartRequest struct {
	Username         string `json:"username"`
	RecoveryVerifier []byte `json:"recovery_verifier"` // Argon2id(恢复码归一化串, recovery_auth_salt)
}

// recoveryStart 校验恢复码并签发 10 分钟 recovery 会话；错误与"用户不存在"同形，
// 失败计入与登录相同的 auth-write 限流桶。
func (s *Server) recoveryStart(w http.ResponseWriter, r *http.Request) {
	s.guardAuth(rateCategoryAuth, s.doRecoveryStart)(w, r)
}

func (s *Server) doRecoveryStart(w http.ResponseWriter, r *http.Request) authResult {
	var req recoveryStartRequest
	if !decodeJSON(w, r, &req) {
		return authSkipped
	}
	if req.Username == "" || len(req.RecoveryVerifier) != 32 {
		writeError(w, http.StatusBadRequest, "bad_request", "用户名或恢复验证器不合法")
		return authSkipped
	}
	session, err := s.auth.RecoveryStart(req.Username, req.RecoveryVerifier)
	if err != nil {
		// detail 只记录用户名，绝不记录恢复码/验证器。
		s.audit("", "recovery_start_failed", "username="+req.Username, r.RemoteAddr)
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "用户名或恢复码错误")
		return authFailed
	}
	s.audit(session.UserID, "recovery_start", "recovery", r.RemoteAddr)
	writeJSON(w, http.StatusOK, session)
	return authSucceeded
}

// recoveryResetRequest 携带新主密码材料 + 强制轮换的新恢复码材料 + 恢复设备公钥。
type recoveryResetRequest struct {
	AuthSalt         []byte `json:"auth_salt"`
	KEKSalt          []byte `json:"kek_salt"`
	AuthVerifier     []byte `json:"auth_verifier"`
	WrappedMasterKey []byte `json:"wrapped_master_key"`

	RecoveryAuthSalt         []byte `json:"recovery_auth_salt"`
	RecoveryKEKSalt          []byte `json:"recovery_kek_salt"`
	RecoveryVerifier         []byte `json:"recovery_verifier"`
	WrappedMasterKeyRecovery []byte `json:"wrapped_master_key_recovery"`

	DevicePublicKey []byte `json:"device_public_key"`
	DeviceName      string `json:"device_name"`
}

// recoveryReset 使用 recovery 会话完成账户接管重置，并把执行设备批准为正式设备。
func (s *Server) recoveryReset(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var req recoveryResetRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if !validMaterials(w, req.AuthSalt, req.KEKSalt, req.AuthVerifier, req.WrappedMasterKey) {
		return
	}
	if !validMaterials(w, req.RecoveryAuthSalt, req.RecoveryKEKSalt, req.RecoveryVerifier, req.WrappedMasterKeyRecovery) {
		writeError(w, http.StatusBadRequest, "bad_request", "新恢复密钥材料不完整")
		return
	}
	if len(req.DevicePublicKey) != crypto.DevicePublicKeyLen {
		writeError(w, http.StatusBadRequest, "bad_request", "设备公钥必须为 32 字节")
		return
	}
	if req.DeviceName == "" {
		req.DeviceName = r.UserAgent()
	}
	// FU-3：传入 recovery 令牌的签发时间，供单次化校验（reset 后旧令牌一律 401）。
	var tokenIAT time.Time
	if claims.IssuedAt != nil {
		tokenIAT = claims.IssuedAt.Time
	}
	pair, err := s.auth.RecoveryReset(claims.UserID, req.DeviceName, req.DevicePublicKey,
		auth.PasswordMaterials{
			AuthSalt: req.AuthSalt, KEKSalt: req.KEKSalt,
			AuthVerifier: req.AuthVerifier, WrappedMasterKey: req.WrappedMasterKey,
		},
		auth.RecoveryMaterials{
			RecoveryAuthSalt:         req.RecoveryAuthSalt,
			RecoveryKEKSalt:          req.RecoveryKEKSalt,
			RecoveryVerifier:         req.RecoveryVerifier,
			WrappedMasterKeyRecovery: req.WrappedMasterKeyRecovery,
		}, tokenIAT)
	if err != nil {
		if errors.Is(err, auth.ErrInvalidCredentials) {
			writeError(w, http.StatusUnauthorized, "unauthorized", "恢复会话无效")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal", "恢复重置失败")
		return
	}
	s.audit(claims.UserID, "password_reset", "via=recovery device="+req.DeviceName, r.RemoteAddr)
	writeJSON(w, http.StatusOK, pair)
}

type changePasswordRequest struct {
	AuthSalt         []byte `json:"auth_salt"`
	KEKSalt          []byte `json:"kek_salt"`
	AuthVerifier     []byte `json:"auth_verifier"`
	WrappedMasterKey []byte `json:"wrapped_master_key"`

	// 可选：同时轮换恢复码（四个字段必须同时出现）。
	RecoveryAuthSalt         []byte `json:"recovery_auth_salt,omitempty"`
	RecoveryKEKSalt          []byte `json:"recovery_kek_salt,omitempty"`
	RecoveryVerifier         []byte `json:"recovery_verifier,omitempty"`
	WrappedMasterKeyRecovery []byte `json:"wrapped_master_key_recovery,omitempty"`

	// 可选：客户端当前 refresh，随改密一并吊销并换发。
	RefreshToken string `json:"refresh_token,omitempty"`
}

// changePassword 在已批准设备上修改主密码；其他设备会话全部吊销。
func (s *Server) changePassword(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var req changePasswordRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if !validMaterials(w, req.AuthSalt, req.KEKSalt, req.AuthVerifier, req.WrappedMasterKey) {
		return
	}
	// 恢复材料要么整组缺省（不轮换），要么整组合法。
	var newRec *auth.RecoveryMaterials
	hasAny := len(req.RecoveryAuthSalt)+len(req.RecoveryKEKSalt)+
		len(req.RecoveryVerifier)+len(req.WrappedMasterKeyRecovery) > 0
	if hasAny {
		if !validMaterials(w, req.RecoveryAuthSalt, req.RecoveryKEKSalt,
			req.RecoveryVerifier, req.WrappedMasterKeyRecovery) {
			writeError(w, http.StatusBadRequest, "bad_request", "新恢复密钥材料不完整")
			return
		}
		newRec = &auth.RecoveryMaterials{
			RecoveryAuthSalt:         req.RecoveryAuthSalt,
			RecoveryKEKSalt:          req.RecoveryKEKSalt,
			RecoveryVerifier:         req.RecoveryVerifier,
			WrappedMasterKeyRecovery: req.WrappedMasterKeyRecovery,
		}
	}
	pair, err := s.auth.ChangePassword(claims.UserID, claims.DeviceID, req.RefreshToken,
		auth.PasswordMaterials{
			AuthSalt: req.AuthSalt, KEKSalt: req.KEKSalt,
			AuthVerifier: req.AuthVerifier, WrappedMasterKey: req.WrappedMasterKey,
		}, newRec)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "修改主密码失败")
		return
	}
	detail := "self"
	if newRec != nil {
		detail = "self+recovery_rotated"
	}
	// 事件名遵循 FR-25 审计目录（password_change，非过去式）。
	s.audit(claims.UserID, "password_change", detail, r.RemoteAddr)
	writeJSON(w, http.StatusOK, pair)
}

// validMaterials 校验"盐16B/盐16B/验证器32B/包裹非空"四元组；不合法时已写 400 响应。
func validMaterials(w http.ResponseWriter, salt1, salt2, verifier, wrapped []byte) bool {
	if len(salt1) != crypto.SaltLen || len(salt2) != crypto.SaltLen {
		writeError(w, http.StatusBadRequest, "bad_request", "盐必须为 16 字节")
		return false
	}
	if len(verifier) != 32 {
		writeError(w, http.StatusBadRequest, "bad_request", "验证器必须为 32 字节")
		return false
	}
	if len(wrapped) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request", "包裹后的主密钥不能为空")
		return false
	}
	return true
}
