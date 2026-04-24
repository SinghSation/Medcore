import path from 'node:path'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
      '/fhir': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    sourcemap: true,
    target: 'es2022',
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    css: false,
    restoreMocks: true,
    setupFiles: ['./src/test-setup.ts'],
    // Playwright specs live in `e2e/` and run under a different
    // runner — keep Vitest away from them so `pnpm test` stays
    // a pure unit/component gate.
    exclude: ['**/node_modules/**', '**/dist/**', 'e2e/**'],
  },
})
