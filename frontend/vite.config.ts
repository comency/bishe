import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: mode === 'modeltrial' ? 15176 : mode === 'integration' ? 15174 : 5174,
    strictPort: true,
    proxy: { '/api': { target: mode === 'modeltrial' ? 'http://127.0.0.1:18081' : mode === 'integration' ? 'http://127.0.0.1:18080' : 'http://127.0.0.1:8080', changeOrigin: true } },
  },
  preview: { host: '127.0.0.1', port: 4174, strictPort: true },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    clearMocks: true,
    restoreMocks: true,
    maxWorkers: 2,
  },
}))
