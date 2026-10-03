import { defineConfig, devices } from "@playwright/test";

// Only use an isolated demo: these tests create accounts and orders.
export default defineConfig({
  testDir: "./e2e",
  testMatch: /recovery-real\.spec\.ts/,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: "list",
  use: {
    ...devices["Desktop Chrome"],
    baseURL: process.env.HOTSHOP_DELIVERY_URL ?? "http://127.0.0.1:18080",
    actionTimeout: 15_000,
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
