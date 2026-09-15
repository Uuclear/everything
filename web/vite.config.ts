import { defineConfig } from 'vite'
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
})
