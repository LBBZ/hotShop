import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import test from "node:test";
import {
  checkLinks,
  headingAnchors,
  linkDestinations,
  markdownFiles,
} from "../check-docs.mjs";

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "hotshop-docs-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const write = (name, content) => {
    const file = path.join(root, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content);
  };
  return { root, write };
}

test("checks multilingual, encoded, reference and same-page links", (t) => {
  const { root, write } = fixture(t);
  write(
    "README.md",
    '# HotShop\n[local](#hotshop)\n[CN](docs/guide%20one.md#快速开始)\n[ref][guide]\n[guide]: <docs/guide one.md> "Title"\n![image](docs/icon.svg)\n',
  );
  write("docs/guide one.md", "# 快速开始\n");
  write("docs/icon.svg", "<svg/>");
  const result = checkLinks(root, ["README.md", "docs/guide one.md"]);
  assert.deepEqual(result.failures, []);
  assert.equal(result.localLinksChecked, 4);
  assert.equal(result.anchorsChecked, 2);
});

test("reports missing files, missing anchors, and malformed escaping", (t) => {
  const { root, write } = fixture(t);
  write(
    "README.md",
    "# Home\n[missing](gone.md) [anchor](#gone) [encoding](bad%ZZ.md) [remote](https://example.invalid/#gone)\n",
  );
  assert.deepEqual(
    checkLinks(root, ["README.md"]).failures.map(({ reason }) => reason),
    [
      "local link missing",
      "Markdown anchor missing",
      "invalid percent encoding",
    ],
  );
});

test("ignores fenced/indented/inline code and comments but handles titles and parentheses", () => {
  const markdown =
    '```md\n[bad](gone.md)\n```\n~~~\n[bad](gone.md)\n~~~\n    [bad](gone.md)\n`[bad](gone.md)` <!-- [bad](gone.md) -->\n[good](docs/a(b).md "Title") ![art](art.svg)\n';
  assert.deepEqual(linkDestinations(markdown), ["docs/a(b).md", "art.svg"]);
});

test("recognizes Unicode, duplicate, setext and explicit HTML anchors", () => {
  assert.deepEqual(
    [
      ...headingAnchors(
        '# Hello, **world**!\n# Hello, world!\n## 快速开始\nSetup\n-----\n<a id="custom"></a>\n',
      ),
    ],
    ["hello-world", "hello-world-1", "快速开始", "setup", "custom"],
  );
});

test("collects current tracked and untracked Markdown without deleted or ignored files", (t) => {
  const { root, write } = fixture(t);
  execFileSync("git", ["init", "--quiet"], { cwd: root });
  write("README.md", "# Main\n");
  write("deleted.md", "# Deleted\n");
  write(".gitignore", "ignored.md\n");
  execFileSync("git", ["add", "."], { cwd: root });
  fs.unlinkSync(path.join(root, "deleted.md"));
  write("README.en.md", "# English\n");
  write("ignored.md", "# Ignored\n");
  assert.deepEqual(markdownFiles(root), ["README.en.md", "README.md"]);
});

test("resolves repository-root links and percent-encoded filename fragments", (t) => {
  const { root, write } = fixture(t);
  write("docs/deep/note.md", "[main](/README.md#home) [file](../a%23b.md)\n");
  write("README.md", "# Home\n");
  write("docs/a#b.md", "# File\n");
  assert.deepEqual(checkLinks(root, ["docs/deep/note.md"]).failures, []);
});
