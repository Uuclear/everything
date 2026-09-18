import { createRouter, createWebHashHistory } from 'vue-router'
import { getAccessToken } from '../api/client'
import WelcomeView from '../views/WelcomeView.vue'
import AppShell from '../views/AppShell.vue'
import LoginsView from '../views/LoginsView.vue'
import NotesView from '../views/NotesView.vue'
import CardsView from '../views/CardsView.vue'
import IdentitiesView from '../views/IdentitiesView.vue'
import ExpiringView from '../views/ExpiringView.vue'
import DevicesView from '../views/DevicesView.vue'
import SecurityView from '../views/SecurityView.vue'
import LocationsView from '../views/LocationsView.vue'
// 阶段 4b — 日程/日历视图：与既有密码库/轨迹同级，在 /vault 主框架内。
import CalendarView from '../views/CalendarView.vue'
// 阶段 5 — 财务视图：与轨迹 / 日历同级, 在 /finance 主框架内（独立子应用
// 框架, 便于后续拆分子包 / 权限独立配置）。
import FinanceView from '../views/FinanceView.vue'
import FinanceAccountEditor from '../views/finance/FinanceAccountEditor.vue'
import FinanceCardEditor from '../views/finance/FinanceCardEditor.vue'
import FinanceTxEditor from '../views/finance/FinanceTxEditor.vue'
// 阶段 5 v2 — 4 子类型编辑器（TR-1.3）
import FinanceSubscriptionEditor from '../views/finance/FinanceSubscriptionEditor.vue'
import FinancePolicyEditor from '../views/finance/FinancePolicyEditor.vue'
import FinanceLoanEditor from '../views/finance/FinanceLoanEditor.vue'
import FinanceContractEditor from '../views/finance/FinanceContractEditor.vue'
// 阶段 5 v2 B5 — 多币种汇率设置（默认币种 + 离线汇率包导入；FR-V2-C）
import SettingsRatesView from '../views/SettingsRatesView.vue'

export const router = createRouter({
  history: createWebHashHistory(), // hash 模式对单二进制 SPA 托管最友好
  routes: [
    { path: '/', redirect: '/vault/logins' },
    { path: '/welcome', name: 'welcome', component: WelcomeView },
    {
      path: '/vault',
      component: AppShell,
      children: [
        { path: '', redirect: '/vault/logins' },
        { path: 'logins', name: 'logins', component: LoginsView },
        { path: 'notes', name: 'notes', component: NotesView },
        { path: 'cards', name: 'cards', component: CardsView },
        { path: 'identities', name: 'identities', component: IdentitiesView },
        { path: 'expiring', name: 'expiring', component: ExpiringView },
        // 轨迹页（阶段 4a）：与既有页面同在 /vault 主框架内，继承全局登录守卫。
        { path: 'locations', name: 'locations', component: LocationsView },
        // 日历页（阶段 4b）：4a 之后第二个扩展模块；与轨迹共享 /vault 框架与登录守卫。
        { path: 'calendar', name: 'calendar', component: CalendarView },
        // 设备与安全设置也在主框架内（侧边导航进入）。
        { path: 'devices', name: 'devices', component: DevicesView },
        { path: 'security', name: 'security', component: SecurityView },
      ],
    },
    // 阶段 5 — 财务独立子框架（继承全局登录守卫）。
    {
      path: '/finance',
      component: AppShell,
      children: [
        { path: '', name: 'finance', component: FinanceView },
        {
          path: 'editor/account/:id?',
          name: 'finance-editor-account',
          component: FinanceAccountEditor,
        },
        {
          path: 'editor/card/:id?',
          name: 'finance-editor-card',
          component: FinanceCardEditor,
        },
        {
          path: 'editor/tx/:id?',
          name: 'finance-editor-tx',
          component: FinanceTxEditor,
        },
        // 阶段 5 v2 — 4 子类型编辑器（TR-1.3）
        {
          path: 'editor/subscription/:id?',
          name: 'finance-editor-subscription',
          component: FinanceSubscriptionEditor,
        },
        {
          path: 'editor/policy/:id?',
          name: 'finance-editor-policy',
          component: FinancePolicyEditor,
        },
        {
          path: 'editor/loan/:id?',
          name: 'finance-editor-loan',
          component: FinanceLoanEditor,
        },
        {
          path: 'editor/contract/:id?',
          name: 'finance-editor-contract',
          component: FinanceContractEditor,
        },
        // 阶段 5 v2 B5 — 汇率设置（FR-V2-C；spec 路径 /settings/rates，
        // 实挂 finance 子树下，完整路径 /finance/settings/rates）。
        {
          path: 'settings/rates',
          name: 'finance-settings-rates',
          component: SettingsRatesView,
        },
      ],
    },
  ],
})

// 路由守卫：无令牌去登录/注册；主密钥只在内存，进入主框架后由锁屏重新解锁。
router.beforeEach((to) => {
  if (to.name !== 'welcome' && !getAccessToken()) return { name: 'welcome' }
  if (to.name === 'welcome' && getAccessToken()) return { name: 'logins' }
  return true
})
