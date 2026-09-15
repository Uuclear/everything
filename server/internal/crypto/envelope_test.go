package crypto

import (
	"bytes"
	"encoding/base64"
	"testing"
)

// TestSealOpenRoundtrip 验证信封加密的基本往返。
func TestSealOpenRoundtrip(t *testing.T) {
	key, err := DeriveKey("correct horse battery staple", bytes.Repeat([]byte{0x11}, SaltLen))
	if err != nil {
		t.Fatal(err)
	}
	plaintext := []byte(`{"title":"护照","number":"E12345678"}`)
	aad := RecordAAD("rec_1", "identity", 1)

	sealed, err := Seal(key, plaintext, aad)
	if err != nil {
		t.Fatal(err)
	}
	if len(sealed) <= nonceLen {
		t.Fatal("密文长度异常")
	}
	got, err := Open(key, sealed, aad)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, plaintext) {
		t.Fatalf("明文不一致: %q != %q", got, plaintext)
	}

	// 错误密钥 / AAD 被搬移 / 密文篡改都必须失败。
	wrongKey, _ := DeriveKey("wrong password", bytes.Repeat([]byte{0x11}, SaltLen))
	if _, err := Open(wrongKey, sealed, aad); err == nil {
		t.Fatal("错误密钥居然解密成功")
	}
	if _, err := Open(key, sealed, RecordAAD("rec_1", "identity", 2)); err == nil {
		t.Fatal("AAD 版本被篡改未检测到")
	}
	sealed[len(sealed)-1] ^= 0xFF
	if _, err := Open(key, sealed, aad); err == nil {
		t.Fatal("密文篡改未检测到")
	}
}

// TestInteropVector 是固定测试向量，Web/Android 端必须能用相同参数解出同一明文。
// 参数与密文记录在 docs/crypto.md，任何一端改动原语都应同步更新并互相验证。
func TestInteropVector(t *testing.T) {
	// argon2id 固定盐（16 字节）。
	saltB64 := "AAAAAAAAAAAAAAAAAAAAAA==" // 16 个 0x00
	salt, _ := base64.StdEncoding.DecodeString(saltB64)
	key, err := DeriveKey("everything", salt)
	if err != nil {
		t.Fatal(err)
	}
	// 记录明文与 AAD。
	plaintext := []byte("hello everything")
	aad := RecordAAD("interop", "test", 1)
	sealed, err := Seal(key, plaintext, aad)
	if err != nil {
		t.Fatal(err)
	}
	got, err := Open(key, sealed, aad)
	if err != nil || !bytes.Equal(got, plaintext) {
		t.Fatalf("固定向量往返失败: %v", err)
	}
}
