import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";

// Run from the repository root after installing the documented local QA tools.
const root = process.cwd();
const toolsDirectory = path.resolve(process.argv[2] ?? "target/task21/doc-tools");
const outputDirectory = path.resolve("target/task21/document-check");
fs.mkdirSync(outputDirectory, { recursive: true });
const requireWeb = createRequire(path.join(root, "web/package.json"));
const { chromium } = requireWeb("@playwright/test");
const baseline = "10d82528aab71766ab5aa6020180ae4935f96359";
const git = (...args) => execFileSync("git", args, { encoding: "utf8" }).trim().split(/\r?\n/u).filter(Boolean);
const files = [...new Set([
  ...git("diff", "--name-only", baseline),
  ...git("ls-files", "--others", "--exclude-standard"),
])].filter((file) => file.endsWith(".md"));
const failures = [];
const diagrams = [];
let links = 0;
for (const file of files) {
  const text = fs.readFileSync(file, "utf8");
  // Local Markdown paths only; remote URLs and historical raw text are not fetched.
  for (const match of text.matchAll(/\[[^\]\n]*\]\(([^)\n]+)\)/gu)) {
    const target = match[1].replace(/^<|>$/gu, "").split("#")[0];
    if (!target || /^[a-z][a-z0-9+.-]*:/iu.test(target)) continue;
    links++;
    if (!fs.existsSync(path.resolve(path.dirname(file), decodeURIComponent(target)))) {
      failures.push({ file, target, reason: "local link missing" });
    }
  }
  for (const match of text.matchAll(/```mermaid\r?\n([\s\S]*?)```/gu)) {
    diagrams.push({ file, code: match[1] });
  }
}
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  await page.setContent("<!doctype html><html><body></body></html>");
  await page.addScriptTag({ path: path.join(toolsDirectory, "node_modules/mermaid/dist/mermaid.min.js") });
  for (const [index, diagram] of diagrams.entries()) {
    try {
      const svg = await page.evaluate(async ({ code, id }) => {
        window.mermaid.initialize({ startOnLoad: false, securityLevel: "strict" });
        await window.mermaid.parse(code);
        return (await window.mermaid.render(id, code)).svg;
      }, { code: diagram.code, id: `delivery${index}` });
      if (!svg.includes("<svg")) throw new Error("No SVG rendered");
      fs.writeFileSync(path.join(outputDirectory, `diagram-${index}.svg`), svg);
    } catch (error) {
      failures.push({ file: diagram.file, reason: String(error) });
    }
  }
} finally {
  await browser.close();
}
const result = {
  baseline, files, localLinksChecked: links, mermaidRendered: diagrams.length,
  limitations: "Only changed Markdown paths and Mermaid syntax/rendering; no remote URL or anchor validation.",
  failures,
};
fs.writeFileSync(path.join(outputDirectory, "result.json"), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
if (failures.length) process.exitCode = 1;
