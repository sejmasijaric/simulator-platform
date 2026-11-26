import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/simulations': backend,
      '/layouts': backend,
      // The layouts' own HTTP APIs (/ov/burn, /vgr/..., ...): their paths come from the YAML, so
      // everything that is not a file of the dev server goes to the simulator.
      '^/(?!@|src/|node_modules/|pictures/|favicon|index\\.html).+': backend,
    },
  },
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
});
