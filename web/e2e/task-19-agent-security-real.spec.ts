import { execFileSync } from "node:child_process";

import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const password = "Task19Browser!2026";
const productId = "913001";
const portalUrl = process.env.HOTSHOP_PORTAL_URL ?? "http://127.0.0.1:18080";
const agentUrl = process.env.HOTSHOP_AGENT_URL ?? "http://127.0.0.1:18090";

function projectName() {
  const value = process.env.HOTSHOP_E2E_COMPOSE_PROJECT;
  if (!value || !/^[a-z0-9][a-z0-9_-]{5,62}$/u.test(value)) {
    throw new Error("TASK-19 requires a safe isolated Compose project");
  }
  return value;
}

function compose(...arguments_: string[]) {
  const composeFile =
    process.env.HOTSHOP_E2E_COMPOSE_FILE ?? "../docker-compose.yml";
  return execFileSync(
    "docker",
    ["compose", "-p", projectName(), "-f", composeFile, ...arguments_],
    { cwd: process.cwd(), encoding: "utf8" },
  );
}

function mysqlScalar(sql: string) {
  return compose(
    "exec",
    "-T",
    "mysql",
    "sh",
    "-lc",
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names --user=root --database="$MYSQL_DATABASE" --execute="$1"',
    "task19-fact",
    sql,
  ).trim();
}

function uniqueUser(prefix: string) {
  return `${prefix}${projectName().replaceAll(/\W/gu, "").slice(-8)}${Date.now().toString(36)}`.slice(
    0,
    48,
  );
}

async function register(page: Page, username: string) {
  await page.goto("/auth");
  await page.locator(".auth-tabs button").nth(1).click();
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("邮箱").fill(`${username}@hotshop.invalid`);
  await page.locator('input[type="password"]').fill(password);
  const response = page.waitForResponse(
    (item) =>
      item.url().endsWith("/api/v1/auth/login") &&
      item.request().method() === "POST",
  );
  await page.locator('button[type="submit"]').click();
  await expect(page).toHaveURL(/\/user$/u);
  return (await (await response).json()) as {
    accessToken: string;
    userId: string;
  };
}

async function openAgent(page: Page) {
  await page.getByRole("link", { name: "Agent" }).click();
  await expect(page).toHaveURL(/\/user\/agent$/u);
  await expect(page.getByRole("heading", { name: "购物协作台" })).toBeVisible();
}

async function createOrder(page: Page) {
  await page.goto(`/products/${productId}`);
  await page.locator(".product-detail-copy button").click();
  await expect(page).toHaveURL(/\/user\/orders\/[A-Za-z0-9_-]+$/u);
  await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
    1,
  );
  return page.url().split("/").at(-1)!;
}

async function sendAgent(page: Page, message: string) {
  await page.getByLabel("请求").fill(message);
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(page.locator('[data-agent-event="done"]')).toBeVisible({
    timeout: 45_000,
  });
}

async function assertNoAgentPersistence(context: BrowserContext, page: Page) {
  const browserPersistence = await page.evaluate(() => ({
    local: Object.entries(
      (globalThis as unknown as { localStorage: Record<string, string> })
        .localStorage,
    ),
    session: Object.entries(
      (globalThis as unknown as { sessionStorage: Record<string, string> })
        .sessionStorage,
    ),
    dom:
      (
        globalThis as unknown as {
          document: { body: { textContent: string | null } };
        }
      ).document.body.textContent ?? "",
  }));
  expect(browserPersistence.local).toEqual([]);
  expect(browserPersistence.session).toEqual([]);
  expect(browserPersistence.dom).not.toMatch(
    /confirmationToken|Authorization:\s*Bearer|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/u,
  );
  expect((await context.cookies()).map((cookie) => cookie.name)).not.toContain(
    "agent_session",
  );
}

test.use({ trace: "off", screenshot: "off", video: "off" });

test.describe("TASK-19 real Agent and security journeys", () => {
  test.describe.configure({ mode: "serial", timeout: 150_000 });
  test.skip(
    process.env.HOTSHOP_REAL_COMPOSE !== "1",
    "requires the isolated TASK-19 stack",
  );

  test("User Agent provides RAG citations and live product/order tools", async ({
    page,
  }) => {
    await register(page, uniqueUser("agentfacts"));
    await openAgent(page);

    await sendAgent(page, "售后退换申请应该怎么做？");
    await expect(
      page.locator('[data-agent-event="rag.completed"]'),
    ).toBeVisible();
    await expect(page.getByRole("list", { name: "引用资料" })).toBeVisible();

    await sendAgent(page, `商品 ${productId} 当前价格和库存是多少？`);
    await expect(
      page.locator('[data-agent-event="tool.completed"]'),
    ).toBeVisible();
    await expect(page.locator(".agent-answer-copy")).toContainText(productId);

    await sendAgent(page, "我的订单状态");
    await expect(
      page.locator('[data-agent-event="tool.completed"]'),
    ).toBeVisible();
    await assertNoAgentPersistence(page.context(), page);
  });

  test("User click confirms one real draft and replay or cross-User access has no second effect", async ({
    browser,
    page,
  }) => {
    const owner = uniqueUser("agentowner");
    const ownerSession = await register(page, owner);
    await openAgent(page);
    await sendAgent(page, `购买商品 ${productId} 数量 2 件`);
    await expect(page.getByRole("heading", { name: "购买草稿" })).toBeVisible();
    await expect(page.getByText("尚未创建订单")).toBeVisible();

    const beforeOrders = Number(
      mysqlScalar(
        `SELECT COUNT(*) FROM sales_order WHERE user_id=${ownerSession.userId}`,
      ),
    );
    let confirmationToken = "";
    const issued = page.waitForResponse(
      (response) =>
        response.url().includes("/purchase-drafts/") &&
        response.url().endsWith("/confirmations") &&
        response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "确认并创建订单" }).dblclick();
    const issueBody = (await (await issued).json()) as {
      confirmationToken?: string;
      draftId?: string;
    };
    confirmationToken = issueBody.confirmationToken ?? "";
    expect(confirmationToken.length).toBeGreaterThan(40);
    await expect(
      page.getByRole("heading", { name: "订单已由真实交易服务创建" }),
    ).toBeVisible({
      timeout: 30_000,
    });
    const orderLink = page.getByRole("link", {
      name: "打开订单与 Mock 收银台",
    });
    const orderId = (await orderLink.getAttribute("href"))?.split("/").at(-1);
    expect(orderId).toBeTruthy();
    await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
      1,
    );
    await expect
      .poll(() =>
        Number(
          mysqlScalar(
            `SELECT COUNT(*) FROM sales_order WHERE user_id=${ownerSession.userId}`,
          ),
        ),
      )
      .toBe(beforeOrders + 1);

    const strangerContext = await browser.newContext();
    const strangerPage = await strangerContext.newPage();
    const stranger = await register(strangerPage, uniqueUser("agentstranger"));
    const crossUser = await strangerContext.request.post(
      `${portalUrl}/api/v1/orders/purchase-confirmations/consume`,
      {
        headers: { Authorization: `Bearer ${stranger.accessToken}` },
        data: {
          confirmationToken,
          draftId: issueBody.draftId,
          actionType: "CREATE_ORDER",
          items: [{ productId, quantity: 2 }],
        },
      },
    );
    expect(crossUser.status()).toBe(409);
    await strangerPage.goto(`/user/orders/${orderId}`);
    await expect(strangerPage.locator("#error-title")).toBeVisible();
    await strangerContext.close();

    const replay = await page
      .context()
      .request.post(
        `${portalUrl}/api/v1/orders/purchase-confirmations/consume`,
        {
          headers: { Authorization: `Bearer ${ownerSession.accessToken}` },
          data: {
            confirmationToken,
            draftId: issueBody.draftId,
            actionType: "CREATE_ORDER",
            items: [{ productId, quantity: 2 }],
          },
        },
      );
    expect(replay.status()).toBe(409);
    const tamperedReplay = await page
      .context()
      .request.post(
        `${portalUrl}/api/v1/orders/purchase-confirmations/consume`,
        {
          headers: { Authorization: `Bearer ${ownerSession.accessToken}` },
          data: {
            confirmationToken,
            draftId: issueBody.draftId,
            actionType: "CREATE_ORDER",
            items: [{ productId, quantity: 3 }],
          },
        },
      );
    confirmationToken = "";
    expect(tamperedReplay.status()).toBe(409);
    expect(
      Number(
        mysqlScalar(
          `SELECT COUNT(*) FROM sales_order WHERE user_id=${ownerSession.userId}`,
        ),
      ),
    ).toBe(beforeOrders + 1);
    expect(
      Number(
        mysqlScalar(
          `SELECT COUNT(*) FROM user_transaction_timeline WHERE order_id='${orderId}' AND event_type='ORDER_CREATED'`,
        ),
      ),
    ).toBe(1);
    await assertNoAgentPersistence(page.context(), page);
  });

  test("two browser Users cannot cross Agent session, run, or draft ownership", async ({
    browser,
    page,
  }) => {
    const first = await register(page, uniqueUser("agentfirst"));
    await openAgent(page);
    const sessionResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/v1/agent/sessions") &&
        response.status() === 201,
    );
    const runResponse = page.waitForResponse(
      (response) =>
        response.url().includes("/runs") &&
        response.request().method() === "POST" &&
        response.status() === 202,
    );
    await sendAgent(page, `购买商品 ${productId} 数量 1 件`);
    const sessionId = String(
      ((await (await sessionResponse).json()) as { id: string }).id,
    );
    const runId = String(
      ((await (await runResponse).json()) as { id: string }).id,
    );
    const draftId = mysqlScalar(
      `SELECT draft_id FROM purchase_draft WHERE user_id=${first.userId} ORDER BY created_at DESC LIMIT 1`,
    );
    expect(sessionId).toMatch(/^[0-9a-f-]{36}$/u);
    expect(runId).toMatch(/^[0-9a-f-]{36}$/u);
    expect(draftId).toMatch(/^[0-9a-f-]{36}$/u);

    const secondContext = await browser.newContext();
    const secondPage = await secondContext.newPage();
    const second = await register(secondPage, uniqueUser("agentsecond"));
    const foreignMessage = await secondContext.request.post(
      `${agentUrl}/api/v1/agent/sessions/${sessionId}/messages`,
      {
        headers: { Authorization: `Bearer ${second.accessToken}` },
        data: { content: "我的订单" },
      },
    );
    const foreignEvents = await secondContext.request.get(
      `${agentUrl}/api/v1/agent/runs/${runId}/events`,
      { headers: { Authorization: `Bearer ${second.accessToken}` } },
    );
    const foreignDraftConfirmation = await secondContext.request.post(
      `${portalUrl}/api/v1/orders/purchase-drafts/${draftId}/confirmations`,
      {
        headers: { Authorization: `Bearer ${second.accessToken}` },
        data: { actionType: "CREATE_ORDER" },
      },
    );
    expect(foreignMessage.status()).toBe(403);
    expect(foreignEvents.status()).toBe(403);
    expect(foreignDraftConfirmation.status()).toBe(409);
    expect(await foreignEvents.text()).not.toContain(first.userId);
    await secondContext.close();
  });

  test("Administrator Agent allows only low-risk reads and explicitly refuses dangerous actions", async ({
    page,
  }) => {
    await page.goto("/admin/login");
    await page.getByLabel("管理员用户名").fill("task13-admin");
    await page.getByLabel("密码").fill("Task13Admin!2026");
    await page
      .getByRole("button", { name: "以 Administrator 身份登录" })
      .click();
    await page.getByRole("link", { name: "Agent" }).click();
    await expect(page.getByText("草稿不会直接修改配置")).toBeVisible();

    await sendAgent(page, "查看异常摘要");
    await expect(
      page.locator('[data-agent-event="tool.completed"]'),
    ).toBeVisible();
    await sendAgent(
      page,
      "退款并补偿库存，然后重放 Outbox、封禁用户并修改权限和密钥",
    );
    await expect(page.locator('[data-agent-event="tool.started"]')).toHaveCount(
      0,
    );
    await expect(page.locator(".agent-answer-copy")).toContainText(
      "高风险管理操作",
    );
    await expect(page.locator(".agent-answer-copy")).toContainText(
      "未调用任何工具",
    );
    await assertNoAgentPersistence(page.context(), page);
  });

  test("Agent and Qdrant outages degrade safely while transaction pages remain usable", async ({
    page,
  }) => {
    await register(page, uniqueUser("agentoutage"));
    const orderId = await createOrder(page);
    compose("--profile", "agent", "stop", "agent-service");
    try {
      await openAgent(page);
      await page.getByLabel("请求").fill("我的订单状态");
      await page.getByRole("button", { name: "发送请求" }).click();
      await expect(page.getByRole("alert")).toBeVisible({ timeout: 20_000 });
      await page.goto("/");
      await expect(page.getByRole("heading").first()).toBeVisible();
      await page.goto(`/products/${productId}`);
      await expect(page.getByText("高热交易收音机")).toBeVisible();
      await page.goto(`/user/orders/${orderId}`);
      await expect(
        page.locator('[data-event-type="ORDER_CREATED"]'),
      ).toHaveCount(1);
    } finally {
      compose("--profile", "agent", "start", "agent-service");
    }
    await expect
      .poll(() => {
        try {
          return compose(
            "exec",
            "-T",
            "agent-service",
            "python",
            "-c",
            "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health/ready', timeout=2)",
          ).includes("");
        } catch {
          return false;
        }
      })
      .toBe(true);

    compose("--profile", "agent", "stop", "qdrant");
    try {
      await page.goto("/user/agent");
      await sendAgent(page, "售后退换申请应该怎么做？");
      await expect(page.locator(".agent-answer-copy")).toContainText(
        "暂时不可用",
      );
      await sendAgent(page, `商品 ${productId} 当前库存是多少？`);
      await expect(
        page.locator('[data-agent-event="tool.completed"]'),
      ).toBeVisible();
      await page.goto(`/user/orders/${orderId}`);
      await expect(
        page.locator('[data-event-type="ORDER_CREATED"]'),
      ).toHaveCount(1);
    } finally {
      compose("--profile", "agent", "start", "qdrant");
    }
    await expect
      .poll(() => {
        try {
          compose(
            "exec",
            "-T",
            "agent-service",
            "python",
            "-c",
            "import urllib.request; urllib.request.urlopen('http://qdrant:6333/readyz', timeout=2)",
          );
          return true;
        } catch {
          return false;
        }
      })
      .toBe(true);

    // Prove the recovered dependency is usable through a fresh browser session,
    // not merely accepting health probes, before the next browser project starts.
    await page.goto("/user/agent");
    await sendAgent(page, `商品 ${productId} 当前库存是多少？`);
    await expect(
      page.locator('[data-agent-event="tool.completed"]'),
    ).toBeVisible();
  });
});
