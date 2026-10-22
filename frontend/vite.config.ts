import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      /**
       * ★★ 为什么用代理而不是直接请求 http://127.0.0.1:8080：
       *   1. **彻底避开 CORS** —— 浏览器认为请求同源（都是 localhost:5173）
       *   2. 前端代码里写相对路径 `/api/xxx`，部署时改一层 nginx 就行，**不用改代码**
       *   3. 后端 CORS 配置即使在生产收紧，也不影响开发
       *
       * ⚠ SSE（`/api/chat/stream`）也走这个代理。
       *   `timeout: 0` 表示不设超时 —— SSE 是长连接，
       *   默认超时会在两分钟内把流掐断，表现为"推送突然没了"。
       */
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        timeout: 0,
      },
      '/actuator': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
