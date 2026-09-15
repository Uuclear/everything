import { createRouter, createWebHashHistory } from 'vue-router'
import { getAccessToken } from '../api/client'
import WelcomeView from '../views/WelcomeView.vue'
import VaultView from '../views/VaultView.vue'

export const router = createRouter({
  history: createWebHashHistory(), // hash 模式对单二进制 SPA 托管最友好
  routes: [
    { path: '/', redirect: '/vault' },
    { path: '/welcome', name: 'welcome', component: WelcomeView },
    { path: '/vault', name: 'vault', component: VaultView },
  ],
})

// 路由守卫：无令牌去登录/注册；主密钥只在内存，刷新页面后需在资料库页重新解锁。
router.beforeEach((to) => {
  if (to.name !== 'welcome' && !getAccessToken()) return { name: 'welcome' }
  if (to.name === 'welcome' && getAccessToken()) return { name: 'vault' }
  return true
})
