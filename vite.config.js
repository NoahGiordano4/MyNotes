import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    host: true,          // écoute sur 0.0.0.0 (aperçu distant)
    port: 5173,
    allowedHosts: true,  // autorise le domaine du proxy d'aperçu
  },
  preview: {
    host: true,
    port: 5173,
    allowedHosts: true,
  },
  build: {
    target: 'es2020',
  },
});
