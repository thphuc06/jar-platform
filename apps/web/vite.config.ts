/// <reference types="vitest/config" />
import path from "node:path";
import react from "@vitejs/plugin-react-swc";
import { defineConfig } from "vite";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  server: {
    // must match the callback URL registered for the Cognito app client (infra/cdk, jars:localWebOrigin)
    port: 5173,
    strictPort: true,
    proxy: {
      // same origin for the browser: no CORS, and the API sees requests as they will arrive through CloudFront
      "/api": "http://localhost:8080",
    },
  },
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["favicon.ico", "pwa-192x192.png", "pwa-512x512.png"],
      workbox: {
        // never serve the cached index.html for the OAuth callback or the API
        navigateFallbackDenylist: [/^\/auth\//, /^\/api\//],
        globPatterns: ["**/*.{js,css,html,ico,png,svg,woff2}"],
      },
      manifest: {
        name: "Jars - personal finance",
        short_name: "Jars",
        description: "Personal finance with the 6-jar method",
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
    alias: { "@": path.resolve(import.meta.dirname, "./src") },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    // unit tests never talk to Cognito; no .env file needed (.env.* is ignored repo-wide)
    env: {
      VITE_COGNITO_AUTHORITY: "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_TEST",
      VITE_COGNITO_CLIENT_ID: "test-client",
    },
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
