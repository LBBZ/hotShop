import { expect, test, type Page } from "@playwright/test";

const products = [
  {
    productId: "101",
    name: "轻盈无线耳机",
    category: "数码",
    description: "轻松戴上，让喜欢的声音陪伴每一天。",
    price: "299.00",
    stock: 28,
  },
  {
    productId: "102",
    name: "日常机械键盘",
    category: "数码",
    description: "舒适敲击，给工作和灵感留一点空间。",
    price: "499.00",
    stock: 16,
  },
  {
    productId: "103",
    name: "午后便携音箱",
    category: "音频",
    description: "小巧随行，为日常收藏一段好旋律。",
    price: "189.00",
    stock: 0,
  },
].map((product) => ({
  ...product,
  createdAt: "2026-09-01T00:00:00Z",
  version: "1",
}));

function activities() {
  const now = Date.now();
  return [
    {
      activityId: "201",
      activityCode: "WEEKEND-FINDS",
      productId: "101",
      productName: "轻盈无线耳机",
      category: "数码",
      description: "让喜欢的声音，更近一点。",
      salePrice: "239.00",
      availableStock: 12,
      perUserLimit: 1,
      status: "ACTIVE",
      phase: "LIVE",
      startsAt: new Date(now - 3_600_000).toISOString(),
      endsAt: new Date(now + 3_600_000).toISOString(),
      serverTime: new Date(now).toISOString(),
    },
  ];
}

async function mockStore(
  page: Page,
  options: {
    empty?: boolean;
    failProducts?: boolean;
    failActivities?: boolean;
  } = {},
) {
  const requests: URL[] = [];
  await page.route("**/api/v1/products?*", async (route) => {
    const url = new URL(route.request().url());
    requests.push(url);
    if (options.failProducts) {
      await route.fulfill({
        status: 503,
        json: {
          status: 503,
          title: "Service unavailable",
          detail: "Catalog temporarily unavailable",
        },
      });
      return;
    }
    const keyword = url.searchParams.get("keyword") ?? "";
    const category = url.searchParams.get("category") ?? "";
    const min = Number(url.searchParams.get("minPrice") ?? "0");
    const max = Number(url.searchParams.get("maxPrice") ?? "Infinity");
    const items = options.empty
      ? []
      : products.filter(
          (product) =>
            product.name.includes(keyword) &&
            (!category || product.category === category) &&
            Number(product.price) >= min &&
            Number(product.price) <= max,
        );
    await route.fulfill({ json: { items, hasMore: false, nextCursor: null } });
  });
  await page.route("**/api/v1/flash-sale-activities?*", async (route) => {
    await route.fulfill(
      options.failActivities
        ? { status: 503, json: { status: 503, title: "Service unavailable" } }
        : { json: options.empty ? [] : activities() },
    );
  });
  return { requests, options };
}

test("catalog filtering preserves query parameters, validates prices, and clears applied conditions", async ({
  page,
}) => {
  const { requests } = await mockStore(page);
  await page.goto("/");
  await expect(page.locator(".product-card")).toHaveCount(3);
  for (const card of await page.locator(".product-card").all())
    await expect(card.getByRole("link")).toHaveCount(1);
  await expect(
    page.getByRole("link", { name: "轻盈无线耳机，查看商品与购买入口" }),
  ).toHaveAttribute("href", "/products/101");
  await page
    .getByRole("searchbox", { name: "搜索", exact: true })
    .fill("  无线  ");
  await page
    .getByRole("searchbox", { name: "搜索", exact: true })
    .press("Enter");
  await expect(page.locator(".product-card")).toHaveCount(1);
  expect(requests.at(-1)?.searchParams.get("keyword")).toBe("无线");
  await page.getByRole("button", { name: "筛选条件" }).click();
  await page.getByLabel("最低价", { exact: true }).fill("400");
  await page.getByLabel("最高价", { exact: true }).fill("100");
  const requestCount = requests.length;
  await page.getByRole("button", { name: "应用筛选" }).click();
  await expect(page.getByRole("alert")).toContainText("最低价不能高于最高价");
  expect(requests).toHaveLength(requestCount);
  await page.getByLabel("最低价", { exact: true }).fill("200");
  await page.getByLabel("最高价", { exact: true }).fill("350");
  await page.getByLabel("分类", { exact: true }).fill("数码");
  await page.getByRole("button", { name: "应用筛选" }).click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("maxPrice"))
    .toBe("350");
  expect(requests.at(-1)?.searchParams.get("minPrice")).toBe("200");
  expect(requests.at(-1)?.searchParams.get("category")).toBe("数码");
  await page.getByRole("button", { name: "清除筛选", exact: true }).click();
  await expect(page.locator(".product-card")).toHaveCount(3);
  await expect(
    page.getByRole("searchbox", { name: "搜索", exact: true }),
  ).toHaveValue("");
});

test("an unsuccessful search gives a working route back to all products", async ({
  page,
}) => {
  await mockStore(page);
  await page.goto("/");
  await page
    .getByRole("searchbox", { name: "搜索", exact: true })
    .fill("不存在的商品");
  await page.getByRole("button", { name: "应用筛选" }).click();
  await expect(page.locator(".product-card")).toHaveCount(0);
  await page.getByRole("button", { name: "清除筛选，浏览全部" }).click();
  await expect(page.locator(".product-card")).toHaveCount(3);
});

test("an empty catalog can refresh newly available products", async ({
  page,
}) => {
  const { options } = await mockStore(page, { empty: true });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "这里暂时还没有商品。" }),
  ).toBeVisible();
  options.empty = false;
  await page.getByRole("button", { name: "刷新商品" }).click();
  await expect(page.locator(".product-card")).toHaveCount(3);
});

test("catalog failure can retry successfully while navigation remains available", async ({
  page,
}) => {
  const { options } = await mockStore(page, {
    failProducts: true,
    failActivities: true,
  });
  await page.goto("/");
  const catalog = page.locator("#catalog");
  await expect(catalog.getByRole("alert")).toBeVisible();
  await expect(page.getByRole("link", { name: "逛逛好物" })).toBeVisible();
  options.failProducts = false;
  await catalog.getByRole("button", { name: "重新同步" }).click();
  await expect(page.locator(".product-card")).toHaveCount(3);
});

test("mobile navigation closes with Escape, restores focus, and follows section links", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockStore(page);
  await page.goto("/");
  const toggle = page.getByRole("button", { name: "打开导航" });
  const navigation = page.getByRole("navigation", { name: "主要导航" });
  await expect(navigation).toBeHidden();
  await toggle.click();
  await expect(navigation).toBeVisible();
  await navigation.getByRole("link", { name: "限时活动" }).focus();
  await page.keyboard.press("Escape");
  await expect(navigation).toBeHidden();
  await expect(toggle).toBeFocused();
  await toggle.click();
  await navigation.getByRole("link", { name: "限时活动" }).click();
  await expect(page).toHaveURL(/#drops$/u);
  await expect(navigation).toBeHidden();
  await expect(page.locator("#drops")).toBeInViewport();
});

test("reduced motion keeps the storefront readable without ongoing animation", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await mockStore(page);
  await page.goto("/");
  await expect(page.locator(".product-card")).toHaveCount(3);
  await expect(page.locator("h1")).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          document
            .getAnimations()
            .filter((animation) => animation.playState === "running").length,
      ),
    )
    .toBe(0);
  const scrollBehavior = await page.evaluate(
    () => getComputedStyle(document.documentElement).scrollBehavior,
  );
  expect(scrollBehavior).toBe("auto");
});

test("primary shopping actions retain readable text contrast", async ({
  page,
}) => {
  await mockStore(page);
  await page.goto("/");
  await expect(page.locator(".product-card")).toHaveCount(3);
  for (const action of [
    page.getByRole("link", { name: "逛逛好物", exact: true }),
    page.getByRole("link", { name: "和 AI 聊聊", exact: true }),
    page.getByRole("button", { name: "应用筛选", exact: true }),
    page.getByRole("button", { name: "立即预约", exact: true }),
  ]) {
    const contrast = await action.evaluate((element) => {
      const luminance = (color: string) => {
        const channels = (color.match(/[\d.]+/gu) ?? [])
          .slice(0, 3)
          .map(Number)
          .map((value) => {
            const channel = value / 255;
            return channel <= 0.04045
              ? channel / 12.92
              : ((channel + 0.055) / 1.055) ** 2.4;
          });
        return (
          channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
        );
      };
      const style = getComputedStyle(element);
      const foreground = luminance(style.color);
      const background = luminance(style.backgroundColor);
      return (
        (Math.max(foreground, background) + 0.05) /
        (Math.min(foreground, background) + 0.05)
      );
    });
    expect(
      contrast,
      `Text contrast for ${await action.innerText()}`,
    ).toBeGreaterThanOrEqual(4.5);
  }
});

for (const width of [320, 390, 768, 1440]) {
  test(`storefront content fits a ${width}px viewport with usable filters`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockStore(page);
    await page.goto("/");
    await expect(page.locator(".product-card")).toHaveCount(3);
    await page.getByRole("button", { name: "筛选条件" }).click();
    await expect(page.getByLabel("最低价", { exact: true })).toBeVisible();
    const size = await page.evaluate(() => ({
      width: window.innerWidth,
      content: document.documentElement.scrollWidth,
    }));
    expect(size.content).toBeLessThanOrEqual(size.width);
    for (const control of [
      page.getByRole("searchbox", { name: "搜索", exact: true }),
      page.getByRole("button", { name: "应用筛选" }),
      page.getByLabel("最低价", { exact: true }),
    ]) {
      const box = await control.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width);
      expect(box!.height).toBeGreaterThanOrEqual(40);
    }
  });
}
