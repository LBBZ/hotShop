// Real browser -> running Agent -> real Qdrant; stops only this worktree's demo Qdrant.
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(path.join(root, "web/package.json"));
const { chromium } = require("@playwright/test");
const [project, baseURL = "http://127.0.0.1:18080"] = process.argv.slice(2);
assert.match(project ?? "", /^hotshop$/);
assert.ok(existsSync(path.join(root, ".local/keys", project, ".env.demo")), "Require this worktree's demo project");
assert.ok(["127.0.0.1", "localhost"].includes(new URL(baseURL).hostname));
const docker = (...args) => execFileSync("docker", args, { encoding: "utf8", timeout: 60_000 }).trim();
function owned(service) {
  const ids = docker("ps", "-aq", "--filter", `label=com.docker.compose.project=${project}`,
    "--filter", `label=com.docker.compose.service=${service}`).split(/\r?\n/).filter(Boolean);
  assert.equal(ids.length, 1);
  const item = JSON.parse(docker("inspect", ids[0]))[0];
  assert.equal(item.Config.Labels["com.docker.compose.project"], project);
  assert.equal(item.Config.Labels["com.docker.compose.service"], service);
  assert.ok(item.State.Running, `${service} must already be running`);
  return item;
}
const qdrant = owned("qdrant");
const agent = owned("agent-service");
const web = owned("web-demo");
assert.ok(web.NetworkSettings.Ports["8080/tcp"].some(binding => binding.HostPort === new URL(baseURL).port),
  "The browser URL must belong to the selected demo project");
assert.ok(agent.Config.Env.includes("AGENT_MODEL_PROVIDER=fake"), "This verification requires FakeModel");
const started = new Date().toISOString();
const sources = new Map(readdirSync(path.join(root, "agent/knowledge"))
  .filter(file => file.endsWith(".json"))
  .map(file => JSON.parse(readFileSync(path.join(root, "agent/knowledge", file), "utf8")))
  .map(document => [document.documentId, document]));
const results = [];
const browser = await chromium.launch();
const page = await browser.newPage({ baseURL });
page.setDefaultTimeout(45_000);
await page.addInitScript({ path: path.join(root, "script/reconcile-browser-observer.js") });
let stopped = false;
try {
  const username = `ragfault${Date.now().toString(36)}`;
  await page.goto("/auth");
  await page.locator(".auth-tabs button").nth(1).click();
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("邮箱").fill(`${username}@hotshop.invalid`);
  await page.locator('input[type="password"]').fill("Task21Browser!2026");
  await page.locator('button[type="submit"]').click();
  await page.getByRole("heading", { name: `你好，${username}` }).waitFor();
  await page.goto("/user/agent");
  async function ask(label, question, outcome, documentId, expectedText) {
    const requestId = `reconcile-${label}-${Date.now()}`;
    await page.setExtraHTTPHeaders({ "X-Request-ID": requestId });
    await page.evaluate(() => { document.documentElement.dataset.deliveryRagEvents = "[]"; });
    await page.getByRole("textbox", { name: "请求", exact: true }).fill(question);
    const creation = page.waitForResponse(response => response.request().method() === "POST"
      && /\/agent\/sessions\/[^/]+\/runs$/.test(new URL(response.url()).pathname));
    await page.getByRole("button", { name: "发送请求" }).click();
    const response = await creation;
    assert.equal(response.status(), 202, `Run creation failed before retrieval: ${label}`);
    await page.waitForFunction(() => JSON.parse(document.documentElement.dataset.deliveryRagEvents ?? "[]").some(e => e.type === "done"));
    const events = await page.evaluate(() => JSON.parse(document.documentElement.dataset.deliveryRagEvents ?? "[]"));
    const rag = events.find(e => e.type === "rag.completed");
    assert.ok(events.some(e => e.type === "done"));
    const answer = await page.locator(".agent-answer-copy").textContent();
    if (outcome === "dynamic") {
      assert.equal(rag, undefined, "Live facts must not use static knowledge");
      assert.ok(events.some(e => e.type === "tool.completed" && e.data.tool === "get_product"
        && e.data.outcome === "SUCCESS"));
      results.push({ label, requestId, runId: events.find(e => e.type === "done").runId,
        outcome, tool: "get_product" });
      console.error(`${label}: ${outcome}`);
      return;
    }
    assert.equal(rag?.data.outcome, outcome);
    if (outcome === "hit") {
      assert.equal(rag.data.citations[0].documentId, documentId);
      for (const citation of rag.data.citations) {
        const source = sources.get(citation.documentId);
        assert.ok(source, "Citation must exist in the current source files");
        assert.deepEqual(citation, { documentId: source.documentId, title: source.title,
          version: source.documentVersion, source: source.source, chunkId: `${source.documentId}-0000` });
      }
      await page.getByRole("list", { name: "引用资料" }).waitFor();
      if (expectedText) assert.ok(answer.includes(expectedText), `Missing answer fact: ${label}`);
    } else {
      assert.deepEqual(rag.data.citations, []);
      assert.ok(answer.includes(outcome === "empty" ? "我不知道" : "暂时不可用"));
    }
    results.push({ label, requestId, runId: rag.runId, outcome, citations: rag.data.citations });
    console.error(`${label}: ${outcome}`);
  }
  await ask("direct-cn", "售后申请应从哪里发起？", "hit", "after-sales-general", "暂未提供退换货、退款或售后申请入口");
  await ask("original-paraphrase", "售后退换申请应该怎么做？", "hit", "after-sales-general");
  await ask("browse-policy", "看看售后政策", "hit", "after-sales-general");
  await ask("english", "How does the after-sales return policy work?", "hit", "after-sales-general-en", "no return, exchange, refund or after-sales request form");
  await ask("english-refund", "Where can I request a refund?", "hit", "after-sales-general-en");
  await ask("account-english", "How can I secure my account?", "hit", "faq-account-security-en");
  await ask("support-cn", "怎么联系支持？", "hit", "faq-user-support", "没有站内帮助中心或客服工单");
  await ask("campaign-english", "What are the flash sale rules?", "hit", "campaign-general-rules-en");
  await ask("unrelated", "常见问题：火星门店在哪里？", "empty");
  docker("stop", qdrant.Id);
  stopped = true;
  await ask("disconnected", "售后申请应从哪里发起？", "unavailable");
  await ask("dynamic-during-outage", "看看商品 913001 的库存和售后政策", "dynamic");
  docker("start", qdrant.Id);
  stopped = false;
  const deadline = Date.now() + 60_000;
  while (JSON.parse(docker("inspect", qdrant.Id))[0].State.Health?.Status !== "healthy") {
    assert.ok(Date.now() < deadline, "Qdrant did not recover");
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  await ask("recovered", "售后申请应从哪里发起？", "hit", "after-sales-general");
  assert.equal(owned("agent-service").State.StartedAt, agent.State.StartedAt, "Agent process must not restart");
  const logProcess = spawnSync("docker", ["logs", "--since", started, agent.Id], { encoding: "utf8", timeout: 60_000 });
  assert.equal(logProcess.status, 0, "Could not read this project's Agent diagnostics");
  const records = `${logProcess.stdout}\n${logProcess.stderr}`.split(/\r?\n/).flatMap(line => {
    try { const value = JSON.parse(line); return value.event === "agent.rag.retrieval" ? [value] : []; }
    catch { return []; }
  });
  const diagnostics = results.filter(result => result.outcome !== "dynamic").map(result => {
    const record = records.find(row => row.runId === result.runId && row.requestId === result.requestId);
    assert.ok(record, `missing running-service correlation for ${result.label}`);
    assert.equal(record.outcome, result.outcome);
    if (result.outcome === "unavailable") assert.ok(["qdrant_connect", "qdrant_timeout", "qdrant_transport"].includes(record.errorType));
    return { requestId: record.requestId, runId: record.runId, outcome: record.outcome, errorType: record.errorType };
  });
  console.log(JSON.stringify({ project, agentImage: agent.Image,
    sourceCommit: gitHead(), dirty: execFileSync("git", ["status", "--porcelain"], { cwd: root, encoding: "utf8" }).trim().length > 0,
    sameAgentProcess: true,
    originalTransientCause: "unresolved; injected outage does not prove the historical cause",
    results, diagnostics }, null, 2));
} finally {
  if (stopped) docker("start", qdrant.Id);
  await browser.close();
}

function gitHead() {
  return execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
}
