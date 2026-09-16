// Real browser -> running Agent -> real Qdrant; stops only this worktree's demo Qdrant.
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(path.join(root, "web/package.json"));
const { chromium } = require("@playwright/test");
const [project, baseURL = "http://127.0.0.1:18080"] = process.argv.slice(2);
assert.match(project ?? "", /^hotshop-task21-[a-z0-9]{8,24}$/);
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
const started = new Date().toISOString();
const corpus = JSON.parse(readFileSync(path.join(root, "agent/knowledge/after-sales.json"), "utf8"));
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
  await page.getByRole("heading", { name: `早上好，${username}` }).waitFor();
  await page.goto("/user/agent");
  async function ask(label, question, outcome) {
    const requestId = `reconcile-${label}-${Date.now()}`;
    await page.setExtraHTTPHeaders({ "X-Request-ID": requestId });
    await page.evaluate(() => { document.documentElement.dataset.deliveryRagEvents = "[]"; });
    await page.getByLabel("请求").fill(question);
    await page.getByRole("button", { name: "发送请求" }).click();
    await page.waitForFunction(() => JSON.parse(document.documentElement.dataset.deliveryRagEvents ?? "[]").some(e => e.type === "done"));
    const events = await page.evaluate(() => JSON.parse(document.documentElement.dataset.deliveryRagEvents ?? "[]"));
    const rag = events.find(e => e.type === "rag.completed");
    assert.equal(rag?.data.outcome, outcome);
    assert.ok(events.some(e => e.type === "done"));
    const answer = await page.locator(".agent-answer-copy").textContent();
    if (outcome === "hit") {
      assert.deepEqual(rag.data.citations, [{ documentId: corpus.documentId, title: corpus.title,
        version: corpus.documentVersion, source: corpus.source, chunkId: `${corpus.documentId}-0000` }]);
      assert.ok(corpus.content.includes("售后申请应从订单详情页发起"));
      await page.getByRole("list", { name: "引用资料" }).waitFor();
    } else {
      assert.deepEqual(rag.data.citations, []);
      assert.ok(answer.includes(outcome === "empty" ? "我不知道" : "暂时不可用"));
    }
    results.push({ label, requestId, runId: rag.runId, outcome, citations: rag.data.citations });
  }
  await ask("direct-cn", "售后申请应从哪里发起？", "hit");
  await ask("original-paraphrase", "售后退换申请应该怎么做？", "empty");
  await ask("english", "How does the after-sales return policy work?", "hit");
  docker("stop", qdrant.Id);
  stopped = true;
  await ask("disconnected", "售后申请应从哪里发起？", "unavailable");
  docker("start", qdrant.Id);
  stopped = false;
  const deadline = Date.now() + 60_000;
  while (JSON.parse(docker("inspect", qdrant.Id))[0].State.Health?.Status !== "healthy") {
    assert.ok(Date.now() < deadline, "Qdrant did not recover");
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  await ask("recovered", "售后申请应从哪里发起？", "hit");
  assert.equal(owned("agent-service").State.StartedAt, agent.State.StartedAt, "Agent process must not restart");
  const logProcess = spawnSync("docker", ["logs", "--since", started, agent.Id], { encoding: "utf8", timeout: 60_000 });
  assert.equal(logProcess.status, 0, "Could not read this project's Agent diagnostics");
  const records = `${logProcess.stdout}\n${logProcess.stderr}`.split(/\r?\n/).flatMap(line => {
    try { const value = JSON.parse(line); return value.event === "agent.rag.retrieval" ? [value] : []; }
    catch { return []; }
  });
  const diagnostics = results.map(result => {
    const record = records.find(row => row.runId === result.runId && row.requestId === result.requestId);
    assert.ok(record, `missing running-service correlation for ${result.label}`);
    assert.equal(record.outcome, result.outcome);
    if (result.outcome === "unavailable") assert.ok(["qdrant_connect", "qdrant_timeout", "qdrant_transport"].includes(record.errorType));
    return { requestId: record.requestId, runId: record.runId, outcome: record.outcome, errorType: record.errorType };
  });
  console.log(JSON.stringify({ project, agentImage: agent.Image, sameAgentProcess: true,
    originalTransientCause: "unresolved; injected outage does not prove the historical cause",
    results, diagnostics }, null, 2));
} finally {
  if (stopped) docker("start", qdrant.Id);
  await browser.close();
}
