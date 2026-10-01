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

test("anonymous home introduces shopping and an AI assistant without external services", async ({
  page,
}) => {
  await page.goto("/");

  await expect(page.locator("h1")).toContainText("GOOD FINDS.");
  await expect(page.locator("h1")).toContainText("GREAT DAYS.");
  await expect(page.getByRole("link", { name: "逛逛好物" })).toHaveAttribute(
    "href",
    "#catalog",
  );
  await expect(page.getByRole("link", { name: "和 AI 聊聊" })).toHaveAttribute(
    "href",
    "/user/agent",
  );
});

test("User and Administrator shells restore only their own mocked sessions", async ({
  page,
}) => {
  let userRefreshes = 0;
  let adminRefreshes = 0;
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    await route.fulfill({
      json: url.pathname.endsWith("/operations/overview")
        ? {
            productsCreated: 0,
            activitiesCreated: 0,
            ordersCreated: 0,
            reservationsCreated: 0,
            paymentsCreated: 0,
            failedOutboxUpdated: 0,
            openReconciliationIssues: 0,
            pendingManualReviews: 0,
            rangeFrom: "2026-09-30T00:00:00Z",
            rangeTo: "2026-10-01T00:00:00Z",
            generatedAt: "2026-10-01T00:00:00Z",
            source: "e2e-fixture",
          }
        : { items: [], hasMore: false },
    });
  });
  await page.route("**/api/v1/auth/refresh", async (route) => {
    userRefreshes += 1;
    await route.fulfill({ json: accessPayload("ROLE_USER") });
  });
  await page.route("**/admin/api/v1/auth/refresh", async (route) => {
    adminRefreshes += 1;
    await route.fulfill({ json: accessPayload("ROLE_ADMIN") });
  });

  await page.goto("/user");
  await expect(page.getByRole("heading", { name: "你好，lin" })).toBeVisible();
  await expect(page.getByText("已登录", { exact: true })).toBeVisible();

  await page.goto("/admin");
  await expect(page.getByRole("heading", { name: "运营脉冲" })).toBeVisible();
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

test("switching accounts in the same SPA never shows the previous user's cached orders", async ({
  page,
}) => {
  let currentUser: string | null = null;
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/auth/login")) {
      const body = route.request().postDataJSON() as { username: string };
      currentUser = body.username;
      await route.fulfill({
        json: {
          ...accessPayload("ROLE_USER"),
          userId: currentUser,
          username: currentUser,
        },
      });
    } else if (url.pathname.endsWith("/auth/logout")) {
      currentUser = null;
      await route.fulfill({ json: { message: "Logged out" } });
    } else if (url.pathname.endsWith("/auth/refresh")) {
      await route.fulfill(
        currentUser
          ? {
              json: {
                ...accessPayload("ROLE_USER"),
                userId: currentUser,
                username: currentUser,
              },
            }
          : { status: 401, json: {} },
      );
    } else if (url.pathname.endsWith("/orders")) {
      await route.fulfill({
        json: {
          hasMore: false,
          items: [
            {
              orderId: `order-${currentUser}`,
              userId: currentUser,
              createdAt: "2026-09-30T00:00:00Z",
              status: "PAID",
              totalAmount: "12.00",
              currency: "CNY",
              items: [],
            },
          ],
        },
      });
    } else {
      await route.fulfill({
        json: url.pathname.endsWith("/flash-sale-activities")
          ? []
          : { items: [], hasMore: false },
      });
    }
  });
  await page.goto("/auth");
  await page.evaluate(() => {
    document.documentElement.dataset.identityTest = "same-spa";
  });
  const login = async (username: string) => {
    await page.getByLabel("用户名", { exact: true }).fill(username);
    await page.getByLabel("密码", { exact: true }).fill("Password!2026");
    await page.getByRole("button", { name: "登录并继续" }).click();
    await expect(page).toHaveURL(/\/user$/u);
    await page.getByRole("link", { name: "我的订单", exact: true }).click();
    await expect(
      page.getByText(`order-${username}`, { exact: true }),
    ).toBeVisible();
  };
  await login("account-a");
  await page.getByRole("button", { name: "退出登录" }).click();
  await page.getByRole("link", { name: "登录 / 注册" }).click();
  await login("account-b");
  await expect(page.getByText("order-account-a", { exact: true })).toHaveCount(
    0,
  );
  expect(
    await page.evaluate(() => document.documentElement.dataset.identityTest),
  ).toBe("same-spa");
});
