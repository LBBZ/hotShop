// Capture real UI components with deterministic presentation fixtures.
// This script intercepts API requests: it never creates accounts or orders.
import { chromium } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const baseURL = process.env.HOTSHOP_DOCS_URL ?? "http://127.0.0.1:4173";
if (!["127.0.0.1", "localhost", "[::1]"].includes(new URL(baseURL).hostname)) {
  throw new Error("Documentation capture requires a local preview URL.");
}
const output = fileURLToPath(new URL("../../docs/assets/", import.meta.url));
await mkdir(output, { recursive: true });
const products = [
  ["101", "云雾头戴耳机", "音频", "柔软耳垫，为通勤留一段安静。", "329.00", 18],
  ["102", "留白机械键盘", "数码", "为工作与灵感，留一点舒适。", "499.00", 12],
  ["103", "森野便携音箱", "音频", "把日常的好旋律，带在身边。", "189.00", 24],
].map(([productId, name, category, description, price, stock], index) => ({
  productId,
  name,
  category,
  description,
  price,
  stock,
  createdAt: "2026-10-01T00:00:00Z",
  version: "1",
  presentation: {
    images: [
      {
        url: `/media/products/${["headphones", "keyboard", "speaker"][index]}.webp`,
        alt: `${name}的正面展示`,
      },
      ...(index === 0
        ? [
            {
              url: "/media/products/headphones-detail.webp",
              alt: "云雾头戴耳机的材质与结构细节",
            },
          ]
        : []),
    ],
    specifications:
      index === 0
        ? [
            { name: "颜色", value: "雾紫" },
            { name: "佩戴", value: "头戴式" },
            { name: "连接", value: "蓝牙 5.3 / AUX" },
            { name: "续航", value: "约 40 小时" },
            { name: "重量", value: "230 g" },
          ]
        : [],
    imageNote: "AI 生成的演示图 · 商品及规格均为虚构，仅用于体验购物流程",
  },
}));
const sessionId = "11111111-1111-4111-8111-111111111111";
const messageId = "22222222-2222-4222-8222-222222222222";
const runId = "33333333-3333-4333-8333-333333333333";
const draft = {
  draftId: "docs-purchase-draft",
  actionType: "CREATE_ORDER",
  items: [
    {
      productId: "101",
      productName: "云雾头戴耳机",
      quantity: 1,
      unitPriceSnapshot: "329.00",
      lineAmountSnapshot: "329.00",
    },
  ],
  totalPriceSnapshot: "329.00",
  currency: "CNY",
  confirmationRequired: true,
  validUntil: "2030-01-01T00:00:00Z",
};
const frames = [
  [
    "tool.started",
    { tool: "purchase_drafts.create", resourceType: "purchase_draft" },
  ],
  [
    "tool.completed",
    {
      tool: "purchase_drafts.create",
      resourceType: "purchase_draft",
      outcome: "SUCCESS",
      summary: "购买草稿已准备好。",
    },
  ],
  ["purchase_draft.created", draft],
  [
    "message.delta",
    { delta: "购买草稿已准备好。请核对商品与数量，确认后再创建订单。" },
  ],
  ["done", { state: "COMPLETED" }],
]
  .map(
    ([type, data], index) =>
      `event: ${type}\ndata: ${JSON.stringify({ type, data, sessionId, runId, messageId, sequence: index + 1 })}\n\n`,
  )
  .join("");

const browser = await chromium.launch();
try {
  for (const [name, routePath] of [
    ["catalog", "/"],
    ["product", "/products/101"],
    ["assistant", "/user/agent"],
    ["operations", "/admin"],
  ]) {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 980 },
      reducedMotion: "reduce",
      deviceScaleFactor: 1,
      timezoneId: "Asia/Shanghai",
    });
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.route("**/*", async (route) => {
      const url = new URL(route.request().url());
      if (url.origin !== new URL(baseURL).origin) {
        errors.push(`Unexpected external request: ${url.origin}`);
        return route.abort();
      }
      if (
        !url.pathname.startsWith("/api/") &&
        !url.pathname.startsWith("/admin/api/") &&
        !url.pathname.startsWith("/agent-api/")
      )
        return route.continue();
      let json;
      if (url.pathname.endsWith("/auth/refresh")) {
        const admin = url.pathname.startsWith("/admin/");
        if (name === "catalog" || name === "product")
          return route.fulfill({ status: 401, json: { status: 401 } });
        json = {
          accessToken: "documentation-preview",
          expiresAt: "2030-01-01T00:00:00Z",
          role: admin ? "ROLE_ADMIN" : "ROLE_USER",
          userId: admin ? "901" : "101",
          username: admin ? "operator" : "lin",
        };
      } else if (url.pathname.endsWith("/products")) {
        json = { items: products, hasMore: false };
      } else if (url.pathname === "/api/v1/products/101") {
        json = products[0];
      } else if (url.pathname.endsWith("/flash-sale-activities")) {
        json = [];
      } else if (url.pathname.endsWith("/operations/overview")) {
        json = {
          productsCreated: 12,
          activitiesCreated: 3,
          ordersCreated: 48,
          reservationsCreated: 20,
          paymentsCreated: 36,
          failedOutboxUpdated: 0,
          openReconciliationIssues: 0,
          pendingManualReviews: 0,
          rangeFrom: "2026-09-30T00:00:00Z",
          rangeTo: "2026-10-01T00:00:00Z",
          generatedAt: "2026-10-01T00:00:00Z",
          source: "演示数据 / Demo fixtures",
        };
      } else if (url.pathname.endsWith("/sessions")) {
        json = { id: sessionId };
      } else if (url.pathname.endsWith("/messages")) {
        json = { id: messageId };
      } else if (url.pathname.endsWith("/runs")) {
        json = { id: runId };
      } else if (url.pathname.endsWith("/events")) {
        return route.fulfill({
          contentType: "text/event-stream",
          body: frames,
        });
      } else {
        errors.push(
          `Unexpected API request: ${route.request().method()} ${url.pathname}`,
        );
        return route.abort();
      }
      return route.fulfill({ json });
    });
    await page.goto(new URL(routePath, baseURL).href, {
      waitUntil: "networkidle",
    });
    if (name === "catalog") {
      await page.locator(".product-card").first().waitFor();
      await page.locator("img").evaluateAll((images) =>
        Promise.all(
          images.map((image) => {
            image.loading = "eager";
            return image.decode();
          }),
        ),
      );
      await page.evaluate(() => globalThis.document.fonts.ready);
      const section = await page.locator("#catalog").boundingBox();
      if (!section) throw new Error("Catalog section is not visible.");
      await page.screenshot({
        path: `${output}${name}.png`,
        fullPage: true,
        clip: { x: 0, y: section.y, width: 1440, height: section.height },
      });
    } else {
      if (name === "assistant") {
        await page
          .getByLabel("请求", { exact: true })
          .fill("购买商品 101 数量 1 件");
        await page
          .getByRole("button", { name: "发送请求", exact: true })
          .click();
        await page.locator(".purchase-draft").waitFor();
        await page.getByRole("button", { name: "确认并创建订单" }).waitFor();
      } else if (name === "product") {
        await page.locator(".product-gallery").waitFor();
      } else {
        await page.locator(".admin-metric-card").first().waitFor();
      }
      await page.evaluate(() => globalThis.document.fonts.ready);
      await page
        .locator("img")
        .evaluateAll((images) =>
          Promise.all(images.map((image) => image.decode())),
        );
      await page.screenshot({ path: `${output}${name}.png`, fullPage: true });
    }
    if (errors.length) throw new Error(errors.join("\n"));
    console.log(`Captured ${name}.png`);
    await page.close();
  }
} finally {
  await browser.close();
}
