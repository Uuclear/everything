import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 轨迹页地图（leaflet）样式全局引入（tasks.md Task 10 要求）。
import 'leaflet/dist/leaflet.css'
import App from './App.vue'
import { router } from './router'
import { runDevSelfTests } from './crypto/selftest'

createApp(App).use(createPinia()).use(router).mount('#app')

// 开发模式启动时跑一次密码学向量自测（Crockford / RFC 6238），结果在控制台。
if (import.meta.env.DEV) {
  void runDevSelfTests()
}
