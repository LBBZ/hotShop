import { expect, test } from "@playwright/test";

function accessPayload(role: "ROLE_USER" | "ROLE_ADMIN") {
  return {
    accessToken: role === "ROLE_USER" ? "e2e-user-access" : "e2e-admin-access",
    expiresAt: "2030-01-01T00:00:00Z",
    role,
    userId: role === "ROLE_USER" ? "101" : "901",
    username: role === "ROLE_USER" ? "lin" : "operator",
  };
}

test("anonymous home exposes the transaction thesis without external services", async ({
  page,
}) => {
  await page.goto("/");

  await expect(page.locator("h1")).toContainText("热度会突发，");
  await expect(page.locator("h1")).toContainText("交易必须冷静。");
  await expect(page.getByLabel("从商品发现到支付的交易路径")).toBeVisible();
});

test("User and Administrator shells restore only their own mocked sessions", async ({
  page,
}) => {
  let userRefreshes = 0;
  let adminRefreshes = 0;
  await page.route("**/api/v1/auth/refresh", async (route) => {
    userRefreshes += 1;
    await route.fulfill({ json: accessPayload("ROLE_USER") });
  });
  await page.route("**/admin/api/v1/auth/refresh", async (route) => {
    adminRefreshes += 1;
    await route.fulfill({ json: accessPayload("ROLE_ADMIN") });
  });

  await page.goto("/user");
  await expect(
    page.getByRole("heading", { name: "早上好，lin" }),
  ).toBeVisible();
  await expect(page.getByText("会话已隔离")).toBeVisible();

  await page.goto("/admin");
  await expect(
    page.getByRole("heading", { name: "交易脉冲总览" }),
  ).toBeVisible();
  await expect(page.getByText("会话已隔离")).toBeVisible();

  expect(userRefreshes).toBe(1);
  expect(adminRefreshes).toBe(1);
});

test("unknown routes show a keyboard-reachable recovery action", async ({
  page,
}) => {
  await page.goto("/not-a-route");
  await expect(
    page.getByRole("heading", { name: "这条交易路径不存在" }),
  ).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "返回首页" })).toBeFocused();
});
