import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理目标只在 Vite 服务端使用;构建仍保留单 JAR 的 /admin/ 发布形态。
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../resources/static/admin',
    emptyOutDir: true,
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    fs: { strict: true, allow: [fileURLToPath(new URL('.', import.meta.url))] },
    proxy: {
      '/api': process.env.ORYXOS_API_TARGET || 'http://127.0.0.1:8080',
    },
  },
})
