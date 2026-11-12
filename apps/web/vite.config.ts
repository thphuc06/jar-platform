import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react-swc";
import { VitePWA } from "vite-plugin-pwa";
import { defineConfig } from "vitest/config";

// jars-api as started by `./mvnw spring-boot:run` (docs/05-operations/local-development.md)
const BACKEND = "http://localhost:8080";

export default defineConfig({
  server: {
    // the Cognito app client allows http://localhost:5173/auth/callback (infra AppStack), so the port is fixed
    port: 5173,
    strictPort: true,
    // The browser only ever talks to this origin, exactly like CloudFront in production (/api/* -> ALB):
    // no CORS configuration is needed anywhere. The Host header is kept (changeOrigin: false), so for
    // the backend a POST from this page is a same-origin request as well.
    proxy: {
      "/api": { target: BACKEND },
      "/dev": { target: BACKEND },
    },
  },
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["favicon.ico", "pwa-192x192.png", "pwa-512x512.png"],
      workbox: {
        // Only the static shell is precached. API and dev-token calls (and the SSE stream) must never be
        // answered with index.html by the service worker.
        navigateFallbackDenylist: [/^\/api\//, /^\/dev\//],
        globPatterns: ["**/*.{js,css,html,ico,png,svg,woff2}"],
      },
      manifest: {
        name: "FinAdvisor - Quản lý tài chính theo 6 chiếc hũ",
        short_name: "FinAdvisor",
        description: "Quản lý chi tiêu cá nhân theo phương pháp 6 chiếc hũ",
        lang: "vi",
        theme_color: "#2563eb",
        background_color: "#f5f6fa",
        display: "standalone",
        orientation: "portrait",
        scope: "/",
        start_url: "/",
        icons: [
          { src: "pwa-192x192.png", sizes: "192x192", type: "image/png" },
          { src: "pwa-512x512.png", sizes: "512x512", type: "image/png" },
          { src: "pwa-512x512.png", sizes: "512x512", type: "image/png", purpose: "any maskable" },
        ],
      },
    }),
  ],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
  },
});
