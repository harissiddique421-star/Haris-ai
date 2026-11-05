import { defineConfig } from 'vite';
import path from 'node:path';
export default defineConfig({
  base: './',
  resolve: { alias: { '@': path.resolve(__dirname, '.') } },
  build: { outDir: 'android/app/src/main/assets', emptyOutDir: true,
    rollupOptions: { input: 'mobile.html' }, target: 'es2020', chunkSizeWarningLimit: 1600 },
});
