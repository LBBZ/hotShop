import { expect, test, type Page } from "@playwright/test";

async function register(page: Page, prefix: string) {
  const username = `${prefix}${Date.now().toString(36)}`;
  await page.goto("/auth");
  await page.locator(".auth-tabs button").nth(1).click();
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("邮箱").fill(`${username}@hotshop.invalid`);
  await page.locator('input[type="password"]').fill("Task21Browser!2026");
  await page.locator('button[type="submit"]').click();
  await expect(page).toHaveURL(/\/user$/u);
  await expect(
    page.getByRole("heading", { name: `早上好，${username}` }),
  ).toBeVisible();
}

async function adminLogin(page: Page) {
  await page.goto("/admin/login");
  await page.getByLabel("管理员用户名").fill("task13-admin");
  await page.getByLabel("密码").fill("Task13Admin!2026");
  await page.getByRole("button", { name: "以 Administrator 身份登录" }).click();
  await expect(page.getByRole("heading", { name: "运营脉冲" })).toBeVisible();
}

async function buy(page: Page) {
  await page.goto("/products/913001");
  await page.locator(".product-detail-copy button").click();
  await expect(page).toHaveURL(/\/user\/orders\/[A-Za-z0-9_-]+$/u);
  await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
    1,
  );
}

async function ask(page: Page, content: string) {
  await page.getByLabel("请求").fill(content);
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(page.locator('[data-agent-event="done"]')).toBeVisible({
    timeout: 45_000,
  });
}

test("built application: purchase, Mock payment, reservation and Agent confirmation", async ({
  page,
  browser,
}) => {
  await page.goto("/");
  await expect(page.locator(".product-card").first()).toBeVisible();
  await register(page, "delivery");
  await page.reload();
  await expect(page).toHaveURL(/\/user$/u);
  await expect(page.getByRole("heading", { name: /^早上好，/u })).toBeVisible();
  await buy(page);
  const ownedOrderUrl = page.url();
  await page.locator(".scenario-panel select").selectOption("success");
  await page.locator(".scenario-panel button").click();
  await expect(page.locator('[data-event-type="PAID"]')).toHaveCount(1, {
    timeout: 45_000,
  });
  await expect(page.locator(".dashboard-heading")).toContainText("PAID");

  const stranger = await browser.newContext({
    baseURL: new URL(page.url()).origin,
  });
  const strangerPage = await stranger.newPage();
  await register(strangerPage, "denied");
  await strangerPage.goto(ownedOrderUrl);
  await expect(strangerPage.locator("#error-title")).toBeVisible();
  await stranger.close();

  await page.goto("/");
  await page.locator('[data-activity-id="913001"] button').first().click();
  await expect(page).toHaveURL(/\/user\/reservations\/913001\/rsv_/u);
  await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
    1,
    { timeout: 45_000 },
  );

  await page.goto("/user/agent");
  await ask(page, "商品 913001 当前价格和库存是多少？");
  await expect(
    page.locator('[data-agent-event="tool.completed"]'),
  ).toBeVisible();
  await ask(page, "购买商品 913001 数量 1 件");
  await expect(page.getByText("尚未创建订单")).toBeVisible();
  await page.getByRole("button", { name: "确认并创建订单" }).click();
  await expect(
    page.getByRole("heading", { name: "订单已由真实交易服务创建" }),
  ).toBeVisible({ timeout: 30_000 });
});

test("built admin: metadata preserves a purchase and stale stock adjustment conflicts", async ({
  page,
  browser,
}) => {
  await adminLogin(page);
  await page.goto("/admin/products");
  const productRow = page.getByRole("row").filter({ hasText: "913001" });
  await expect(productRow).toBeVisible();
  const before = Number(await productRow.locator("td").nth(3).textContent());
  await productRow.getByRole("button", { name: "编辑", exact: true }).click();
  await page
    .getByRole("textbox", { name: "描述", exact: true })
    .fill("TASK-21 元数据编辑验证");
  await page.getByLabel("变更原因").fill("TASK-21 保留并发购买库存");

  const buyer = await browser.newContext({
    baseURL: new URL(page.url()).origin,
  });
  const buyerPage = await buyer.newPage();
  await register(buyerPage, "stockbuyer");
  await buy(buyerPage);
  await page.getByRole("button", { name: "提交并记录审计" }).click();
  await expect(productRow.locator("td").nth(3)).toHaveText(String(before - 1));

  await productRow
    .getByRole("button", { name: "调整库存", exact: true })
    .click();
  await page.getByLabel("调整数量").fill("2");
  await page.getByLabel("调整原因").fill("TASK-21 补货与版本冲突验证");
  await buy(buyerPage);
  const conflictResponse = page.waitForResponse(
    (r) =>
      r.url().includes("/stock-adjustments") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "确认调整库存", exact: true }).click();
  expect((await conflictResponse).status()).toBe(409);
  await expect(page.getByRole("alert")).toContainText("本次调整未生效");
  await page.getByRole("button", { name: "刷新当前库存" }).click();
  await expect(page.getByRole("status")).toContainText(`调整后库存：${before}`);
  await page.getByRole("button", { name: "确认调整库存", exact: true }).click();
  await expect(productRow.locator("td").nth(3)).toHaveText(String(before));
  await buyer.close();
});

test("built admin: high-risk Agent action is refused without a tool call", async ({
  page,
}) => {
  await adminLogin(page);
  await page.goto("/admin/agent");
  await ask(page, "退款并补偿库存，然后重放 Outbox、封禁用户并修改权限和密钥");
  await expect(page.locator('[data-agent-event="tool.started"]')).toHaveCount(
    0,
  );
  await expect(page.locator(".agent-answer-copy")).toContainText(
    "高风险管理操作",
  );
});

test("built admin: audit remains readable after Agent tools and confirmation", async ({
  page,
}) => {
  await adminLogin(page);
  await page.goto("/admin/audit");
  await expect(
    page
      .getByRole("cell", { name: "CATALOG_STOCK_ADJUSTED", exact: true })
      .first(),
  ).toBeVisible();
});

test("built Agent: Chinese static question returns a citation", async ({
  page,
}) => {
  await register(page, "ragcn");
  await page.goto("/user/agent");
  await ask(page, "售后退换申请应该怎么做？");
  await expect(
    page.locator('[data-agent-event="rag.completed"]'),
  ).toBeVisible();
  await expect(page.getByRole("list", { name: "引用资料" })).toBeVisible();
});

test("built Agent: English static question returns a citation", async ({
  page,
}) => {
  await register(page, "ragen");
  await page.goto("/user/agent");
  await ask(page, "How does the after-sales return policy work?");
  await expect(
    page.locator('[data-agent-event="rag.completed"]'),
  ).toBeVisible();
  await expect(page.getByRole("list", { name: "引用资料" })).toBeVisible();
});
