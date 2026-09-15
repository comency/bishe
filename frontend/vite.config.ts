import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
  preview: { host: '127.0.0.1', port: 4174, strictPort: true },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    clearMocks: true,
    restoreMocks: true,
    maxWorkers: 2,
  },
})
