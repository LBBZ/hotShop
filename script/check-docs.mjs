import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

// No install or historical commit is needed for the normal link check.
export function markdownFiles(root) {
  const files = execFileSync(
    "git",
    ["ls-files", "-z", "--cached", "--others", "--exclude-standard"],
    {
      cwd: root,
      encoding: "utf8",
    },
  ).split("\0");
  return [...new Set(files)]
    .filter(
      (file) => /\.md$/iu.test(file) && fs.existsSync(path.join(root, file)),
    )
    .sort();
}

export function withoutCodeBlocks(markdown) {
  let fence;
  return markdown
    .split(/\r?\n/u)
    .map((line) => {
      const marker = /^ {0,3}(`{3,}|~{3,})/u.exec(line)?.[1];
      if (fence) {
        if (
          marker?.[0] === fence[0] &&
          marker.length >= fence.length &&
          /^ {0,3}(?:`+|~+)\s*$/u.test(line)
        )
          fence = undefined;
        return "";
      }
      if (marker) {
        fence = marker;
        return "";
      }
      return /^(?: {4}|\t)/u.test(line) ? "" : line;
    })
    .join("\n");
}

function destination(text, start) {
  let index = start;
  while (index < text.length && /\s/u.test(text[index])) index++;
  if (text[index] === "<") {
    const end = text.indexOf(">", index + 1);
    return end < 0 ? null : text.slice(index + 1, end);
  }
  let depth = 0;
  let value = "";
  for (; index < text.length; index++) {
    const character = text[index];
    if (character === "\\" && index + 1 < text.length) {
      value += text[++index];
    } else if (character === "(") {
      depth++;
      value += character;
    } else if (character === ")") {
      if (depth === 0) break;
      depth--;
      value += character;
    } else if (/\s/u.test(character) && depth === 0) {
      break;
    } else {
      value += character;
    }
  }
  return value;
}

export function linkDestinations(markdown) {
  const text = withoutCodeBlocks(markdown)
    .replace(/<!--[^]*?-->/gu, "")
    .replace(/(`+)([^]*?)\1/gu, "");
  const links = [];
  for (const match of text.matchAll(/(?<!\\)\]\(/gu)) {
    const target = destination(text, match.index + 2);
    if (target !== null) links.push(target);
  }
  // Definitions cover full, collapsed, and shortcut reference links.
  for (const match of text.matchAll(/^ {0,3}\[[^\]\n]+\]:\s*/gmu)) {
    const target = destination(text, match.index + match[0].length);
    if (target !== null) links.push(target);
  }
  return links;
}

export function headingAnchors(markdown) {
  const text = withoutCodeBlocks(markdown).replace(/<!--[^]*?-->/gu, "");
  const anchors = new Set();
  const used = new Set();
  const lines = text.split("\n");
  for (let index = 0; index < lines.length; index++) {
    const atx = /^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$/u.exec(lines[index]);
    const setext =
      lines[index].trim() &&
      /^ {0,3}(?:=+|-+)\s*$/u.test(lines[index + 1] ?? "");
    if (!atx && !setext) continue;
    const heading = (atx?.[1] ?? lines[index])
      .replace(/!?\[([^\]]*)\]\([^)]*\)/gu, "$1")
      .replace(/<[^>]+>/gu, "")
      .replace(/&amp;/gu, "&")
      .replace(/&(?:lt|gt|quot|apos);/gu, "")
      .toLowerCase()
      .replace(/[^\p{L}\p{M}\p{N}\p{Pc}\-\s]/gu, "")
      .replace(/ /gu, "-");
    let slug = heading;
    let suffix = 0;
    while (used.has(slug)) slug = `${heading}-${++suffix}`;
    used.add(slug);
    anchors.add(slug);
    if (setext) index++;
  }
  for (const match of text.matchAll(/\b(?:id|name)\s*=\s*["']([^"']+)["']/gu))
    anchors.add(match[1]);
  return anchors;
}

export function checkLinks(root, files = markdownFiles(root)) {
  const failures = [];
  const anchorCache = new Map();
  let localLinksChecked = 0;
  let anchorsChecked = 0;
  for (const file of files) {
    const markdown = fs.readFileSync(path.join(root, file), "utf8");
    for (const target of linkDestinations(markdown)) {
      if (!target || /^(?:[a-z][a-z0-9+.-]*:|\/\/)/iu.test(target)) continue;
      localLinksChecked++;
      let fileTarget;
      let anchor;
      try {
        const hash = target.indexOf("#");
        fileTarget = decodeURIComponent(
          (hash < 0 ? target : target.slice(0, hash)).split("?")[0],
        );
        anchor = hash < 0 ? "" : decodeURIComponent(target.slice(hash + 1));
      } catch {
        failures.push({ file, target, reason: "invalid percent encoding" });
        continue;
      }
      const resolved = fileTarget
        ? path.resolve(
            fileTarget.startsWith("/")
              ? root
              : path.dirname(path.join(root, file)),
            fileTarget.replace(/^\//u, ""),
          )
        : path.join(root, file);
      if (!fs.existsSync(resolved)) {
        failures.push({ file, target, reason: "local link missing" });
      } else if (anchor && /\.md$/iu.test(resolved)) {
        anchorsChecked++;
        if (!anchorCache.has(resolved))
          anchorCache.set(
            resolved,
            headingAnchors(fs.readFileSync(resolved, "utf8")),
          );
        if (!anchorCache.get(resolved).has(anchor))
          failures.push({ file, target, reason: "Markdown anchor missing" });
      }
    }
  }
  return { files, localLinksChecked, anchorsChecked, failures };
}

async function renderMermaid(root, files, toolsDirectory, outputDirectory) {
  const requireWeb = createRequire(path.join(root, "web/package.json"));
  const { chromium } = requireWeb("@playwright/test");
  const browser = await chromium.launch({ headless: true });
  const failures = [];
  let rendered = 0;
  try {
    const page = await browser.newPage();
    await page.setContent("<!doctype html><html><body></body></html>");
    await page.addScriptTag({
      path: path.resolve(
        toolsDirectory,
        "node_modules/mermaid/dist/mermaid.min.js",
      ),
    });
    for (const file of files) {
      const markdown = fs.readFileSync(path.join(root, file), "utf8");
      for (const match of markdown.matchAll(/```mermaid\r?\n([^]*?)```/gu)) {
        try {
          const svg = await page.evaluate(
            async ({ code, id }) => {
              window.mermaid.initialize({
                startOnLoad: false,
                securityLevel: "strict",
              });
              await window.mermaid.parse(code);
              return (await window.mermaid.render(id, code)).svg;
            },
            { code: match[1], id: `documentation${rendered}` },
          );
          if (!svg.includes("<svg")) throw new Error("No SVG rendered");
          fs.writeFileSync(
            path.join(outputDirectory, `diagram-${rendered++}.svg`),
            svg,
          );
        } catch (error) {
          failures.push({ file, reason: String(error) });
        }
      }
    }
  } finally {
    await browser.close();
  }
  return { mermaidRendered: rendered, failures };
}

export async function runCheck({
  root = process.cwd(),
  output = "target/docs-check",
  mermaidTools,
} = {}) {
  root = path.resolve(root);
  const outputDirectory = path.resolve(root, output);
  fs.mkdirSync(outputDirectory, { recursive: true });
  const result = checkLinks(root);
  result.limitations =
    "Local inline/reference Markdown destinations and heading/HTML anchors; remote URLs and anchors in non-Markdown files are not checked. Mermaid rendering is opt-in.";
  if (mermaidTools) {
    const mermaid = await renderMermaid(
      root,
      result.files,
      mermaidTools,
      outputDirectory,
    );
    result.mermaidRendered = mermaid.mermaidRendered;
    result.failures.push(...mermaid.failures);
  }
  fs.writeFileSync(
    path.join(outputDirectory, "result.json"),
    `${JSON.stringify(result, null, 2)}\n`,
  );
  for (const failure of result.failures)
    console.error(
      `${failure.file}: ${failure.reason}${failure.target ? `: ${failure.target}` : ""}`,
    );
  console.log(
    `Documentation check: ${result.files.length} files, ${result.localLinksChecked} local links, ${result.anchorsChecked} anchors, ${result.failures.length} failures.`,
  );
  return result;
}

if (
  process.argv[1] &&
  fileURLToPath(import.meta.url) === path.resolve(process.argv[1])
) {
  const options = {};
  const names = {
    "--root": "root",
    "--output": "output",
    "--mermaid-tools": "mermaidTools",
  };
  for (let index = 2; index < process.argv.length; index++) {
    const argument = process.argv[index];
    if (argument === "--help") {
      console.log(
        "Usage: node script/check-docs.mjs [--root DIR] [--output DIR] [--mermaid-tools DIR]\nOptional Mermaid rendering requires web Playwright/browser and DIR/node_modules/mermaid.",
      );
      process.exit(0);
    }
    if (!names[argument] || !process.argv[index + 1])
      throw new Error(`Unknown or incomplete option: ${argument}`);
    options[names[argument]] = process.argv[++index];
  }
  const result = await runCheck(options);
  if (result.failures.length) process.exitCode = 1;
}
