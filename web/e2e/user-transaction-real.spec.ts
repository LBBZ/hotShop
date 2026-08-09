import { execFileSync } from "node:child_process";

import { expect, test, type Page, type Request } from "@playwright/test";

const productId = "913001";
const password = "Task13demo!";

function composeProject() {
  const project =
    process.env.HOTSHOP_E2E_COMPOSE_PROJECT ?? process.env.COMPOSE_PROJECT_NAME;
  if (!project || !/^[a-z0-9_-]+$/u.test(project)) {
    throw new Error("A safe isolated Compose project name is required");
  }
  return project;
}

function compose(...args: string[]) {
  return execFileSync(
    "docker",
    ["compose", "-p", composeProject(), "-f", "../docker-compose.yml", ...args],
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
    "hotshop-e2e-query",
    sql,
  ).trim();
}

function uniqueUser(prefix: string, project: string) {
  const suffix = `${project.replaceAll(/\W/g, "").slice(0, 8)}${Date.now().toString(36)}`;
  return `${prefix}${suffix}`.slice(0, 48);
}

async function register(page: Page, username: string, keyboardSubmit = false) {
  await page.goto("/auth");
  await page.locator(".auth-tabs button").nth(1).click();
  await page.locator('input[autocomplete="username"]').fill(username);
  await page
    .locator('input[autocomplete="email"]')
    .fill(`${username}@hotshop.invalid`);
  const passwordInput = page.locator('input[type="password"]');
  await passwordInput.fill(password);
  const loginResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/v1/auth/login") &&
      response.request().method() === "POST",
  );
  if (keyboardSubmit) {
    await passwordInput.press("Enter");
  } else {
    await page.locator(".auth-card form button[type=submit]").click();
  }
  await expect(page).toHaveURL(/\/user$/);
  return (await loginResponse).json() as Promise<{
    expiresAt: string;
    accessToken: string;
  }>;
}

async function createOrder(
  page: Page,
  interaction: "single" | "double" = "double",
) {
  await page.goto(`/products/${productId}`);
  const create = page.locator(".product-detail-copy button");
  const responses: string[] = [];
  const observe = (request: Request) => {
    if (
      request.url().endsWith("/api/v1/orders") &&
      request.method() === "POST"
    ) {
      const key = request.headers()["idempotency-key"];
      if (key) responses.push(key);
    }
  };
  page.on("request", observe);
  if (interaction === "double") {
    await create.dblclick();
  } else {
    await create.click();
  }
  await expect(page).toHaveURL(/\/user\/orders\/[A-Za-z0-9_-]+$/);
  page.off("request", observe);
  expect(responses).toHaveLength(1);
  expect(new Set(responses).size).toBe(1);
  return page.url().split("/").at(-1)!;
}

async function runPayment(page: Page, scenario: string, expectedEvent: string) {
  await page.locator(".scenario-panel select").selectOption(scenario);
  const action = page.waitForResponse(
    (response) =>
      response.url().includes("/mock-actions") &&
      response.request().method() === "POST",
  );
  await page.locator(".scenario-panel button").click();
  expect((await action).status()).toBe(202);
  await expect(page.locator(".action-ack")).toBeVisible();
  await expect(
    page.locator(`[data-event-type="${expectedEvent}"]`),
  ).toHaveCount(1, { timeout: 45_000 });
}

test.describe("real Compose user transaction journey", () => {
  test.describe.configure({ mode: "serial", timeout: 120_000 });

  test("anonymous Chinese catalog, keyboard auth, refresh recovery, and ownership denial", async ({
    browser,
    page,
  }, testInfo) => {
    const owner = uniqueUser("journey", testInfo.project.name);
    await page.goto("/");
    await expect(page.locator("h1")).toBeVisible();
    await page.locator(".catalog-filter input").first().fill("高热交易收音机");
    await page.locator(".catalog-filter").press("Enter");
    await expect(page.locator(".product-card")).toHaveCount(1);
    await page.locator(".product-card a").click();
    await expect(page).toHaveURL(new RegExp(`/products/${productId}$`));
    await page.locator(".product-detail-copy button").click();
    await expect(page).toHaveURL(/\/auth\?returnTo=/);
    await register(page, owner, true);

    await page.reload();
    await expect(page).toHaveURL(/\/user$/);
    await expect(page.locator("#workspace-main")).toBeVisible();
    const orderId = await createOrder(page);

    const strangerContext = await browser.newContext();
    const strangerPage = await strangerContext.newPage();
    await register(strangerPage, uniqueUser("stranger", testInfo.project.name));
    await strangerPage.goto(`/user/orders/${orderId}`);
    await expect(strangerPage.locator("#error-title")).toBeVisible();
    await strangerContext.close();
  });

  test("ordinary payment success, failure, delay, and duplicate callback use the real worker", async ({
    page,
  }, testInfo) => {
    test.setTimeout(240_000);
    await register(page, uniqueUser("payment", testInfo.project.name));

    await createOrder(page);
    await runPayment(page, "success", "PAID");
    await expect(page.locator(".dashboard-heading")).toContainText("PAID");

    await createOrder(page);
    await runPayment(page, "failed", "PAYMENT_FAILED");

    await createOrder(page);
    await runPayment(page, "delayed", "PAID");

    const duplicateOrderId = await createOrder(page);
    await runPayment(page, "duplicate", "PAID");
    await expect(page.locator('[data-event-type="PAID"]')).toHaveCount(1);
    if (process.env.HOTSHOP_REAL_COMPOSE === "1") {
      const paymentNo = mysqlScalar(
        `SELECT payment_no FROM payment_order WHERE order_id='${duplicateOrderId}'`,
      );
      expect(paymentNo).toMatch(/^MOCK_[0-9a-f]{32}$/u);
      await expect
        .poll(() =>
          Number(
            mysqlScalar(
              `SELECT COUNT(*) FROM audit_log WHERE resource_id='${paymentNo}' AND action='MOCK_PAYMENT_CALLBACK_ACCEPTED'`,
            ),
          ),
        )
        .toBeGreaterThanOrEqual(3);
      expect(
        Number(
          mysqlScalar(
            `SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.duplicateCount')) FROM outbox_event WHERE aggregate_id='${paymentNo}' AND event_type='MOCK_PAYMENT_CALLBACK_REQUESTED'`,
          ),
        ),
      ).toBe(3);
      expect(
        Number(
          mysqlScalar(
            `SELECT COUNT(*) FROM payment_callback_ledger WHERE payment_no='${paymentNo}'`,
          ),
        ),
      ).toBe(1);
      expect(
        Number(
          mysqlScalar(
            `SELECT COUNT(*) FROM user_transaction_timeline WHERE order_id='${duplicateOrderId}' AND event_type='PAID'`,
          ),
        ),
      ).toBe(1);
    }
  });

  test("payment timeout wins the race and a late callback cannot regress facts", async ({
    page,
  }, testInfo) => {
    test.skip(
      process.env.HOTSHOP_E2E_SHORT_TIMEOUT !== "1",
      "Requires Compose payment timeout configured below the 20-second callback delay",
    );
    await register(page, uniqueUser("race", testInfo.project.name));
    await createOrder(page);
    await runPayment(page, "race", "LATE_SUCCEEDED");
    await expect(page.locator('[data-event-type="CLOSED"]')).toHaveCount(1);
    await expect(page.locator('[data-event-type="CANCELED"]')).toHaveCount(1);
    await expect(
      page.locator('[data-event-type="LATE_SUCCEEDED"]'),
    ).toHaveCount(1);
    await expect(page.locator(".dashboard-heading")).toContainText("CANCELED");
  });

  test("flash-sale double click reuses one intent and reaches async order creation", async ({
    page,
  }, testInfo) => {
    await register(page, uniqueUser("seckill", testInfo.project.name));
    await page.goto("/");
    const keys: string[] = [];
    page.on("request", (request) => {
      if (
        request.url().includes("/flash-sales/913001/reservations") &&
        request.method() === "POST"
      ) {
        const key = request.headers()["idempotency-key"];
        if (key) keys.push(key);
      }
    });
    await page.locator('[data-activity-id="913001"] button').first().dblclick();
    await expect(page).toHaveURL(
      /\/user\/reservations\/913001\/rsv_[0-9a-f]{32}$/,
    );
    expect(keys).toHaveLength(1);
    expect(new Set(keys).size).toBe(1);
    await expect(page.locator('[data-event-type="RESERVED"]')).toHaveCount(1);
    await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
      1,
      {
        timeout: 30_000,
      },
    );
    const reservationUrl = page.url();
    await page.goto("/");
    await page.locator('[data-activity-id="913001"] button').first().click();
    await expect(page).toHaveURL(reservationUrl);
    await page.reload();
    await expect(page.locator('[data-event-type="ORDER_CREATED"]')).toHaveCount(
      1,
    );
  });

  test("sold-out, expiry, offline reconnect, Last-Event-ID, and restart recovery", async ({
    page,
  }, testInfo) => {
    const login = await register(
      page,
      uniqueUser("recovery", testInfo.project.name),
    );
    await page.goto("/");
    await expect(page.locator('[data-activity-id="913002"]')).toHaveAttribute(
      "data-phase",
      "SOLD_OUT",
    );
    await expect(
      page.locator('[data-activity-id="913002"] button').first(),
    ).toBeDisabled();
    const expired = await page.request.post(
      "/api/v1/flash-sales/913003/reservations",
      {
        headers: {
          Authorization: `Bearer ${login.accessToken}`,
          "Content-Type": "application/json",
          "Idempotency-Key": `expired:${Date.now().toString(36)}`,
        },
        data: { quantity: 1 },
      },
    );
    expect(expired.status()).toBe(409);

    const reconnectRequests: Array<{
      request: Request;
      url: string;
      lastEventId: string | undefined;
    }> = [];
    const failedEventRequests: Request[] = [];
    page.on("request", (request) => {
      if (request.url().endsWith("/events")) {
        reconnectRequests.push({
          request,
          url: request.url(),
          lastEventId: request.headers()["last-event-id"],
        });
      }
    });
    page.on("requestfailed", (request) => {
      if (request.url().endsWith("/events")) {
        failedEventRequests.push(request);
      }
    });
    await createOrder(page);
    await runPayment(page, "success", "PAID");
    await page.context().setOffline(true);
    await expect(page.locator(".transaction-receipt")).toHaveAttribute(
      "data-connection",
      "offline",
      { timeout: 15_000 },
    );
    await page.context().setOffline(false);
    await expect(page.locator(".transaction-receipt")).toHaveAttribute(
      "data-connection",
      "live",
      { timeout: 30_000 },
    );
    await expect
      .poll(() => reconnectRequests.some(({ lastEventId }) => lastEventId))
      .toBe(true);

    if (
      process.env.HOTSHOP_REAL_COMPOSE === "1" &&
      testInfo.project.name === "chromium"
    ) {
      const latestEventText = await page
        .locator(".transaction-receipt li code")
        .last()
        .textContent();
      const latestEventId = latestEventText?.replace(/^EVT\s+/u, "");
      expect(latestEventId).toMatch(/^\d+$/u);
      const requestsBeforeRestart = reconnectRequests.length;
      const activeRequestBeforeRestart = reconnectRequests.at(-1)?.request;
      expect(activeRequestBeforeRestart).toBeDefined();
      compose("--profile", "app", "stop", "portal-service");
      try {
        await expect
          .poll(
            () => failedEventRequests.includes(activeRequestBeforeRestart!),
            { timeout: 35_000 },
          )
          .toBe(true);
      } finally {
        compose("--profile", "app", "start", "portal-service");
      }
      await expect
        .poll(
          () =>
            reconnectRequests
              .slice(requestsBeforeRestart)
              .some(({ lastEventId }) => lastEventId === latestEventId),
          { timeout: 60_000 },
        )
        .toBe(true);
      const restartEvidence = {
        latestEventId,
        disconnectedRequest: {
          url: activeRequestBeforeRestart?.url(),
          failure: activeRequestBeforeRestart?.failure()?.errorText,
        },
        requests: reconnectRequests
          .slice(requestsBeforeRestart)
          .map(({ url, lastEventId }) => ({ url, lastEventId })),
      };
      console.info(
        "HOTSHOP_SSE_RESTART_EVIDENCE",
        JSON.stringify(restartEvidence),
      );
      await testInfo.attach("portal-restart-sse", {
        body: JSON.stringify(restartEvidence, null, 2),
        contentType: "application/json",
      });
      await expect(page.locator(".transaction-receipt")).toHaveAttribute(
        "data-connection",
        "live",
        { timeout: 60_000 },
      );
    }
    await page.reload();
    await expect(page.locator('[data-event-type="PAID"]')).toHaveCount(1);
  });

  test("real Redis outage retries the same reservation intent", async ({
    page,
  }, testInfo) => {
    test.skip(
      process.env.HOTSHOP_REAL_COMPOSE !== "1" ||
        testInfo.project.name !== "chromium",
      "Destructive availability proof runs once against the isolated Compose project",
    );
    await register(page, uniqueUser("retry", testInfo.project.name));
    await page.goto("/");
    const keys: string[] = [];
    const statuses: number[] = [];
    const responseEvidence: Array<
      Promise<{
        status: number;
        contentType: string;
        problem: Record<string, unknown>;
      }>
    > = [];
    page.on("request", (request) => {
      if (
        request.url().includes("/flash-sales/913001/reservations") &&
        request.method() === "POST"
      ) {
        const key = request.headers()["idempotency-key"];
        if (key) keys.push(key);
      }
    });
    page.on("response", (response) => {
      if (
        response.url().includes("/flash-sales/913001/reservations") &&
        response.request().method() === "POST"
      ) {
        statuses.push(response.status());
        responseEvidence.push(
          (async () => ({
            status: response.status(),
            contentType: response.headers()["content-type"] ?? "",
            problem: (await response.json()) as Record<string, unknown>,
          }))(),
        );
      }
    });
    compose("pause", "redis-seckill");
    try {
      await page.locator('[data-activity-id="913001"] button').first().click();
      await expect(page.locator('[role="alert"]')).toBeVisible({
        timeout: 45_000,
      });
      await expect.poll(() => statuses.length).toBe(3);
      expect(statuses).toEqual([503, 503, 503]);
      const failedResponses = await Promise.all(responseEvidence);
      expect(failedResponses).toHaveLength(3);
      for (const response of failedResponses) {
        expect(response.status).toBe(503);
        expect(response.contentType).toMatch(
          /^application\/problem\+json(?:;|$)/u,
        );
        expect(response.problem).toMatchObject({
          type: expect.stringMatching(/^https?:\/\//u),
          title: expect.any(String),
          status: 503,
          code: "SECKILL_SERVICE_UNAVAILABLE",
          detail:
            "The flash-sale reservation service is temporarily unavailable",
          instance: expect.any(String),
        });
        expect(response.problem.requestId).toEqual(
          expect.stringMatching(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/u),
        );
        expect(response.problem.traceId).toEqual(
          expect.stringMatching(/^[0-9a-f]{32}$/u),
        );
      }
      await expect(page.locator('[role="alert"]')).toContainText(
        "The flash-sale reservation service is temporarily unavailable",
      );
      console.info(
        "HOTSHOP_REDIS_503_EVIDENCE",
        JSON.stringify(
          failedResponses.map(({ status, contentType, problem }) => ({
            status,
            contentType,
            code: problem.code,
            requestId: problem.requestId,
            traceId: problem.traceId,
          })),
        ),
      );
    } finally {
      compose("unpause", "redis-seckill");
    }
    await page.locator('[data-activity-id="913001"] button').first().click();
    await expect(page).toHaveURL(
      /\/user\/reservations\/913001\/rsv_[0-9a-f]{32}$/,
    );
    const acceptedReservationUrl = page.url();
    expect(statuses.at(-1)).toBe(202);
    expect(keys.length).toBeGreaterThanOrEqual(4);
    expect(new Set(keys).size).toBe(1);

    await page.goto("/");
    const replayResponse = page.waitForResponse(
      (response) =>
        response.url().includes("/flash-sales/913001/reservations") &&
        response.request().method() === "POST",
    );
    await page.locator('[data-activity-id="913001"] button').first().click();
    const replay = await replayResponse;
    expect(replay.status()).toBe(202);
    expect(replay.headers()["idempotency-replayed"]).toBe("true");
    await expect(page).toHaveURL(acceptedReservationUrl);
    expect(statuses.slice(-2)).toEqual([202, 202]);
    expect(keys.length).toBeGreaterThanOrEqual(5);
    expect(new Set(keys).size).toBe(1);
  });

  test("transaction 429 keeps the original order intent and reports the limit", async ({
    page,
  }, testInfo) => {
    await register(page, uniqueUser("ratelimit", testInfo.project.name));
    for (let accepted = 0; accepted < 8; accepted += 1) {
      await createOrder(page, "single");
    }

    await page.goto(`/products/${productId}`);
    const keys: string[] = [];
    const statuses: number[] = [];
    page.on("request", (request) => {
      if (
        request.url().endsWith("/api/v1/orders") &&
        request.method() === "POST"
      ) {
        const key = request.headers()["idempotency-key"];
        if (key) keys.push(key);
      }
    });
    page.on("response", (response) => {
      if (
        response.url().endsWith("/api/v1/orders") &&
        response.request().method() === "POST"
      ) {
        statuses.push(response.status());
      }
    });
    await page.locator(".product-detail-copy button").click();
    await expect(page.locator(".inline-problem")).toContainText(
      "Request rate limit exceeded",
    );
    await expect.poll(() => statuses.length).toBe(3);
    expect(statuses).toEqual([429, 429, 429]);
    expect(keys).toHaveLength(3);
    expect(new Set(keys).size).toBe(1);
  });

  test("expired access token refreshes automatically before protected navigation", async ({
    page,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Long expiry proof runs once on desktop",
    );
    test.skip(
      process.env.HOTSHOP_E2E_SHORT_ACCESS === undefined,
      "Requires the real portal access TTL to be configured to 60 seconds",
    );
    await page.context().addCookies([
      {
        name: "hotshop_user_csrf",
        value: "legacy-user-csrf-before-login",
        domain: "127.0.0.1",
        path: "/api/v1/auth",
        httpOnly: false,
        secure: false,
        sameSite: "Strict",
      },
    ]);
    const login = await register(
      page,
      uniqueUser("refresh", testInfo.project.name),
    );
    const csrfAfterLogin = (await page.context().cookies()).filter(
      (cookie) => cookie.name === "hotshop_user_csrf",
    );
    expect(csrfAfterLogin.map((cookie) => cookie.path)).toEqual(["/"]);

    await page.context().addCookies([
      {
        name: "hotshop_user_csrf",
        value: csrfAfterLogin[0].value,
        domain: "127.0.0.1",
        path: "/api/v1/auth",
        httpOnly: false,
        secure: false,
        sameSite: "Strict",
      },
    ]);
    expect(
      (await page.context().cookies())
        .filter((cookie) => cookie.name === "hotshop_user_csrf")
        .map((cookie) => cookie.path)
        .sort(),
    ).toEqual(["/", "/api/v1/auth"]);
    const expiresAt = new Date(login.expiresAt).getTime();
    await expect
      .poll(() => page.evaluate((expiry) => Date.now() >= expiry, expiresAt), {
        timeout: 95_000,
        intervals: [1000],
      })
      .toBe(true);
    const refreshed = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/v1/auth/refresh") &&
        response.status() === 200,
    );
    const protectedOrders = page.waitForResponse(
      (response) =>
        response.url().includes("/api/v1/orders?") &&
        response.request().method() === "GET" &&
        response.status() === 200,
    );
    await page.locator('a[href="/user/orders"]').click();
    await refreshed;
    await protectedOrders;
    await expect(page).toHaveURL(/\/user\/orders$/);
    await expect(page.locator("#workspace-main")).toBeVisible();
    const csrfAfterRefresh = (await page.context().cookies()).filter(
      (cookie) => cookie.name === "hotshop_user_csrf",
    );
    expect(csrfAfterRefresh.map((cookie) => cookie.path)).toEqual(["/"]);
  });
});
