// Package crypto 定义三端（Go/Web/Android）必须一致的零知识加密原语。
//
// 信封规范（详见 docs/crypto.md）：
//   - 密钥派生：Argon2id，time=3，memory=64MiB，threads=2，输出 32 字节，salt 16 字节
//   - 内容加密：XChaCha20-Poly1305（IETF），随机 24 字节 nonce
//   - 密文布局：nonce(24) || ciphertext
//   - 每条记录以 AAD 绑定 id / module / version，防止密文被搬移或重放
package crypto

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/chacha20poly1305"
)

const (
	// Argon2id 参数（三端一致）。
	ArgonTime   uint32 = 3
	ArgonMemory uint32 = 64 * 1024 // KiB = 64 MiB
	// ArgonThreads 必须为 1：libsodium(Web/Android) 的 crypto_pwhash 固定单线程，
	// 三端需派生完全一致的密钥。
	ArgonThreads uint8  = 1
	KeyLen       uint32 = chacha20poly1305.KeySize // 32
	SaltLen             = 16
	nonceLen            = chacha20poly1305.NonceSizeX // 24
)

// ErrDecrypt 在密钥错误、密文被篡改或 AAD 不匹配时返回（不区分具体原因）。
var ErrDecrypt = errors.New("解密失败：密钥错误或数据已损坏")

// DeriveKey 以 Argon2id 从口令与盐派生 32 字节密钥（KEK / 登录验证器均用此函数）。
func DeriveKey(password string, salt []byte) ([]byte, error) {
	if len(salt) != SaltLen {
		return nil, fmt.Errorf("salt 长度必须为 %d，实际 %d", SaltLen, len(salt))
	}
	return argon2.IDKey([]byte(password), salt, ArgonTime, ArgonMemory, ArgonThreads, KeyLen), nil
}

// NewSalt 生成 16 字节随机盐。
func NewSalt() ([]byte, error) { return randomBytes(SaltLen) }

// NewMasterKey 生成 32 字节随机主密钥。
func NewMasterKey() ([]byte, error) { return randomBytes(int(KeyLen)) }

// Seal 使用 XChaCha20-Poly1305 加密 plaintext，返回 nonce||ciphertext。
// aad 为附加认证数据，解密时必须原样提供。
func Seal(key, plaintext, aad []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, fmt.Errorf("初始化 AEAD: %w", err)
	}
	nonce, err := randomBytes(nonceLen)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, nonceLen+len(plaintext)+aead.Overhead())
	out = append(out, nonce...)
	return aead.Seal(out, nonce, plaintext, aad), nil
}

// Open 解密 Seal 产生的 nonce||ciphertext。
func Open(key, sealed, aad []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, fmt.Errorf("初始化 AEAD: %w", err)
	}
	if len(sealed) < nonceLen+aead.Overhead() {
		return nil, ErrDecrypt
	}
	pt, err := aead.Open(nil, sealed[:nonceLen], sealed[nonceLen:], aad)
	if err != nil {
		return nil, ErrDecrypt
	}
	return pt, nil
}

// wrapAAD 是包裹主密钥时的固定 AAD，三端必须逐字节一致。
var wrapAAD = []byte("eve:v1:master-key/v1")

// WrapMasterKey 用口令派生的 KEK 包裹随机主密钥 MK。
func WrapMasterKey(kek, mk []byte) ([]byte, error) { return Seal(kek, mk, wrapAAD) }

// UnwrapMasterKey 用 KEK 解开主密钥。
func UnwrapMasterKey(kek, wrapped []byte) ([]byte, error) { return Open(kek, wrapped, wrapAAD) }

// RecordAAD 生成记录信封的 AAD：eve:v1:record:<id>:<module>:<version>。
// 三端必须逐字节一致，故版本号使用大端定长 8 字节而非文本，避免编码歧义。
func RecordAAD(id, module string, version int64) []byte {
	buf := make([]byte, 0, 16+len(id)+len(module)+8)
	buf = append(buf, []byte("eve:v1:record:")...)
	buf = append(buf, []byte(id)...)
	buf = append(buf, ':')
	buf = append(buf, []byte(module)...)
	buf = append(buf, ':')
	var v [8]byte
	binary.BigEndian.PutUint64(v[:], uint64(version))
	buf = append(buf, v[:]...)
	return buf
}

func randomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return nil, fmt.Errorf("生成随机数: %w", err)
	}
	return b, nil
}
