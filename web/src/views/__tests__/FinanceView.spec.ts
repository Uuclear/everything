// ============================================================================
// FinanceView / AppShell 通知接线静态断言（stage5-finance-v2 / B7 / FR-V2-G）
// ============================================================================
//
// 验证目标（6 用例，node 环境，禁止 mount）：
//   1. FinanceView 顶部工具区存在常驻通知开关：
//      data-testid="finance-notification-switch" + n-switch，且开关节点
//      不带 v-if（任何 Tab 下都常驻显示）；
//   2. FinanceView 含「浏览器通知」标签，开关 :value 绑定 store 偏好位，
//      切换回调走 onToggleNotifications → store.setNotificationsEnabled；
//   3. FinanceView 失败提示为两条固定抽象文案（不支持 / 权限未开启），
//      且开启前先用 getFinanceNotifier 探测环境；
//   4. 零知识负向断言：FinanceView 新增通知相关代码不引入任何货币符号、
//      日期形态数字或四位连续数字（标题 / 正文 / toast 只有抽象类型文案）；
//   5. AppShell 解锁收尾接线：注册 Service Worker、偏好位为 true 时
//      重新 enable 并补发到期提醒；
//   6. AppShell 锁定 / 退出 / 授权失效三处均停止通知（stopNotifications
//      在源码中至少出现三次，且分别落在三个流程函数体内）。
//
// 测试策略：项目 vitest 环境固定为 node（无 jsdom / test-utils），沿用
// B6 BudgetList.spec.ts 的 ?raw 静态源码断言方案：模板结构事实通过读取
// .vue 源文本做字符串 / 正则断言，不挂载组件。
//
// 关联:
//   - web/src/views/FinanceView.vue（被测目标一）
//   - web/src/views/AppShell.vue（被测目标二）
// ============================================================================

import { describe, it, expect } from 'vitest'
// Vite 的 ?raw 后缀把单文件组件源码作为字符串导入（vitest 原生支持，
// vite/client 已带类型声明），避免引入 node:fs（项目未装 @types/node）。
import FINANCE_SOURCE from '../FinanceView.vue?raw'
import SHELL_SOURCE from '../AppShell.vue?raw'

/**
 * 截取源码中某个子串所在行（用于“开关不带 v-if、与 testid 同节点”
 * 这类需要就近核对的断言）。
 */
function lineContaining(source: string, needle: string): string {
  const hit = source.split('\n').find((line) => line.includes(needle))
  return hit ?? ''
}

describe('FinanceView / 浏览器通知开关 UI（FR-V2-G / AC-V2F-14）', () => {
  it('工具区存在 data-testid 固定的 n-switch，且为常驻节点（无 v-if）', () => {
    // 测试锚点：E2E / 手工验证均靠该固定 testid 定位开关。
    expect(FINANCE_SOURCE).toContain('data-testid="finance-notification-switch"')
    expect(FINANCE_SOURCE).toContain('<n-switch')
    // 开关所在标签行不得挂 v-if（与按 Tab 切换的“新建”按钮区分，必须常驻）。
    const switchLine = lineContaining(FINANCE_SOURCE, 'data-testid="finance-notification-switch"')
    expect(switchLine).not.toContain('v-if')
    // 常驻开关必须位于 head-tools 工具区内（页面头部，而非某个 Tab 列表内）。
    const headToolsIdx = FINANCE_SOURCE.indexOf('class="head-tools"')
    const switchIdx = FINANCE_SOURCE.indexOf('data-testid="finance-notification-switch"')
    expect(headToolsIdx).toBeGreaterThanOrEqual(0)
    expect(switchIdx).toBeGreaterThan(headToolsIdx)
  })

  it('开关带「浏览器通知」标签，:value 绑定偏好位并经回调调 store action', () => {
    expect(FINANCE_SOURCE).toContain('浏览器通知')
    // 单向绑定 store 偏好位：失败时 store 位不变，开关自动回弹。
    expect(FINANCE_SOURCE).toContain(':value="store.notificationsEnabled"')
    // 切换事件必须收口到本地回调（回调内部再调 store，不直接在模板内联）。
    expect(FINANCE_SOURCE).toContain('@update:value="onToggleNotifications"')
    expect(FINANCE_SOURCE).toContain('function onToggleNotifications')
    expect(FINANCE_SOURCE).toContain('store.setNotificationsEnabled')
  })

  it('开启前探测通知器，失败给出两条固定抽象文案', () => {
    // 环境探测：node / 不支持 Notification 或 IndexedDB 时单例恒为 null。
    expect(FINANCE_SOURCE).toContain('getFinanceNotifier')
    // 提示走 naive-ui useMessage（App.vue 根已挂 NMessageProvider）。
    expect(FINANCE_SOURCE).toContain('useMessage')
    expect(FINANCE_SOURCE).toContain('message.warning')
    // 文案一：环境不支持（不承诺“去设置修改”，因为根本没有该能力）。
    expect(FINANCE_SOURCE).toContain('当前浏览器不支持系统通知')
    // 文案二：权限申请未获 granted（引导用户改浏览器设置）。
    expect(FINANCE_SOURCE).toContain('通知权限未开启，可在浏览器设置中修改')
  })
})

describe('FinanceView / 通知 UI 零知识负向断言', () => {
  it('开关相关源码不含货币符号、日期形态或四位连续数字', () => {
    // 货币符号黑名单（含全角人民币 ￥、半角美元、欧元、英镑）。
    // 注意：既有注释中出现的 ¥ 为 U+00A5（另一码位），不在本黑名单内；
    // 新增代码一律不允许出现下列任一符号。
    expect(/[￥$€£]/.test(FINANCE_SOURCE)).toBe(false)
    // 日期形态（年与月以连字符或斜杠相连）不得出现在通知 UI 文案中。
    expect(/\d{4}[-/]\d{1,2}/.test(FINANCE_SOURCE)).toBe(false)
    // 四位连续数字（年份 / 卡号片段 / 金额千分位等的粗略形态）不得出现。
    expect(/\d{4}/.test(FINANCE_SOURCE)).toBe(false)
    // 通知相关代码不得拼接任何业务数据字段名（金额 / 日期 / 卡号 / 名称等）。
    const notifyBlock = FINANCE_SOURCE.slice(
      FINANCE_SOURCE.indexOf('B7 浏览器通知开关'),
      FINANCE_SOURCE.indexOf('</script>'),
    )
    for (const forbidden of [
      'amount_minor',
      'counterparty',
      'card_no',
      'next_renewal_ts',
      'expiry_ts',
      'due_ts',
      'policy_no',
      'provider',
    ]) {
      expect(notifyBlock).not.toContain(forbidden)
    }
  })
})

describe('AppShell / 解锁恢复通知（FR-V2-G / AC-V2F-15）', () => {
  it('finishUnlock 注册 SW，并按偏好位重新 enable 后全量重排与补发', () => {
    // 从通知模块直接导入并调用注册函数（不是从 store re-export 取）。
    expect(SHELL_SOURCE).toContain(
      "import { registerFinanceServiceWorker } from '../notifications/financeNotifications'",
    )
    expect(SHELL_SOURCE).toContain('registerFinanceServiceWorker()')
    // 偏好位为 true 才重新申请启用（已 granted 不会再弹框）。
    expect(SHELL_SOURCE).toContain('financeStore.notificationsEnabled')
    expect(SHELL_SOURCE).toContain('financeStore.setNotificationsEnabled(true)')
    // 启用成功后立即全量重排 + 到期补发。
    expect(SHELL_SOURCE).toContain('financeStore.syncNotificationSchedules()')
    expect(SHELL_SOURCE).toContain('financeStore.replayDueNotifications()')
    // 恢复逻辑必须挂在解锁公共收尾内，保证三种解锁路径（密码 / MFA / 待审批）
    // 都能走到。
    const finishIdx = SHELL_SOURCE.indexOf('async function finishUnlock')
    const restoreCallIdx = SHELL_SOURCE.indexOf('restoreFinanceNotifications()', finishIdx)
    expect(finishIdx).toBeGreaterThanOrEqual(0)
    expect(restoreCallIdx).toBeGreaterThan(finishIdx)
  })
})

describe('AppShell / 锁定、登出、授权失效停止通知', () => {
  it('lock / logout / onAuthExpired 三个流程均调用 stopNotifications', () => {
    // 至少三次：锁定、退出登录、刷新令牌失效回调各一次。
    const occurrences = SHELL_SOURCE.split('financeStore.stopNotifications()').length - 1
    expect(occurrences).toBeGreaterThanOrEqual(3)

    // 逐流程就近核对，防止三次调用都挤在同一个函数内。
    const lockIdx = SHELL_SOURCE.indexOf('function lock()')
    const logoutIdx = SHELL_SOURCE.indexOf('function logout()')
    const expiredIdx = SHELL_SOURCE.indexOf('onAuthExpired(() =>')

    const stopAfter = (start: number) =>
      SHELL_SOURCE.indexOf('financeStore.stopNotifications()', start)

    expect(stopAfter(lockIdx)).toBeGreaterThan(lockIdx)
    expect(stopAfter(logoutIdx)).toBeGreaterThan(logoutIdx)
    expect(stopAfter(expiredIdx)).toBeGreaterThan(expiredIdx)
    // 授权失效处的停止调用必须出现在 auth.logout() 之前（先撤通知再清会话）。
    const expiredStop = stopAfter(expiredIdx)
    const expiredLogout = SHELL_SOURCE.indexOf('auth.logout()', expiredIdx)
    expect(expiredStop).toBeLessThan(expiredLogout)
  })
})
