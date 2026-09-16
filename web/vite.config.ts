// 注意：defineConfig 改从 vitest/config 导入（它完整兼容并扩展 vite 配置类型），
// 以便 test 字段获得类型检查；构建行为与 vite 原生 defineConfig 完全一致。
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 构建产物直接输出到 server/web/dist，由 Go 的 go:embed 内嵌；
// 开发时 /api 与 /api/v1/events 代理到本机 Go 服务（默认 :8787）。
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../server/web/dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8787',
        changeOrigin: true,
      },
    },
  },
  // vitest 基建（tasks.md Task 8）：轨迹纯函数核心无 DOM 依赖，node 环境即可。
  test: {
    environment: 'node',
  },
})
