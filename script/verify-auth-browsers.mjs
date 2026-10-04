// Real built Web + real authentication backend. Creates disposable test users only.
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const [project, baseURL] = process.argv.slice(2);
assert.match(project ?? "", /^hotshop$/);
assert.ok(existsSync(path.join(root, ".local/keys", project, ".env.demo")));
const url = new URL(baseURL);
assert.ok(["http:", "https:"].includes(url.protocol));
assert.ok(["127.0.0.1", "localhost"].includes(url.hostname));
const docker = (...args) => execFileSync("docker", args, { encoding: "utf8", timeout: 30_000 }).trim();
const ids = docker("ps", "-q", "--filter", `label=com.docker.compose.project=${project}`,
  "--filter", "label=com.docker.compose.service=web-demo").split(/\r?\n/).filter(Boolean);
assert.equal(ids.length, 1, "Require one running Web container in the selected demo");
const web = JSON.parse(docker("inspect", ids[0]))[0];
assert.equal(web.Config.Labels["com.docker.compose.project"], project);
assert.ok(web.NetworkSettings.Ports["8080/tcp"].some(binding => binding.HostPort === url.port));
const runId = `auth-browsers-${new Date().toISOString().replace(/\D/g, "")}`;
const destination = path.join(root, ".local/verification", runId);
mkdirSync(destination, { recursive: true });
const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
const dirty = execFileSync("git", ["status", "--porcelain"], { cwd: root, encoding: "utf8" }).trim().length > 0;
const reportPath = path.join(destination, "browser.json");
const log = openSync(path.join(destination, "browser.log"), "w");
console.log(`Browser evidence: ${destination}`);
const result = spawnSync(process.execPath, [
  path.join(root, "web/node_modules/@playwright/test/cli.js"), "test",
  "--config", path.join(root, "web/playwright.auth-browsers.config.ts"),
], { cwd: root, encoding: "utf8", timeout: 15 * 60_000, stdio: ["ignore", log, log],
  env: { ...process.env, HOTSHOP_AUTH_BROWSER_URL: url.origin, HOTSHOP_AUTH_BROWSER_REPORT: reportPath } });
closeSync(log);
const report = existsSync(reportPath) ? JSON.parse(readFileSync(reportPath, "utf8")) : null;
const summary = { sourceCommit, dirty, project, baseURL: url.origin, webImage: web.Image,
  exitCode: result.status, error: result.error?.name, stats: report?.stats,
  browserProjects: report?.config?.projects?.map(item => item.name),
  artifacts: destination };
writeFileSync(path.join(destination, "result.json"), `${JSON.stringify(summary, null, 2)}\n`);
console.log(JSON.stringify(summary, null, 2));
process.exitCode = result.status ?? 1;
