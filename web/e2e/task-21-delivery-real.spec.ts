/// <reference lib="dom" />
import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

type RagObservation = {
  type: string;
  data: { outcome?: string; citations?: Array<Record<string, string>> };
};

test.beforeEach(async ({ page }) => {
  if (process.env.HOTSHOP_E2E_NETWORK_LOG === "1") {
    page.on("response", (response) => {
      if (response.status() >= 400)
        console.info(
          `[HTTP ${response.status()}] ${response.request().method()} ${new URL(response.url()).pathname}`,
        );
    });
    page.on("requestfailed", (request) => {
      if (request.failure()?.errorText !== "net::ERR_ABORTED")
        console.info(
          `[network failure] ${request.method()} ${new URL(request.url()).pathname}: ${request.failure()?.errorText}`,
        );
    });
  }
  await page.addInitScript({
    path: fileURLToPath(
      new URL("../../script/reconcile-browser-observer.js", import.meta.url),
    ),
  });
});

const afterSales = JSON.parse(
  readFileSync(
    new URL("../../agent/knowledge/after-sales.json", import.meta.url),
    "utf8",
  ),
) as {
  documentId: string;
  documentVersion: string;
  title: string;
  source: string;
  content: string;
};

async function register(page: Page, prefix: string) {
  const username = `${prefix}${Date.now().toString(36)}`;
  await page.goto("/auth");
  await page.getByRole("tab", { name: "注册", exact: true }).click();
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("邮箱").fill(`${username}@hotshop.invalid`);
  await page.locator('input[type="password"]').fill("Task21Browser!2026");
  await page.locator('button[type="submit"]').click();
  await expect(page).toHaveURL(/\/user$/u);
  await expect(
    page.getByRole("heading", { name: `你好，${username}` }),
  ).toBeVisible();
}

async function adminLogin(page: Page) {
  await page.goto("/admin/login");
  await page.getByLabel("管理员用户名").fill("task13-admin");
  await page.getByLabel("密码").fill("Task13Admin!2026");
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const loginResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith("/admin/api/v1/auth/login") &&
        response.request().method() === "POST",
    );
    await page
      .getByRole("button", { name: "以 Administrator 身份登录" })
      .click();
    const response = await loginResponse;
    if (response.status() !== 429 || attempt === 1) {
      expect(
        response.ok(),
        `Admin login returned HTTP ${response.status()}`,
      ).toBe(true);
      break;
    }
    // Desktop and mobile journeys share the demo administrator. Honor the real
    // login throttle between contexts instead of changing application limits.
    const retryAfter = Number(response.headers()["retry-after"]);
    expect(
      Number.isFinite(retryAfter) && retryAfter > 0 && retryAfter <= 300,
    ).toBe(true);
    console.info(
      `Admin login rate limited; retrying after ${retryAfter} seconds.`,
    );
    test.setTimeout(test.info().timeout + retryAfter * 1000 + 1000);
    await page.waitForTimeout(retryAfter * 1000 + 1000);
  }
  await expect(page.getByRole("heading", { name: "运营脉冲" })).toBeVisible();
}

async function buy(page: Page) {
  await page.goto("/products/913001");
  await page.locator("[data-purchase-button]").click();
  await expect(page).toHaveURL(/\/user\/orders\/[A-Za-z0-9_-]+$/u);
  await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
    1,
  );
}

async function ask(page: Page, content: string, readRag = false) {
  await page.evaluate(() => {
    document.documentElement.dataset.deliveryRagEvents = "[]";
  });
  await page.getByLabel("请求").fill(content);
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(page.locator('[data-agent-event="done"]')).toBeVisible({
    timeout: 45_000,
  });
  if (!readRag) return [];
  await page.waitForFunction(() =>
    (
      JSON.parse(
        document.documentElement.dataset.deliveryRagEvents ?? "[]",
      ) as RagObservation[]
    ).some((event: { type: string }) => event.type === "rag.completed"),
  );
  return await page.evaluate(
    () =>
      JSON.parse(
        document.documentElement.dataset.deliveryRagEvents ?? "[]",
      ) as RagObservation[],
  );
}

async function expectCitation(page: Page, question: string) {
  const events = await ask(page, question, true);
  const rag = events.find((event) => event.type === "rag.completed");
  expect(rag?.data.outcome).toBe("hit");
  expect(rag?.data.citations).toEqual([
    {
      documentId: afterSales.documentId,
      version: afterSales.documentVersion,
      title: afterSales.title,
      source: afterSales.source,
      chunkId: `${afterSales.documentId}-0000`,
    },
  ]);
  expect(afterSales.content).toContain("售后申请应从订单详情页发起");
  await expect(page.getByRole("list", { name: "引用资料" })).toBeVisible();
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
  await expect(page.getByRole("heading", { name: /^你好，/u })).toBeVisible();
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
  await expect(strangerPage.getByRole("alert")).toBeVisible();
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
    page.getByRole("heading", { name: "订单已创建", exact: true }),
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
  await expect(page.getByLabel("图片 1 地址", { exact: true })).toHaveValue(
    "/media/products/radio.webp",
  );
  await page
    .getByLabel("图片 1 说明", { exact: true })
    .fill("日光便携收音机的正面展示（已校对）");

  const buyer = await browser.newContext({
    baseURL: new URL(page.url()).origin,
  });
  const buyerPage = await buyer.newPage();
  await register(buyerPage, "stockbuyer");
  await buy(buyerPage);
  await page.getByRole("button", { name: "提交并记录审计" }).click();
  await expect(productRow.locator("td").nth(3)).toHaveText(String(before - 1));
  const saved = await page.request.get("/api/v1/products/913001");
  const savedProduct = (await saved.json()) as {
    presentation: { images: Array<{ alt: string }>; specifications: unknown[] };
  };
  expect(savedProduct.presentation.images[0].alt).toBe(
    "日光便携收音机的正面展示（已校对）",
  );
  expect(savedProduct.presentation.specifications.length).toBe(4);

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
    page.getByText("CATALOG_STOCK_ADJUSTED", { exact: true }).first(),
  ).toBeVisible();
  await page.getByLabel("动作代码（精确）").fill("AGENT_TOOL_INVOKED");
  await page.getByLabel("资源类型（精确）").fill("PURCHASE_DRAFT");
  await expect(
    page.getByText("AGENT_TOOL_INVOKED", { exact: true }).first(),
  ).toBeVisible();
  await expect(
    page.getByText("PURCHASE_DRAFT", { exact: true }).first(),
  ).toBeVisible();
});

test("built Agent: original Chinese paraphrase safely returns empty at default threshold", async ({
  page,
}) => {
  await register(page, "ragcn");
  await page.goto("/user/agent");
  const events = await ask(page, "售后退换申请应该怎么做？", true);
  await expect(
    page.locator('[data-agent-event="rag.completed"]'),
  ).toBeVisible();
  expect(events.find((event) => event.type === "rag.completed")?.data).toEqual({
    outcome: "empty",
    citations: [],
  });
  await expect(page.getByRole("list", { name: "引用资料" })).toHaveCount(0);
  await expect(page.locator(".agent-answer-copy")).toContainText(
    "暂无可靠资料，我不知道该问题的答案",
  );
});

test("built Agent: Chinese direct corpus question returns a verified citation", async ({
  page,
}) => {
  await register(page, "ragdirect");
  await page.goto("/user/agent");
  await expectCitation(page, "售后申请应从哪里发起？");
});

test("built Agent: English static question returns a citation", async ({
  page,
}) => {
  await register(page, "ragen");
  await page.goto("/user/agent");
  await expectCitation(page, "How does the after-sales return policy work?");
});

test("built catalog: filters, gallery, image fallback and sold-out state", async ({
  page,
}) => {
  await page.goto("/#catalog");
  await expect(page.locator(".product-card")).toHaveCount(8);
  await page.getByRole("button", { name: "筛选条件" }).click();
  await page.getByLabel("分类", { exact: true }).fill("音频");
  await page.getByLabel("最高价").fill("200");
  await page.getByRole("button", { name: "应用筛选" }).click();
  await expect(page.locator(".product-card")).toHaveCount(2);
  await expect(page.locator(".product-grid")).toContainText("桃气无线耳机");
  await expect(page.locator(".product-grid")).toContainText("森野便携音箱");
  await page.getByRole("button", { name: "清除筛选", exact: true }).click();
  await expect(page.locator(".product-card")).toHaveCount(8);
  await page.goto("/products/913003");
  await expect(
    page.getByRole("heading", { name: "云雾头戴耳机" }),
  ).toBeVisible();
  await page.getByRole("button", { name: /^查看图片 2/u }).click();
  await expect(page.locator(".gallery-main img")).toHaveAttribute(
    "src",
    "/media/products/headphones-detail.webp",
  );
  await page.getByRole("button", { name: "放大查看", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "放大图片", exact: true }).click();
  await expect(page.locator(".gallery-zoom")).toHaveClass(/is-zoomed/u);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(
    page.getByRole("button", { name: "放大查看", exact: true }),
  ).toBeFocused();
  await page.getByLabel("购买数量").fill("1.5");
  await expect(page.locator("[data-purchase-button]")).toBeDisabled();
  await page.getByLabel("购买数量").fill("1");
  await expect(page.locator("[data-purchase-button]")).toBeEnabled();
  await page.route("**/media/products/headphones.webp", (route) =>
    route.abort(),
  );
  await page.reload();
  await expect(page.locator(".gallery-main .product-art")).toBeVisible();
  await expect(page.locator(".product-specifications")).toContainText(
    "约 40 小时",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.goto("/products/913007");
  await expect(page.getByRole("button", { name: "暂时售罄" })).toBeDisabled();
});

test("built shopping journey: recommendation, comparison, draft and confirmed order", async ({
  page,
}) => {
  await register(page, "discovery");
  await page.goto("/products/913003");
  await page.getByRole("link", { name: "问问购物助手" }).click();
  await expect(page.getByLabel("请求")).toHaveValue("查看商品 913003");
  await expect(page.locator(".agent-product")).toHaveCount(0);
  await ask(page, "推荐通勤耳机");
  await expect(page.locator(".agent-product")).toHaveCount(2);
  await expect(page.locator(".agent-answer-copy")).not.toContainText('"tool"');
  await page.getByRole("checkbox", { name: "加入对比：云雾头戴耳机" }).check();
  await page.getByRole("checkbox", { name: "加入对比：桃气无线耳机" }).check();
  await page.getByRole("button", { name: "对比这两件" }).click();
  await expect(page.locator(".agent-comparison")).toBeVisible();
  await expect(page.locator(".agent-comparison")).toContainText("329.00");
  await expect(page.locator(".agent-comparison")).toContainText("199.00");
  await expect(page.locator(".agent-comparison")).toContainText("约 40 小时");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  const headphones = page
    .locator(".agent-product")
    .filter({ hasText: "云雾头戴耳机" });
  await headphones.getByRole("button", { name: "准备购买草稿" }).click();
  await expect(page.getByText("尚未创建订单")).toBeVisible();
  await expect(page.locator(".purchase-draft")).toContainText("云雾头戴耳机");
  await expect(
    page.getByRole("heading", { name: "订单已创建", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "确认并创建订单" }).click();
  await expect(
    page.getByRole("heading", { name: "订单已创建", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "查看订单与模拟支付" }).click();
  await expect(page).toHaveURL(/\/user\/orders\/[A-Za-z0-9_-]+$/u);
  await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
    1,
  );
});
