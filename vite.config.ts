import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import type { Plugin } from 'vite';

function apkDownloadHeaders(): Plugin {
  return {
    name: 'spektralab-apk-download-headers',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        if ((request as { url?: string }).url?.split('?')[0].endsWith('.apk')) {
          response.setHeader('Content-Type', 'application/vnd.android.package-archive');
          response.setHeader('Content-Disposition', 'attachment; filename="Silvercam-Android.apk"');
        }
        next();
      });
    },
    configurePreviewServer(server) {
      server.middlewares.use((request, response, next) => {
        if ((request as { url?: string }).url?.split('?')[0].endsWith('.apk')) {
          response.setHeader('Content-Type', 'application/vnd.android.package-archive');
          response.setHeader('Content-Disposition', 'attachment; filename="Silvercam-Android.apk"');
        }
        next();
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), apkDownloadHeaders()],
  server: { host: '0.0.0.0' },
  build: {
    target: 'es2022',
    cssCodeSplit: true,
  },
});
