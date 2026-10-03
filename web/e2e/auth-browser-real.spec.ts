import {
  expect,
  request,
  test,
  type BrowserContext,
  type Page,
} from "@playwright/test";

const refreshPath = "/api/v1/auth/refresh";
const lockName = `hotshop:refresh:user:${refreshPath}`;
const password = "AuthBrowser!2026";

test.beforeEach(async ({ browser }, testInfo) => {
  testInfo.annotations.push({
    type: "browser-version",
    description: browser.version(),
  });
  // Twenty cases include fresh logins and UI relogins. Keep the original
  // 20-per-minute IP limit enabled instead of exhausting it with test setup.
  await new Promise((resolve) => setTimeout(resolve, 5_000));
});

async function signIn(context: BrowserContext, username: string) {
  const response = await context.request.post("/api/v1/auth/login", {
    data: { username, password },
  });
  expect(response.status(), "Test account login must succeed").toBe(200);
}

async function register(context: BrowserContext) {
  const username = `authbrowser${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
  const response = await context.request.post("/api/v1/auth/register", {
    data: { username, password, email: `${username}@hotshop.invalid` },
  });
  expect(response.status()).toBe(201);
  await signIn(context, username);
  return username;
}

function observeRotations(context: BrowserContext) {
  const statuses: number[] = [];
  context.on("response", (response) => {
    if (new URL(response.url()).pathname === refreshPath)
      statuses.push(response.status());
  });
  return statuses;
}

async function holdRefreshLock(page: Page) {
  await page.goto("/auth");
  expect(await page.evaluate(() => !!navigator.locks)).toBe(true);
  await page.evaluate((name) => {
    void navigator.locks.request(name, async () => {
      document.documentElement.dataset.authTestLock = "held";
      await new Promise<void>((resolve) => {
        window.addEventListener("release-auth-test-lock", () => resolve(), {
          once: true,
        });
      });
    });
  }, lockName);
  await page.waitForFunction(
    () => document.documentElement.dataset.authTestLock === "held",
  );
}

async function expectWaiting(page: Page) {
  await expect
    .poll(() =>
      page.evaluate(async (name) => {
        const snapshot = await navigator.locks.query();
        return snapshot.pending?.some((lock) => lock.name === name) ?? false;
      }, lockName),
    )
    .toBe(true);
}

async function expectLoginRequired(page: Page) {
  await expect(page).toHaveURL(/\/session-expired\?domain=user$/u);
  await expect(
    page.getByRole("heading", { name: "需要重新登录" }),
  ).toBeVisible();
}

async function relogin(page: Page, username: string) {
  await page.getByRole("link", { name: "重新登录" }).click();
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.locator('input[type="password"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await expect(page).toHaveURL(/\/user\/orders$/u);
}

test("two tabs serialize refresh and remain signed in after reload", async ({
  context,
}) => {
  const username = await register(context);
  const statuses = observeRotations(context);
  await context.route(`**${refreshPath}`, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 350));
    await route.continue();
  });
  const first = await context.newPage();
  const second = await context.newPage();
  await Promise.all([first.goto("/user"), second.goto("/user")]);
  for (const page of [first, second]) {
    await expect(
      page.getByRole("heading", { name: `你好，${username}` }),
    ).toBeVisible();
  }
  expect(statuses).toEqual([200, 200]);
  await first.reload();
  await expect(
    first.getByRole("heading", { name: `你好，${username}` }),
  ).toBeVisible();
  expect(statuses).toEqual([200, 200, 200]);
});

test("closing a queued tab does not dispatch its refresh", async ({
  context,
  page,
}) => {
  await register(context);
  const statuses = observeRotations(context);
  await holdRefreshLock(page);
  const queued = await context.newPage();
  await queued.goto("/user/orders");
  await expectWaiting(page);
  await queued.close();
  await page.evaluate(() =>
    window.dispatchEvent(new Event("release-auth-test-lock")),
  );
  const survivor = await context.newPage();
  await survivor.goto("/user/orders");
  await expect(
    survivor.getByRole("heading", { name: "还没有订单", exact: true }),
  ).toBeVisible();
  expect(statuses).toEqual([200]);
});

test("closing a lock owner releases the next tab", async ({
  context,
  page,
}) => {
  await register(context);
  const statuses = observeRotations(context);
  await holdRefreshLock(page);
  const queued = await context.newPage();
  await queued.goto("/user/orders");
  await expectWaiting(page);
  await page.close();
  await expect(
    queued.getByRole("heading", { name: "还没有订单", exact: true }),
  ).toBeVisible();
  expect(statuses).toEqual([200]);
});

test("a lost rotation response stops after one attempt and can sign in again", async ({
  context,
  page,
}, testInfo) => {
  const username = await register(context);
  let attempts = 0;
  // An independent HTTP context commits the real rotation without delivering its
  // Set-Cookie to the browser. No response body or credentials are recorded.
  const upstream = await request.newContext({
    storageState: { cookies: await context.cookies(), origins: [] },
  });
  try {
    await context.route(`**${refreshPath}`, async (route) => {
      attempts += 1;
      const headers = await route.request().allHeaders();
      testInfo.annotations.push({
        type: "fault-cookie-header",
        description: String(!!headers.cookie),
      });
      const response = await upstream.post(route.request().url(), {
        headers,
      });
      expect(response.status()).toBe(200);
      await route.abort("connectionreset");
    });
    await page.goto("/user/orders");
    await expectLoginRequired(page);
    expect(attempts).toBe(1);
    await context.unroute(`**${refreshPath}`);
    await relogin(page, username);
  } finally {
    await upstream.dispose();
  }
});

test("closing after a committed rotation releases the lock and requires fresh login", async ({
  context,
  page,
}, testInfo) => {
  const username = await register(context);
  const upstream = await request.newContext({
    storageState: { cookies: await context.cookies(), origins: [] },
  });
  let committed = false;
  let release: (() => void) | undefined;
  const closing = new Promise<void>((resolve) => {
    release = resolve;
  });
  try {
    await page.route(`**${refreshPath}`, async (route) => {
      const headers = await route.request().allHeaders();
      testInfo.annotations.push({
        type: "fault-cookie-header",
        description: String(!!headers.cookie),
      });
      const response = await upstream.post(route.request().url(), {
        headers,
      });
      expect(response.status()).toBe(200);
      committed = true;
      await closing;
      await route.abort("connectionreset").catch(() => undefined);
    });
    await page.goto("/user/orders");
    await expect.poll(() => committed).toBe(true);
    const next = await context.newPage();
    const statuses = observeRotations(context);
    await next.goto("/user/orders");
    await expectWaiting(next);
    await page.close();
    release?.();
    await expectLoginRequired(next);
    expect(statuses).toEqual([401]);
    await relogin(next, username);
    await next.reload();
    await expect(
      next.getByRole("heading", { name: "还没有订单", exact: true }),
    ).toBeVisible();
    expect(statuses).toEqual([401, 200]);
  } finally {
    release?.();
    await upstream.dispose();
  }
});
