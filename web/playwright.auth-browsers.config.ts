import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.HOTSHOP_AUTH_BROWSER_URL;
if (!baseURL || !["127.0.0.1", "localhost"].includes(new URL(baseURL).hostname))
  throw new Error(
    "Use script/verify-auth-browsers.mjs with an isolated local demo.",
  );

export default defineConfig({
  testDir: "./e2e",
  testMatch: /auth-browser-real\.spec\.ts/,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: process.env.HOTSHOP_AUTH_BROWSER_REPORT
    ? [
        ["line"],
        ["json", { outputFile: process.env.HOTSHOP_AUTH_BROWSER_REPORT }],
      ]
    : "list",
  use: {
    baseURL,
    actionTimeout: 15_000,
    trace: "off",
    screenshot: "off",
    video: "off",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "firefox", use: { ...devices["Desktop Firefox"] } },
    { name: "webkit", use: { ...devices["Desktop Safari"] } },
    { name: "mobile-webkit", use: { ...devices["iPhone 13"] } },
  ],
});
