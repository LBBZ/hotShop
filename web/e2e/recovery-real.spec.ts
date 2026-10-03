import { expect, test, type BrowserContext } from "@playwright/test";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execute = promisify(execFile);

test("a lost refresh response requests login without an automatic rotation retry", async ({
  context,
  page,
}) => {
  const username = await register(context);
  let rotations = 0;
  await context.route("**/api/v1/auth/refresh", async (route) => {
    rotations += 1;
    const response = await route.fetch();
    expect(response.status()).toBe(200);
    await route.abort("connectionreset");
  });
  await page.goto("/user/orders");
  await expect(page).toHaveURL(/\/session-expired\?domain=user$/u);
  expect(rotations).toBe(1);
  await expect(
    page.getByRole("heading", { name: "需要重新登录" }),
  ).toBeVisible();
  await context.unroute("**/api/v1/auth/refresh");
  await page.getByRole("link", { name: "重新登录" }).click();
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.locator('input[type="password"]').fill("RecoveryBrowser!2026");
  await page.locator('button[type="submit"]').click();
  await expect(page).toHaveURL(/\/user\/orders$/u);
});

async function register(context: BrowserContext) {
  const username = `recovery${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const response = await context.request.post("/api/v1/auth/register", {
    data: {
      username,
      password: "RecoveryBrowser!2026",
      email: `${username}@hotshop.invalid`,
    },
  });
  expect(response.ok()).toBe(true);
  const login = await context.request.post("/api/v1/auth/login", {
    data: { username, password: "RecoveryBrowser!2026" },
  });
  expect(login.ok()).toBe(true);
  return username;
}

test("two tabs restore one cookie session without revoking its refresh family", async ({
  context,
}) => {
  const username = await register(context);
  const statuses: number[] = [];
  context.on("response", (response) => {
    if (new URL(response.url()).pathname === "/api/v1/auth/refresh")
      statuses.push(response.status());
  });
  // Delay dispatch so uncoordinated pages send the same cookie generation.
  await context.route("**/api/v1/auth/refresh", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 500));
    await route.continue();
  });
  const first = await context.newPage();
  const second = await context.newPage();
  await Promise.all([first.goto("/user"), second.goto("/user")]);
  await expect.poll(() => statuses.length).toBeGreaterThanOrEqual(2);
  expect(statuses).toEqual([200, 200]);
  for (const page of [first, second]) {
    await expect(
      page.getByRole("heading", { name: `你好，${username}` }),
    ).toBeVisible();
  }
  await first.reload();
  await expect(
    first.getByRole("heading", { name: `你好，${username}` }),
  ).toBeVisible();
  expect(statuses.every((status) => status === 200)).toBe(true);
});

test("retrying after a lost purchase response returns the committed order", async ({
  context,
  page,
}) => {
  await register(context);
  let committedOrder: string | undefined;
  const keys: Array<string | undefined> = [];
  await context.route("**/api/v1/orders", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    keys.push(route.request().headers()["idempotency-key"]);
    if (committedOrder) return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    const result = (await response.json()) as { orderId: string };
    committedOrder = result.orderId;
    await route.abort("connectionreset");
  });
  await page.goto("/products/913001");
  await page.locator("[data-purchase-button]").click();
  await expect.poll(() => committedOrder).toBeTruthy();
  await expect(page.locator("[data-purchase-button]")).toBeEnabled();
  await page.reload();
  await page.locator("[data-purchase-button]").click();
  await expect(page).toHaveURL(
    new RegExp(`/user/orders/${committedOrder}$`, "u"),
  );
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBeTruthy();
  expect(keys[1]).toBe(keys[0]);
  await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
    1,
  );
  await context.setOffline(true);
  await expect(page.locator('[data-connection="offline"]')).toBeVisible();
  await context.setOffline(false);
  await expect(page.locator('[data-connection="live"]')).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
    1,
  );
});

test("an interrupted assistant answer clears its draft and preserves the question", async ({
  context,
  page,
}) => {
  await register(context);
  let truncate = true;
  await context.route(
    "**/agent-api/api/v1/agent/runs/*/events",
    async (route) => {
      if (!truncate) return route.continue();
      truncate = false;
      const response = await route.fetch();
      const stream = await response.text();
      const end = stream.indexOf("event: done");
      expect(end).toBeGreaterThan(0);
      await route.fulfill({ response, body: stream.slice(0, end) });
    },
  );
  await page.goto("/user/agent");
  const question = "购买商品 913001 数量 1 件";
  await page.getByRole("textbox", { name: "请求", exact: true }).fill(question);
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(
    page.getByText("Agent 连接中断，回答尚未完成。请重试。"),
  ).toBeVisible();
  await expect(
    page.getByRole("textbox", { name: "请求", exact: true }),
  ).toHaveValue(question);
  await expect(
    page.getByRole("button", { name: "确认并创建订单" }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(page.locator('[data-agent-event="done"]')).toBeVisible();
  await expect(
    page.getByText("购买草稿已生成。请核对商品和数量后主动确认。"),
  ).toBeVisible();
});

test("an Agent restart before event subscription gives an explicit recoverable state", async ({
  context,
  page,
}) => {
  const container = process.env.HOTSHOP_RECOVERY_AGENT_CONTAINER;
  const runId = process.env.HOTSHOP_RECOVERY_RUN_ID;
  test.skip(
    !container || !runId,
    "Run script/verify-browser-recovery.ps1 for the owned restart fixture.",
  );
  test.setTimeout(90_000);
  if (
    !container ||
    !runId ||
    !/^hotshop-recovery-[a-z0-9-]+-agent$/u.test(container)
  )
    throw new Error("A run-owned recovery Agent is required.");
  const owner = await execute("docker", [
    "inspect",
    "--format",
    '{{index .Config.Labels "hotshop.recovery.owner"}}',
    container,
  ]);
  expect(owner.stdout.trim()).toBe(runId);
  await register(context);
  let restart = true;
  await context.route(
    "**/agent-api/api/v1/agent/sessions/*/runs",
    async (route) => {
      if (!restart) return route.continue();
      restart = false;
      const response = await route.fetch();
      expect(response.status()).toBe(202);
      await execute("docker", ["restart", "--timeout", "10", container]);
      await expect
        .poll(
          async () => {
            try {
              return (
                await context.request.get("/agent-api/health/ready")
              ).status();
            } catch {
              return 0;
            }
          },
          { timeout: 45_000 },
        )
        .toBe(200);
      await route.fulfill({ response });
    },
  );
  await page.goto("/user/agent");
  await page
    .getByRole("textbox", { name: "请求", exact: true })
    .fill("推荐通勤耳机");
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(
    page.getByText(
      "上一段对话已失效，请重新发送问题以开始新对话。购买仍需你确认。",
    ),
  ).toBeVisible({ timeout: 45_000 });
  await expect(
    page.getByRole("textbox", { name: "请求", exact: true }),
  ).toHaveValue("推荐通勤耳机");
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(page.locator('[data-agent-event="done"]')).toBeVisible();
});
