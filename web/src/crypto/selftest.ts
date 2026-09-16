// 开发模式密码学向量自测：应用启动时执行一次，结果输出到控制台。
// 向量与 Go 端测试、docs/crypto.md 保持同一来源。

import { crockfordDecode, crockfordEncode, normalizeRecoveryCode } from './crockford'
import { deriveKey, loadSodium, toBase64, unwrapForRecovery } from './envelope'
import { totpSelfTest } from './totp'

/** Crockford 向量（TR-7.1）。 */
export function crockfordSelfTest(): void {
  // 全 0 → 32 个 '0'；全 0xFF → 32 个 'Z'（每 5bit 组都是 31）。
  const zeros = new Uint8Array(20)
  const ones = new Uint8Array(20).fill(0xff)
  if (crockfordEncode(zeros) !== '0'.repeat(32)) {
    throw new Error('Crockford 全零向量失败')
  }
  if (crockfordEncode(ones) !== 'Z'.repeat(32)) {
    throw new Error('Crockford 全 FF 向量失败')
  }
  // Go 恢复信封锁定向量使用的恢复码（完整 32 字符字母表序列）：
  // 必须能解码为 20 字节，且重新编码归一化后恒等。
  const lockedCode = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
  const decoded = crockfordDecode(lockedCode)
  if (decoded.length !== 20 || crockfordEncode(decoded) !== lockedCode) {
    throw new Error('Crockford 跨端锁定向量失败')
  }
  // 输入归一化：小写 + 连字符分隔后必须能还原原始字节。
  const messy = '01234567-89abcdef-ghjkmnpq-rstvwxyz'
  const normalized = normalizeRecoveryCode(messy)
  const roundtrip = crockfordEncode(crockfordDecode(normalized))
  if (roundtrip !== normalized) {
    throw new Error('Crockford 归一化往返失败')
  }
}

/** 开发模式启动自测（捕获异常并打印，不阻断应用）。 */
export async function runDevSelfTests(): Promise<void> {
  try {
    crockfordSelfTest()
    await recoveryEnvelopeSelfTest()
    await totpSelfTest()
    console.info(
      '%c[Everything] 密码学向量自测通过（Crockford Base32 / 恢复信封 / RFC 6238 TOTP）',
      'color:#18a058',
    )
  } catch (err) {
    console.error('[Everything] 密码学自测失败（三端互通可能被破坏）：', err)
  }
}

// ---- 恢复信封锁定向量（FU-2）----
// 与 Go TestRecoveryInteropVector、docs/crypto.md §6.2 同一来源：
// 固定盐/nonce/MK，任何一端 Argon2id、XChaCha20-Poly1305 参数或 AAD 文本漂移都会在此暴露。
const RECOVERY_VECTOR = {
  code: '0123456789ABCDEFGHJKMNPQRSTVWXYZ', // 完整 Crockford 字母表
  saltByte: 0x44,
  nonceByte: 0x66,
  mkByte: 0x5a,
  aad: 'eve:v1:master-key-recovery/v1',
  sealedB64:
    'ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmIWDn9l9j4ZLuseuCaVv4fttrfeQEX6yIjtKfYJ+tAQAXXyOjaMvn6A/BQ0hc9idK',
} as const

/** 恢复信封向量自测：手工加密比对锁定 base64，再走生产解包路径验证往返。 */
export async function recoveryEnvelopeSelfTest(): Promise<void> {
  const sodium = await loadSodium()
  const salt = new Uint8Array(16).fill(RECOVERY_VECTOR.saltByte)
  const nonce = new Uint8Array(24).fill(RECOVERY_VECTOR.nonceByte)
  const mk = new Uint8Array(32).fill(RECOVERY_VECTOR.mkByte)
  // AAD 独立构造而非引用 envelope.ts 私有常量——AAD 文本漂移也必须被本测试捕获。
  const aad = new TextEncoder().encode(RECOVERY_VECTOR.aad)
  const rek = deriveKey(sodium, RECOVERY_VECTOR.code, salt)
  // 生产 seal() 使用随机 nonce 不可控，这里直接调原语用固定 nonce 加密。
  const ct = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(mk, aad, null, nonce, rek)
  const sealed = new Uint8Array(nonce.length + ct.length)
  sealed.set(nonce, 0)
  sealed.set(ct, nonce.length)
  if (toBase64(sealed) !== RECOVERY_VECTOR.sealedB64) {
    throw new Error('恢复信封跨端锁定向量失败（派生/加密参数或 AAD 漂移）')
  }
  // 生产解包路径必须能解开同一固定向量（nonce||ciphertext 布局与 AAD 一致性）。
  const opened = unwrapForRecovery(sodium, rek, sealed)
  if (toBase64(opened) !== toBase64(mk)) {
    throw new Error('恢复信封固定向量解包失败')
  }
}
