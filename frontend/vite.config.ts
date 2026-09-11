import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The dev server proxies the API and the runtime config to the gateway, so
// the browser sees one origin - no CORS, and the same paths as production,
// where the gateway serves the built bundle itself.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/config.json': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
});
