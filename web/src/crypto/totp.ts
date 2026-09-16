// RFC 6238 TOTP（基于时间的一次性密码），WebCrypto HMAC-SHA1 原生实现，
// 与服务端 github.com/pquerna/otp、Google Authenticator 默认参数一致：
//   period=30s、digits=6（可选 8）、T0=0、算法 HMAC-SHA1。
// otpauth 密钥交换 URI 见 https://github.com/google/google-authenticator/wiki/Key-Uri-Format

export interface TotpOptions {
  digits?: number // 6 或 8，默认 6
  period?: number // 秒，默认 30
  at?: Date // 测试注入时间，默认当前时间
}

/** 生成指定时刻的 TOTP 码（左侧补零到 digits 位）。 */
export async function totpCode(
  secret: Uint8Array,
  opts: TotpOptions = {},
): Promise<string> {
  const digits = opts.digits ?? 6
  const period = opts.period ?? 30
  const counter = Math.floor((opts.at ?? new Date()).getTime() / 1000 / period)
  return hotp(secret, counter, digits)
}

/** RFC 4226 HOTP：HMAC-SHA1(K, C 的 8 字节大端) → 动态截断 → 模 10^digits。 */
async function hotp(secret: Uint8Array, counter: number, digits: number): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    secret,
    { name: 'HMAC', hash: 'SHA-1' },
    false,
    ['sign'],
  )
  const msg = new ArrayBuffer(8)
  new DataView(msg).setBigUint64(0, BigInt(counter), false) // 计数器大端 64 位
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, msg))

  // 动态截断：低 4 位决定偏移，取 4 字节并屏蔽最高位。
  const offset = mac[mac.length - 1] & 0x0f
  const binary =
    ((mac[offset] & 0x7f) << 24) |
    (mac[offset + 1] << 16) |
    (mac[offset + 2] << 8) |
    mac[offset + 3]
  const code = binary % 10 ** digits
  return String(code).padStart(digits, '0')
}

/** 标准 RFC 4648 Base32（A-Z2-7，忽略大小写、空格与填充 =），otpauth secret 用。 */
export function decodeBase32(input: string): Uint8Array {
  const clean = input.toUpperCase().replace(/[\s-]/g, '').replace(/=+$/, '')
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = 0
  let value = 0
  const out: number[] = []
  for (const ch of clean) {
    const idx = alphabet.indexOf(ch)
    if (idx < 0) throw new Error(`Base32 非法字符：${ch}`)
    value = (value << 5) | idx
    bits += 5
    if (bits >= 8) {
      bits -= 8
      out.push((value >>> bits) & 0xff)
    }
  }
  return Uint8Array.from(out)
}

export interface OtpauthParams {
  secret: string // 原始 Base32 secret（直接展示给用户手输）
  issuer: string
  account: string
  digits: number
  period: number
  algorithm: string
}

/** 解析 otpauth://totp/Issuer:account?secret=...&issuer=...&digits=6&period=30。 */
export function parseOtpauth(uri: string): OtpauthParams {
  const url = new URL(uri)
  if (url.protocol !== 'otpauth:' || url.host !== 'totp') {
    throw new Error('不是合法的 otpauth://totp 链接')
  }
  // pathname 形如 "/Everything:nora"；label 中 issuer 可被 query.issuer 覆盖。
  const label = decodeURIComponent(url.pathname).replace(/^\//, '')
  const colon = label.indexOf(':')
  const labelIssuer = colon >= 0 ? label.slice(0, colon) : ''
  const account = colon >= 0 ? label.slice(colon + 1) : label
  const secret = url.searchParams.get('secret')
  if (!secret) throw new Error('otpauth 链接缺少 secret')
  return {
    secret,
    issuer: url.searchParams.get('issuer') ?? labelIssuer,
    account,
    digits: Number(url.searchParams.get('digits') ?? '6'),
    period: Number(url.searchParams.get('period') ?? '30'),
    algorithm: (url.searchParams.get('algorithm') ?? 'SHA1').toUpperCase(),
  }
}

/**
 * RFC 6238 附录 B 自测：固定 ASCII 密钥 "12345678901234567890"、8 位码，
 * 三个时间点必须与 RFC 表完全一致；另含 6 位 59s 向量。开发模式在控制台输出一次。
 */
export async function totpSelfTest(): Promise<void> {
  const secret = new TextEncoder().encode('12345678901234567890')
  const vectors8: Array<[number, string]> = [
    [59, '94287082'],
    [1111111109, '07081804'],
    [1111111111, '14050471'],
  ]
  for (const [sec, want] of vectors8) {
    const got = await hotp(secret, Math.floor(sec / 30), 8)
    if (got !== want) throw new Error(`TOTP 自测失败 t=${sec}: 期望 ${want}，实际 ${got}`)
  }
  // 6 位向量：t=59 应为 287082。
  const got6 = await hotp(secret, 1, 6)
  if (got6 !== '287082') throw new Error(`TOTP 6 位自测失败：实际 ${got6}`)
}
