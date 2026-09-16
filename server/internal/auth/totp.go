// TOTP 二次验证（RFC 6238）：
//   - setup 生成 Base32 密钥与 otpauth:// URI（issuer=Everything）及二维码 data URI；
//     生成的密钥在 enable 成功前不影响登录（totp_confirmed_at 仍为 NULL）；
//   - enable 校验当前 6 位码（±1 个时间步容差）后写确认时间戳；
//   - 已启用账户登录时只签发 5 分钟 mfa 短期会话，不创建设备；
//     /auth/totp/verify 通过后才进入设备审批分支。
package auth

import (
	"bytes"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"image/png"
	"time"

	"github.com/pquerna/otp"
	"github.com/pquerna/otp/totp"
)

const totpIssuer = "Everything"

var (
	// ErrTOTPNotConfigured 尚未 setup 或未启用。
	ErrTOTPNotConfigured = errors.New("尚未配置二次验证")
	// ErrTOTPAlreadyEnabled 二次验证已处于启用状态。
	ErrTOTPAlreadyEnabled = errors.New("二次验证已启用")
	// ErrTOTPInvalidCode 验证码错误或已过期。
	ErrTOTPInvalidCode = errors.New("二次验证码错误")
)

// TOTPSetup 是 setup 响应：密钥、otpauth URI 与内嵌二维码 PNG。
type TOTPSetup struct {
	Secret     string `json:"secret"`
	OtpauthURL string `json:"otpauth_url"`
	// QRDataURI 是 data:image/png;base64,... 形式的二维码，WebView 可直接 <img src>。
	QRDataURI string `json:"qr_data_uri"`
	// Confirmed 是否已启用（setup 阶段恒 false，回显便于客户端判断状态）。
	Confirmed bool `json:"confirmed"`
}

// SetupTOTP 生成新的待确认密钥。已启用时拒绝（须先 disable，避免静默换掉在用密钥）。
// username 由服务端按 userID 取出（otpauth 标签需要），不采信客户端。
func (s *Service) SetupTOTP(userID string) (TOTPSetup, error) {
	var confirmed sql.NullInt64
	var username string
	if err := s.db.QueryRow(`SELECT username, totp_confirmed_at FROM users WHERE id=?`, userID).
		Scan(&username, &confirmed); err != nil {
		return TOTPSetup{}, err
	}
	if confirmed.Valid {
		return TOTPSetup{}, ErrTOTPAlreadyEnabled
	}
	key, err := totp.Generate(totp.GenerateOpts{
		Issuer:      totpIssuer,
		AccountName: username,
		// 三端（Google Authenticator/1Password/各类客户端）默认参数，兼容性优先。
		Period:    30,
		Digits:    otp.DigitsSix,
		Algorithm: otp.AlgorithmSHA1,
	})
	if err != nil {
		return TOTPSetup{}, fmt.Errorf("生成 TOTP 密钥: %w", err)
	}
	img, err := key.Image(200, 200)
	if err != nil {
		return TOTPSetup{}, err
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return TOTPSetup{}, err
	}
	if _, err := s.db.Exec(`UPDATE users SET totp_secret=? WHERE id=?`, key.Secret(), userID); err != nil {
		return TOTPSetup{}, err
	}
	return TOTPSetup{
		Secret:     key.Secret(),
		OtpauthURL: key.URL(),
		QRDataURI:  "data:image/png;base64," + base64.StdEncoding.EncodeToString(buf.Bytes()),
	}, nil
}

// EnableTOTP 用当前验证码确认待启用密钥。
func (s *Service) EnableTOTP(userID, code string) error {
	secret, confirmed, err := s.pendingOrEnabledSecret(userID)
	if err != nil {
		return err
	}
	if confirmed {
		return ErrTOTPAlreadyEnabled
	}
	if secret == "" {
		return ErrTOTPNotConfigured
	}
	if !validateCode(code, secret, time.Now()) {
		return ErrTOTPInvalidCode
	}
	_, err = s.db.Exec(`UPDATE users SET totp_confirmed_at=? WHERE id=?`,
		time.Now().UnixMilli(), userID)
	return err
}

// DisableTOTP 校验当前码后关闭二次验证并清除密钥。
func (s *Service) DisableTOTP(userID, code string) error {
	secret, confirmed, err := s.pendingOrEnabledSecret(userID)
	if err != nil {
		return err
	}
	if !confirmed || secret == "" {
		return ErrTOTPNotConfigured
	}
	if !validateCode(code, secret, time.Now()) {
		return ErrTOTPInvalidCode
	}
	_, err = s.db.Exec(`UPDATE users SET totp_secret=NULL, totp_confirmed_at=NULL WHERE id=?`, userID)
	return err
}

// VerifyTOTPCode 校验验证码（mfa 登录端点用）；未启用或密钥缺失一律拒绝。
func (s *Service) VerifyTOTPCode(userID, code string) error {
	secret, confirmed, err := s.pendingOrEnabledSecret(userID)
	if err != nil {
		return err
	}
	if !confirmed || secret == "" {
		return ErrTOTPNotConfigured
	}
	if !validateCode(code, secret, time.Now()) {
		return ErrTOTPInvalidCode
	}
	return nil
}

// pendingOrEnabledSecret 读出密钥与启用标记（任一为空都安全降级）。
func (s *Service) pendingOrEnabledSecret(userID string) (secret string, confirmed bool, err error) {
	var sec sql.NullString
	var at sql.NullInt64
	if err = s.db.QueryRow(`SELECT totp_secret, totp_confirmed_at FROM users WHERE id=?`, userID).
		Scan(&sec, &at); err != nil {
		return "", false, err
	}
	return sec.String, at.Valid, nil
}

// TOTPSecretState 返回是否已有密钥（含待确认）与是否已启用，供状态查询端点使用。
func (s *Service) TOTPSecretState(userID string) (hasSecret, confirmed bool, err error) {
	var sec sql.NullString
	var at sql.NullInt64
	if err = s.db.QueryRow(`SELECT totp_secret, totp_confirmed_at FROM users WHERE id=?`, userID).
		Scan(&sec, &at); err != nil {
		return false, false, err
	}
	return sec.String != "", at.Valid, nil
}

// IsTOTPEnabled 登录流程判定：仅当确认时间戳非空时要求二次验证。
func (s *Service) IsTOTPEnabled(userID string) (bool, error) {
	var at sql.NullInt64
	if err := s.db.QueryRow(`SELECT totp_confirmed_at FROM users WHERE id=?`, userID).
		Scan(&at); err != nil {
		return false, err
	}
	return at.Valid, nil
}

// validateCode 以 ±1 个时间步（共约 90 秒窗口）校验 6 位码，容忍客户端时钟漂移。
func validateCode(code, secret string, now time.Time) bool {
	ok, err := totp.ValidateCustom(code, secret, now, totp.ValidateOpts{
		Period:    30,
		Skew:      1,
		Digits:    otp.DigitsSix,
		Algorithm: otp.AlgorithmSHA1,
	})
	return err == nil && ok
}
