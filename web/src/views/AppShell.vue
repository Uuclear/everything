<script setup lang="ts">
// 应用主框架：侧边导航 + 顶栏（搜索/同步状态/用户菜单）+ 锁屏。
// 主密钥仅存内存：刷新页面后令牌仍在但 MK 消失，需要在锁屏重新输入主密码。
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NLayout,
  NLayoutSider,
  NLayoutHeader,
  NLayoutContent,
  NMenu,
  NInput,
  NButton,
  NSpace,
  NCard,
  NDropdown,
  NTag,
  NBadge,
  useMessage,
} from 'naive-ui'
import type { MenuOption } from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { useVaultStore } from '../stores/vault'
import { useLocationsStore } from '../stores/locations'
// B7 / FR-V2-G：财务 store（通知偏好位与补发 / 停止动作的收口方）。
import { useFinanceStore } from '../stores/finance'
// B7：解锁成功后注册全站唯一 Service Worker（不支持 / 失败均静默）。
import { registerFinanceServiceWorker } from '../notifications/financeNotifications'
import { eventsStatus, getSharedChannel } from '../stores/events'
import { uiSearch } from '../composables/useUiSearch'
import { getAccessToken, onAuthExpired } from '../api/client'
import MfaPanel from '../components/MfaPanel.vue'
import PendingPanel from '../components/PendingPanel.vue'
import type { LoginResponse } from '../api/client'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const auth = useAuthStore()
const vault = useVaultStore()
const locations = useLocationsStore()
const financeStore = useFinanceStore()

const unlockPassword = ref('')
const unlocking = ref(false)
// 本地“外壳已解锁”标志：不能直接用 auth.unlocked 作为锁屏容器的 v-if 条件——
// finishMfa/登录返回 approved 的瞬间 masterKey 即已写入 store，Vue 会在同一微任务
// flush 中先卸载锁屏内的 MfaPanel，随后面板的 emit('resolved') 因组件已卸载被静默
// 丢弃，导致首同步/SSE 永不启动。改由 finishUnlock 全部完成后再翻转本标志。
const shellUnlocked = ref(false)

// ---- 导航 ----
const expiringCount = computed(() => vault.expiring.length)
const menuOptions = computed<MenuOption[]>(() => [
  { label: '登录项', key: 'logins' },
  { label: '安全笔记', key: 'notes' },
  { label: '银行卡', key: 'cards' },
  { label: '证件', key: 'identities' },
  {
    label: '到期提醒',
    key: 'expiring',
    // 菜单项尾部挂到期数量角标（红/橙/黄条目数）。
    render: () =>
      h('span', { class: 'menu-label' }, [
        h('span', null, '到期提醒'),
        expiringCount.value > 0
          ? h(NBadge, { value: expiringCount.value, max: 99, size: 'small' }, {
              default: () => h('span', { class: 'badge-anchor' }),
            })
          : null,
      ]),
  },
  { type: 'divider', key: 'd1' },
  { label: '轨迹', key: 'locations' },
  // 阶段 4b — 日程/日历：与轨迹同级侧栏入口，对应 /vault/calendar 路由。
  { label: '日历', key: 'calendar' },
  { type: 'divider', key: 'd2' },
  // 阶段 5 — 财务：与日历/轨迹同级侧栏入口, 对应 /finance 路由。
  { label: '财务', key: 'finance' },
  { label: '设备管理', key: 'devices' },
  { label: '安全设置', key: 'security' },
])

const activeKey = computed(() => (route.name as string) ?? 'logins')
function onMenu(key: string) {
  // 阶段 5 — 财务路由独立挂载在 /finance, 其它既有项均位于 /vault。
  if (key === 'finance') {
    router.push({ name: 'finance' })
    return
  }
  router.push({ name: key })
}

// ---- 实时通道 ----
const channel = getSharedChannel(() => {
  if (auth.unlocked) void vault.sync()
})
let offBus: (() => void) | null = null

function startChannel() {
  offBus?.()
  offBus = channel.bus.on((e) => {
    if (e.type === 'records_changed') void vault.sync()
  })
  void channel.start()
}

// ---- 解锁 / 锁定 / 退出 ----
async function unlock() {
  unlocking.value = true
  try {
    const result = await auth.login(auth.username, unlockPassword.value)
    if (result.status === 'approved') {
      await finishUnlock()
    } else if (result.status === 'mfa_required') {
      // 账户启用了 TOTP：密码正确后停留在锁屏卡片，由内嵌的 MfaPanel 接续。
      // 不能跳 welcome——那里的守卫会因令牌仍在被弹回，且没有 MFA 输入入口。
      unlockPassword.value = ''
    } else {
      // pending（设备待审批，例如本机被吊销后重新登录）：同样留在锁屏卡片，
      // 由内嵌的 PendingPanel 承接（跳 welcome 会被路由守卫以“仍有令牌”弹回）。
      unlockPassword.value = ''
    }
  } catch {
    message.error('主密码错误')
  } finally {
    unlocking.value = false
  }
}

/** MFA 二步通过（MfaPanel resolved）后完成解锁：首同步并开启事件通道。 */
async function onLockMfaResolved(result: LoginResponse) {
  if (result.status === 'pending') {
    // TOTP 通过但设备需重新审批：PendingPanel 已因 store 中 pending 状态自动显示。
    return
  }
  if (result.status !== 'approved') {
    router.replace({ name: 'welcome' })
    return
  }
  unlocking.value = true
  try {
    await finishUnlock()
  } finally {
    unlocking.value = false
  }
}

/** 待审批面板轮询到 approved（MK 已开箱入内存）后完成解锁。 */
async function onLockPairingUnlocked() {
  unlocking.value = true
  try {
    await finishUnlock()
  } finally {
    unlocking.value = false
  }
}

/** 锁屏内待审批面板选择“改用恢复码”：清令牌后交给欢迎页恢复向导。 */
function onLockUseRecovery() {
  channel.stop()
  vault.reset()
  locations.reset() // 轨迹明文同样清空
  auth.logout()
  router.replace({ name: 'welcome' })
}

/**
 * B7：解锁成功后恢复财务浏览器通知（FR-V2-G / AC-V2F-15）。
 *
 * 顺序刻意安排在首同步（vault.sync → finance.pullAll）之后：此时远端提醒
 * 数据已入 store。全流程 void 非阻塞，且各环节内部自带异常吞咽，任何
 * 失败都不影响进入主框架。零知识：恢复过程只传递 { kind, id, triggerMs }。
 */
function restoreFinanceNotifications(): void {
  // 1. 注册 Service Worker（通知点击 / 展示由根作用域 sw.js 承接）；
  //    环境不支持或注册失败时该 Promise 内部已静默 resolve。
  void registerFinanceServiceWorker()
  // 2. 还原本地持久化偏好位（幂等；finance 视图自身也会 hydrate，
  //    这里提前做是为了不依赖用户是否已访问过财务页）。
  if (!financeStore.hydrated) financeStore.hydrate()
  // 3. 仅当用户上次锁定前开启过通知时：重新向通知器申请启用（已是
  //    granted 权限时不会再弹申请框），随后立即全量重排并补发到期提醒。
  if (financeStore.notificationsEnabled) {
    void financeStore.setNotificationsEnabled(true).then((ok) => {
      if (!ok) return
      void financeStore.syncNotificationSchedules()
      void financeStore.replayDueNotifications()
    })
  }
}

/** 解锁公共收尾：首同步、开启 SSE，最后翻转外壳标志进入主框架。 */
async function finishUnlock() {
  unlockPassword.value = ''
  // 首同步失败不阻断解锁：进入主框架后用户仍可手动同步（顶部有同步按钮与状态）。
  try {
    await vault.sync(true)
    message.success('资料库已解锁')
  } catch {
    message.warning('已解锁，但首次同步失败，可稍后点击顶部“同步”重试')
  }
  startChannel()
  shellUnlocked.value = true
  // B7：解锁成功后恢复财务通知（SW 注册 + 按偏好位重新启用 + 到期补发）。
  restoreFinanceNotifications()
}

function lock() {
  // 锁定：清空内存 MK 与全部明文，保留登录令牌（重新解锁只需主密码）。
  channel.stop()
  auth.lock()
  vault.reset()
  locations.reset() // 轨迹明文只驻内存，锁定即清
  // B7：撤销所有已排期财务通知并释放定时器（偏好位保留，解锁后无感恢复）。
  financeStore.stopNotifications()
  shellUnlocked.value = false
  uiSearch.query = ''
}

function logout() {
  channel.stop()
  vault.reset()
  locations.reset()
  // B7：退出登录同样立即停止财务通知（偏好位留在本地，下次登录仍生效）。
  financeStore.stopNotifications()
  shellUnlocked.value = false
  auth.logout()
  router.replace({ name: 'welcome' })
}

const userMenuOptions: MenuOption[] = [
  { label: '安全设置', key: 'security' },
  { label: '设备管理', key: 'devices' },
  { type: 'divider', key: 'd' },
  { label: '锁定资料库', key: 'lock' },
  { label: '退出登录', key: 'logout' },
]
function onUserMenu(key: string) {
  if (key === 'lock') lock()
  else if (key === 'logout') logout()
  else router.push({ name: key })
}

const syncText = computed(() => {
  if (vault.syncing) return '同步中…'
  if (!vault.lastSyncAt) return '未同步'
  return `上次同步 ${new Date(vault.lastSyncAt).toLocaleTimeString()}`
})

onMounted(async () => {
  // 刷新令牌也失效时：停通道、清明文、回欢迎页重新登录。
  onAuthExpired(() => {
    channel.stop()
    vault.reset()
    locations.reset()
    // B7：授权失效立即停止财务通知（与 lock / logout 同口径）。
    financeStore.stopNotifications()
    shellUnlocked.value = false
    auth.logout()
    router.replace({ name: 'welcome' })
  })
  if (!getAccessToken()) {
    router.replace({ name: 'welcome' })
    return
  }
  if (auth.unlocked) {
    await vault.sync(true)
    startChannel()
    shellUnlocked.value = true
  }
})
onBeforeUnmount(() => offBus?.())
</script>

<template>
  <n-layout class="shell">
    <!-- 锁屏（显隐由本地 shellUnlocked 控制，原因见其声明处注释） -->
    <div v-if="!shellUnlocked" class="lock">
      <n-card class="lock-card" title="资料库已锁定">
        <!--
          三态承接（显隐均不能用 auth.unlocked，否则 await 恢复时面板先被卸载丢 emit，
          详见 shellUnlocked 注释）：
          1) 设备待审批（含被吊销后重新登录）→ PendingPanel
          2) 密码正确但需 TOTP → MfaPanel
          3) 常规 → 主密码表单
        -->
        <PendingPanel
          v-if="auth.pendingPairing"
          @unlocked="onLockPairingUnlocked"
          @use-recovery="onLockUseRecovery"
        />
        <MfaPanel v-else-if="auth.mfaToken" @resolved="onLockMfaResolved" />
        <template v-else>
          <p class="lock-sub">
            主密钥仅保存在内存中，刷新页面后需要重新解锁（{{ auth.username }}）
          </p>
          <n-input
            v-model:value="unlockPassword"
            type="password"
            show-password-on="click"
            placeholder="输入主密码"
            @keyup.enter="unlock"
          />
          <n-space class="lock-actions">
            <n-button type="primary" :loading="unlocking" @click="unlock">解锁</n-button>
            <n-button @click="logout">切换账户</n-button>
          </n-space>
        </template>
      </n-card>
    </div>

    <!-- 主框架 -->
    <n-layout v-else has-sider class="main">
      <n-layout-sider bordered class="sider" :width="196" :collapsed-width="0" show-trigger="bar">
        <div class="brand">Everything</div>
        <n-menu
          :value="activeKey"
          :options="menuOptions"
          :indent="18"
          @update:value="onMenu"
        />
      </n-layout-sider>

      <n-layout>
        <n-layout-header bordered class="header">
          <n-input
            v-model:value="uiSearch.query"
            class="search"
            clearable
            placeholder="搜索当前分类（标题/账号/网址/卡号尾号/备注/证号）"
          />
          <n-space align="center" :size="12">
            <n-tag size="small" :bordered="false" :type="eventsStatus.connected ? 'success' : 'warning'">
              {{ eventsStatus.connected ? '实时' : '重连中' }}
            </n-tag>
            <span class="sync-text">{{ syncText }}</span>
            <n-button size="tiny" quaternary :loading="vault.syncing" @click="vault.sync(true)">
              同步
            </n-button>
            <n-dropdown :options="userMenuOptions" trigger="click" @select="onUserMenu">
              <n-button size="small">{{ auth.username }} ▾</n-button>
            </n-dropdown>
          </n-space>
        </n-layout-header>

        <n-layout-content class="content" content-style="padding: 20px;">
          <router-view />
        </n-layout-content>
      </n-layout>
    </n-layout>
  </n-layout>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}
.lock {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2a44 0%, #0f1525 100%);
}
.lock-card {
  width: 420px;
  max-width: 92vw;
  border-radius: 14px;
}
.lock-sub {
  margin: 0 0 12px;
  font-size: 12px;
  color: #8a8f9c;
}
.lock-actions {
  margin-top: 14px;
}
.sider {
  background: #fff;
}
.brand {
  font-weight: 700;
  font-size: 17px;
  letter-spacing: 1px;
  padding: 18px 20px 12px;
}
.menu-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 8px;
}
.badge-anchor {
  display: inline-block;
  width: 8px;
}
.header {
  height: 56px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #fff;
}
.search {
  max-width: 460px;
  flex: 1;
}
.sync-text {
  font-size: 12px;
  color: #8a8f9c;
  white-space: nowrap;
}
.content {
  min-height: calc(100vh - 56px);
  background: #f5f6f8;
}
:deep(.content > *) {
  max-width: 860px;
  margin: 0 auto;
}
</style>
