// 密码生成器：crypto.getRandomValues 无偏拒绝采样。
// 长度 8–64；四类字符集可独立开关（至少保留一类）；可选排除易混淆字符。

const SETS = {
  lower: 'abcdefghijklmnopqrstuvwxyz',
  upper: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
  digit: '0123456789',
  symbol: '!@#$%^&*()-_=+[]{};:,.?/',
} as const

// 易混淆字符（排除歧义选项）：0/O、1/l/I 等。
const AMBIGUOUS = new Set('0O1lI|`\'\'""')

export interface GeneratorOptions {
  length: number
  lower: boolean
  upper: boolean
  digit: boolean
  symbol: boolean
  excludeAmbiguous: boolean
}

export const DEFAULT_GENERATOR_OPTIONS: GeneratorOptions = {
  length: 20,
  lower: true,
  upper: true,
  digit: true,
  symbol: false,
  excludeAmbiguous: true,
}

/** 无偏采样：拒绝 >= floor(256 / len) * len 的字节，避免模运算导致的字符概率倾斜。 */
function pickUnbiased(pool: string): string {
  const limit = Math.floor(256 / pool.length) * pool.length
  const buf = new Uint8Array(1)
  // 极低概率连续越界，循环在统计上必然快速终止。
  for (;;) {
    crypto.getRandomValues(buf)
    if (buf[0] < limit) return pool[buf[0] % pool.length]
  }
}

export function generatePassword(opts: GeneratorOptions): string {
  const length = Math.min(64, Math.max(8, Math.floor(opts.length) || 8))
  let pool = ''
  const guaranteed: string[] = []
  ;(Object.keys(SETS) as (keyof typeof SETS)[]).forEach((key) => {
    if (!opts[key]) return
    const chars = opts.excludeAmbiguous
      ? SETS[key].split('').filter((c) => !AMBIGUOUS.has(c)).join('')
      : SETS[key]
    if (chars) {
      pool += chars
      guaranteed.push(pickUnbiased(chars)) // 保证每类至少出现 1 个
    }
  })
  if (!pool) throw new Error('至少选择一类字符')

  const chars: string[] = []
  for (let i = 0; i < length; i += 1) chars.push(pickUnbiased(pool))
  // 把各类保底字符随机填入前 N 位，再整体 Fisher-Yates 洗牌（避免保底位可预测）。
  guaranteed.forEach((c, i) => {
    chars[i] = c
  })
  for (let i = chars.length - 1; i > 0; i -= 1) {
    const jBuf = new Uint32Array(1)
    crypto.getRandomValues(jBuf)
    const j = jBuf[0] % (i + 1)
    ;[chars[i], chars[j]] = [chars[j], chars[i]]
  }
  return chars.slice(0, length).join('')
}
