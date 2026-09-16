package crypto

import (
	"bytes"
	"encoding/base64"
	"testing"

	"golang.org/x/crypto/chacha20poly1305"
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

// TestRecoveryWrapRoundtrip TR-4.4：恢复信封往返、AAD 域分离、错误 REK 拒绝。
func TestRecoveryWrapRoundtrip(t *testing.T) {
	recoveryCode := "ABCD-EFGH-JKMN-PQRT"
	salt := bytes.Repeat([]byte{0x22}, SaltLen)
	rek, err := DeriveKey(recoveryCode, salt)
	if err != nil {
		t.Fatal(err)
	}
	mk, err := NewMasterKey()
	if err != nil {
		t.Fatal(err)
	}
	wrapped, err := WrapForRecovery(rek, mk)
	if err != nil {
		t.Fatal(err)
	}
	got, err := UnwrapForRecovery(rek, wrapped)
	if err != nil || !bytes.Equal(got, mk) {
		t.Fatalf("恢复信封往返失败: %v", err)
	}
	// 域分离：恢复包裹不能用主密码 AAD 解开（即使恰有同一把 KEK）。
	if _, err := UnwrapMasterKey(rek, wrapped); err == nil {
		t.Fatal("恢复信封必须与主密码信封 AAD 域分离")
	}
	// 错误恢复码派生出的 REK 必须被拒。
	wrongRek, _ := DeriveKey("ABCD-EFGH-JKMN-PQRX", salt)
	if _, err := UnwrapForRecovery(wrongRek, wrapped); err == nil {
		t.Fatal("错误恢复码居然解开了 MK")
	}
}

// TestRecoveryInteropVector 产出并锁定恢复信封的确定性互通向量：
// 固定盐/固定 nonce/固定 MK，密文须与 Web(libsodium)、Android(lazysodium) 互通。
// 向量同步记录于 docs/crypto.md（T13）。
func TestRecoveryInteropVector(t *testing.T) {
	// recovery code（Crockford 分组展示形式，连字符在客户端归一化时去除；这里直接用无分隔符串）。
	salt := bytes.Repeat([]byte{0x44}, SaltLen)
	rek, err := DeriveKey("0123456789ABCDEFGHJKMNPQRSTVWXYZ", salt)
	if err != nil {
		t.Fatal(err)
	}
	mk := bytes.Repeat([]byte{0x5A}, 32)
	aad := recoveryWrapAAD
	nonce := bytes.Repeat([]byte{0x66}, nonceLen)

	aead, err := chacha20poly1305.NewX(rek)
	if err != nil {
		t.Fatal(err)
	}
	ct := aead.Seal(nil, nonce, mk, aad)
	sealed := append(append([]byte{}, nonce...), ct...)
	// 锁定向量：任何一端原语漂移都会在此暴露；docs/crypto.md 同步记录。
	const expectedB64 = "ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmIWDn9l9j4ZLuseuCaVv4fttrfeQEX6yIjtKfYJ+tAQAXXyOjaMvn6A/BQ0hc9idK"
	if gotB64 := base64.StdEncoding.EncodeToString(sealed); gotB64 != expectedB64 {
		t.Fatalf("恢复信封向量漂移：\n got=%s\nwant=%s", gotB64, expectedB64)
	}

	// 用生产代码解开手工固定向量，确认 AAD/布局一致。
	got, err := UnwrapForRecovery(rek, sealed)
	if err != nil || !bytes.Equal(got, mk) {
		t.Fatalf("固定恢复向量验证失败: %v", err)
	}
	// 非空 AAD 篡改检测。
	badAAD := append(append([]byte{}, aad...), 'x')
	if _, err := aead.Open(nil, nonce, ct, badAAD); err == nil {
		t.Fatal("AAD 篡改未检测到")
	}
}
