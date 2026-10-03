// Built Web + real admin APIs. Fixtures belong only to a disposable local demo.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(path.join(root, "web/package.json"));
const { chromium, devices, expect: baseExpect } = require("@playwright/test");
const expect = baseExpect.configure({ timeout: 15_000 });
const [project, address] = process.argv.slice(2);
assert.match(project ?? "", /^hotshop-task21-[a-z0-9]{8,24}$/);
assert.ok(existsSync(path.join(root, ".local/keys", project, ".env.demo")));
const url = new URL(address);
assert.ok(["http:", "https:"].includes(url.protocol));
assert.ok(["127.0.0.1", "localhost"].includes(url.hostname));
const docker = (...args) => execFileSync("docker", args, { encoding: "utf8", timeout: 30_000 }).trim();
function owned(service) {
  const ids = docker("ps", "-q", "--filter", `label=com.docker.compose.project=${project}`,
    "--filter", `label=com.docker.compose.service=${service}`).split(/\r?\n/).filter(Boolean);
  assert.equal(ids.length, 1, `Require one running ${service} in the selected demo`);
  const container = JSON.parse(docker("inspect", ids[0]))[0];
  assert.equal(container.Config.Labels["com.docker.compose.project"], project);
  return container;
}
const web = owned("web-demo");
const mysql = owned("mysql");
const redis = owned("redis-seckill");
const task = owned("task-service");
const discovery = task.Config.Env.find(value => value.startsWith("HOTSHOP_SECKILL_ORDER_DISCOVERY_INTERVAL="))?.split("=")[1] ?? "10s";
const discoveryMatch = /^(\d+)(ms|s)$/.exec(discovery);
assert.ok(discoveryMatch, "Verification requires a discovery interval expressed in ms or s");
const discoveryMs = Number(discoveryMatch[1]) * (discoveryMatch[2] === "s" ? 1000 : 1);
assert.ok(discoveryMs > 0 && discoveryMs <= 30_000, "Use a disposable demo with discovery at most 30 seconds");
assert.ok(web.NetworkSettings.Ports["8080/tcp"].some(item => item.HostPort === url.port));
const owner = `adm${randomBytes(6).toString("hex")}`;
const destination = path.join(root, ".local/verification", `admin-investigation-${owner}`);
mkdirSync(destination, { recursive: true });
const sql = (statement) => execFileSync("docker", ["exec", "-i", mysql.Id, "sh", "-c",
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --database="$MYSQL_DATABASE" --batch --skip-column-names'],
{ encoding: "utf8", input: statement, timeout: 30_000 }).trim();
const redisCommand = (...args) => docker("exec", redis.Id, "redis-cli", "--raw", ...args);
const prefix = "hotshop:seckill:v1:{hotshop-seckill-v1}";
const results = [];
const startedAt = new Date().toISOString();
const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
const dirty = execFileSync("git", ["status", "--porcelain"], { cwd: root, encoding: "utf8" }).trim().length > 0;
let activityId;
let browser;
let cleanup = false;
let failure;
console.log(`Evidence: ${destination}`);

function seed() {
  const userId = sql("SELECT MIN(user_id) FROM app_user WHERE role='ROLE_USER' AND deleted_at IS NULL;");
  assert.match(userId, /^[1-9][0-9]*$/);
  const statements = ["START TRANSACTION;"];
  for (let index = 1; index <= 25; index++) {
    const id = `${owner}${String(index).padStart(2, "0")}`;
    statements.push(`INSERT INTO seckill_reconciliation_issue(issue_key,issue_type,severity,status,evidence_summary,last_seen_at)
      VALUES(SHA2('${id}',256),'${id}','CRITICAL','OPEN',JSON_OBJECT('owner','${owner}'),UTC_TIMESTAMP(6)-INTERVAL ${index} SECOND);`);
    statements.push(`INSERT INTO seckill_event_processing(event_id,stream_key,stream_entry_id,payload_hash,status,attempts,reason_code,updated_at)
      VALUES('${id}','${owner}','${index}-0',SHA2('${id}',256),'MANUAL_REVIEW',3,'AUDIT_FIXTURE',UTC_TIMESTAMP(6)-INTERVAL ${index} SECOND);`);
    statements.push(`INSERT INTO sales_order(order_id,user_id,total_amount,currency,status,paid_at,created_at)
      VALUES('${id}',${userId},19.90,'CNY','PAID',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)-INTERVAL ${index} SECOND);`);
    statements.push(`INSERT INTO payment_order(payment_no,order_id,provider,amount,currency,status,paid_at,created_at)
      VALUES('${id}','${id}','MOCK',19.90,'CNY','SUCCEEDED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)-INTERVAL ${index} SECOND);`);
  }
  statements.push(`INSERT INTO seckill_reconciliation_issue(issue_key,issue_type,severity,status,evidence_summary)
    VALUES(SHA2('${owner}resolved',256),'${owner}resolved','WARNING','RESOLVED',JSON_OBJECT('owner','${owner}'));`);
  statements.push(`INSERT INTO sales_order(order_id,user_id,total_amount,currency,status,created_at)
    VALUES('${owner}late',${userId},19.90,'CNY','CANCELED',UTC_TIMESTAMP(6)-INTERVAL 2 DAY);`);
  statements.push(`INSERT INTO payment_order(payment_no,order_id,provider,amount,currency,status,paid_at,created_at)
    VALUES('${owner}late','${owner}late','MOCK',19.90,'CNY','LATE_SUCCEEDED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)-INTERVAL 2 DAY);`);
  statements.push(`INSERT INTO catalog_product(sku,name,price,stock,expected_stock,status)
    VALUES('${owner}','Admin audit fixture',30,30,30,'ACTIVE');`);
  statements.push(`INSERT INTO flash_sale_activity(activity_code,product_id,sale_price,total_stock,available_stock,expected_available_stock,
    per_user_limit,status,starts_at,ends_at) SELECT '${owner}',product_id,20,20,20,20,1,'DRAFT',UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE,
    UTC_TIMESTAMP(6)+INTERVAL 1 HOUR FROM catalog_product WHERE sku='${owner}';`);
  statements.push("COMMIT;");
  sql(statements.join("\n"));
  activityId = sql(`SELECT activity_id FROM flash_sale_activity WHERE activity_code='${owner}';`);
  assert.match(activityId, /^[1-9][0-9]*$/);
}

try {
  seed();
  browser = await chromium.launch();
  for (const [device, settings] of [["desktop", { viewport: { width: 1440, height: 1000 } }],
    ["mobile", devices["Pixel 7"]]]) {
    const context = await browser.newContext({ ...settings, baseURL: url.origin });
    const page = await context.newPage();
    page.setDefaultTimeout(15_000);
    const pageErrors = [];
    page.on("pageerror", error => pageErrors.push(error.name));
    const record = (scenario, details = {}) => {
      results.push({ device, scenario, status: "passed", ...details });
      console.log(`${device}: ${scenario}`);
    };
    try {
      await page.goto("/admin/login");
      await page.getByLabel("管理员用户名").fill("task13-admin");
      await page.getByLabel("密码", { exact: true }).fill("Task13Admin!2026");
      const login = page.waitForResponse(response => new URL(response.url()).pathname === "/admin/api/v1/auth/login");
      await page.getByRole("button", { name: "以 Administrator 身份登录" }).click();
      assert.equal((await login).status(), 200, "Demo administrator login must succeed");
      await expect(page.getByRole("heading", { name: "运营脉冲" })).toBeVisible();
      await page.goto("/admin/exceptions");
      for (const panelName of ["对账异常", "待人工处理", "支付状态"]) {
        const panel = page.getByRole("region", { name: panelName, exact: true });
        await expect(panel.locator("tbody tr").filter({ hasText: `${owner}01` })).toBeVisible();
        await panel.getByRole("button", { name: "下一页", exact: true }).click();
        await expect(panel.locator("tbody tr").filter({ hasText: `${owner}21` })).toBeVisible();
        await panel.getByRole("button", { name: "上一页", exact: true }).click();
        await expect(panel.locator("tbody tr").filter({ hasText: `${owner}01` })).toBeVisible();
        record(`${panelName}: signed cursor forward and back`);
      }
      await page.getByRole("combobox", { name: "异常状态", exact: true }).selectOption("RESOLVED");
      await expect(page.getByText(`${owner}resolved`, { exact: true })).toBeVisible();
      record("resolved findings remain reachable");
      await page.getByRole("combobox", { name: "支付结果", exact: true }).selectOption("LATE_SUCCEEDED");
      await page.getByRole("combobox", { name: "支付时间范围", exact: true }).selectOption("168");
      await expect(page.getByText(`${owner}late`, { exact: true }).first()).toBeVisible();
      record("late payments outside 24 hours remain reachable");

      const issuePanel = page.getByRole("region", { name: "对账异常", exact: true });
      const paymentPanel = page.getByRole("region", { name: "支付状态", exact: true });
      await page.route("**/admin/api/v1/operations/payments?*", route => route.fulfill({
        status: 503, contentType: "application/problem+json", body: JSON.stringify({
          type: "about:blank", title: "Unavailable", status: 503, detail: "Audit payment outage",
          code: "AUDIT_OUTAGE", requestId: owner, traceId: "", instance: "/admin/api/v1/operations/payments",
        }),
      }));
      await page.getByRole("button", { name: "刷新全部", exact: true }).click();
      await expect(paymentPanel.getByRole("alert")).toContainText("Audit payment outage");
      await expect(issuePanel.getByText(`${owner}resolved`, { exact: true })).toBeVisible();
      await expect(page.getByRole("region", { name: "待人工处理", exact: true }).getByText(`${owner}01`, { exact: true })).toBeVisible();
      await page.unroute("**/admin/api/v1/operations/payments?*");
      await paymentPanel.getByRole("button", { name: "重新同步", exact: true }).click();
      await expect(paymentPanel.getByText(`${owner}late`, { exact: true }).first()).toBeVisible();
      record("payment outage stays local and retries with the same filters");
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), "No body overflow");
      await paymentPanel.scrollIntoViewIfNeeded();
      await page.screenshot({ path: path.join(destination, `${device}-payments.png`) });
      await page.getByRole("heading", { name: "异常与人工处理", exact: true }).scrollIntoViewIfNeeded();
      await page.screenshot({ path: path.join(destination, `${device}-overview.png`) });

      await page.goto("/admin/activities");
      const row = page.getByRole("row").filter({ hasText: owner });
      const load = async () => {
        await row.getByRole("button", { name: "加载并核验", exact: true }).click();
        await page.getByLabel("操作原因", { exact: true }).fill("Audit disposable fixture inventory");
        await page.getByRole("button", { name: "确认加载", exact: true }).click();
      };
      await load();
      await expect(page.getByRole("status")).toContainText("本次库存核验一致");
      assert.equal(redisCommand("GET", `${prefix}:activity:${activityId}:stock`), "20");
      redisCommand("SET", `${prefix}:activity:${activityId}:stock`, "19", "KEEPTTL");
      try {
        await load();
        await expect(page.getByRole("alert")).toContainText("库存核验未确认一致");
        await expect(page.getByRole("alert")).toContainText("MySQL 可用库存：20");
        await expect(page.getByRole("alert")).toContainText("Redis 可用库存：19");
        await expect(page.getByRole("link", { name: "查看异常与人工处理" })).toBeVisible();
        assert.equal(redisCommand("GET", `${prefix}:activity:${activityId}:stock`), "19", "Loading must not reset the discrepancy");
        await page.screenshot({ path: path.join(destination, `${device}-activity-warning.png`) });
        record("successful load with inconsistent inventory remains a warning");
      } finally { redisCommand("SET", `${prefix}:activity:${activityId}:stock`, "20", "KEEPTTL"); }
      const loadPath = `**/admin/api/v1/flash-sales/${activityId}/load`;
      let releaseResponse;
      const withheld = new Promise(resolve => { releaseResponse = resolve; });
      let upstreamCompleted = false;
      let loadAttempts = 0;
      await page.route(loadPath, async route => {
        loadAttempts++;
        const response = await route.fetch({ timeout: 10_000 });
        assert.equal(response.status(), 200);
        upstreamCompleted = true;
        await withheld;
        await route.abort("connectionreset").catch(() => undefined);
      });
      try {
        await load();
        await expect(page.getByRole("button", { name: "取消", exact: true })).toBeDisabled();
        await expect(page.getByRole("alert")).toContainText("尚未确认", { timeout: 20_000 });
        await expect(page.getByRole("alert")).toContainText("审计记录");
        await expect(page.getByRole("button", { name: "取消", exact: true })).toBeEnabled();
        assert.ok(upstreamCompleted, "The server completed even though the browser lost its response");
        assert.equal(loadAttempts, 1, "No automatic replay after an ambiguous outcome");
        record("committed load with withheld response ends as unconfirmed after deadline");
      } finally {
        releaseResponse();
        await page.unroute(loadPath);
      }
      assert.deepEqual(pageErrors, []);
      record("no page errors or horizontal body overflow");
    } finally { await context.close(); }
  }
} catch (error) {
  failure = error;
  console.error(error.message);
  process.exitCode = 1;
} finally {
  if (browser) await browser.close();
  try {
    if (activityId) {
      const stream = `${prefix}:activity:${activityId}:reservations`;
      assert.equal(redisCommand("XLEN", stream), "0", "Never clean fixtures that acquired reservations");
      redisCommand("SREM", `${prefix}:registry:reservation-streams`, stream);
      redisCommand("ZREM", `${prefix}:registry:reconciliation-streams`, stream);
      // Let this demo's consumer discovery cache retire its empty draft stream.
      await new Promise(resolve => setTimeout(resolve, discoveryMs + 2000));
      assert.equal(redisCommand("XLEN", stream), "0");
      const keys = ["meta", "stock", "reservations", "reconciliation:conservation", "reconciliation:conservation:seen"]
        .map(suffix => `${prefix}:activity:${activityId}:${suffix}`);
      redisCommand("DEL", ...keys);
      assert.equal(redisCommand("EXISTS", ...keys), "0");
      assert.equal(redisCommand("SISMEMBER", `${prefix}:registry:reservation-streams`, stream), "0");
      assert.equal(redisCommand("ZSCORE", `${prefix}:registry:reconciliation-streams`, stream), "");
      sql(`DELETE FROM seckill_reconciliation_checkpoint WHERE checkpoint_name='reservation-stream-${activityId}';`);
    }
    assert.equal(sql(`SELECT COUNT(*) FROM sales_order_item WHERE product_id IN
      (SELECT product_id FROM catalog_product WHERE sku='${owner}');`), "0", "Never remove a fixture product used by another journey");
    sql(`START TRANSACTION;
      DELETE FROM payment_order WHERE payment_no LIKE '${owner}%';
      DELETE FROM sales_order WHERE order_id LIKE '${owner}%';
      DELETE FROM seckill_event_processing WHERE event_id LIKE '${owner}%';
      DELETE FROM seckill_reconciliation_issue WHERE issue_type LIKE '${owner}%' ${activityId ? `OR activity_id=${activityId}` : ""};
      DELETE FROM flash_sale_activity WHERE activity_code='${owner}';
      DELETE FROM catalog_product WHERE sku='${owner}'; COMMIT;`);
    const remaining = sql(`SELECT (SELECT COUNT(*) FROM payment_order WHERE payment_no LIKE '${owner}%')
      +(SELECT COUNT(*) FROM sales_order WHERE order_id LIKE '${owner}%')
      +(SELECT COUNT(*) FROM seckill_event_processing WHERE event_id LIKE '${owner}%')
      +(SELECT COUNT(*) FROM seckill_reconciliation_issue WHERE issue_type LIKE '${owner}%')
      +(SELECT COUNT(*) FROM flash_sale_activity WHERE activity_code='${owner}')
      +(SELECT COUNT(*) FROM catalog_product WHERE sku='${owner}');`);
    assert.equal(remaining, "0");
    cleanup = true;
  } catch (error) {
    console.error(`Fixture cleanup failed: ${error.message}`);
    process.exitCode = 1;
  }
  const report = { sourceCommit, dirty, project, owner, startedAt, finishedAt: new Date().toISOString(),
    webImage: web.Image, browser: browser?.version(), results, cleanup,
    failure: failure?.message, artifacts: destination };
  writeFileSync(path.join(destination, "result.json"), `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({ passed: results.length, cleanup, artifacts: destination }));
}
