import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";

import {
  compareTrees,
  normalizeGeneratedTypeScript,
} from "./api-generation.mjs";

describe("normalizeGeneratedTypeScript", () => {
  it("removes trailing spaces and tabs from every line", () => {
    const source = "const alpha = 1;   \nconst beta = 2;\t\n";

    expect(normalizeGeneratedTypeScript(source)).toBe(
      "const alpha = 1;\nconst beta = 2;\n",
    );
  });

  it("collapses extra end-of-file blank lines to one LF", () => {
    const source = "export const ready = true;\n\n\n";

    expect(normalizeGeneratedTypeScript(source)).toBe(
      "export const ready = true;\n",
    );
  });

  it("preserves normal code content", () => {
    const source = "export function value() {\n  return 42;\n}\n";

    expect(normalizeGeneratedTypeScript(source)).toBe(source);
  });

  it("is idempotent", () => {
    const source = "export const value = 42; \t\n\n";
    const normalized = normalizeGeneratedTypeScript(source);

    expect(normalizeGeneratedTypeScript(normalized)).toBe(normalized);
  });
});

describe("generated API platform independence", () => {
  let directory;
  afterEach(() => {
    if (directory) rmSync(directory, { recursive: true, force: true });
  });

  it("normalizes Windows generator output to LF", () => {
    expect(
      normalizeGeneratedTypeScript("export const value = 42;  \r\n\r\n"),
    ).toBe("export const value = 42;\n");
  });

  it("ignores checkout line endings while detecting contract differences", () => {
    directory = mkdtempSync(join(tmpdir(), "hotshop-api-drift-"));
    const expected = join(directory, "expected");
    const actual = join(directory, "actual");
    mkdirSync(expected);
    mkdirSync(actual);
    writeFileSync(join(expected, "model.ts"), "export type Id = string;\r\n");
    writeFileSync(join(actual, "model.ts"), "export type Id = string;\n");
    expect(compareTrees(expected, actual)).toEqual([]);

    writeFileSync(join(actual, "model.ts"), "export type Id = number;\n");
    expect(compareTrees(expected, actual)).toEqual([
      "generated file differs: model.ts",
    ]);
  });
});
