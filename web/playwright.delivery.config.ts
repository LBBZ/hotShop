import { defineConfig, devices } from "@playwright/test";

// Run against the built Nginx application from script/demo.ps1, with real APIs.
export default defineConfig({
  testDir: "./e2e",
  testMatch: /task-21-delivery-real\.spec\.ts/,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  reporter: "list",
  use: {
    actionTimeout: 15_000,
    baseURL: process.env.HOTSHOP_DELIVERY_URL ?? "http://127.0.0.1:18080",
    trace: "off",
    screenshot: "off",
    video: "off",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile-chromium", use: { ...devices["Pixel 7"] } },
  ],
});
