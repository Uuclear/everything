// Crockford Base32（https://www.crockford.com/base32.html）：
// 恢复密钥的人工备份编码。字母表去除易混淆字符（无 I/L/O/U）：
//   0123456789ABCDEFGHJKMNPQRSTVWXYZ
// 20 字节随机量恰好编码为 32 个字符，展示时按 4 组×8 字符用连字符分隔。
// 本模块必须与 Android 端、docs/crypto.md 中的向量逐字节一致；服务端不接触
// 恢复码明文（归一化只在客户端发生，归一化后的串才进入 Argon2id 派生）。

export const RECOVERY_KEY_BYTES = 20
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const GROUP_SIZE = 8
const GROUP_COUNT = 4

/**
 * 把任意长度字节按 MSB 优先的 5bit 分组编码为 Crockford Base32。
 * 恢复密钥固定 20 字节 → 32 字符（160 位 / 5 = 32，整除无填充）。
 */
export function crockfordEncode(bytes: Uint8Array): string {
  let bits = 0
  let value = 0
  let out = ''
  for (const b of bytes) {
    value = (value << 8) | b
    bits += 8
    while (bits >= 5) {
      bits -= 5
      // 取最高 5 位作为一个字符索引。
      out += ALPHABET[(value >>> bits) & 0x1f]
    }
  }
  // 20 字节输入恰好耗尽；其他长度（非 5bit 整数倍）补 0 位。
  if (bits > 0) {
    out += ALPHABET[(value << (5 - bits)) & 0x1f]
  }
  return out
}

/**
 * 规范化用户输入的恢复码：
 *   - 去首尾空白、内部空格与连字符（支持 "XXXX-XXXX" 分组粘贴）；
 *   - 小写转大写；
 *   - Crockford 经典纠错映射 I/L→1、O→0（U 不在字母表内，直接报错）。
 * 返回 32 字符大写串；长度或字符非法时抛错（调用方负责提示）。
 */
export function normalizeRecoveryCode(input: string): string {
  const cleaned = input
    .trim()
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/I/g, '1')
    .replace(/L/g, '1')
    .replace(/O/g, '0')

  if (cleaned.length !== 32) {
    throw new Error(`恢复码应为 32 个字符，当前 ${cleaned.length} 个`)
  }
  for (const ch of cleaned) {
    if (!ALPHABET.includes(ch)) {
      throw new Error(`恢复码包含非法字符：${ch}`)
    }
  }
  return cleaned
}

/** 解码规范化后的 32 字符恢复码，必须恰好还原为 20 字节。 */
export function crockfordDecode(normalized: string): Uint8Array {
  let bits = 0
  let value = 0
  const out: number[] = []
  for (const ch of normalized) {
    const idx = ALPHABET.indexOf(ch)
    if (idx < 0) throw new Error(`非法字符：${ch}`)
    value = (value << 5) | idx
    bits += 5
    if (bits >= 8) {
      bits -= 8
      out.push((value >>> bits) & 0xff)
    }
  }
  if (out.length !== RECOVERY_KEY_BYTES || bits !== 0) {
    throw new Error('恢复码解码结果不是 20 字节')
  }
  return Uint8Array.from(out)
}

/** 生成 4 组×8 字符的展示串（连字符只用于展示，不参与派生）。 */
export function formatRecoveryCode(code: string): string {
  const groups: string[] = []
  for (let i = 0; i < GROUP_COUNT; i++) {
    groups.push(code.slice(i * GROUP_SIZE, (i + 1) * GROUP_SIZE))
  }
  return groups.join('-')
}

/** 便捷方法：规范化 → 分组展示（输入框失焦回显用）。 */
export function normalizeAndFormat(input: string): string {
  return formatRecoveryCode(normalizeRecoveryCode(input))
}
