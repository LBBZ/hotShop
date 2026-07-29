import { describe, expect, it } from "vitest";

import { normalizeGeneratedTypeScript } from "./api-generation.mjs";

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
