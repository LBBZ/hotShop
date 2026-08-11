import { describe, expect, it } from "vitest";

import {
  createAdminTraceLinkBuilder,
  isValidAdminTraceId,
} from "./admin-trace-link";

const TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

describe("isValidAdminTraceId", () => {
  it("accepts only a non-zero, 32-character lowercase hexadecimal trace ID", () => {
    expect(isValidAdminTraceId(TRACE_ID)).toBe(true);
    expect(isValidAdminTraceId(undefined)).toBe(false);
    expect(isValidAdminTraceId(null)).toBe(false);
    expect(isValidAdminTraceId("")).toBe(false);
    expect(isValidAdminTraceId("00000000000000000000000000000000")).toBe(false);
    expect(isValidAdminTraceId("4BF92F3577B34DA6A3CE929D0E0E4736")).toBe(false);
    expect(isValidAdminTraceId(`${TRACE_ID}0`)).toBe(false);
    expect(isValidAdminTraceId("../../grafana?traceId=anything")).toBe(false);
  });
});

describe("createAdminTraceLinkBuilder", () => {
  it("adds the trace ID as a query parameter without losing configured values", () => {
    const buildLink = createAdminTraceLinkBuilder(
      "https://grafana.example/explore?orgId=7&traceId=old#tempo",
    );

    const result = buildLink(TRACE_ID);
    const destination = new URL(result!);

    expect(destination.origin).toBe("https://grafana.example");
    expect(destination.pathname).toBe("/explore");
    expect(destination.searchParams.get("orgId")).toBe("7");
    expect(destination.searchParams.getAll("traceId")).toEqual([TRACE_ID]);
    expect(destination.hash).toBe("#tempo");
  });

  it("does not create a link for invalid or absent trace IDs", () => {
    const buildLink = createAdminTraceLinkBuilder(
      "https://tempo.example/trace",
    );

    expect(buildLink(undefined)).toBeNull();
    expect(buildLink("4BF92F3577B34DA6A3CE929D0E0E4736")).toBeNull();
    expect(buildLink("00000000000000000000000000000000")).toBeNull();
  });

  it.each([
    undefined,
    "",
    "not-a-url",
    "javascript:alert(1)",
    "ftp://tempo.example/trace",
    "https://operator:secret@tempo.example/trace",
  ])("rejects an unsafe configured base URL: %s", (configuredBaseUrl) => {
    const buildLink = createAdminTraceLinkBuilder(configuredBaseUrl);

    expect(buildLink(TRACE_ID)).toBeNull();
  });

  it("does not let trace data replace the configured destination", () => {
    const buildLink = createAdminTraceLinkBuilder(
      "https://tempo.example/trace",
    );

    expect(buildLink("https://attacker.example/redirect")).toBeNull();
  });
});
