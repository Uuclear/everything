// 零知识加密信封（Web 端实现），必须与 server/internal/crypto、Android crypto 包逐字节一致。
// 规范见 docs/crypto.md：
//   - Argon2id：time=3，memory=64MiB，threads=1（libsodium 固定），输出 32B，salt 16B
//   - XChaCha20-Poly1305 IETF：随机 24B nonce，密文布局 nonce||ciphertext
//   - 记录 AAD："eve:v1:record:" + id + ":" + module + ":" + BE(uint64 version)
//   - 主密钥包裹 AAD："eve:v1:master-key/v1"
// 必须使用 sumo 完整版：Argon2id（crypto_pwhash）不在标准版构建中。
import _sodium from 'libsodium-wrappers-sumo'

export const KEY_LEN = 32
export const SALT_LEN = 16
export const ARGON_TIME = 3
export const ARGON_MEMORY = 64 * 1024 * 1024 // 64 MiB

export type Sodium = typeof _sodium

let ready: Promise<Sodium> | null = null

/** 初始化 libsodium（WASM），全局只做一次。 */
export function loadSodium(): Promise<Sodium> {
  if (!ready) {
    ready = _sodium.ready.then(() => _sodium)
  }
  return ready
}

export function randomSalt(sodium: Sodium): Uint8Array {
  return sodium.randombytes_buf(SALT_LEN)
}

export function newMasterKey(sodium: Sodium): Uint8Array {
  return sodium.randombytes_buf(KEY_LEN)
}

/** Argon2id 派生 32 字节密钥（登录验证器与 KEK 同源不同盐）。 */
export function deriveKey(sodium: Sodium, password: string, salt: Uint8Array): Uint8Array {
  return sodium.crypto_pwhash(
    KEY_LEN,
    password,
    salt,
    ARGON_TIME,
    ARGON_MEMORY,
    sodium.crypto_pwhash_ALG_ARGON2ID13,
  )
}

const WRAP_AAD = new TextEncoder().encode('eve:v1:master-key/v1')

export function wrapMasterKey(sodium: Sodium, kek: Uint8Array, mk: Uint8Array): Uint8Array {
  return seal(sodium, kek, mk, WRAP_AAD)
}

export function unwrapMasterKey(sodium: Sodium, kek: Uint8Array, wrapped: Uint8Array): Uint8Array {
  return open(sodium, kek, wrapped, WRAP_AAD)
}

// 恢复密钥包裹使用独立 AAD 域（与主密码 KEK 包裹严格分离，杜绝跨场景密文混用）。
// 必须与 server/internal/crypto/envelope.go 的 recoveryAAD 逐字节一致。
const RECOVERY_WRAP_AAD = new TextEncoder().encode('eve:v1:master-key-recovery/v1')

/** 用恢复密钥派生的 REK 包裹 MK（注册与恢复码轮换时调用）。 */
export function wrapForRecovery(sodium: Sodium, rek: Uint8Array, mk: Uint8Array): Uint8Array {
  return seal(sodium, rek, mk, RECOVERY_WRAP_AAD)
}

/** 用恢复码派生的 REK 解开 MK（忘记主密码的恢复向导调用）。 */
export function unwrapForRecovery(
  sodium: Sodium,
  rek: Uint8Array,
  wrapped: Uint8Array,
): Uint8Array {
  return open(sodium, rek, wrapped, RECOVERY_WRAP_AAD)
}

export function recordAAD(id: string, module: string, version: number): Uint8Array {
  const prefix = new TextEncoder().encode(`eve:v1:record:${id}:${module}:`)
  const out = new Uint8Array(prefix.length + 8)
  out.set(prefix, 0)
  const view = new DataView(out.buffer, prefix.length, 8)
  view.setBigUint64(0, BigInt(version), false) // 大端
  return out
}

export function sealRecord(
  sodium: Sodium,
  key: Uint8Array,
  plaintext: Uint8Array,
  id: string,
  module: string,
  version: number,
): Uint8Array {
  return seal(sodium, key, plaintext, recordAAD(id, module, version))
}

export function openRecord(
  sodium: Sodium,
  key: Uint8Array,
  sealed: Uint8Array,
  id: string,
  module: string,
  version: number,
): Uint8Array {
  return open(sodium, key, sealed, recordAAD(id, module, version))
}

// ---- 位置轨迹块（阶段 4a）AAD 域 ----
// 与 Android CryptoEnvelope.locationBlockAAD 逐字节一致（Task 5 契约锚点）：
// 块不可变、幂等，因此无版本号段。

export function locationBlockAAD(blockId: string): Uint8Array {
  return new TextEncoder().encode(`eve:v1:location-block:${blockId}`)
}

export function openLocationBlock(
  sodium: Sodium,
  key: Uint8Array,
  sealed: Uint8Array,
  blockId: string,
): Uint8Array {
  return open(sodium, key, sealed, locationBlockAAD(blockId))
}

function seal(sodium: Sodium, key: Uint8Array, plaintext: Uint8Array, aad: Uint8Array): Uint8Array {
  const nonce = sodium.randombytes_buf(sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  // 注意 libsodium-wrappers 0.7.13+ 的参数顺序为
  // (message, additional_data, secret_nonce, public_nonce, key)，
  // secret_nonce 固定 null（由我们外置随机 public nonce）。
  const ciphertext = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
    plaintext,
    aad,
    null,
    nonce,
    key,
  )
  const out = new Uint8Array(nonce.length + ciphertext.length)
  out.set(nonce, 0)
  out.set(ciphertext, nonce.length)
  return out
}

function open(sodium: Sodium, key: Uint8Array, sealed: Uint8Array, aad: Uint8Array): Uint8Array {
  const nonceLen = sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
  const nonce = sealed.subarray(0, nonceLen)
  const ciphertext = sealed.subarray(nonceLen)
  return sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null,
    ciphertext,
    aad,
    nonce,
    key,
  )
}

// ---- base64 工具：API 的字节字段以标准 base64 传输 ----

export function toBase64(bytes: Uint8Array): string {
  let bin = ''
  bytes.forEach((b) => (bin += String.fromCharCode(b)))
  return btoa(bin)
}

export function fromBase64(b64: string): Uint8Array {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}
