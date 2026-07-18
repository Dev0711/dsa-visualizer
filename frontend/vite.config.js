import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  build: {
    // Split large vendor libs into separate chunks so the app shell
    // loads fast and heavy deps (CodeMirror, Framer Motion) load in parallel.
    // Note: Vite 8 uses Rolldown which requires manualChunks as a function.
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/react') || id.includes('node_modules/react-dom')) {
            return 'react-vendor';
          }
          if (
            id.includes('node_modules/codemirror') ||
            id.includes('node_modules/@codemirror')
          ) {
            return 'codemirror-vendor';
          }
          if (id.includes('node_modules/framer-motion')) {
            return 'motion-vendor';
          }
        },
      },
    },
  },

  server: {
    port: 5173,
    // Proxy all /api requests to the Spring Boot backend during development.
    // In production (Vercel), the frontend calls the backend directly via
    // VITE_API_BASE_URL — no proxy is needed at the CDN level.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

