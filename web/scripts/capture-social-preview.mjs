// Render the repository card with the application's existing vector artwork.
import { chromium } from "@playwright/test";
import { readFile, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const baseURL = process.env.HOTSHOP_PREVIEW_URL ?? "http://127.0.0.1:18080";
if (!["127.0.0.1", "localhost", "[::1]"].includes(new URL(baseURL).hostname)) {
  throw new Error("Social preview capture requires a local application URL.");
}
const source = new URL(
  "../../docs/assets/social-preview.html",
  import.meta.url,
);
const output = fileURLToPath(
  new URL("../../docs/assets/social-preview.png", import.meta.url),
);
const fontFiles = [
  ["Manrope", 400, "manrope/files/manrope-latin-400-normal.woff2"],
  ["Manrope", 600, "manrope/files/manrope-latin-600-normal.woff2"],
  ["Manrope", 700, "manrope/files/manrope-latin-700-normal.woff2"],
  [
    "IBM Plex Mono",
    500,
    "ibm-plex-mono/files/ibm-plex-mono-latin-500-normal.woff2",
  ],
];
const fontCSS = await Promise.all(
  fontFiles.map(async ([family, weight, path]) => {
    const bytes = await readFile(
      new URL(`../node_modules/@fontsource/${path}`, import.meta.url),
    );
    return `@font-face { font-family: "${family}"; font-weight: ${weight}; src: url(data:font/woff2;base64,${bytes.toString("base64")}) format("woff2"); }`;
  }),
);
const browser = await chromium.launch();
try {
  const page = await browser.newPage({
    viewport: { width: 1280, height: 640 },
    reducedMotion: "reduce",
    deviceScaleFactor: 1,
  });
  await page.goto(baseURL, { waitUntil: "networkidle" });
  const sculpture = page.locator(".shopping-sculpture__canvas");
  await sculpture.waitFor();
  const artwork = await sculpture.evaluate((element) => element.outerHTML);
  await page.setContent(await readFile(source, "utf8"));
  await page.addStyleTag({ content: fontCSS.join("\n") });
  await page.locator("#artwork").evaluate((element, svg) => {
    element.innerHTML = svg;
  }, artwork);
  await page.evaluate(() => globalThis.document.fonts.ready);
  await page.screenshot({ path: output });
  const file = await stat(output);
  if (file.size >= 1_000_000)
    throw new Error("GitHub social preview must be smaller than 1 MB.");
  console.log(`Social preview: 1280 × 640, ${file.size} bytes — ${output}`);
} finally {
  await browser.close();
}
