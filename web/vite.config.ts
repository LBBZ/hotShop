import path from "node:path";
import { fileURLToPath } from "node:url";

import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { configDefaults, defineConfig } from "vitest/config";

const currentDirectory = path.dirname(fileURLToPath(import.meta.url));
const securityHeaders = {
  "Content-Security-Policy":
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};
const developmentSecurityHeaders = {
  ...securityHeaders,
  // Vite's pinned React refresh preamble is inline in development only. Keep the
  // production policy strict and authorize exactly this preamble by its hash.
  "Content-Security-Policy": securityHeaders["Content-Security-Policy"].replace(
    "script-src 'self'",
    "script-src 'self' 'sha256-Z2/iFzh9VMlVkEOar1f/oSHWwQk3ve1qk/C2WdsC4Xk='",
  ),
};

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(currentDirectory, "src"),
    },
  },
  server: {
    host: "0.0.0.0",
    port: 4173,
    headers: developmentSecurityHeaders,
    proxy: {
      "/agent-api": {
        target: process.env.HOTSHOP_AGENT_URL ?? "http://127.0.0.1:8090",
        changeOrigin: false,
        rewrite: (pathValue) => pathValue.replace(/^\/agent-api/u, ""),
      },
      "/api": {
        target: process.env.HOTSHOP_PORTAL_URL ?? "http://127.0.0.1:8080",
        changeOrigin: false,
      },
      "/provider-callbacks": {
        target: process.env.HOTSHOP_PORTAL_URL ?? "http://127.0.0.1:8080",
        changeOrigin: false,
      },
      "/admin/api": {
        target: process.env.HOTSHOP_ADMIN_URL ?? "http://127.0.0.1:8088",
        changeOrigin: false,
      },
    },
  },
  preview: {
    host: "0.0.0.0",
    port: 4173,
    headers: securityHeaders,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    exclude: [...configDefaults.exclude, "e2e/**"],
    css: true,
    coverage: {
      provider: "v8",
      reportsDirectory: "coverage",
      reporter: ["text", "html", "json-summary", "lcov"],
    },
  },
});
