import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/simulations': backend,
      '/simulation-definitions': backend,
    },
  },
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
});
