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
      // 扫码下载落地页及其二维码静态库：开发态访问 /download、/static 也代理到后端，
      // 否则 5173/download 会落到 Vite dev server 返回空白。
      '/download': {
        target: 'http://localhost:15160',
        changeOrigin: true,
      },
      '/static': {
        target: 'http://localhost:15160',
        changeOrigin: true,
      },
    },
  },
})
