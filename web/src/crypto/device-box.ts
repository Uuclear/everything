// 设备 X25519 密钥对与 crypto_box 密封（配对审批时端到端下发 MK）。
// 协议与 Go 端 golang.org/x/crypto/nacl/box、Android lazysodium 完全一致：
//   - 审批端生成一次性临时密钥对 (eph_pk, eph_sk) 与随机 24B nonce；
//   - sealed = crypto_box_easy(MK, nonce, recipient_pk=新设备公钥, eph_sk)；
//   - 新设备 box_open_easy(sealed, nonce, sender_pk=eph_pk, 本机 sk) 还原 MK。
// 服务端只透传 32B eph_pk / 24B nonce / 密文，永远见不到 MK 明文。
//
// 持久化风险边界：设备 seed 存 localStorage（独立键），因此浏览器 XSS 可窃取
// 设备身份并冒充该设备参与配对；但 MK 仅保存在内存中，页面刷新/重开后仍必须
// 通过主密码（或 TOTP + 已审批设备）重新解锁，窃取 seed 不等于获得资料库内容。

import type { Sodium } from './envelope'
import { toBase64, fromBase64 } from './envelope'

const DEVICE_SEED_KEY = 'eve.deviceSeed'

export interface DeviceIdentity {
  publicKey: Uint8Array // 32B X25519 公钥（随注册/登录提交）
  secretKey: Uint8Array // 32B X25519 私钥（仅本机）
}

export interface SealedBox {
  ephemeralPublicKey: Uint8Array // 审批端一次性临时公钥 32B
  nonce: Uint8Array // 24B
  sealed: Uint8Array // box 密文（含 16B Poly1305 标签）
}

/** 生成并持久化（或从 seed 还原）本机设备密钥对。每台浏览器只有一个身份。 */
export function loadOrCreateDeviceIdentity(sodium: Sodium): DeviceIdentity {
  let seed = readSeed()
  if (!seed) {
    seed = sodium.randombytes_buf(sodium.crypto_box_SEEDBYTES)
    localStorage.setItem(DEVICE_SEED_KEY, toBase64(seed))
  }
  // seed_keypair 保证同一 seed 恒等导出同一 (pk, sk)，无需分别持久化。
  const kp = sodium.crypto_box_seed_keypair(seed)
  return { publicKey: kp.publicKey, secretKey: kp.privateKey }
}

/** 清除本机设备身份（退出登录/切换账户时调用；下次启动重新生成）。 */
export function forgetDeviceIdentity() {
  localStorage.removeItem(DEVICE_SEED_KEY)
}

function readSeed(): Uint8Array | null {
  const raw = localStorage.getItem(DEVICE_SEED_KEY)
  if (!raw) return null
  try {
    const seed = fromBase64(raw)
    // 长度不符（历史脏数据）视为不存在，强制重新生成。
    return seed.length === 32 ? seed : null
  } catch {
    return null
  }
}

/**
 * 审批端：用新设备公钥密封 MK。
 * 每次调用生成全新临时密钥对与 nonce（一次性，禁止复用）。
 */
export function boxSeal(
  sodium: Sodium,
  message: Uint8Array,
  recipientPublicKey: Uint8Array,
): SealedBox {
  const ephemeral = sodium.crypto_box_keypair()
  const nonce = sodium.randombytes_buf(sodium.crypto_box_NONCEBYTES)
  const sealed = sodium.crypto_box_easy(message, nonce, recipientPublicKey, ephemeral.privateKey)
  return { ephemeralPublicKey: ephemeral.publicKey, nonce, sealed }
}

/**
 * 新设备：用本机私钥打开审批端下发的盒。
 * 认证失败（盒被调包/材料不匹配）时 libsodium 抛异常，调用方按配对失败处理。
 */
export function boxOpen(
  sodium: Sodium,
  box: SealedBox,
  senderPublicKey: Uint8Array,
  ownSecretKey: Uint8Array,
): Uint8Array {
  return sodium.crypto_box_open_easy(box.sealed, box.nonce, senderPublicKey, ownSecretKey)
}
