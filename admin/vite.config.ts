import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 开发环境把 /api 和 /health 代理到 Python 服务端，避免跨域
      // ws: true 让 WebSocket 也走代理转发（命令回执实时推送依赖）
      '/api': {
        target: 'http://localhost:15160',
        changeOrigin: true,
        ws: true,
      },
      '/health': {
        target: 'http://localhost:15160',
        changeOrigin: true,
      },
    },
  },
})
