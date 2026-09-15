import { defineConfig, devices } from "@playwright/test";

// Run against the built Nginx application from task21-demo.ps1, with real APIs.
export default defineConfig({
  testDir: "./e2e",
  testMatch: /task-21-delivery-real\.spec\.ts/,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  reporter: "list",
  use: {
    actionTimeout: 15_000,
    ...devices["Desktop Chrome"],
    baseURL: process.env.HOTSHOP_DELIVERY_URL ?? "http://127.0.0.1:18080",
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
