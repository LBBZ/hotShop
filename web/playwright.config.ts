import { defineConfig, devices } from "@playwright/test";

const realCompose = process.env.HOTSHOP_REAL_COMPOSE === "1";
const webPort = Number(process.env.HOTSHOP_E2E_WEB_PORT ?? "4173");

if (!Number.isInteger(webPort) || webPort < 1 || webPort > 65_535) {
  throw new Error("HOTSHOP_E2E_WEB_PORT must be a valid TCP port");
}

export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/real-compose.setup.ts",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: realCompose ? 1 : process.env.CI ? 2 : undefined,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: `http://127.0.0.1:${webPort}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: `pnpm dev --port ${webPort} --strictPort`,
    url: `http://127.0.0.1:${webPort}`,
    reuseExistingServer: !process.env.CI && !realCompose,
    stdout: "pipe",
    stderr: "pipe",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
    {
      name: "mobile-chromium",
      use: { ...devices["Pixel 7"] },
    },
  ],
});
