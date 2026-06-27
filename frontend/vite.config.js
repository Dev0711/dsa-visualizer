import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Proxy all /api requests to the Spring Boot backend during development.
    // In production, the built frontend is served as static files FROM
    // Spring Boot itself, so no proxy is needed — same origin.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
