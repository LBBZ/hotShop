import { expect, test } from "@playwright/test";
import { execFileSync } from "node:child_process";

const replayEventId = "14141414-1414-4141-8141-141414141414";
const paginationOrderPrefix = "task14-pagination-";

function composeProject() {
  const project =
    process.env.HOTSHOP_E2E_COMPOSE_PROJECT ?? process.env.COMPOSE_PROJECT_NAME;
  if (!project || !/^[a-z0-9_-]+$/u.test(project)) {
    throw new Error("A safe isolated Compose project name is required");
  }
  return project;
}

function executeSql(sql: string) {
  execFileSync(
    "docker",
    [
      "compose",
      "-p",
      composeProject(),
      "-f",
      "../docker-compose.yml",
      "exec",
      "-T",
      "mysql",
      "sh",
      "-lc",
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --database="$MYSQL_DATABASE" --execute="$1"',
      "hotshop-task14-seed",
      sql,
    ],
    { cwd: process.cwd(), encoding: "utf8" },
  );
}

function seedFailedOutbox() {
  const sql = `INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,publish_attempts,consecutive_attempts,failure_category,last_error,updated_at) VALUES('${replayEventId}','ORDER','task14-demo-order','ORDER_CREATED',JSON_OBJECT('schemaVersion',1),'FAILED',3,3,'TASK14_DEMO_FAILURE','TASK14_DEMO_FAILURE',UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status='FAILED',consecutive_attempts=3,failure_category='TASK14_DEMO_FAILURE',last_error='TASK14_DEMO_FAILURE',updated_at=UTC_TIMESTAMP(6)`;
  executeSql(sql);
}

function seedPaginatedOrders() {
  const values = Array.from({ length: 25 }, (_, index) => {
    const sequence = String(index + 1).padStart(2, "0");
    return `('${paginationOrderPrefix}${sequence}',913001,99.00,'CNY','COMPLETED',UTC_TIMESTAMP(6) - INTERVAL ${index + 1} MICROSECOND,UTC_TIMESTAMP(6))`;
  }).join(",");
  executeSql(
    `INSERT INTO sales_order(order_id,user_id,total_amount,currency,status,created_at,updated_at) VALUES${values} ON DUPLICATE KEY UPDATE status='COMPLETED',created_at=VALUES(created_at),updated_at=UTC_TIMESTAMP(6)`,
  );
}

test.describe("real Compose administrator journey", () => {
  test.skip(
    process.env.HOTSHOP_REAL_COMPOSE !== "1",
    "requires the real Compose stack and seeded failure facts",
  );

  test("login, triage, locate a Trace, replay with reason, and verify audit", async ({
    page,
  }) => {
    seedFailedOutbox();
    seedPaginatedOrders();
    await page.goto("/admin/login");
    await page
      .getByLabel("管理员用户名")
      .fill(process.env.HOTSHOP_E2E_ADMIN_USERNAME ?? "task13-admin");
    await page
      .getByLabel("密码")
      .fill(process.env.HOTSHOP_E2E_ADMIN_PASSWORD ?? "Task13Admin!2026");
    await page
      .getByRole("button", { name: "以 Administrator 身份登录" })
      .click();
    await expect(page.getByRole("heading", { name: "运营脉冲" })).toBeVisible();

    await page.getByRole("link", { name: "异常" }).click();
    await expect(
      page.getByRole("heading", { name: "异常与人工处理" }),
    ).toBeVisible();
    await expect(
      page.getByText(/只发现，不修改|只展示发现事实/u),
    ).toBeVisible();
    await expect(page.getByText(/自动修复/u).first()).toBeVisible();

    await page.getByRole("link", { name: "审计" }).click();
    const traceLink = page.locator("a.trace-link").first();
    await expect(traceLink).toBeVisible();
    await expect(traceLink).toHaveAttribute("href", /traceId=[0-9a-f]{32}/u);

    await page.getByRole("link", { name: "Outbox" }).click();
    const replayButton = page
      .getByRole("button", { name: "重放", exact: true })
      .first();
    await expect(replayButton).toBeVisible();
    await replayButton.click();
    const dialog = page.getByRole("dialog", { name: "二次确认 Outbox 重放" });
    await expect(dialog).toContainText("资源 ID");
    await expect(dialog).toContainText("可能再次向下游投递");
    const eventId = (
      await dialog.locator("code").first().textContent()
    )?.trim();
    expect(eventId).toBeTruthy();
    await dialog
      .getByLabel("操作原因（必填）")
      .fill("TASK-14 真实 Compose 管理旅程验证");
    await dialog.getByRole("button", { name: `确认重放 ${eventId}` }).click();
    await expect(page.getByRole("status")).toContainText(
      "已进入现有安全重放流程",
    );

    await page.getByRole("link", { name: "审计" }).click();
    await page.getByLabel("执行结果").selectOption("SUCCESS");
    await expect(
      page.getByRole("cell", { name: "OUTBOX_REPLAY" }).first(),
    ).toBeVisible();
    await expect(
      page.getByText(eventId!, { exact: true }).first(),
    ).toBeVisible();

    await page.locator('a[href="/admin/orders"]').click();
    await page.getByLabel("\u8ba2\u5355\u72b6\u6001").selectOption("COMPLETED");
    await expect(
      page.getByRole("cell", { name: `${paginationOrderPrefix}01` }),
    ).toBeVisible();
    const loadNext = page.getByRole("button", {
      name: "\u52a0\u8f7d\u4e0b\u4e00\u9875",
    });
    await expect(loadNext).toBeVisible();
    await loadNext.click();
    await expect(
      page.getByRole("cell", { name: `${paginationOrderPrefix}21` }),
    ).toBeVisible();
    await expect(page.getByText("CURSOR_INVALID", { exact: true })).toHaveCount(
      0,
    );
  });
});
