// 敏感字段复制：30 秒后自动清空剪贴板，降低明文驻留时间。
// 若期间复制了别的内容则不清空（尽力比对，无读取权限时直接覆写为空）。
import { ref } from 'vue'

const CLEAR_AFTER_MS = 30_000
let timer = 0
/** 当前由本应用写入、等待清空的值（用于避免误清用户后续复制的其他内容）。 */
let pendingValue: string | null = null

/** 全局倒计时秒数（多个组件共用一个徽标显示）。 */
export const clipboardCountdown = ref(0)
let countdownTimer = 0

export function useClipboard() {
  async function copySecret(text: string): Promise<boolean> {
    if (!text) return false
    if (!navigator.clipboard?.writeText) return false
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      return false
    }

    window.clearTimeout(timer)
    window.clearInterval(countdownTimer)
    pendingValue = text
    const deadline = Date.now() + CLEAR_AFTER_MS
    clipboardCountdown.value = Math.round(CLEAR_AFTER_MS / 1000)
    countdownTimer = window.setInterval(() => {
      clipboardCountdown.value = Math.max(0, Math.round((deadline - Date.now()) / 1000))
    }, 1000)

    timer = window.setTimeout(async () => {
      window.clearInterval(countdownTimer)
      clipboardCountdown.value = 0
      // 能读取剪贴板时先比对，避免清掉用户后来复制的无关内容。
      try {
        const cur = await navigator.clipboard.readText()
        if (cur !== pendingValue) return
      } catch {
        // readText 权限被拒：按计划清空（Firefox 等环境的兜底行为）。
      }
      try {
        await navigator.clipboard.writeText('')
      } catch {
        // 某些浏览器后台标签禁止写剪贴板：尽力而为，不打扰用户。
      } finally {
        pendingValue = null
      }
    }, CLEAR_AFTER_MS)
    return true
  }

  return { copySecret, countdown: clipboardCountdown }
}
