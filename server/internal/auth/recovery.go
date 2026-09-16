// 恢复密钥与主密码变更领域。
//
// 威胁模型：恢复码是与主密码独立的账户级根凭证（20 字节随机，分组恢复码展示）。
// 服务端只存 Argon2id 验证器与 REK 包裹的 MK，永远接触不到恢复码与 MK 明文。
// 恢复成功等价于账户接管：重置主密码材料、轮换新恢复码、吊销全部既有会话、
// 作废待审批设备；执行恢复的设备凭其 X25519 公钥直接成为已批准设备。
package auth

import (
	"crypto/sha256"
	"database/sql"
	"errors"
	"time"

	"github.com/google/uuid"
)

// PasswordMaterials 是一组"两盐 + 验证器 + 包裹 MK"客户端加密材料，
// 注册/登录改密/恢复三处共用（恢复场景第二组为恢复码材料）。
type PasswordMaterials struct {
	AuthSalt         []byte `json:"auth_salt"`
	KEKSalt          []byte `json:"kek_salt"`
	AuthVerifier     []byte `json:"auth_verifier"`
	WrappedMasterKey []byte `json:"wrapped_master_key"`
}

// RecoveryMaterials 是恢复码对应的独立一组材料（派生方式相同，域分离靠不同盐与 AAD）。
type RecoveryMaterials struct {
	RecoveryAuthSalt         []byte `json:"recovery_auth_salt"`
	RecoveryKEKSalt          []byte `json:"recovery_kek_salt"`
	RecoveryVerifier         []byte `json:"recovery_verifier"`
	WrappedMasterKeyRecovery []byte `json:"wrapped_master_key_recovery"`
}

// RecoverySession 是恢复码校验通过后的短期会话。
type RecoverySession struct {
	Token     string `json:"recovery_token"`
	ExpiresIn int64  `json:"expires_in"`
	TokenType string `json:"token_type"`
	UserID    string `json:"user_id"`
	// 客户端解开 MK 所需的盐与恢复包裹（与登录 parameters 同源，一并返回减少往返）。
	RecoveryKEKSalt          []byte `json:"recovery_kek_salt"`
	WrappedMasterKeyRecovery []byte `json:"wrapped_master_key_recovery"`
}

// RecoveryStart 常量时间比对恢复验证器；用户不存在、未登记恢复材料、验证器不符
// 全部返回同一错误 ErrInvalidCredentials，防止用户名/恢复码枚举。
func (s *Service) RecoveryStart(username string, verifier []byte) (RecoverySession, error) {
	var (
		userID     string
		got        []byte
		kekSalt    []byte
		wrappedRec []byte
	)
	err := s.db.QueryRow(`SELECT id, recovery_verifier, recovery_kek_salt, wrapped_master_key_recovery
		FROM users WHERE username = ?`, username).
		Scan(&userID, &got, &kekSalt, &wrappedRec)
	if errors.Is(err, sql.ErrNoRows) {
		// 用户不存在：与验证器错误同一响应，防枚举。
		return RecoverySession{}, ErrInvalidCredentials
	}
	if err != nil {
		return RecoverySession{}, err
	}
	// 防御：未登记恢复材料的账户（理论上 0002 起注册强制）同样拒绝，不暴露差异。
	if len(got) == 0 || len(verifier) == 0 || !constantTimeEqual(got, verifier) {
		return RecoverySession{}, ErrInvalidCredentials
	}
	token, err := s.IssueScopedToken(userID, "", ScopeRecovery, RecoveryTokenTTL)
	if err != nil {
		return RecoverySession{}, err
	}
	return RecoverySession{
		Token:                    token,
		ExpiresIn:                int64(RecoveryTokenTTL.Seconds()),
		TokenType:                "Bearer",
		UserID:                   userID,
		RecoveryKEKSalt:          kekSalt,
		WrappedMasterKeyRecovery: wrappedRec,
	}, nil
}

// RecoveryResetInput 是恢复重置的全部客户端输入：新主密码材料 + 强制轮换新恢复码材料
// + 执行恢复的设备身份（恢复成功即批准该设备）。
type RecoveryResetInput struct {
	NewPassword PasswordMaterials
	NewRecovery RecoveryMaterials
	DeviceName  string
}

// RecoveryReset 在一个事务内完成"账户接管"重置并返回恢复设备的新令牌对。
// tokenIAT 是本次 recovery 令牌的签发时间：FU-3 单次化——凡签发时间不晚于上一次
// 成功 reset 的令牌一律拒绝（ErrInvalidCredentials），防止 TTL 内重放。
func (s *Service) RecoveryReset(userID, deviceName string, devicePublicKey []byte,
	pw PasswordMaterials, rec RecoveryMaterials, tokenIAT time.Time) (TokenPair, error) {
	now := time.Now()
	tx, err := s.db.Begin()
	if err != nil {
		return TokenPair{}, err
	}
	defer tx.Rollback()

	// 0) 恢复会话单次化校验：已发生过 reset 时，旧 recovery 令牌（iat <= 上次 reset）作废。
	var lastReset sql.NullInt64
	if err := tx.QueryRow(`SELECT recovery_reset_at FROM users WHERE id=?`, userID).
		Scan(&lastReset); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return TokenPair{}, ErrInvalidCredentials
		}
		return TokenPair{}, err
	}
	// 注意零值/缺失 iat 的 UnixMilli 远小于任何 reset 时间戳，天然被拒绝（宁可误杀不放行）。
	if lastReset.Valid && tokenIAT.UnixMilli() <= lastReset.Int64 {
		return TokenPair{}, ErrInvalidCredentials
	}

	// 1) 重写主密码材料与恢复材料（恢复码一次性轮换，旧码立即作废）；
	//    同时记录本次 reset 时间戳，此前签发的 recovery 令牌随之全部失效。
	res, err := tx.Exec(`UPDATE users SET
		auth_salt=?, kek_salt=?, auth_verifier=?, wrapped_master_key=?,
		recovery_auth_salt=?, recovery_kek_salt=?, recovery_verifier=?, wrapped_master_key_recovery=?,
		recovery_reset_at=?
		WHERE id=?`,
		pw.AuthSalt, pw.KEKSalt, pw.AuthVerifier, pw.WrappedMasterKey,
		rec.RecoveryAuthSalt, rec.RecoveryKEKSalt, rec.RecoveryVerifier, rec.WrappedMasterKeyRecovery,
		now.UnixMilli(), userID)
	if err != nil {
		return TokenPair{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return TokenPair{}, ErrInvalidCredentials
	}
	// 2) 吊销该用户全部刷新令牌（所有既有会话失效，必须用新密码重新登录）。
	if _, err := tx.Exec(`UPDATE refresh_tokens SET revoked=1 WHERE user_id=?`, userID); err != nil {
		return TokenPair{}, err
	}
	// 3) 待审批设备与配对全部作废（防止恢复进行中挂起的审批被事后利用）。
	if err := InvalidatePendingTx(tx, userID); err != nil {
		return TokenPair{}, err
	}
	// 4) 恢复设备：公钥命中既有设备则复用并转正，否则新建一台 approved 设备。
	deviceID, err := ensureApprovedDeviceTx(tx, userID, deviceName, devicePublicKey, now)
	if err != nil {
		return TokenPair{}, err
	}
	if err := tx.Commit(); err != nil {
		return TokenPair{}, err
	}
	return s.issuePair(userID, deviceID, ScopeApproved)
}

// ChangePassword 用当前已批准会话修改主密码（MK 不变，仅换包裹与登录材料）。
// newRecovery 为 nil 时保留原恢复码材料；否则一并轮换。
// 除当前设备外的刷新令牌全部吊销；当前设备旧 refresh（如随请求提交）一并吊销，
// 返回值是为当前设备签发的新令牌对。
func (s *Service) ChangePassword(userID, deviceID, currentRefresh string,
	pw PasswordMaterials, rec *RecoveryMaterials) (TokenPair, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return TokenPair{}, err
	}
	defer tx.Rollback()

	if rec != nil {
		// 恢复码轮换后，基于旧恢复码签发的在途 recovery 会话一并作废（与 FU-3 同一时间戳语义）。
		if _, err := tx.Exec(`UPDATE users SET
			auth_salt=?, kek_salt=?, auth_verifier=?, wrapped_master_key=?,
			recovery_auth_salt=?, recovery_kek_salt=?, recovery_verifier=?, wrapped_master_key_recovery=?,
			recovery_reset_at=?
			WHERE id=?`,
			pw.AuthSalt, pw.KEKSalt, pw.AuthVerifier, pw.WrappedMasterKey,
			rec.RecoveryAuthSalt, rec.RecoveryKEKSalt, rec.RecoveryVerifier, rec.WrappedMasterKeyRecovery,
			time.Now().UnixMilli(), userID); err != nil {
			return TokenPair{}, err
		}
	} else {
		if _, err := tx.Exec(`UPDATE users SET
			auth_salt=?, kek_salt=?, auth_verifier=?, wrapped_master_key=? WHERE id=?`,
			pw.AuthSalt, pw.KEKSalt, pw.AuthVerifier, pw.WrappedMasterKey, userID); err != nil {
			return TokenPair{}, err
		}
	}
	// 吊销其他设备的全部 refresh；当前设备若随请求携带 refresh，则吊销旧值随后换发。
	if _, err := tx.Exec(`UPDATE refresh_tokens SET revoked=1 WHERE user_id=? AND device_id<>?`,
		userID, deviceID); err != nil {
		return TokenPair{}, err
	}
	if currentRefresh != "" {
		sum := sha256.Sum256([]byte(currentRefresh))
		if _, err := tx.Exec(`UPDATE refresh_tokens SET revoked=1 WHERE user_id=? AND device_id=? AND token_hash=?`,
			userID, deviceID, sum[:]); err != nil {
			return TokenPair{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return TokenPair{}, err
	}
	return s.issuePair(userID, deviceID, ScopeApproved)
}

// ensureApprovedDeviceTx 在事务内按公钥复用（任意 state→approved）或新建已批准设备。
func ensureApprovedDeviceTx(tx *sql.Tx, userID, deviceName string, pub []byte, now time.Time) (string, error) {
	var deviceID, state string
	err := tx.QueryRow(`SELECT id, state FROM devices WHERE user_id=? AND public_key=?
		ORDER BY created_at DESC LIMIT 1`, userID, pub).Scan(&deviceID, &state)
	switch {
	case err == nil:
		if _, err := tx.Exec(`UPDATE devices SET state=?, approved=1, name=?, last_seen=? WHERE id=?`,
			StateApproved, deviceName, now.UnixMilli(), deviceID); err != nil {
			return "", err
		}
		return deviceID, nil
	case errors.Is(err, sql.ErrNoRows):
		deviceID = uuid.NewString()
		if _, err := tx.Exec(`INSERT INTO devices (id, user_id, name, public_key, approved, state, created_at, last_seen)
			VALUES (?, ?, ?, ?, 1, ?, ?, ?)`,
			deviceID, userID, deviceName, pub, StateApproved, now.UnixMilli(), now.UnixMilli()); err != nil {
			return "", err
		}
		return deviceID, nil
	default:
		return "", err
	}
}
